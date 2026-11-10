package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DETAIL_PORTAL_URL = "https://service.iglutech.eu"

/** Opis jednego parametru z config/render. */
private class Param(
    val keyword: String,
    val valueType: String,
    val inputType: String,
    val scale: Double,
    val units: String,
    val access: String,
    val group: String,
    val groupOrder: Int,
    val order: Int,
    /** Dla pól typu lista: wartość -> opis (np. "true" -> "Curve2"). */
    val range: Map<String, String>
)

private class Detail(
    val title: String,
    val sw: String,
    val sbc: String,
    val connection: String,
    val alarm: Boolean,
    val warning: Boolean,
    val params: List<Param>,
    val current: Map<String, Any?>,
    val desired: Map<String, Any?>
)

private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)

private fun toMap(o: JSONObject?): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    if (o == null) return m
    val it = o.keys()
    while (it.hasNext()) {
        val k = it.next()
        m[k] = if (o.isNull(k)) null else o.get(k)
    }
    return m
}

/** Pobiera szczegóły, stan i opis parametrów (trzy żądania do API). Wołać poza wątkiem interfejsu. */
private fun loadDetail(p: Pump, token: String): Detail {
    val hp = IgluApi.heatPump(p.id, token)
    val st = IgluApi.state(p.id, token)
    val cfg = IgluApi.config(p.serial, token)

    val params = ArrayList<Param>()
    for (i in 0 until cfg.length()) {
        val o = cfg.getJSONObject(i)
        val g = o.optJSONObject("group")
        val range = LinkedHashMap<String, String>()
        val ra = o.optJSONArray("rangeValues")
        if (ra != null) {
            for (j in 0 until ra.length()) {
                val r = ra.getJSONObject(j)
                range[r.s("value")] = r.s("displayValue")
            }
        }
        params.add(
            Param(
                keyword = o.s("keyword"),
                valueType = o.s("valueType"),
                inputType = o.s("inputType"),
                scale = o.optDouble("scale", 1.0),
                units = o.s("units"),
                access = o.s("access"),
                group = g?.s("name") ?: "Inne",
                groupOrder = g?.optInt("sortOrder", 999) ?: 999,
                order = o.optInt("sortOrder"),
                range = range
            )
        )
    }

    val sd = hp.optJSONObject("systemData")
    val custom = hp.s("customName")
    return Detail(
        title = if (custom.isBlank()) p.serial else custom,
        sw = sd?.s("swVersion").orEmpty(),
        sbc = sd?.s("sbcVersion").orEmpty(),
        connection = sd?.s("connectionType").orEmpty(),
        alarm = sd?.optBoolean("alarm") ?: false,
        warning = sd?.optBoolean("warning") ?: false,
        params = params,
        current = toMap(st.optJSONObject("currentState")),
        desired = toMap(st.optJSONObject("desiredState"))
    )
}

/** Liczba miejsc po przecinku wynika z kroku pola (scale): 0.1 -> 1, 0.01 -> 2 itd. */
private fun decimals(scale: Double): Int = when {
    scale <= 0.0001 -> 4
    scale <= 0.001 -> 3
    scale <= 0.01 -> 2
    scale <= 0.1 -> 1
    else -> 0
}

/** Wartość do wyświetlenia w polu (bez jednostki): opis z listy, Tak/Nie dla logicznych, liczba jak na portalu. */
private fun valueText(v: Any?, p: Param): String {
    if (v == null) return "—"
    val raw = v.toString()
    p.range[raw]?.trim()?.let { return it }
    if (v is Boolean) return if (v) "☑ Tak" else "☐ Nie"
    if (p.valueType == "decimal" && v is Number) {
        return String.format(Locale.US, "%.${decimals(p.scale)}f", v.toDouble())
    }
    return raw
}

/** Jedna ramka ze strumienia: czas odbioru i wartości liczbowe (logiczne jako 1/0). */
private class LiveFrame(val t: Long, val values: Map<String, Double>)

/** Ile historii trzymamy w pamięci dla wykresów trybu Live. */
private const val LIVE_WINDOW_MS = 10 * 60 * 1000L

private fun appendFrame(old: List<LiveFrame>, p: Map<String, Any?>): List<LiveFrame> {
    val now = System.currentTimeMillis()
    val vals = LinkedHashMap<String, Double>()
    for ((k, v) in p) {
        when (v) {
            is Boolean -> vals[k] = if (v) 1.0 else 0.0
            is Number -> vals[k] = v.toDouble()
            else -> {}
        }
    }
    if (vals.isEmpty()) return old
    return (old + LiveFrame(now, vals)).filter { it.t >= now - LIVE_WINDOW_MS }
}

private fun clockText(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))

/** Wartość z wykresu w postaci zrozumiałej dla valueText (Boolean, Long albo Double). */
private fun chartValue(v: Double, p: Param): Any = when {
    p.valueType == "boolean" -> v > 0.5
    p.valueType != "decimal" && v == Math.floor(v) && Math.abs(v) < 1e12 -> v.toLong()
    else -> v
}

/** Zapisuje ramki jako CSV (średnik, przecinek dziesiętny: otwiera się w Excelu) i otwiera okno udostępniania. */
private fun exportCsv(ctx: android.content.Context, serial: String, frames: List<LiveFrame>, keys: List<String>) {
    val sb = StringBuilder()
    sb.append("czas;").append(keys.joinToString(";")).append("\n")
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    for (f in frames) {
        sb.append(fmt.format(Date(f.t)))
        for (k in keys) {
            sb.append(';')
            f.values[k]?.let { sb.append(it.toString().replace('.', ',')) }
        }
        sb.append("\n")
    }
    val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
    val file = File(dir, "Live_${serial}.csv")
    file.writeText("\uFEFF" + sb.toString(), Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Eksport CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Niewidoczny WebView, który ładuje portal tylko po to, żeby sesja (ciasteczka) odnowiła token
 * w pamięci strony; potem odczytuje go do IgluToken. Kończy po zdobyciu tokenu albo po 30 s.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TokenFetchView(onDone: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
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
                CookieManager.getInstance().setAcceptCookie(true)
                setBackgroundColor(0xFF070B14.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setOnTouchListener { _, _ -> true }
                webViewClient = WebViewClient()
                web = this
                loadUrl(DETAIL_PORTAL_URL)
            }
        },
        onRelease = { it.destroy() }
    )

    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 30_000 && !IgluToken.valid()) {
            delay(1500)
            try {
                val raw = w.evalJs(TOKEN_JS)
                if (!raw.isNullOrBlank()) {
                    val t = JSONObject(raw).optString("t")
                    if (t.isNotBlank()) IgluToken.set(t)
                }
            } catch (e: Exception) {
            }
        }
        onDone()
    }
}

/** Most JS -> Kotlin dla strumienia na żywo (wywołania z wątku WebView przekazujemy na wątek główny). */
private class LiveBridge(
    private val onParams: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun data(json: String) { main.post { onParams(json) } }

    @JavascriptInterface
    fun status(s: String) { main.post { onStatus(s) } }
}

/**
 * Strumień na żywo (SignalR, protokół JSON) z https://signalr.iglutech.eu/hubs/devicePayload-stream.
 * Tak samo robi portal: handshake {"protocol":"json","version":1}, potem wywołanie
 * SubscribeToServiceDeviceStream z numerem seryjnym; w odpowiedzi przychodzą ServiceStreamData z pełnym
 * zestawem parametrów (także odczyty analogowe: temperatury, ciśnienia, wyjścia, liczniki), mniej więcej co 25 s.
 * Połączenie robi ukryty WebView z adresem bazowym portalu (żeby origin się zgadzał), bez dodatkowych bibliotek.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveStreamView(
    serial: String,
    token: String,
    onParams: (Map<String, Any?>) -> Unit,
    onStatus: (String) -> Unit
) {
    val html = """
<html><body><script>
(function(){
  var RS=String.fromCharCode(30);
  var TOKEN=${JSONObject.quote(token)};
  var SERIAL=${JSONObject.quote(serial)};
  var ws=null, timer=null;
  function send(o){ try{ ws.send(JSON.stringify(o)+RS); }catch(e){} }
  function connect(){
    Android.status('łączenie…');
    try{
      ws=new WebSocket('wss://signalr.iglutech.eu/hubs/devicePayload-stream?access_token='+encodeURIComponent(TOKEN));
    }catch(e){ Android.status('błąd: '+e); return; }
    ws.onopen=function(){ ws.send(JSON.stringify({protocol:'json',version:1})+RS); };
    ws.onmessage=function(ev){
      String(ev.data).split(RS).forEach(function(part){
        if(!part) return;
        var o; try{ o=JSON.parse(part); }catch(e){ return; }
        if(o.type===undefined){
          if(o.error){ Android.status('błąd: '+o.error); return; }
          send({type:6});
          send({arguments:[SERIAL],target:'SubscribeToServiceDeviceStream',type:1});
        } else if(o.type===1 && o.target==='OnSubscribeSuccess'){
          Android.status('połączono');
        } else if(o.type===1 && o.target==='ServiceStreamData'){
          var a=(o.arguments||[])[0];
          if(a && a.parameters){ Android.data(JSON.stringify(a.parameters)); Android.status('połączono'); }
        } else if(o.type===6){
          send({type:6});
        } else if(o.type===7){
          Android.status('rozłączono przez serwer');
        }
      });
    };
    ws.onclose=function(){
      Android.status('rozłączono (ponawiam)');
      clearTimeout(timer); timer=setTimeout(connect,5000);
    };
    ws.onerror=function(){};
  }
  connect();
  setInterval(function(){ if(ws && ws.readyState===1) send({type:6}); },15000);
})();
</script></body></html>
"""
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(0xFF070B14.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setOnTouchListener { _, _ -> true }
                addJavascriptInterface(
                    LiveBridge(
                        onParams = { json ->
                            try { onParams(toMap(JSONObject(json))) } catch (e: Exception) { }
                        },
                        onStatus = onStatus
                    ),
                    "Android"
                )
                webViewClient = WebViewClient()
                loadDataWithBaseURL(DETAIL_PORTAL_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() }
    )
}

/**
 * Ekran szczegółów pompy: stan, wersje oprogramowania i wszystkie parametry (tylko odczyt),
 * pogrupowane tak jak w portalu. Parametry bez wartości w stanie pompy są pomijane.
 */
@Composable
fun PumpDetailScreen(pump: Pump, onBack: () -> Unit, onOpenPortal: () -> Unit) {
    var tokenOk by remember { mutableStateOf(IgluToken.valid()) }
    var tokenFailed by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    // Edycja jest domyślnie wyłączona i znika po wyjściu z ekranu.
    var editMode by remember(pump.serial) { mutableStateOf(false) }
    var confirmEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Param?>(null) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    // Odczyty na żywo ze strumienia SignalR (nakładają się na stan z API) i stan połączenia.
    var live by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var liveStatus by remember { mutableStateOf("") }
    // Tryb Live: strumień łączy się tylko wtedy, edycja jest zablokowana, a odczyty rysują wykresy.
    var liveMode by remember(pump.serial) { mutableStateOf(false) }
    var paused by remember(pump.serial) { mutableStateOf(false) }
    var frames by remember(pump.serial) { mutableStateOf<List<LiveFrame>>(emptyList()) }
    var chartSel by remember(pump.serial) { mutableStateOf<Set<String>>(emptySet()) }
    val pausedNow by rememberUpdatedState(paused)
    val liveNow by rememberUpdatedState(liveMode)
    // Jednorazowy odczyt ze strumienia przy otwarciu i po "Odśwież": analogi nie są w stanie z API.
    var snapshot by remember(pump.serial) { mutableStateOf(true) }
    val effective: Map<String, Any?> = remember(detail, live) { (detail?.current ?: emptyMap()) + live }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload, tokenOk) {
        snapshot = true
        delay(30_000)
        snapshot = false
    }

    LaunchedEffect(tokenOk, reload) {
        if (!tokenOk || pump.id <= 0L) return@LaunchedEffect
        val token = IgluToken.token ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            detail = withContext(Dispatchers.IO) { loadDetail(pump, token) }
        } catch (e: Exception) {
            error = e.message ?: "Nieznany błąd"
            if (e.message?.contains("401") == true) {
                // Token odrzucony: następna próba pobierze nowy z sesji portalu.
                IgluToken.clear()
                tokenOk = false
                tokenFailed = true
            }
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!tokenOk && !tokenFailed && pump.id > 0L) {
            TokenFetchView(onDone = {
                tokenOk = IgluToken.valid()
                if (!tokenOk) tokenFailed = true
            })
        }

        val streamToken = IgluToken.token
        if ((liveMode || snapshot) && tokenOk && streamToken != null && pump.id > 0L) {
            // Klucz = token: po odnowieniu tokenu strumień łączy się od nowa.
            key(streamToken) {
                LiveStreamView(
                    serial = pump.serial,
                    token = streamToken,
                    onParams = {
                        if (!pausedNow) {
                            live = live + it
                            if (liveNow) frames = appendFrame(frames, it)
                        }
                        snapshot = false
                    },
                    onStatus = { liveStatus = it }
                )
            }
        }

        AppScaffold(pump.serial, onBack) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp)
            ) {
                val d = detail
                when {
                    pump.id <= 0L -> {
                        Text(
                            "Brak identyfikatora tej pompy. Wróć do listy i poczekaj, aż odświeży się z API " +
                                "(linijka „Źródło danych” ma pokazać „API OK”).",
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Button(onClick = onOpenPortal, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Otwórz w portalu")
                        }
                    }
                    tokenFailed -> {
                        Text(
                            (error ?: "Nie udało się pobrać tokenu z sesji portalu.") +
                                " Otwórz portal Iglutech i zaloguj się, potem spróbuj ponownie.",
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                error = null
                                tokenFailed = false
                                tokenOk = IgluToken.valid()
                                reload++
                            }) { Text("Spróbuj ponownie") }
                            TextButton(onClick = onOpenPortal) { Text("Otwórz w portalu") }
                        }
                    }
                    d == null && (loading || !tokenOk) -> {
                        Row(
                            Modifier.padding(top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Pobieranie danych pompy…")
                        }
                    }
                    d == null -> {
                        Text(error ?: "Brak danych.", modifier = Modifier.padding(top = 12.dp))
                        Button(onClick = { reload++ }, modifier = Modifier.padding(top = 12.dp)) { Text("Spróbuj ponownie") }
                    }
                    else -> DetailBody(
                        pump = pump,
                        d = d,
                        loading = loading,
                        error = error,
                        query = query,
                        onQuery = { query = it },
                        onRefresh = { reload++ },
                        onOpenPortal = onOpenPortal,
                        editMode = editMode,
                        onToggleEdit = { if (editMode) editMode = false else confirmEdit = true },
                        onEdit = { editing = it },
                        saveMsg = saveMsg,
                        current = effective,
                        liveStatus = liveStatus,
                        liveMode = liveMode,
                        onToggleLive = {
                            liveMode = !liveMode
                            if (liveMode) editMode = false else liveStatus = ""
                            paused = false
                        },
                        paused = paused,
                        onTogglePause = { paused = !paused },
                        frames = frames,
                        chartSel = chartSel,
                        onChartSel = { k, on -> chartSel = if (on) chartSel + k else chartSel - k }
                    )
                }
            }
        }
    }

    if (confirmEdit) {
        AlertDialog(
            onDismissRequest = { confirmEdit = false },
            title = { Text("Włączyć edycję parametrów?") },
            text = {
                Text(
                    "Zmiany są wysyłane do prawdziwej pompy (${pump.serial}). Każdą zmianę potwierdzisz osobno. " +
                        "Aplikacja nie zna limitów wartości, pilnuje ich tylko serwer Iglutech."
                )
            },
            confirmButton = { TextButton(onClick = { editMode = true; confirmEdit = false }) { Text("Włącz") } },
            dismissButton = { TextButton(onClick = { confirmEdit = false }) { Text("Anuluj") } }
        )
    }

    val ep = editing
    val dnow = detail
    if (ep != null && dnow != null) {
        EditDialog(
            p = ep,
            current = effective[ep.keyword],
            onDismiss = { editing = null },
            onSave = { value ->
                editing = null
                val token = IgluToken.token
                if (token == null) {
                    saveMsg = "Brak tokenu, odśwież ekran i spróbuj ponownie."
                } else {
                    scope.launch {
                        saveMsg = "Wysyłanie…"
                        try {
                            withContext(Dispatchers.IO) { IgluApi.setParam(pump.id, ep.keyword, value, token) }
                            saveMsg = "Wysłano: ${ep.keyword} = $value. Pompa zastosuje zmianę po chwili (zobacz „zadane”)."
                            reload++
                        } catch (e: Exception) {
                            saveMsg = "Błąd zapisu: ${e.message}"
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun DetailBody(
    pump: Pump,
    d: Detail,
    loading: Boolean,
    error: String?,
    query: String,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenPortal: () -> Unit,
    editMode: Boolean,
    onToggleEdit: () -> Unit,
    onEdit: (Param) -> Unit,
    saveMsg: String?,
    current: Map<String, Any?>,
    liveStatus: String,
    liveMode: Boolean,
    onToggleLive: () -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    frames: List<LiveFrame>,
    chartSel: Set<String>,
    onChartSel: (String, Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val q = query.trim().lowercase()
    val groups = remember(d, current.size) {
        d.params
            .filter { current.containsKey(it.keyword) || it.group.equals("Analogs", ignoreCase = true) }
            .groupBy { it.group }
            .entries
            .sortedBy { e -> e.value.first().groupOrder }
            .map { e -> e.key to e.value.sortedBy { it.order } }
    }
    var selected by remember(pump.serial) { mutableStateOf<String?>(null) }
    val active = groups.firstOrNull { it.first == selected }?.first
        ?: groups.firstOrNull { it.first == "UserSettings" }?.first
        ?: groups.firstOrNull()?.first
    val shown: List<Param> =
        if (q.isNotEmpty()) groups.flatMap { it.second }.filter { it.keyword.lowercase().contains(q) }
        else groups.firstOrNull { it.first == active }?.second.orEmpty()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("${pump.serial} · ${pump.model}", fontSize = 14.sp)
                    Text("Status: ${if (pump.status.isBlank()) "—" else pump.status}", fontSize = 14.sp)
                    Text(
                        "Alarmy: ${if (d.alarm) "TAK" else "nie"} · Ostrzeżenia: ${if (d.warning) "TAK" else "nie"}",
                        fontSize = 14.sp
                    )
                    Text(
                        "Oprogramowanie: ${d.sw.ifBlank { "—" }} · SBC: ${d.sbc.ifBlank { "—" }}" +
                            if (d.connection.isNotBlank()) " · ${d.connection}" else "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (error != null) {
                        Text("Błąd odświeżania: $error", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (saveMsg != null) {
                        Text(saveMsg, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "Na żywo: ${liveStatus.ifBlank { "—" }}" +
                            if (liveMode) " · ramek: ${frames.size}" +
                                (frames.lastOrNull()?.let { " · ostatnia: ${clockText(it.t)}" } ?: "") else "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = liveMode, onCheckedChange = { onToggleLive() })
                        Text(
                            if (liveMode) "  Tryb Live włączony (edycja zablokowana)" else "  Tryb Live wyłączony",
                            fontSize = 14.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = editMode, enabled = !liveMode, onCheckedChange = { onToggleEdit() })
                        Text(
                            if (editMode) "  Edycja włączona (dotknij pola z ✎)" else "  Edycja parametrów wyłączona",
                            fontSize = 14.sp
                        )
                    }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(enabled = !loading, onClick = onRefresh) { Text(if (loading) "Odświeżanie…" else "Odśwież") }
                        TextButton(onClick = onOpenPortal) { Text("Otwórz w portalu") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Szukaj parametru (we wszystkich grupach)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
        if (liveMode) {
            item {
                Row(
                    Modifier.padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
                        Text(if (paused) "Wznów" else "Pauza")
                    }
                    Button(
                        enabled = frames.isNotEmpty(),
                        onClick = {
                            val keys = d.params.map { it.keyword }
                                .filter { k -> (if (chartSel.isEmpty()) true else k in chartSel) && frames.any { it.values.containsKey(k) } }
                            exportCsv(ctx, pump.serial, frames, keys)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Eksport CSV") }
                }
            }
            val charted = d.params.filter { it.keyword in chartSel && frames.any { f -> f.values.containsKey(it.keyword) } }
            if (frames.isEmpty()) {
                item {
                    Text(
                        "Czekam na dane ze strumienia (pierwsza ramka może przyjść po kilkunastu sekundach)…",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            } else if (charted.isEmpty()) {
                item {
                    Text(
                        "Zaznacz poniżej parametry, które mają się rysować na wykresach.",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
            items(charted, key = { "c_" + it.keyword }) { p -> LiveChart(p, frames) }
            item {
                Text("Filtry wykresów", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            val avail = frames.flatMap { it.values.keys }.toSet()
            val list = d.params
                .filter { it.keyword in avail && (q.isEmpty() || it.keyword.lowercase().contains(q)) }
                .sortedWith(compareBy({ it.groupOrder }, { it.order }))
            items(list, key = { "f_" + it.keyword }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChartSel(p.keyword, p.keyword !in chartSel) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = p.keyword in chartSel, onCheckedChange = { onChartSel(p.keyword, it) })
                    Text(p.keyword + if (p.units.isNotBlank()) " (${p.units})" else "", fontSize = 15.sp)
                }
            }
        } else if (q.isEmpty()) {
            // Zakładki grup jak na portalu (przewijane w bok).
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEach { (name, list) ->
                        val on = name == active
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { selected = name }
                        ) {
                            Text(
                                "$name (${list.size})",
                                fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    active ?: "",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }
        if (!liveMode) items(shown, key = { "p_" + it.keyword }) { p ->
            val editable = editMode && canEdit(p)
            FieldRow(p, current[p.keyword], d.desired[p.keyword], editable, locked = editMode && p.access == "RW" && !editable) { onEdit(p) }
        }
    }
}

/** Wykres jednego parametru z historii ramek: linia (liczby) albo schodki (stany 0/1). */
@Composable
private fun LiveChart(p: Param, frames: List<LiveFrame>) {
    val pts = frames.mapNotNull { f -> f.values[p.keyword]?.let { f.t to it } }
    val isBool = p.valueType == "boolean"
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                p.keyword + if (p.units.isNotBlank()) " (${p.units})" else "",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            val last = pts.lastOrNull()?.second
            Text(
                "Teraz: " + if (last == null) "—" else valueText(chartValue(last, p), p),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pts.size < 2) {
                Text("Zbieram punkty…", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                val t0 = pts.first().first
                val t1 = pts.last().first
                var lo = if (isBool) 0.0 else pts.minOf { it.second }
                var hi = if (isBool) 1.0 else pts.maxOf { it.second }
                if (!isBool) {
                    if (hi - lo < 1e-9) {
                        lo -= 1.0
                        hi += 1.0
                    } else {
                        val pad = (hi - lo) * 0.1
                        lo -= pad
                        hi += pad
                    }
                }
                val loF = lo
                val hiF = hi
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(top = 8.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val span = (t1 - t0).coerceAtLeast(1L).toFloat()
                    fun px(t: Long): Float = (t - t0).toFloat() / span * w
                    fun py(v: Double): Float = h - ((v - loF) / (hiF - loF)).toFloat() * h
                    drawLine(gridColor, Offset(0f, h), Offset(w, h), 1f)
                    drawLine(gridColor, Offset(0f, 0f), Offset(0f, h), 1f)
                    val path = Path()
                    pts.forEachIndexed { i, (t, v) ->
                        val x = px(t)
                        val y = py(v)
                        if (i == 0) path.moveTo(x, y)
                        else {
                            if (isBool) path.lineTo(x, py(pts[i - 1].second))
                            path.lineTo(x, y)
                        }
                    }
                    drawPath(path, lineColor, style = Stroke(width = 3f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clockText(t0), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!isBool) {
                        Text(
                            "min ${valueText(chartValue(pts.minOf { it.second }, p), p)} · max ${valueText(chartValue(pts.maxOf { it.second }, p), p)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(clockText(t1), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Jedno pole parametru jak na portalu: podpis z jednostką, pod nim wartość w ramce (tylko odczyt). */
@Composable
private fun FieldRow(p: Param, cur: Any?, des: Any?, editable: Boolean, locked: Boolean, onEdit: () -> Unit) {
    val readOnly = p.access == "R"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
    ) {
        Text(
            p.keyword + if (p.units.isNotBlank()) " (${p.units})" else "",
            fontSize = 14.sp
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .border(
                    if (editable) 2.dp else 1.dp,
                    if (editable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(8.dp)
                )
                .then(if (editable) Modifier.clickable { onEdit() } else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                valueText(cur, p),
                fontSize = 16.sp,
                color = if (readOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (p.inputType == "Select") {
                Text("▾", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editable) {
                Text("  ✎", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (readOnly) {
                Text(
                    "  tylko odczyt",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (locked) {
            Text(
                "harmonogram: edycja wyłączona",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        // Gdy zadana wartość różni się od aktualnej, pokazujemy ją pod spodem.
        if (des != null && des.toString() != cur?.toString()) {
            Text(
                "zadane: ${valueText(des, p)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Grupy parametrów serwisowych/instalacyjnych: przy ich zmianie pokazujemy mocniejsze ostrzeżenie. */
private val SENSITIVE_GROUPS = setOf("Manufacture", "Installation", "Service")

/** Harmonogram tygodniowy (ScheduleS1Period1, ScheduleS1Period1Mode, ScheduleS1Weekdays, ScheduleTemperatures...). */
private fun isSchedule(p: Param): Boolean = p.keyword.startsWith("Schedule")

/**
 * Czy parametr można zmieniać z aplikacji: tak, gdy API podaje go jako RW (limity z ParamLimits sprawdzamy w oknie edycji).
 * Wyjątek: parametry harmonogramu, bo ich kodowanie jest nieznane i nie są u nas używane.
 */
private fun canEdit(p: Param): Boolean = p.access == "RW" && !isSchedule(p)

private fun sameValue(a: Any?, b: Any?): Boolean {
    if (a == null || b == null) return a == b
    if (a is Number && b is Number) return a.toDouble() == b.toDouble()
    return a.toString() == b.toString()
}

/** Liczba do wysłania: całkowita jako Long (np. 61), inaczej Double (np. 23.5). */
private fun numberOut(d: Double): Any = if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong() else d

/**
 * Okno zmiany jednego parametru: pole liczbowe albo lista opcji (Tak/Nie i listy z portalu).
 * Pokazuje starą i nową wartość, wysyła dopiero po dotknięciu przycisku.
 */
@Composable
private fun EditDialog(p: Param, current: Any?, onDismiss: () -> Unit, onSave: (Any) -> Unit) {
    val isBool = p.valueType == "boolean"
    val options: List<Pair<String, String>> = when {
        p.range.isNotEmpty() -> p.range.entries.map { it.key to it.value.trim() }
        isBool -> listOf("true" to "Tak", "false" to "Nie")
        else -> emptyList()
    }
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    var choice by remember { mutableStateOf(current?.toString()) }

    val limit = ParamLimits.MIN_MAX[p.keyword]
    val parsed = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    // Bez wpisu w ParamLimits aplikacja nie sprawdza zakresu (pilnuje go tylko serwer Iglutech).
    val inRange = parsed != null && (limit == null || (parsed >= limit.first && parsed <= limit.second))
    val stepOk = parsed != null && (p.scale <= 0.0 ||
        Math.abs(parsed / p.scale - Math.round(parsed / p.scale).toDouble()) < 1e-6)
    val newValue: Any? = if (options.isNotEmpty()) {
        choice?.let { c -> if (isBool) c.toBooleanStrictOrNull() else (c.toDoubleOrNull()?.let { numberOut(it) } ?: c) }
    } else {
        if (parsed != null && inRange && stepOk) numberOut(parsed) else null
    }
    val changed = newValue != null && !sameValue(newValue, current)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.keyword + if (p.units.isNotBlank()) " (${p.units})" else "") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aktualnie: ${valueText(current, p)}", fontSize = 14.sp)
                if (options.isNotEmpty()) {
                    options.forEach { (v, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { choice = v },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = choice == v, onClick = { choice = v })
                            Text(label, fontSize = 15.sp)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Nowa wartość" + if (p.units.isNotBlank()) " (${p.units})" else "") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (limit != null) {
                        Text(
                            "Zakres: ${limit.first} – ${limit.second}" +
                                (if (p.units.isNotBlank()) " ${p.units}" else "") +
                                (if (p.scale > 0.0 && p.scale < 1.0) ", krok ${p.scale}" else ""),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (limit == null) {
                        Text(
                            "Brak limitu w pliku rejestrów: aplikacja nie sprawdza zakresu tej wartości.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (parsed != null && limit != null && !inRange) {
                        Text("Wartość poza zakresem, nie zostanie wysłana.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    } else if (parsed != null && !stepOk) {
                        Text("Wartość niezgodna z krokiem ${p.scale}.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (changed && newValue != null) {
                    Text("Nowa: ${valueText(newValue, p)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                if (p.keyword in ParamLimits.READ_ONLY) {
                    Text(
                        "Plik rejestrów oznacza ten parametr jako tylko do odczytu, choć API pozwala go zmienić.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (p.group in SENSITIVE_GROUPS) {
                    Text(
                        "To parametr serwisowy/instalacyjny. Zmiana może wpłynąć na pracę pompy.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = changed, onClick = { newValue?.let { onSave(it) } }) { Text("Wyślij do pompy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}
