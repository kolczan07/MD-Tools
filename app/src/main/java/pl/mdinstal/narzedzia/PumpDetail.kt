package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // Edycja jest domyślnie wyłączona i znika po wyjściu z ekranu.
    var editMode by remember(pump.serial) { mutableStateOf(false) }
    var confirmEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Param?>(null) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                        onRefresh = { reload++ },
                        onOpenPortal = onOpenPortal,
                        editMode = editMode,
                        onToggleEdit = { if (editMode) editMode = false else confirmEdit = true },
                        onEdit = { editing = it },
                        saveMsg = saveMsg
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
            current = dnow.current[ep.keyword],
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
    saveMsg: String?
) {
    val q = query.trim().lowercase()
    val groups = remember(d) {
        d.params
            .filter { d.current.containsKey(it.keyword) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = editMode, onCheckedChange = { onToggleEdit() })
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
        if (q.isEmpty()) {
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
        items(shown, key = { "p_" + it.keyword }) { p ->
            val editable = editMode && canEdit(p)
            FieldRow(p, d, editable, locked = editMode && p.access == "RW" && !editable) { onEdit(p) }
        }
    }
}

/** Jedno pole parametru jak na portalu: podpis z jednostką, pod nim wartość w ramce (tylko odczyt). */
@Composable
private fun FieldRow(p: Param, d: Detail, editable: Boolean, locked: Boolean, onEdit: () -> Unit) {
    val cur = d.current[p.keyword]
    val des = d.desired[p.keyword]
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
                "edycja zablokowana (brak limitu w pliku rejestrów albo parametr tylko do odczytu)",
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

/** Czy parametr można zmieniać z aplikacji: tak, gdy API podaje go jako RW (limity z ParamLimits sprawdzamy w oknie edycji). */
private fun canEdit(p: Param): Boolean = p.access == "RW"

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
