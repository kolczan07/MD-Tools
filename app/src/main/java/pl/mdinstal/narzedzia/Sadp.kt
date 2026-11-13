package pl.mdinstal.narzedzia

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
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
private fun CamStat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = color)
            SubText(label)
        }
    }
}

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

    val cs = MaterialTheme.colorScheme
    val activeCount = devices.count { it.activated }
    AppScaffold("Kamery Hikvision", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CamStat(devices.size.toString(), "urządzeń", cs.onSurface, Modifier.weight(1f))
                CamStat(activeCount.toString(), "aktywnych", OnlineGreen, Modifier.weight(1f))
                CamStat((devices.size - activeCount).toString(), "nieaktywnych", cs.onSurfaceVariant, Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (scanning) OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") }
                else Button(onClick = { scan() }) { Text("Szukaj ponownie") }
                SubText(if (scanning) "Szukanie w sieci…" else "Wyszukiwanie SADP zakończone")
            }
            if (!scanning && devices.isEmpty()) {
                SubText(
                    "Nie znaleziono urządzeń. Połącz telefon z tą samą siecią co kamery (Wi-Fi lub hotspot) i spróbuj ponownie.",
                    Modifier.padding(top = 16.dp)
                )
            } else {
                SubText("Tylko wyszukiwanie — aplikacja niczego nie zmienia w urządzeniach. Dotknij kamery, aby zobaczyć obraz.", Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                items(devices.sortedBy { ipKey(it.ip) }, key = { it.ip + it.mac }) { d ->
                    AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { selected = d }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(if (d.activated) OnlineGreen else cs.outline))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.ip, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = cs.onSurface)
                                    SubText(d.model)
                                }
                                StatusPill(if (d.activated) "Aktywna" else "Nieaktywna", d.activated)
                            }
                            SubText(
                                listOf(
                                    d.serial.takeIf { it.isNotEmpty() }?.let { "SN: $it" },
                                    d.mac.takeIf { it.isNotEmpty() }?.let { "MAC: ${it.uppercase()}" },
                                    d.firmware.takeIf { it.isNotEmpty() }?.let { "FW: $it" }
                                ).filterNotNull().joinToString(" • "),
                                Modifier.padding(top = 8.dp)
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

/** Liczba kanałów rejestratora z nazwy modelu (np. DS-7608NXI → 8). Kamery i nieznane modele: 1. */
private fun nvrChannels(model: String): Int =
    Regex("""^i?DS-[789]\d(\d{2})""", RegexOption.IGNORE_CASE).find(model.trim())?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it in 1..64 } ?: 1

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun CameraView(d: SadpDevice, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val saved = remember { Prefs.camCreds(ctx) }
    var creds by remember { mutableStateOf(saved) }
    var asking by remember { mutableStateOf(saved == null && d.activated) }
    var channel by remember { mutableStateOf(1) }
    var sub by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    var live by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            when {
                !d.activated -> "Kamera nie jest aktywowana — aktywuj ją najpierw w SADP na komputerze."
                else -> ""
            }
        )
    }
    var user by remember { mutableStateOf(Prefs.camUser(ctx)) }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }

    val vlc = remember { LibVLC(ctx.applicationContext, arrayListOf("--network-caching=300")) }
    val player = remember { MediaPlayer(vlc) }

    DisposableEffect(Unit) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { live = true; status = "" }
                MediaPlayer.Event.EncounteredError -> {
                    live = false
                    status = "UWAGA: nie udało się odtworzyć obrazu — to może być błędny login/hasło albo kodek (strumień główny bywa w H.265). Nie ponawiam automatycznie. Sprawdź dane albo zmień strumień."
                }
            }
        }
        onDispose {
            player.setEventListener(null)
            try { player.stop() } catch (e: Exception) { }
            try { player.detachViews() } catch (e: Exception) { }
            player.release()
            vlc.release()
        }
    }

    LaunchedEffect(creds, channel, sub, attempt) {
        val c = creds ?: return@LaunchedEffect
        live = false
        status = "Łączenie…"
        Prefs.setCamBad(ctx, false)
        player.stop()
        val path = "Streaming/Channels/$channel" + (if (sub) "02" else "01")
        val media = Media(vlc, Uri.parse("rtsp://${d.ip}:554/$path"))
        // Login i hasło jako osobne opcje — znaki takie jak @ w haśle nie psują adresu.
        media.addOption(":rtsp-user=${c.first}")
        media.addOption(":rtsp-pwd=${c.second}")
        media.addOption(":rtsp-tcp")
        media.addOption(":network-caching=300")
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()
        player.play()
    }

    val phase = when {
        live -> "Na żywo"
        creds == null -> "Brak danych"
        status.startsWith("UWAGA") || status.startsWith("Błąd") || status.startsWith("Nie udało") -> "Błąd"
        else -> "Łączenie"
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxCh = remember(d.model) { nvrChannels(d.model) }
    var flash by remember { mutableStateOf("") }

    // Po obrocie powstaje nowa powierzchnia wideo, a działający już strumień nie przenosi się na nią
    // (został dźwięk i czarny ekran) — dlatego po zmianie orientacji łączymy się od nowa.
    var firstOrientation by remember { mutableStateOf(true) }
    LaunchedEffect(landscape) {
        if (firstOrientation) firstOrientation = false else attempt++
    }

    // Pełny ekran w poziomie: chowamy paski systemowe; wyjście tylko przyciskiem wstecz.
    DisposableEffect(landscape) {
        val w = ctx.findActivity()?.window
        val ctrl = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape && ctrl != null) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctrl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(channel) {
        if (landscape && maxCh > 1) {
            flash = "Kanał $channel"
            delay(1500)
            flash = ""
        }
    }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { VLCVideoLayout(it).also { v -> try { player.detachViews() } catch (e: Exception) { }; player.attachViews(v, null, false, false) } },
                modifier = Modifier.fillMaxSize()
            )
            // Przezroczysta warstwa na gesty: przesunięcie w lewo/prawo zmienia kanał (rejestratory).
            Box(
                Modifier.fillMaxSize().pointerInput(maxCh) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (maxCh > 1) {
                                if (total < -120f && channel < maxCh) channel++
                                else if (total > 120f && channel > 1) channel--
                            }
                        },
                        onHorizontalDrag = { _, dx -> total += dx }
                    )
                }
            )
            val overlay = if (!live) (if (creds == null) "Podgląd wyłączony" else status.ifEmpty { "Łączenie…" }) else flash
            if (overlay.isNotEmpty()) {
                Text(
                    overlay,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    } else AppScaffold(d.model, onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (live) OnlineGreen else cs.outline))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.ip, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = cs.onSurface)
                        SubText(d.model)
                    }
                    StatusPill(phase, live)
                }
            }

            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { VLCVideoLayout(it).also { v -> try { player.detachViews() } catch (e: Exception) { }; player.attachViews(v, null, false, false) } },
                    modifier = Modifier.fillMaxSize()
                )
                if (!live) {
                    Text(
                        if (creds == null) "Podgląd wyłączony" else "Łączenie…",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            SubText("Obróć telefon w poziom, aby zobaczyć obraz na pełnym ekranie" + if (maxCh > 1) " (przesunięcie palcem zmienia kanał)." else ".")

            if (status.isNotEmpty() && !status.startsWith("Łączenie")) {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(
                        status,
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        color = if (status.startsWith("UWAGA")) cs.error else cs.onSurfaceVariant
                    )
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Strumień", fontWeight = FontWeight.Bold, color = cs.onSurface)
                    OutlinedButton(onClick = { sub = !sub }, modifier = Modifier.fillMaxWidth()) {
                        Text("Strumień: " + if (sub) "pomocniczy (dotknij: główny)" else "główny (dotknij: pomocniczy)")
                    }
                    if (maxCh > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { if (channel > 1) channel-- }) { Text("−") }
                            Text("Kanał $channel z $maxCh", color = cs.onSurface)
                            OutlinedButton(onClick = { if (channel < maxCh) channel++ }) { Text("+") }
                        }
                    }
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Urządzenie", fontWeight = FontWeight.Bold, color = cs.onSurface)
                    if (d.serial.isNotEmpty()) SubText("Numer seryjny: ${d.serial}")
                    if (d.mac.isNotEmpty()) SubText("MAC: ${d.mac.uppercase()}")
                    if (d.firmware.isNotEmpty()) SubText("Firmware: ${d.firmware}")
                    SubText("Port HTTP: ${d.httpPort}")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.activated) OutlinedButton(onClick = { pass = ""; asking = true }) { Text("Dane logowania") }
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
                    LabeledField("Hasło:", pass, { pass = it }, password = !showPass)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showPass, onCheckedChange = { showPass = it })
                        Text("Pokaż hasło", color = MaterialTheme.colorScheme.onSurface)
                    }
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
    var showPass by remember { mutableStateOf(false) }
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
            LabeledField("Hasło:", pass, { pass = it }, password = !showPass)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showPass, onCheckedChange = { showPass = it })
                Text("Pokaż hasło", color = MaterialTheme.colorScheme.onSurface)
            }
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
