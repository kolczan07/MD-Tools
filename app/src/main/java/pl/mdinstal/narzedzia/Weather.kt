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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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

private data class WDay(
    val date: String, val code: Int, val tmax: Int, val tmin: Int,
    val rainMax: Int = 0, val windMax: Int = 0, val rainSum: Double = 0.0,
    val sunrise: String = "", val sunset: String = ""
)
private data class WHour(val time: String, val temp: Int, val code: Int, val rain: Int)
private data class WNow(val time: String, val temp: Int, val feels: Int, val code: Int, val wind: Int)
private data class Forecast(val days: List<WDay>, val hours: List<WHour>, val now: WNow?)

private fun org.json.JSONArray?.intAt(i: Int): Int =
    if (this == null || i >= length() || isNull(i)) 0 else Math.round(getDouble(i)).toInt()

private fun parseForecast(json: String): Forecast {
    val root = JSONObject(json)
    val d = root.getJSONObject("daily")
    val t = d.getJSONArray("time")
    val rainMax = d.optJSONArray("precipitation_probability_max")
    val windMax = d.optJSONArray("wind_speed_10m_max")
    val rainSum = d.optJSONArray("precipitation_sum")
    val sunrise = d.optJSONArray("sunrise")
    val sunset = d.optJSONArray("sunset")
    val days = (0 until t.length()).map { i ->
        WDay(
            t.getString(i),
            d.getJSONArray("weather_code").getInt(i),
            Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
            Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
            rainMax.intAt(i),
            windMax.intAt(i),
            if (rainSum == null || i >= rainSum.length() || rainSum.isNull(i)) 0.0 else rainSum.getDouble(i),
            if (sunrise == null || i >= sunrise.length()) "" else sunrise.optString(i).substringAfter('T'),
            if (sunset == null || i >= sunset.length()) "" else sunset.optString(i).substringAfter('T')
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
    val c = root.optJSONObject("current")
    val now = c?.let {
        WNow(
            it.optString("time"),
            Math.round(it.optDouble("temperature_2m")).toInt(),
            Math.round(it.optDouble("apparent_temperature", it.optDouble("temperature_2m"))).toInt(),
            it.optInt("weather_code"),
            Math.round(it.optDouble("wind_speed_10m", 0.0)).toInt()
        )
    }
    return Forecast(days, hours, now)
}

internal fun weatherText(code: Int): String = when {
    code == 0 -> "Bezchmurnie"
    code in 1..2 -> "Częściowe zachmurzenie"
    code == 3 -> "Pochmurno"
    code in 45..48 -> "Mgła"
    code in 51..57 -> "Mżawka"
    code in 61..67 -> "Deszcz"
    code in 71..77 -> "Śnieg"
    code in 80..82 -> "Przelotny deszcz"
    code in 85..86 -> "Przelotny śnieg"
    code >= 95 -> "Burza"
    else -> ""
}

internal fun weatherIcon(code: Int): String = when {
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
    val names = arrayOf("Nd", "Pn", "Wt", "Śr", "Cz", "Pt", "So")
    return try {
        val cal = Calendar.getInstance()
        cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
        names[cal.get(Calendar.DAY_OF_WEEK) - 1]
    } catch (e: Exception) {
        date
    }
}

/** Nazwa miejscowości dla współrzędnych: Geocoder systemu, a gdy go brak, darmowy reverse geocoding BigDataCloud. */
@Suppress("DEPRECATION")
internal suspend fun placeName(ctx: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
    try {
        if (android.location.Geocoder.isPresent()) {
            val a = android.location.Geocoder(ctx, Locale("pl", "PL")).getFromLocation(lat, lon, 1)?.firstOrNull()
            val n = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            if (!n.isNullOrBlank()) return@withContext n
        }
    } catch (e: Exception) { }
    try {
        val o = JSONObject(Http.get("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=pl"))
        o.optString("city").ifBlank { o.optString("locality") }.ifBlank { null }
    } catch (e: Exception) {
        null
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
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                    "wind_speed_10m_max,precipitation_sum,sunrise,sunset" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability&timezone=auto&forecast_days=7"
                forecast = withContext(Dispatchers.IO) { parseForecast(Http.get(url)) }
                cityName = name
                WeatherWidget.saveLocation(ctx, lat, lon, name)
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
            if (loc != null) fetch(loc.latitude, loc.longitude, placeName(ctx, loc.latitude, loc.longitude))
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

    AppScaffold(
        "Pogoda", onBack,
        onRefresh = { if (manual) { if (cityInput.isNotBlank()) byCity() } else locate() },
        refreshing = status.startsWith("Ładowanie") || status.startsWith("Szukam")
    ) { pad ->
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
                WeatherContent(f, cityName, expanded) { i ->
                    expanded = if (i in expanded) expanded - i else expanded + i
                }
            }
        }
    }
}

@Composable
private fun WChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun WeatherContent(f: Forecast, cityName: String?, expanded: Set<Int>, onToggle: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val allMin = f.days.minOf { it.tmin }
    val span = (f.days.maxOf { it.tmax } - allMin).coerceAtLeast(1).toFloat()
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        f.now?.let { n ->
            item {
                val today = f.days.firstOrNull()
                val nowHour = n.time.take(13)
                val rainNow = f.hours.firstOrNull { it.time.take(13) >= nowHour }?.rain ?: 0
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                SubText(cityName ?: "Twoja lokalizacja")
                                Text("${n.temp}°", fontSize = 44.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                                SubText(weatherText(n.code) + " · odczuwalna ${n.feels}°")
                            }
                            Text(weatherIcon(n.code), fontSize = 56.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WChip("💨 ${n.wind} km/h", Modifier.weight(1f))
                            WChip("💧 Opady $rainNow%", Modifier.weight(1f))
                        }
                        if (today != null && today.sunrise.isNotBlank()) {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WChip("🌅 ${today.sunrise}", Modifier.weight(1f))
                                WChip("🌇 ${today.sunset}", Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        item {
            val nowHour = f.now?.time?.take(13)
            val start = if (nowHour == null) 0 else f.hours.indexOfFirst { it.time.take(13) >= nowHour }.coerceAtLeast(0)
            val list = f.hours.drop(start).take(24)
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    SubText("Najbliższe 24 godziny")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        list.forEachIndexed { i, h -> HourCell(h, if (i == 0 && nowHour != null) "Teraz" else h.time.substringAfter('T').take(2) + ":00", i == 0) }
                    }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    SubText("7 dni", Modifier.padding(top = 8.dp))
                    f.days.forEachIndexed { i, day ->
                        val label = when (i) { 0 -> "Dziś"; 1 -> "Jutro"; else -> weekday(day.date) }
                        val open = i in expanded
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(i) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, Modifier.width(52.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            Text(weatherIcon(day.code), Modifier.width(30.dp), fontSize = 20.sp)
                            Text("${day.rainMax}%", Modifier.width(40.dp), fontSize = 12.sp, color = if (day.rainMax > 0) cs.primary else cs.onSurfaceVariant)
                            Text("${day.tmin}°", Modifier.width(34.dp), fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            val st = ((day.tmin - allMin) / span).coerceIn(0f, 1f)
                            val en = ((day.tmax - allMin) / span).coerceIn(st, 1f)
                            Row(
                                Modifier.weight(1f).padding(horizontal = 8.dp).height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)
                            ) {
                                if (st > 0f) Spacer(Modifier.weight(st))
                                Box(Modifier.weight((en - st).coerceAtLeast(0.02f)).fillMaxHeight().background(cs.primary.copy(alpha = 0.75f)))
                                if (en < 1f) Spacer(Modifier.weight(1f - en))
                            }
                            Text("${day.tmax}°", Modifier.width(34.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                        }
                        if (open) {
                            val hrs = f.hours.filter {
                                it.time.startsWith(day.date) && (it.time.substringAfter('T').take(2).toIntOrNull() ?: 1) % 3 == 0
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) { hrs.forEach { h -> HourCell(h, h.time.substringAfter('T').take(2) + ":00", false) } }
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WChip("💨 do ${day.windMax} km/h", Modifier.weight(1f))
                                WChip("💧 Suma ${String.format(Locale.US, "%.1f", day.rainSum)} mm", Modifier.weight(1f))
                            }
                            if (day.sunrise.isNotBlank()) {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WChip("🌅 ${day.sunrise}", Modifier.weight(1f))
                                    WChip("🌇 ${day.sunset}", Modifier.weight(1f))
                                }
                            }
                        }
                        if (i < f.days.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun HourCell(h: WHour, label: String, highlight: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) cs.primary.copy(alpha = 0.12f) else cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 12.sp, color = cs.onSurfaceVariant)
        Text(weatherIcon(h.code), fontSize = 20.sp, modifier = Modifier.padding(vertical = 3.dp))
        Text("${h.temp}°", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Text(if (h.rain > 0) "${h.rain}%" else " ", fontSize = 11.sp, color = cs.primary)
    }
}
