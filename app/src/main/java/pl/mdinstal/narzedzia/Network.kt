@file:OptIn(ExperimentalMaterial3Api::class)

package pl.mdinstal.narzedzia

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ───────────────────────────── MENU ─────────────────────────────

@Composable
fun NetworkScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    AppScaffold("Sieć", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NetTile("Speedtest", "Prędkość internetu (FIREPROBE)") { onOpen(Screen.Speedtest) }
            NetTile("Skaner WiFi", "Spektrum, kanały i siła sygnału") { onOpen(Screen.Wifi) }
            NetTile("Skaner LAN", "Wykrywanie urządzeń w sieci lokalnej") { onOpen(Screen.Lan) }
            NetTile("Hotspot", "Ustawienia hotspotu i tetheringu telefonu (wbudowany serwer DHCP)") { openTetherSettings(ctx) }
        }
    }
}

@Composable
private fun NetTile(title: String, sub: String, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            SubText(sub)
        }
    }
}

// ───────────────────────────── SPEEDTEST ─────────────────────────────

/**
 * Ciemny motyw dla strony speedtestu (strona sama nie ma trybu ciemnego).
 * Odwraca kolory strony (invert + hue-rotate 180°), obrazki i wideo zostają bez zmian.
 * Tło strony #E7EEFC po odwróceniu daje dokładnie tło aplikacji (#0B1220).
 * Jeśli strona sama jest już ciemna, nic nie robi (żeby jej nie rozjaśnić).
 * on = true włącza, on = false usuwa styl.
 */
private fun speedDarkJs(on: Boolean): String = """
(function(){
  var id='md-dark', on=$on, el=document.getElementById(id);
  if(!on){ if(el) el.parentNode.removeChild(el); return; }
  if(el) return;
  function lum(c){
    var m=(c||'').match(/[0-9.]+/g);
    if(!m||m.length<3) return 1;
    if(m.length>3 && parseFloat(m[3])===0) return 1;
    return (0.299*m[0]+0.587*m[1]+0.114*m[2])/255;
  }
  var b=document.body;
  if(b && lum(getComputedStyle(b).backgroundColor)<0.35 && lum(getComputedStyle(document.documentElement).backgroundColor)<0.35) return;
  el=document.createElement('style'); el.id=id;
  el.textContent='html{background:#E7EEFC !important;filter:invert(1) hue-rotate(180deg) !important}'
    +'body{background-color:#E7EEFC !important}'
    +'img,video{filter:invert(1) hue-rotate(180deg) !important}';
  (document.head||document.documentElement).appendChild(el);
})();
"""

private fun applySpeedDark(view: WebView, on: Boolean) {
    view.evaluateJavascript(speedDarkJs(on), null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpeedtestScreen(onBack: () -> Unit) {
    // Motyw bierzemy z aplikacji (nie z systemu), więc ustawienie "Motyw aplikacji" działa tu tak samo.
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    val darkNow by rememberUpdatedState(dark)
    val bgNow by rememberUpdatedState(bg.toArgb())

    AppScaffold("Speed Test", onBack) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { c ->
                WebView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    setBackgroundColor(bgNow)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            Log.d("SpeedWV", "${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            applySpeedDark(view, darkNow)
                            // Strona dociąga style i układ po załadowaniu, więc sprawdzamy jeszcze raz.
                            view.postDelayed({ applySpeedDark(view, darkNow) }, 1500)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            Log.e("SpeedWV", "onReceivedError: ${error.description} for ${request.url}")
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            Log.e("SpeedWV", "HTTP ${errorResponse.statusCode} for ${request.url}")
                        }
                    }
                    loadUrl("https://md-instal.fireprobe.net")
                }
            },
            update = { wv ->
                // Zmiana motywu w Ustawieniach działa od razu, bez ponownego ładowania strony.
                wv.setBackgroundColor(bg.toArgb())
                applySpeedDark(wv, dark)
            },
            onRelease = { it.destroy() }
        )
    }
}

// ───────────────────────────── WIFI: MODEL ─────────────────────────────

private enum class WifiBand(val label: String) { B24("2.4 GHz"), B5("5 GHz"), B6("6 GHz") }

private fun bandOf(freq: Int): WifiBand = when {
    freq < 3000 -> WifiBand.B24
    freq >= 5925 -> WifiBand.B6
    else -> WifiBand.B5
}

private fun strengthOf(level: Int): Int = ((level + 100) * 1.66).toInt().coerceIn(0, 100)

private data class WifiNet(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val freq: Int,
    val center: Int,
    val width: Int,
    val channel: Int,
    val security: String,
    val connected: Boolean
) {
    val band: WifiBand get() = bandOf(freq)
    val strength: Int get() = strengthOf(level)
}

private data class WifiGroup(val ssid: String, val band: WifiBand, val nets: List<WifiNet>) {
    val best: Int get() = nets.maxOf { it.level }
    val channels: String get() = nets.map { it.channel }.distinct().sorted().joinToString(", ")
    val connected: Boolean get() = nets.any { it.connected }
}

private data class WifiConn(val ssid: String, val level: Int, val freq: Int, val linkSpeed: Int)

private data class ChanHealth(val channel: Int, val count: Int, val load: Float, val bestLevel: Int?)

private val SSID_COLORS = listOf(
    Color(0xFFFF8FA3), Color(0xFFB39DFF), Color(0xFFF2E96B), Color(0xFF5CD6E6), Color(0xFF6EDC8C),
    Color(0xFFFFA860), Color(0xFF6FA8FF), Color(0xFFE58AF0), Color(0xFF9BE15D), Color(0xFFFF6B6B)
)

private val GREEN = Color(0xFF16A34A)
private val YELLOW = Color(0xFFEAB308)
private val RED = Color(0xFFDC2626)

private fun levelColor(strength: Int): Color = when {
    strength > 66 -> GREEN
    strength > 33 -> YELLOW
    else -> RED
}

private fun channelOf(freq: Int): Int = when {
    freq == 2484 -> 14
    freq in 2400..2500 -> (freq - 2407) / 5
    freq in 4900..5895 -> (freq - 5000) / 5
    freq >= 5925 -> (freq - 5950) / 5
    else -> 0
}

private fun widthOf(w: Int): Int = when (w) {
    1 -> 40
    2 -> 80
    3, 4 -> 160
    5 -> 320
    else -> 20
}

private fun securityOf(cap: String): String = when {
    cap.contains("SAE") || cap.contains("WPA3") -> "WPA3"
    cap.contains("WPA2") || cap.contains("RSN") -> "WPA2"
    cap.contains("WPA") -> "WPA"
    cap.contains("WEP") -> "WEP"
    else -> "Otwarta"
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readWifi(wm: WifiManager): List<WifiNet> {
    val connected = try { wm.connectionInfo?.bssid } catch (e: Exception) { null }
    return try {
        wm.scanResults.map { r: ScanResult ->
            val w = widthOf(r.channelWidth)
            val c = if (w > 20 && r.centerFreq0 > 0) r.centerFreq0 else r.frequency
            WifiNet(
                ssid = r.SSID.ifEmpty { "(ukryta sieć)" },
                bssid = r.BSSID ?: "",
                level = r.level,
                freq = r.frequency,
                center = c,
                width = w,
                channel = channelOf(r.frequency),
                security = securityOf(r.capabilities ?: ""),
                connected = connected != null && connected == r.BSSID
            )
        }.sortedByDescending { it.level }
    } catch (e: Exception) {
        emptyList()
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readConn(wm: WifiManager): WifiConn? = try {
    val ci = wm.connectionInfo
    if (ci == null || ci.networkId == -1 || ci.frequency <= 0) null
    else WifiConn(ci.ssid?.trim('"') ?: "", ci.rssi, ci.frequency, ci.linkSpeed)
} catch (e: Exception) {
    null
}

private fun channelHealth(nets: List<WifiNet>, band: WifiBand): List<ChanHealth> {
    val chans: List<Int> = when (band) {
        WifiBand.B24 -> (1..13).toList()
        WifiBand.B5 -> listOf(
            36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120,
            124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165
        )
        WifiBand.B6 -> nets.map { it.channel }.distinct().sorted()
    }
    return chans.map { ch ->
        val fc = when (band) {
            WifiBand.B24 -> 2407 + 5 * ch
            WifiBand.B5 -> 5000 + 5 * ch
            WifiBand.B6 -> 5950 + 5 * ch
        }
        val chHalf = if (band == WifiBand.B24) 11 else 10
        val hits = nets.filter { n ->
            val nh = if (band == WifiBand.B24 && n.width == 20) 11 else n.width / 2
            abs(n.center - fc) < nh + chHalf
        }
        ChanHealth(ch, hits.size, hits.sumOf { it.strength / 100.0 }.toFloat(), hits.maxOfOrNull { it.level })
    }
}

// ───────────────────────────── WIFI: UI ─────────────────────────────

private const val DBM_TOP = -30
private const val DBM_BOTTOM = -100

@Composable
private fun SpectrumChart(nets: List<WifiNet>, band: WifiBand, colors: Map<String, Color>) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val txt = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val left = 34.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val (fMin, fMax) = when (band) {
            WifiBand.B24 -> 2397f to 2490f
            WifiBand.B5 -> 5160f to 5900f
            WifiBand.B6 -> 5940f to 7130f
        }

        fun fx(f: Float): Float = left + (f - fMin) / (fMax - fMin) * (right - left)
        fun ly(l: Int): Float =
            top + (DBM_TOP - l.coerceIn(DBM_BOTTOM, DBM_TOP)).toFloat() / (DBM_TOP - DBM_BOTTOM) * (bottom - top)

        val paint = android.graphics.Paint().apply {
            color = txt.toArgb()
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        val nc = drawContext.canvas.nativeCanvas

        // linie poziome (dBm)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        for (l in -40 downTo -90 step 10) {
            val yy = ly(l)
            drawLine(grid, Offset(left, yy), Offset(right, yy), 1f)
            nc.drawText("$l", left - 4.dp.toPx(), yy + 3.dp.toPx(), paint)
        }
        drawLine(grid, Offset(left, bottom), Offset(right, bottom), 1f)

        // linie pionowe (kanały)
        val marks: List<Pair<Int, Float>> = when (band) {
            WifiBand.B24 -> (1..13).map { it to (2407f + 5 * it) } + (14 to 2484f)
            WifiBand.B5 -> listOf(36, 44, 52, 60, 100, 108, 116, 124, 132, 140, 149, 157, 165).map { it to (5000f + 5 * it) }
            WifiBand.B6 -> listOf(1, 33, 65, 97, 129, 161, 193, 225).map { it to (5950f + 5 * it) }
        }
        paint.textAlign = android.graphics.Paint.Align.CENTER
        marks.forEach { (ch, f) ->
            val xx = fx(f)
            drawLine(grid, Offset(xx, top), Offset(xx, bottom), 1f)
            nc.drawText("$ch", xx, size.height - 4.dp.toPx(), paint)
        }

        // sieci
        clipRect(left, top, right, size.height) {
            nets.sortedBy { it.level }.forEach { n ->
                val color = colors[n.ssid] ?: Color.Gray
                val half = if (band == WifiBand.B24 && n.width == 20) 11f else n.width / 2f
                val slope = if (band == WifiBand.B24) 3f else 5f
                val c = n.center.toFloat()
                val yt = ly(n.level)
                val fill = Path().apply {
                    moveTo(fx(c - half - slope), bottom)
                    lineTo(fx(c - half), yt)
                    lineTo(fx(c + half), yt)
                    lineTo(fx(c + half + slope), bottom)
                    close()
                }
                val line = Path().apply {
                    moveTo(fx(c - half - slope), bottom)
                    lineTo(fx(c - half), yt)
                    lineTo(fx(c + half), yt)
                    lineTo(fx(c + half + slope), bottom)
                }
                drawPath(fill, color.copy(alpha = 0.18f))
                drawPath(line, color, style = Stroke(width = (if (n.connected) 3 else 2).dp.toPx()))
            }
        }
    }
}

@Composable
private fun ConnectedRow(c: WifiConn, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.ssid,
                    modifier = Modifier.weight(1f, fill = false),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Box(
                    Modifier.padding(start = 8.dp)
                        .border(1.dp, GREEN, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text("POŁĄCZONO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GREEN)
                }
            }
            SubText("Kanał: ${channelOf(c.freq)} • ${bandOf(c.freq).label} • ${c.linkSpeed} Mb/s")
        }
        Text("${c.level} dBm", fontWeight = FontWeight.Bold, color = levelColor(strengthOf(c.level)))
    }
}

@Composable
private fun WifiGroupRow(g: WifiGroup, color: Color, showBand: Boolean, expanded: Boolean, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        g.ssid,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SubText(
                        "Kanał: ${g.channels}" +
                            (if (showBand) " • ${g.band.label}" else "") +
                            (if (g.connected) " • połączono" else "")
                    )
                }
                Text("${g.best} dBm", fontWeight = FontWeight.Bold, color = levelColor(strengthOf(g.best)))
            }
            if (expanded) {
                g.nets.sortedByDescending { it.level }.forEach { n ->
                    SubText(
                        "${n.bssid.uppercase()}\nkan. ${n.channel} • ${n.width} MHz • ${n.security} • ${n.level} dBm",
                        Modifier.padding(top = 8.dp, start = 18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChanHealthRow(h: ChanHealth, best: Boolean) {
    val c = when {
        h.load < 0.4f -> GREEN
        h.load < 1.2f -> YELLOW
        else -> RED
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${h.channel}",
            modifier = Modifier.width(38.dp),
            fontWeight = if (best) FontWeight.Bold else FontWeight.Normal,
            color = if (best) GREEN else MaterialTheme.colorScheme.onSurface
        )
        Box(
            Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Box(Modifier.fillMaxWidth((h.load / 2f).coerceIn(0.03f, 1f)).fillMaxHeight().background(c))
        }
        Text(
            if (h.count == 0) "wolny" else "${h.count}× • ${h.bestLevel} dBm",
            modifier = Modifier.width(96.dp).padding(start = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun WifiScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var nets by remember { mutableStateOf<List<WifiNet>>(emptyList()) }
    var conn by remember { mutableStateOf<WifiConn?>(null) }
    var filter by remember { mutableStateOf<WifiBand?>(null) }
    var tab by remember { mutableStateOf(0) }
    var sortSignal by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    if (granted) {
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { nets = readWifi(wm) }
            }
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            nets = readWifi(wm)
            conn = readConn(wm)
            onDispose { try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { } }
        }
        LaunchedEffect(Unit) {
            while (true) {
                try { @Suppress("DEPRECATION") wm.startScan() } catch (e: Exception) { }
                delay(8000)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                nets = readWifi(wm)
                conn = readConn(wm)
                delay(3000)
            }
        }
    }

    val colors = remember(nets) {
        nets.map { it.ssid }.distinct().sorted().withIndex()
            .associate { (i, s) -> s to SSID_COLORS[i % SSID_COLORS.size] }
    }
    val groups = nets.groupBy { it.ssid to it.band }.map { (k, v) -> WifiGroup(k.first, k.second, v) }
    val counts = WifiBand.values().associateWith { b -> groups.count { it.band == b } }
    val connBand = conn?.let { bandOf(it.freq) }
    val specBand = filter ?: connBand ?: WifiBand.B24

    val bandOpts = listOf("Wszystkie (${groups.size})") + WifiBand.values().map { "${it.label} (${counts[it] ?: 0})" }
    val selBand = filter?.let { bandOpts[1 + it.ordinal] } ?: bandOpts[0]
    val tabOpts = listOf("Skan SSID", "Kondycja kanałów")

    val shown = groups
        .filter { filter == null || it.band == filter }
        .let { l -> if (sortSignal) l.sortedByDescending { it.best } else l.sortedBy { it.ssid.lowercase() } }

    val health = remember(nets, specBand) { channelHealth(nets.filter { it.band == specBand }, specBand) }
    val bestChannel: Int? = if (specBand == WifiBand.B6) null else {
        health.filter { specBand != WifiBand.B24 || it.channel in listOf(1, 6, 11) }
            .minWithOrNull(compareBy<ChanHealth>({ it.load }, { it.count }))?.channel
    }

    AppScaffold("Skaner WiFi", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            if (!granted) {
                SubText("Skaner potrzebuje uprawnienia do lokalizacji (wymóg Androida dla listy sieci Wi-Fi).", Modifier.padding(top = 16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Nadaj uprawnienie")
                }
            } else {
                ChipRow(
                    options = bandOpts,
                    selected = setOf(selBand),
                    onToggle = { opt ->
                        val i = bandOpts.indexOf(opt)
                        filter = if (i <= 0) null else WifiBand.values()[i - 1]
                    }
                )

                AppCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Spektrum",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            SubText(specBand.label)
                        }
                        SpectrumChart(nets.filter { it.band == specBand }, specBand, colors)
                        conn?.let { ConnectedRow(it, colors[it.ssid] ?: Color(0xFF3B82F6)) }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ChipRow(
                            options = tabOpts,
                            selected = setOf(tabOpts[tab]),
                            onToggle = { tab = tabOpts.indexOf(it).coerceAtLeast(0) }
                        )
                    }
                    TextButton(onClick = { sortSignal = !sortSignal }) {
                        Text(if (sortSignal) "↓ sygnał" else "A→Z")
                    }
                }

                if (tab == 0) {
                    if (shown.isEmpty()) {
                        SubText(
                            "Skanowanie… (jeśli lista jest pusta, włącz lokalizację i Wi-Fi)",
                            Modifier.padding(top = 16.dp)
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { "${it.ssid}|${it.band}" }) { g ->
                            val key = "${g.ssid}|${g.band}"
                            WifiGroupRow(
                                g = g,
                                color = colors[g.ssid] ?: Color.Gray,
                                showBand = filter == null,
                                expanded = expanded == key,
                                onClick = { expanded = if (expanded == key) null else key }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(top = 4.dp),
                    ) {
                        item {
                            SubText(
                                when {
                                    specBand == WifiBand.B6 -> "Zajęte kanały 6 GHz"
                                    bestChannel != null -> "Najmniej zajęty kanał (20 MHz): $bestChannel  •  ${specBand.label}"
                                    else -> specBand.label
                                },
                                Modifier.padding(bottom = 6.dp)
                            )
                        }
                        items(health, key = { it.channel }) { h -> ChanHealthRow(h, h.channel == bestChannel) }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── LAN: MODEL ─────────────────────────────

private data class LanInfo(val ip: String, val prefix: Int, val gateway: String?)

private data class LanHost(
    val ip: String,
    val name: String? = null,
    val mac: String? = null,
    val ports: List<Int> = emptyList(),
    val self: Boolean = false
)

private enum class TcpProbe { OPEN, REFUSED, NONE }

// porty istotne dla instalatora: SSH, telnet, DNS, WWW, SMB, Modbus TCP, MQTT, HA, alt-HTTP
private val LAN_PORTS = intArrayOf(22, 23, 53, 80, 443, 445, 502, 1883, 8080, 8123, 8443)

private fun ipToLong(ip: String): Long = ip.split(".").fold(0L) { a, p -> (a shl 8) or (p.toLong() and 0xFF) }

private fun longToIp(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

private fun lanMask(prefix: Int): Long {
    val p = prefix.coerceIn(22, 30)
    return (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
}

private fun lanHosts(li: LanInfo): List<String> {
    val m = lanMask(li.prefix)
    val net = ipToLong(li.ip) and m
    val last = net or (m.inv() and 0xFFFFFFFFL)
    return ((net + 1) until last).map { longToIp(it) }
}

private fun inLan(ip: String, li: LanInfo): Boolean = try {
    val m = lanMask(li.prefix)
    (ipToLong(ip) and m) == (ipToLong(li.ip) and m)
} catch (e: Exception) {
    false
}

@Suppress("DEPRECATION")
private fun readLan(ctx: Context): LanInfo? = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // szukamy sieci Wi-Fi / Ethernet (omijamy VPN, np. Tailscale, które bywa "aktywną" siecią)
    val net = cm.allNetworks.firstOrNull { n ->
        val caps = cm.getNetworkCapabilities(n)
        caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    val lp = if (net != null) cm.getLinkProperties(net) else null
    val la = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
    if (lp == null || la == null) null
    else {
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val ip = la.address.hostAddress
        if (ip == null) null else LanInfo(ip, la.prefixLength, gw)
    }
} catch (e: Exception) {
    null
}

private fun tcpProbe(ip: String, port: Int, timeoutMs: Int): TcpProbe = try {
    Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
    TcpProbe.OPEN
} catch (e: ConnectException) {
    // "refused" = host żyje, tylko port zamknięty
    if (e.message?.contains("refused", ignoreCase = true) == true) TcpProbe.REFUSED else TcpProbe.NONE
} catch (e: Exception) {
    TcpProbe.NONE
}

private suspend fun scanHost(ip: String, sem: Semaphore): LanHost? = coroutineScope {
    val reach = async {
        sem.withPermit { try { InetAddress.getByName(ip).isReachable(300) } catch (e: Exception) { false } }
    }
    val probes = LAN_PORTS.map { p -> async { sem.withPermit { p to tcpProbe(ip, p, 200) } } }.awaitAll()
    val alive = reach.await() || probes.any { it.second != TcpProbe.NONE }
    if (!alive) return@coroutineScope null
    val open = probes.filter { it.second == TcpProbe.OPEN }.map { it.first }
    val nameJob = async(Dispatchers.IO) {
        try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { ip }
    }
    val name = withTimeoutOrNull(1500) { nameJob.await() }?.takeIf { it != ip }
    LanHost(ip = ip, name = name, ports = open)
}

private fun webUrl(h: LanHost): String? = when {
    80 in h.ports -> "http://${h.ip}"
    443 in h.ports -> "https://${h.ip}"
    8080 in h.ports -> "http://${h.ip}:8080"
    8123 in h.ports -> "http://${h.ip}:8123"
    8443 in h.ports -> "https://${h.ip}:8443"
    else -> null
}

/** Otwiera systemowe ustawienia hotspotu/tetheringu; gdy producent ich nie udostępnia, ogólne ustawienia sieci. */
private fun openTetherSettings(ctx: Context) {
    val tries = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
    )
    for (i in tries) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) { }
    }
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}

// ───────────────────────────── LAN: UI ─────────────────────────────

@Composable
private fun SimpleProgress(frac: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun LanRowContent(h: LanHost, isGateway: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                h.ip,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            val tag = when {
                h.self -> "TO URZĄDZENIE"
                isGateway -> "BRAMA"
                else -> null
            }
            if (tag != null) {
                Text(tag, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        h.name?.let { SubText(it) }
        val extra = buildList {
            if (h.ports.isNotEmpty()) add("Porty: ${h.ports.joinToString(", ")}")
        }
        if (extra.isNotEmpty()) SubText(extra.joinToString(" • "), Modifier.padding(top = 2.dp))
    }
}

@Composable
fun LanScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(readLan(ctx)) }
    val hosts = remember { mutableStateListOf<LanHost>() }
    var scanning by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun startScan() {
        val li = readLan(ctx)
        info = li
        if (li == null || scanning) return
        val ips = lanHosts(li)
        hosts.clear()
        done = 0
        total = ips.size
        scanning = true
        job = scope.launch {
            val sem = Semaphore(64)
            try {
                coroutineScope {
                    ips.forEach { ip ->
                        launch(Dispatchers.IO) {
                            val h = if (ip == li.ip) LanHost(ip = ip, name = Build.MODEL, self = true) else scanHost(ip, sem)
                            withContext(Dispatchers.Main) {
                                if (h != null) hosts.add(h)
                                done++
                            }
                        }
                    }
                }
            } finally {
                scanning = false
            }
        }
    }

    LaunchedEffect(Unit) { startScan() }

    AppScaffold("Skaner LAN", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            val li = info
            if (li == null) {
                SubText(
                    "Brak połączenia z siecią Wi-Fi lub Ethernet. Połącz się z siecią lokalną i spróbuj ponownie.",
                    Modifier.padding(top = 16.dp)
                )
                Button(onClick = { startScan() }, modifier = Modifier.padding(top = 12.dp)) { Text("Spróbuj ponownie") }
            } else {
                AppCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "${li.ip}/${li.prefix}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SubText("Brama: ${li.gateway ?: "—"}")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (scanning) {
                        OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") }
                    } else {
                        Button(onClick = { startScan() }) { Text("Skanuj") }
                    }
                    SubText("$done/$total • znaleziono ${hosts.size}")
                }
                if (scanning) {
                    SimpleProgress(if (total > 0) done.toFloat() / total else 0f, Modifier.padding(top = 10.dp))
                }
                SubText("Dotknij urządzenia z portem WWW, aby otworzyć jego panel.", Modifier.padding(top = 10.dp))

                val sorted = hosts.sortedBy { ipToLong(it.ip) }
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sorted, key = { it.ip }) { h ->
                        val gw = h.ip == li.gateway
                        val url = webUrl(h)
                        if (url != null) {
                            AppCard(Modifier.fillMaxWidth(), onClick = { openUrl(ctx, url) }) { LanRowContent(h, gw) }
                        } else {
                            AppCard(Modifier.fillMaxWidth()) { LanRowContent(h, gw) }
                        }
                    }
                }
            }
        }
    }
}
