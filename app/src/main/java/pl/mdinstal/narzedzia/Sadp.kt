package pl.mdinstal.narzedzia

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID

// ───────────────────────────── SADP: wykrywanie ─────────────────────────────
// Tylko odczyt (zapytanie "inquiry"). Aplikacja niczego w urządzeniach nie zmienia:
// brak aktywacji, brak zmiany IP, brak resetu hasła.

data class SadpDevice(
    val ip: String,
    val mac: String,
    val model: String,
    val serial: String,
    val firmware: String,
    val httpPort: Int,
    val activated: Boolean
)

private fun xmlTag(xml: String, name: String): String? =
    Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

private fun parseSadp(xml: String): SadpDevice? {
    if (!xml.contains("ProbeMatch")) return null
    val ip = xmlTag(xml, "IPv4Address")?.takeIf { it.isNotEmpty() } ?: return null
    return SadpDevice(
        ip = ip,
        mac = xmlTag(xml, "MAC")?.lowercase() ?: "",
        model = xmlTag(xml, "DeviceDescription") ?: xmlTag(xml, "DeviceType") ?: "Hikvision",
        serial = xmlTag(xml, "DeviceSN") ?: "",
        firmware = xmlTag(xml, "SoftwareVersion") ?: "",
        httpPort = xmlTag(xml, "HttpPort")?.toIntOrNull() ?: 80,
        activated = !(xmlTag(xml, "Activated")?.equals("false", ignoreCase = true) ?: false)
    )
}

/** Interfejs sieci lokalnej (Wi-Fi / Ethernet / hotspot telefonu), na którym wysyłamy multicast. */
private fun lanInterface(): NetworkInterface? = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.interfaceAddresses.any { a -> a.address.isSiteLocalAddress && a.address.address.size == 4 } }
        .sortedBy { n ->
            when {
                n.name.startsWith("wlan") -> 0
                n.name.startsWith("eth") -> 1
                n.name.startsWith("ap") || n.name.startsWith("swlan") -> 2
                else -> 3
            }
        }
        .firstOrNull()
} catch (e: Exception) {
    null
}

private suspend fun sadpScan(ctx: Context, onFound: (SadpDevice) -> Unit) = withContext(Dispatchers.IO) {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wm.createMulticastLock("md-sadp").apply { setReferenceCounted(false); acquire() }
    var sock: MulticastSocket? = null
    try {
        val group = InetAddress.getByName("239.255.255.250")
        val iface = lanInterface()
        val s = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(37020))
            soTimeout = 400
            if (iface != null) {
                networkInterface = iface
                joinGroup(InetSocketAddress(group, 0), iface)
            } else {
                @Suppress("DEPRECATION")
                joinGroup(group)
            }
        }
        sock = s
        val probe = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><Probe><Uuid>${UUID.randomUUID().toString().uppercase()}" +
            "</Uuid><Types>inquiry</Types></Probe>").toByteArray()
        val seen = HashSet<String>()
        val buf = ByteArray(8192)
        val end = System.currentTimeMillis() + 6000
        var nextProbe = 0L
        while (isActive && System.currentTimeMillis() < end) {
            val now = System.currentTimeMillis()
            if (now >= nextProbe) {
                try { s.send(DatagramPacket(probe, probe.size, group, 37020)) } catch (e: Exception) { }
                nextProbe = now + 2000
            }
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val d = parseSadp(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                if (seen.add(d.ip + d.mac)) withContext(Dispatchers.Main) { onFound(d) }
            } catch (e: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                break
            }
        }
    } catch (e: Exception) {
    } finally {
        try { sock?.close() } catch (e: Exception) { }
        try { lock.release() } catch (e: Exception) { }
    }
}

// ───────────────────────────── UI ─────────────────────────────

@Composable
fun SadpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<SadpDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableStateOf<SadpDevice?>(null) }

    fun scan() {
        if (scanning) return
        devices.clear()
        scanning = true
        job = scope.launch {
            try { sadpScan(ctx) { devices.add(it) } } finally { scanning = false }
        }
    }

    LaunchedEffect(Unit) { scan() }

    val sel = selected
    if (sel != null) {
        BackHandler { selected = null }
        CameraView(sel, onBack = { selected = null })
        return
    }

    AppScaffold("SADP Hikvision", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (scanning) OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") }
                else Button(onClick = { scan() }) { Text("Szukaj") }
                SubText(if (scanning) "Szukanie… znaleziono ${devices.size}" else "Znaleziono ${devices.size}")
            }
            SubText(
                "Tylko wyszukiwanie — aplikacja niczego nie zmienia w urządzeniach. Dotknij kamery, aby zobaczyć obraz.",
                Modifier.padding(top = 8.dp)
            )
            if (!scanning && devices.isEmpty()) {
                SubText(
                    "Nie znaleziono urządzeń. Połącz telefon z tą samą siecią co kamery (Wi-Fi lub hotspot) i spróbuj ponownie.",
                    Modifier.padding(top = 16.dp)
                )
            }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices.sortedBy { ipKey(it.ip) }, key = { it.ip + it.mac }) { d ->
                    AppCard(Modifier.fillMaxWidth(), onClick = { selected = d }) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    d.ip,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!d.activated) {
                                    Text("NIEAKTYWOWANA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Text(d.model, color = MaterialTheme.colorScheme.onSurface)
                            SubText(
                                listOf(
                                    d.serial.takeIf { it.isNotEmpty() }?.let { "SN: $it" },
                                    d.mac.takeIf { it.isNotEmpty() }?.let { "MAC: ${it.uppercase()}" },
                                    d.firmware.takeIf { it.isNotEmpty() }?.let { "FW: $it" },
                                    "HTTP: ${d.httpPort}"
                                ).filterNotNull().joinToString(" • ")
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ipKey(ip: String): Long = try {
    ip.split(".").fold(0L) { a, p -> (a shl 8) or (p.toLong() and 0xFF) }
} catch (e: Exception) { 0L }

@Composable
private fun CameraView(d: SadpDevice, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val saved = remember { Prefs.camCreds(ctx) }
    val bad = remember { Prefs.camBad(ctx) }
    // Odrzucone wcześniej dane nie są używane automatycznie.
    var creds by remember { mutableStateOf(if (bad) null else saved) }
    var asking by remember { mutableStateOf(saved == null && d.activated) }
    var channel by remember { mutableStateOf(1) }
    var sub by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    var status by remember {
        mutableStateOf(
            when {
                !d.activated -> "Kamera nie jest aktywowana — aktywuj ją najpierw w SADP na komputerze."
                bad && saved != null -> "UWAGA: kamera odrzuciła zapisane dane logowania. Nie próbuję ponownie — wpisz poprawne dane."
                else -> ""
            }
        )
    }
    var user by remember { mutableStateOf(Prefs.camUser(ctx)) }
    var pass by remember { mutableStateOf("") }

    AppScaffold("${d.model} • ${d.ip}", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            val c = creds
            if (c != null) {
                val player = remember(c, channel, sub, attempt) { ExoPlayer.Builder(ctx).build() }
                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) status = ""
                            else if (state == Player.STATE_BUFFERING) status = "Łączenie…"
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val text = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" ")
                            if (text.contains("401") || text.contains("Unauthorized", ignoreCase = true)) {
                                // Jedna nieudana próba i koniec — bez ponawiania, żeby kamera nie zablokowała konta.
                                Prefs.setCamBad(ctx, true)
                                creds = null
                                status = "UWAGA: kamera odrzuciła login lub hasło. Nie próbuję ponownie, żeby jej nie zablokować — wpisz poprawne dane."
                            } else {
                                status = "Nie udało się połączyć (RTSP, port 554). Sprawdź, czy RTSP jest włączone w kamerze."
                            }
                        }
                    }
                    player.addListener(listener)
                    val url = "rtsp://${Uri.encode(c.first)}:${Uri.encode(c.second)}@${d.ip}:554/Streaming/Channels/$channel" +
                        (if (sub) "02" else "01")
                    val src = RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true)
                        .setTimeoutMs(8000)
                        .createMediaSource(MediaItem.fromUri(url))
                    status = "Łączenie…"
                    player.setMediaSource(src)
                    player.prepare()
                    player.playWhenReady = true
                    onDispose {
                        player.removeListener(listener)
                        player.release()
                    }
                }
                AndroidView(
                    factory = { SurfaceView(it) },
                    update = { player.setVideoSurfaceView(it) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 13.sp,
                    color = if (status.startsWith("UWAGA")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { if (channel > 1) channel-- }) { Text("−") }
                Text("Kanał $channel", color = MaterialTheme.colorScheme.onBackground)
                OutlinedButton(onClick = { channel++ }) { Text("+") }
                OutlinedButton(onClick = { sub = !sub }) { Text(if (sub) "Strumień pomocniczy" else "Strumień główny") }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.activated) OutlinedButton(onClick = { pass = ""; asking = true }) { Text("Wpisz dane logowania") }
                if (creds != null) OutlinedButton(onClick = { attempt++ }) { Text("Połącz ponownie") }
            }
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Logowanie do kamer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubText("Dane zapiszą się (szyfrowane) w Ustawieniach użytkownika i będą używane domyślnie dla wszystkich kamer. Przy błędnych danych próba jest wykonywana jeden raz.")
                    LabeledField("Login:", user, { user = it })
                    LabeledField("Hasło:", pass, { pass = it }, password = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (user.isNotBlank() && pass.isNotEmpty()) {
                        Prefs.saveCamCreds(ctx, user.trim(), pass)
                        creds = user.trim() to pass
                        status = ""
                        pass = ""
                        asking = false
                    }
                }) { Text("Zapisz i połącz") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Anuluj") } }
        )
    }
}

/** Domyślny login i hasło do kamer Hikvision (Ustawienia użytkownika → Kamery Hikvision). */
@Composable
fun CamSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var user by remember { mutableStateOf(Prefs.camUser(ctx)) }
    var pass by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(
            when {
                Prefs.camCreds(ctx) == null -> ""
                Prefs.camBad(ctx) -> "Zapisane dane zostały odrzucone przez kamerę — wpisz poprawne."
                else -> "Dane są zapisane. Wpisz nowe, żeby zmienić."
            }
        )
    }
    AppScaffold("Kamery Hikvision", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SubText("Domyślny login i hasło używane przy podglądzie kamer z SADP. Są szyfrowane w pamięci telefonu. Przy odrzuceniu danych przez kamerę aplikacja nie ponawia próby.")
            LabeledField("Login:", user, { user = it })
            LabeledField("Hasło:", pass, { pass = it }, password = true)
            Button(
                onClick = {
                    if (user.isBlank()) { status = "Wpisz login."; return@Button }
                    if (pass.isEmpty()) { status = "Wpisz hasło."; return@Button }
                    status = if (Prefs.saveCamCreds(ctx, user.trim(), pass)) { pass = ""; "Zapisano ✓" }
                    else "Nie udało się zapisać (brak dostępu do magazynu kluczy)."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz dane") }
            OutlinedButton(
                onClick = { Prefs.clearCamCreds(ctx); user = "admin"; pass = ""; status = "Wyczyszczono" },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Wyczyść dane") }
            if (status.isNotEmpty()) SubText(status)
        }
    }
}
