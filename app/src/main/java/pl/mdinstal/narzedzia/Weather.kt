package pl.mdinstal.narzedzia

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

private data class WDay(val date: String, val code: Int, val tmax: Int, val tmin: Int)
private data class WHour(val time: String, val temp: Int, val code: Int, val rain: Int)
private data class Forecast(val days: List<WDay>, val hours: List<WHour>)

private fun parseForecast(json: String): Forecast {
    val root = JSONObject(json)
    val d = root.getJSONObject("daily")
    val t = d.getJSONArray("time")
    val days = (0 until t.length()).map { i ->
        WDay(
            t.getString(i),
            d.getJSONArray("weather_code").getInt(i),
            Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
            Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)).toInt()
        )
    }
    val hours = ArrayList<WHour>()
    val h = root.optJSONObject("hourly")
    if (h != null) {
        val ht = h.getJSONArray("time")
        val hc = h.getJSONArray("weather_code")
        val hw = h.getJSONArray("temperature_2m")
        val hr = h.optJSONArray("precipitation_probability")
        for (i in 0 until ht.length()) {
            hours.add(
                WHour(
                    ht.getString(i),
                    Math.round(hw.getDouble(i)).toInt(),
                    hc.getInt(i),
                    if (hr != null && !hr.isNull(i)) hr.getInt(i) else 0
                )
            )
        }
    }
    return Forecast(days, hours)
}

private fun weatherIcon(code: Int): String = when {
    code == 0 -> "☀️"
    code in 1..3 -> "⛅"
    code in 45..48 -> "🌫️"
    code in 51..67 -> "🌧️"
    code in 71..77 -> "❄️"
    code in 80..82 -> "🌧️"
    code in 85..86 -> "❄️"
    code >= 95 -> "⛈️"
    else -> "🌤️"
}

private fun weekday(date: String): String {
    val names = arrayOf("Niedziela", "Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek", "Sobota")
    return try {
        val cal = Calendar.getInstance()
        cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
        names[cal.get(Calendar.DAY_OF_WEEK) - 1]
    } catch (e: Exception) {
        date
    }
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(ctx: Context): Location? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
        try {
            val l = lm.getLastKnownLocation(p)
            if (l != null) return l
        } catch (e: Exception) { }
    }
    for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
        try {
            if (!lm.isProviderEnabled(p)) continue
            val l = withTimeoutOrNull(6000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cs = CancellationSignal()
                    cont.invokeOnCancellation { cs.cancel() }
                    LocationManagerCompat.getCurrentLocation(lm, p, cs, ContextCompat.getMainExecutor(ctx)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            }
            if (l != null) return l
        } catch (e: Exception) { }
    }
    return null
}

@Composable
fun WeatherScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ładowanie…") }
    var forecast by remember { mutableStateOf<Forecast?>(null) }
    var cityName by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var cityInput by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<Int>()) }

    fun fetch(lat: Double, lon: Double, name: String?) {
        scope.launch {
            status = "Ładowanie…"
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability&timezone=auto&forecast_days=7"
                forecast = withContext(Dispatchers.IO) { parseForecast(Http.get(url)) }
                cityName = name
                status = ""
            } catch (e: Exception) {
                status = "Błąd pobierania pogody."
            }
        }
    }

    fun locate() {
        scope.launch {
            status = "Ładowanie…"
            val loc = currentLocation(ctx)
            if (loc != null) fetch(loc.latitude, loc.longitude, null)
            else {
                status = "Brak dostępu do lokalizacji."
                manual = true
            }
        }
    }

    fun byCity() {
        val c = cityInput.trim()
        if (c.isEmpty()) { status = "Wpisz miasto."; return }
        scope.launch {
            status = "Szukam miasta…"
            try {
                val r = withContext(Dispatchers.IO) {
                    JSONObject(
                        Http.get(
                            "https://geocoding-api.open-meteo.com/v1/search?name=" +
                                URLEncoder.encode(c, "UTF-8") + "&count=1&language=pl&format=json"
                        )
                    )
                }
                val arr = r.optJSONArray("results")
                if (arr == null || arr.length() == 0) status = "Nie znaleziono miasta."
                else {
                    val o = arr.getJSONObject(0)
                    fetch(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("name"))
                }
            } catch (e: Exception) {
                status = "Błąd wyszukiwania."
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) locate() else { status = "Brak dostępu do lokalizacji."; manual = true }
    }

    LaunchedEffect(Unit) {
        val has = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (has) locate() else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    AppScaffold("Pogoda", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal = 14.dp)) {
            if (status.isNotEmpty()) SubText(status, Modifier.padding(top = 12.dp))
            if (manual) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubText("Nie udało się pobrać lokalizacji. Wpisz miasto:")
                    LabeledField("Miasto", cityInput, { cityInput = it })
                    Button(onClick = { byCity() }, modifier = Modifier.fillMaxWidth()) { Text("Pokaż pogodę") }
                }
            }
            val f = forecast
            if (f != null) {
                cityName?.let {
                    Text(it, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onBackground)
                }
                LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(f.days) { i, day ->
                        val label = when (i) { 0 -> "Dziś"; 1 -> "Jutro"; else -> weekday(day.date) }
                        val p = day.date.split("-")
                        val short = if (p.size == 3) "${p[2]}.${p[1]}" else day.date
                        AppCard(Modifier.fillMaxWidth(), onClick = {
                            expanded = if (i in expanded) expanded - i else expanded + i
                        }) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                    SubText(short)
                                }
                                Text(weatherIcon(day.code), fontSize = 26.sp)
                                Row(Modifier.width(90.dp), horizontalArrangement = Arrangement.End) {
                                    Text("${day.tmax}°", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text(" / ${day.tmin}°", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (i in expanded) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)).padding(10.dp)
                                ) {
                                    f.hours.filter { it.time.startsWith(day.date) }.forEach { h ->
                                        Column(Modifier.width(65.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(h.time.substringAfter('T').take(2) + ":00", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(weatherIcon(h.code), fontSize = 22.sp, modifier = Modifier.padding(vertical = 4.dp))
                                            Text("${h.temp}°", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                            Text("💧${h.rain}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
