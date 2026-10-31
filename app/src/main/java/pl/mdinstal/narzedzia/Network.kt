@file:OptIn(ExperimentalMaterial3Api::class)

package pl.mdinstal.narzedzia

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable
fun NetworkScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    AppScaffold("Sieć", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(Modifier.fillMaxWidth(), onClick = { onOpen(Screen.Speedtest) }) {
                Column(Modifier.padding(20.dp)) {
                    Text("Prędkość internetu", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    SubText("Speed Test (FIREPROBE)")
                }
            }
            AppCard(Modifier.fillMaxWidth(), onClick = { onOpen(Screen.Wifi) }) {
                Column(Modifier.padding(20.dp)) {
                    Text("Skaner WiFi", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    SubText("Analiza sieci i siły sygnału")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpeedtestScreen(onBack: () -> Unit) {
    AppScaffold("Speed Test", onBack) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    
                    // --- POPRAWKI DLA ZBYT MAŁEGO EKRANU ---
                    // 1. Włączamy obsługę gestów (pinch-to-zoom)
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false // Ukrywa brzydkie przyciski +/- 
                    
                    // 2. Wymuszamy tryb responsywny i dopasowanie do ekranu
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.textZoom = 100
                    
                    setBackgroundColor(android.graphics.Color.WHITE)
                    loadUrl("https://md-instal.fireprobe.net")
                }
            },
            onRelease = { it.destroy() }
        )
    }
}

private data class WifiNet(
    val ssid: String,
    val level: Int,
    val freq: Int,
    val channel: Int,
    val security: String,
    val connected: Boolean
) {
    val is24: Boolean get() = freq < 3000
    val strength: Int get() = ((level + 100) * 1.66).toInt().coerceIn(0, 100)
}

private fun channelOf(freq: Int): Int = when {
    freq == 2484 -> 14
    freq in 2400..2500 -> (freq - 2407) / 5
    freq in 4900..5895 -> (freq - 5000) / 5
    freq >= 5925 -> (freq - 5950) / 5
    else -> 0
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
            WifiNet(
                ssid = r.SSID.ifEmpty { "(ukryta sieć)" },
                level = r.level,
                freq = r.frequency,
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
    var filter by remember { mutableStateOf("all") }

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
                delay(3000)
            }
        }
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
                    options = listOf("Wszystkie", "2.4 GHz", "5 GHz"),
                    selected = setOf(when (filter) { "2.4" -> "2.4 GHz"; "5" -> "5 GHz"; else -> "Wszystkie" }),
                    onToggle = { filter = when (it) { "2.4 GHz" -> "2.4"; "5 GHz" -> "5"; else -> "all" } }
                )
                val shown = when (filter) {
                    "2.4" -> nets.filter { it.is24 }
                    "5" -> nets.filter { !it.is24 }
                    else -> nets
                }
                if (shown.isEmpty()) {
                    SubText(
                        "Skanowanie… (jeśli lista jest pusta, włącz lokalizację i Wi-Fi)",
                        Modifier.padding(top = 16.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown) { n -> WifiRow(n) }
                }
            }
        }
    }
}

@Composable
private fun WifiRow(n: WifiNet) {
    val color = when {
        n.connected -> Color(0xFF3B82F6)
        n.strength > 66 -> Color(0xFF16A34A)
        n.strength > 33 -> Color(0xFFEAB308)
        else -> Color(0xFFDC2626)
    }
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(
                        n.ssid,
                        modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                SubText(
                    "Kanał: ${n.channel} (${if (n.is24) "2.4G" else "5G"}) • ${n.security}",
                    Modifier.padding(start = 18.dp, top = 4.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                Text("${n.level} dBm", fontWeight = FontWeight.Bold, color = color)
                SubText("${n.strength}%")
            }
        }
    }
}
