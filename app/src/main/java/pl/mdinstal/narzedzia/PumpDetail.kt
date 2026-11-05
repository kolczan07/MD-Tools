package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DETAIL_PORTAL_URL = "https://service.iglutech.eu"

/** Opis jednego parametru z config/render. */
private class Param(
    val keyword: String,
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

/** Wartość do wyświetlenia: opis z listy, Tak/Nie dla logicznych, liczba z jednostką. */
private fun fmt(v: Any?, p: Param): String {
    if (v == null) return "—"
    val raw = v.toString()
    p.range[raw]?.let { return it }
    if (v is Boolean) return if (v) "Tak" else "Nie"
    return if (p.units.isBlank()) raw else "$raw ${p.units}"
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
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

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
                        expanded = expanded,
                        onRefresh = { reload++ },
                        onOpenPortal = onOpenPortal
                    )
                }
            }
        }
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
    expanded: MutableMap<String, Boolean>,
    onRefresh: () -> Unit,
    onOpenPortal: () -> Unit
) {
    val q = query.trim().lowercase()
    val groups = remember(d, q) {
        d.params
            .filter { d.current.containsKey(it.keyword) && (q.isEmpty() || it.keyword.lowercase().contains(q)) }
            .groupBy { it.group }
            .entries
            .sortedBy { e -> e.value.first().groupOrder }
            .map { e -> e.key to e.value.sortedBy { it.order } }
    }

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
                label = { Text("Szukaj parametru") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
        groups.forEach { (name, list) ->
            val open = q.isNotEmpty() || expanded[name] == true
            item(key = "g_$name") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded[name] = !open }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (if (open) "▼ " else "▶ ") + name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${list.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (open) {
                items(list, key = { "p_" + it.keyword }) { p ->
                    val cur = d.current[p.keyword]
                    val des = d.desired[p.keyword]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.keyword + if (p.access == "R") "  (tylko odczyt)" else "",
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(fmt(cur, p), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        // Gdy zadana wartość różni się od aktualnej, pokazujemy ją pod spodem.
                        if (des != null && des.toString() != cur?.toString()) {
                            Text(
                                "zadane: ${fmt(des, p)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
