package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val ASAJ = "https://www.asaj.pl"

/** Jeden wiersz z listy dokumentów handlowych. */
class AsajDoc(val type: String, val number: String, val date: String, val gross: String, val pdfUrl: String) {
    /** Nazwa pliku z numerem i datą wystawienia, np. WZ-7638-2026-AA_2026-11-09.pdf */
    val fileName: String
        get() = number.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') + "_" + date.trim() + ".pdf"
}

/** Limit kredytowy: wartość dostępna (główna), nadana, pozostałe środki i termin płatności. */
class AsajLimit(val usable: String, val granted: String, val remaining: String, val term: String)

/** Odbiera wyniki skryptów JS z WebView (jedno wywołanie = jeden identyfikator). */
private class AsajBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun register(id: String): CompletableDeferred<String> {
        val d = CompletableDeferred<String>()
        pending[id] = d
        return d
    }

    fun drop(id: String) { pending.remove(id) }

    @JavascriptInterface
    fun done(id: String, payload: String) {
        pending.remove(id)?.complete(payload)
    }
}

/**
 * Sesja w ukrytym WebView na www.asaj.pl: portal ma ochronę Cloudflare, więc logowanie i pobieranie
 * robimy zapytaniami fetch z kontekstu strony (ciasteczka i odcisk przeglądarki zostają te same).
 */
private class AsajSession(private val web: WebView, private val bridge: AsajBridge) {
    private val lock = Mutex()
    private var seq = 0
    private var opened = false

    /** Wykonuje ciało funkcji async w stronie. Zwraca wartość z return albo rzuca IOException. */
    private suspend fun run(body: String, timeoutMs: Long = 45_000L): Any? {
        val id = "c" + (++seq)
        val d = bridge.register(id)
        val q = JSONObject.quote(id)
        val js = "(async function(){try{var __r=await (async function(){" + body + "})();" +
            "Android.done(" + q + ",JSON.stringify({ok:__r}));}catch(e){" +
            "Android.done(" + q + ",JSON.stringify({error:String(e&&e.message?e.message:e)}));}})();"
        withContext(Dispatchers.Main) { web.evaluateJavascript(js, null) }
        val raw = try {
            withTimeout(timeoutMs) { d.await() }
        } finally {
            bridge.drop(id)
        }
        val o = JSONObject(raw)
        if (o.has("error")) throw IOException(o.optString("error"))
        return o.opt("ok")
    }

    private suspend fun open() {
        withContext(Dispatchers.Main) { web.loadUrl("$ASAJ/moje-konto/person-data") }
        repeat(25) {
            delay(1000)
            val o = try {
                run("return {h:location.hostname,t:document.title,s:document.readyState};", 5_000L) as? JSONObject
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (o != null && o.optString("h") == "www.asaj.pl" && o.optString("s") == "complete" &&
                !o.optString("t").contains("moment", true) && !o.optString("t").contains("chwil", true)
            ) {
                opened = true
                return
            }
        }
        throw IOException("Nie udało się otworzyć portalu Asaj (ochrona przed botami?). Spróbuj ponownie.")
    }

    private suspend fun isLoggedIn(): Boolean {
        // Zalogowane strony mają link wylogowania, strona logowania i strony dla gości go nie mają.
        val o = run(
            "var r=await fetch('/moje-konto/index',{credentials:'same-origin'});" +
                "var t=await r.text();" +
                "return {ok:(r.url.indexOf('/login')<0)&&(t.indexOf('/logout')>=0)};"
        ) as JSONObject
        return o.optBoolean("ok", false)
    }

    private suspend fun ensureLogin(user: String, pass: String) {
        if (!opened) open()
        if (isLoggedIn()) return
        val body =
            "var b='app_username='+encodeURIComponent(" + JSONObject.quote(user) + ")" +
                "+'&app_password='+encodeURIComponent(" + JSONObject.quote(pass) + ")" +
                "+'&send='+encodeURIComponent('Zaloguj się');" +
                "var r=await fetch('/login',{method:'POST',credentials:'same-origin'," +
                "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b});" +
                "return r.status;"
        run(body)
        if (!isLoggedIn()) throw IOException("Logowanie nie powiodło się. Sprawdź login i hasło w Ustawieniach.")
    }

    suspend fun login(user: String, pass: String) = lock.withLock { ensureLogin(user, pass) }

    /** Jedna strona listy dokumentów (15 wierszy). Zwraca wiersze i informację, czy jest następna strona. */
    suspend fun docs(page: Int): Pair<List<AsajDoc>, Boolean> = lock.withLock {
        val js =
            "var page=" + page + ";" +
                "var url='/moje-konto/dokumenty-handlowe'+(page>1?'/pageno/'+page:'');" +
                "var r=await fetch(url,{credentials:'same-origin'});" +
                "if(r.url.indexOf('/login')>=0) throw new Error('Sesja wygasła');" +
                "var t=await r.text();" +
                "var d=new DOMParser().parseFromString(t,'text/html');" +
                "var out=[];" +
                "d.querySelectorAll('table.ordersTable tbody tr').forEach(function(tr){" +
                "var td=tr.querySelectorAll('td'); if(td.length<7) return;" +
                "var pdf=''; tr.querySelectorAll('a').forEach(function(a){" +
                "if((a.getAttribute('title')||'')==='Pobierz PDF') pdf=a.getAttribute('href')||''; });" +
                "var sp=td[5].querySelectorAll('span');" +
                "var g=(sp.length>1?sp[1].textContent:td[5].textContent).replace(/\\s+/g,' ').trim();" +
                "out.push({type:td[0].textContent.trim(),number:td[1].textContent.trim()," +
                "date:td[2].textContent.trim(),gross:g,pdf:pdf});});" +
                "var more=!!d.querySelector('a[href*=\"/pageno/'+(page+1)+'\"]');" +
                "return {rows:out,more:more,len:t.length,title:d.title};"
        val o = run(js) as JSONObject
        val a = o.getJSONArray("rows")
        val list = ArrayList<AsajDoc>()
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            list.add(AsajDoc(r.optString("type"), r.optString("number"), r.optString("date"), r.optString("gross"), r.optString("pdf")))
        }
        if (list.isEmpty() && page == 1) {
            throw IOException(
                "Lista dokumentów jest pusta (strona: „" + o.optString("title").take(40) + "”, " +
                    o.optInt("len") + " B). Sesja mogła nie zadziałać."
            )
        }
        list to o.optBoolean("more", false)
    }

    /** Limit kredytowy ze strony "Moje dane" (pola formularza prs_tradecredit_*). */
    suspend fun limitInfo(): AsajLimit = lock.withLock {
        val js =
            "var r=await fetch('/moje-konto/person-data',{credentials:'same-origin'});" +
                "if(r.url.indexOf('/login')>=0) throw new Error('Sesja wygasła');" +
                "var t=await r.text(); var d=new DOMParser().parseFromString(t,'text/html');" +
                "function v(id){var e=d.getElementById(id);" +
                "return e?(e.value||e.getAttribute('value')||'').replace(/\\s+/g,' ').trim():'';}" +
                "return {granted:v('prs_tradecredit_amount'),usable:v('prs_tradecredit_amount_to_use')," +
                "remaining:v('prs_tradecredit_available_amount'),term:v('prs_tradecredit_end_date')};"
        val o = run(js) as JSONObject
        AsajLimit(o.optString("usable"), o.optString("granted"), o.optString("remaining"), o.optString("term"))
    }

    /** Pobiera PDF jako bajty. Przy wygasłej sesji loguje się ponownie i próbuje jeszcze raz. */
    suspend fun pdf(url: String, user: String, pass: String): ByteArray = lock.withLock {
        val js =
            "var r=await fetch(" + JSONObject.quote(url) + ",{credentials:'same-origin'});" +
                "var b=await r.blob();" +
                "var isPdf=(b.type||'').indexOf('pdf')>=0;" +
                "if(!isPdf) return {pdf:false,status:r.status};" +
                "var data=await new Promise(function(res,rej){var fr=new FileReader();" +
                "fr.onload=function(){res(fr.result)};fr.onerror=rej;fr.readAsDataURL(b);});" +
                "return {pdf:true,b64:data.substring(data.indexOf(',')+1)};"
        var o = run(js, 90_000L) as JSONObject
        if (!o.optBoolean("pdf")) {
            ensureLogin(user, pass)
            o = run(js, 90_000L) as JSONObject
        }
        if (!o.optBoolean("pdf")) throw IOException("Serwer nie zwrócił pliku PDF (HTTP ${o.optInt("status")}).")
        Base64.decode(o.getString("b64"), Base64.DEFAULT)
    }
}

private fun asajDir(ctx: Context): File = File(ctx.cacheDir, "pdf/asaj").apply { mkdirs() }

private fun clearAsajCache(ctx: Context) {
    try {
        asajDir(ctx).listFiles()?.forEach { it.delete() }
    } catch (e: Exception) {
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun newAsajWebView(ctx: Context, bridge: AsajBridge): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(1, 1)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    CookieManager.getInstance().setAcceptCookie(true)
    addJavascriptInterface(bridge, "Android")
    webViewClient = WebViewClient()
}

private fun sharePdf(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Udostępnij PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Ekran hurtowni Asaj: dostępny limit w nagłówku i lista WZ z podglądem PDF. Wejście po odcisku palca. */
@Composable
fun AsajScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    val scope = rememberCoroutineScope()
    val bridge = remember { AsajBridge() }
    val web = remember { newAsajWebView(ctx, bridge) }
    val session = remember { AsajSession(web, bridge) }

    var unlocked by remember { mutableStateOf(false) }
    var lockMsg by remember { mutableStateOf("") }
    val hasCreds = remember { Prefs.asajCreds(ctx) != null }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableStateOf<AsajLimit?>(null) }
    var limitChecked by remember { mutableStateOf(false) }
    var docs by remember { mutableStateOf<List<AsajDoc>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showWz by remember { mutableStateOf(true) }
    var showFv by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<AsajDoc, File>?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }

    fun sel(d: AsajDoc): Boolean =
        (showWz && d.type.equals("WZ", ignoreCase = true)) || (showFv && d.type.equals("FV", ignoreCase = true))

    fun unlock() {
        authenticate(activity, "Asaj", "Potwierdź tożsamość") { ok ->
            unlocked = ok
            lockMsg = if (ok) "" else "Odblokowanie anulowane."
        }
    }

    fun reload() {
        val c = Prefs.asajCreds(ctx) ?: return
        scope.launch {
            busy = true
            error = null
            try {
                status = "Logowanie…"
                session.login(c.first, c.second)
                status = "Pobieranie dokumentów…"
                val first = session.docs(1)
                var all = first.first
                var p = 1
                var more = first.second
                // Lista nie jest filtrowana na serwerze, więc dociągamy strony, aż będzie dość wybranych dokumentów.
                while (more && p < 5 && all.count { sel(it) } < 15) {
                    val n = session.docs(p + 1)
                    all = all + n.first
                    p += 1
                    more = n.second
                }
                docs = all
                page = p
                hasMore = more
                status = "Pobieranie limitu…"
                limit = try {
                    session.limitInfo()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                limitChecked = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Nieznany błąd"
            } finally {
                status = ""
                busy = false
            }
        }
    }

    fun loadMore() {
        val c = Prefs.asajCreds(ctx) ?: return
        scope.launch {
            busy = true
            error = null
            try {
                var all = docs
                var p = page
                var more = hasMore
                var added = 0
                while (more && p < page + 5 && added < 10) {
                    val n = session.docs(p + 1)
                    val fresh = n.first.filter { x -> all.none { it.pdfUrl == x.pdfUrl && it.number == x.number } }
                    added += fresh.count { sel(it) }
                    all = all + fresh
                    p += 1
                    more = n.second
                }
                docs = all
                page = p
                hasMore = more
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Nieznany błąd"
            } finally {
                busy = false
            }
        }
    }

    fun openPdf(d: AsajDoc) {
        val c = Prefs.asajCreds(ctx) ?: return
        if (d.pdfUrl.isBlank()) { error = "Ten dokument nie ma pliku PDF."; return }
        scope.launch {
            opening = d.number
            error = null
            try {
                val bytes = session.pdf(d.pdfUrl, c.first, c.second)
                val f = File(asajDir(ctx), d.fileName)
                withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                viewing = d to f
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Nie udało się pobrać PDF."
            } finally {
                opening = null
            }
        }
    }

    LaunchedEffect(Unit) {
        clearAsajCache(ctx)
        unlock()
    }
    LaunchedEffect(unlocked) {
        if (unlocked && hasCreds) reload()
    }
    // Zmiana wyboru WZ/FV: jeśli na wczytanych stronach jest mało takich dokumentów, dociągamy starsze.
    LaunchedEffect(showWz, showFv) {
        if (unlocked && docs.isNotEmpty() && hasMore && !busy && docs.count { sel(it) } < 10) loadMore()
    }
    DisposableEffect(Unit) {
        onDispose {
            web.stopLoading()
            clearAsajCache(ctx)
        }
    }
    BackHandler(enabled = viewing != null) {
        viewing?.second?.delete()
        viewing = null
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { web }, modifier = Modifier.size(1.dp), onRelease = { it.destroy() })

        val v = viewing
        if (v != null) {
            PdfViewerScreen(v.first, v.second, onClose = {
                v.second.delete()
                viewing = null
            })
        } else {
            AppScaffold("Asaj", onBack) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                    when {
                        !hasCreds -> {
                            SubText("Brak danych logowania do Asaj.", Modifier.padding(top = 16.dp))
                            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) {
                                Text("Ustawienia logowania Asaj")
                            }
                        }
                        !unlocked -> {
                            SubText("Wymagane potwierdzenie odciskiem palca.", Modifier.padding(top = 16.dp))
                            if (lockMsg.isNotEmpty()) SubText(lockMsg)
                            Button(onClick = { unlock() }, modifier = Modifier.padding(top = 12.dp)) { Text("Odblokuj") }
                        }
                        else -> AsajBody(
                            docs = docs,
                            limit = limit,
                            limitChecked = limitChecked,
                            busy = busy,
                            status = status,
                            error = error,
                            opening = opening,
                            hasMore = hasMore,
                            query = query,
                            onQuery = { query = it },
                            showWz = showWz,
                            onShowWz = { v ->
                                showWz = v
                                if (!v && !showFv) showFv = true
                            },
                            showFv = showFv,
                            onShowFv = { v ->
                                showFv = v
                                if (!v && !showWz) showWz = true
                            },
                            onRefresh = { reload() },
                            onMore = { loadMore() },
                            onOpen = { openPdf(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AsajBody(
    docs: List<AsajDoc>,
    limit: AsajLimit?,
    limitChecked: Boolean,
    busy: Boolean,
    status: String,
    error: String?,
    opening: String?,
    hasMore: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    showWz: Boolean,
    onShowWz: (Boolean) -> Unit,
    showFv: Boolean,
    onShowFv: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (AsajDoc) -> Unit
) {
    val q = query.trim()
    val shown = docs.filter {
        ((showWz && it.type.equals("WZ", ignoreCase = true)) || (showFv && it.type.equals("FV", ignoreCase = true))) &&
            (q.isEmpty() || it.number.contains(q, ignoreCase = true))
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            AppCard(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dostępny limit", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val usable = limit?.usable.orEmpty()
                    Text(
                        if (usable.isNotBlank()) usable else if (limitChecked) "nie udało się odczytać z „Moje dane”" else "—",
                        fontSize = if (usable.isNotBlank()) 22.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (limit != null && usable.isNotBlank()) {
                        SubText(
                            "Nadany: ${limit.granted} · Pozostałe środki: ${limit.remaining}" +
                                if (limit.term.isNotBlank()) " · Termin: ${limit.term}" else ""
                        )
                    }
                    if (status.isNotEmpty()) SubText(status)
                    if (error != null) Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !busy, onClick = onRefresh) { Text(if (busy) "Chwila…" else "Odśwież") }
                        Checkbox(checked = showWz, onCheckedChange = onShowWz, modifier = Modifier.padding(start = 8.dp))
                        Text("WZ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Checkbox(checked = showFv, onCheckedChange = onShowFv)
                        Text("FV", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Szukaj numeru dokumentu") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
        items(shown, key = { it.pdfUrl + it.number }) { d ->
            AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { onOpen(d) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.number, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        SubText("${d.date} · brutto ${d.gross}")
                    }
                    if (opening == d.number) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("PDF", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (shown.isEmpty() && !busy) {
            item { SubText(if (docs.isEmpty()) "Brak dokumentów." else "Nic nie pasuje do filtra.", Modifier.padding(top = 8.dp)) }
        }
        if (hasMore) {
            item {
                OutlinedButton(
                    enabled = !busy,
                    onClick = onMore,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) { Text("Starsze dokumenty") }
            }
        }
    }
}

/** Renderer stron PDF (android.graphics.pdf), jedna strona naraz. */
private class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            val p = renderer.openPage(index)
            try {
                val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                p.close()
            }
        }
    }

    fun close() {
        try {
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
        }
    }
}

@Composable
private fun PdfViewerScreen(doc: AsajDoc, file: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val pdf = remember(file) { try { PdfDoc(file) } catch (e: Exception) { null } }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    var zoom by remember { mutableStateOf(1f) }
    val screenW = LocalConfiguration.current.screenWidthDp

    AppScaffold(
        doc.fileName,
        onClose,
        actions = {
            IconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }) { Text("−", fontSize = 22.sp) }
            IconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(3f) }) { Text("+", fontSize = 22.sp) }
            IconButton(onClick = { sharePdf(ctx, file) }) { Icon(Icons.Filled.Share, contentDescription = "Udostępnij") }
        }
    ) { pad ->
        if (pdf == null) {
            Text("Nie udało się otworzyć pliku PDF.", modifier = Modifier.padding(pad).padding(16.dp))
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .horizontalScroll(rememberScrollState())
            ) {
                LazyColumn(Modifier.width((screenW * zoom).dp)) {
                    items(pdf.pageCount) { i -> PdfPage(pdf, i) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDoc, index: Int) {
    val bmp by produceState<Bitmap?>(null, pdf, index) {
        value = try { pdf.render(index, 1600) } catch (e: Exception) { null }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )
    } else {
        Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** Login i hasło do asaj.pl, szyfrowane w pamięci telefonu tak jak dla Iglutech (osobne klucze). */
@Composable
fun AsajSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    var user by remember { mutableStateOf(Prefs.asajUser(ctx)) }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (Prefs.asajCreds(ctx) != null) "Dane są zapisane. Wpisz nowe, żeby zmienić." else "") }
    var confirmClear by remember { mutableStateOf(false) }

    AppScaffold("Logowanie Asaj", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SubText("Login i hasło do asaj.pl. Są szyfrowane w pamięci telefonu, a wejście na ekran Asaj chroni odcisk palca.")
            LabeledField("Login:", user, { user = it })
            LabeledField("Hasło:", pass, { pass = it }, password = true)
            Button(
                onClick = {
                    if (user.isBlank()) { status = "Wpisz login."; return@Button }
                    if (pass.isEmpty()) { status = "Wpisz hasło."; return@Button }
                    authenticate(activity, "Zapis danych logowania", "Potwierdź tożsamość") { ok ->
                        if (ok) {
                            if (Prefs.saveAsajCreds(ctx, user.trim(), pass)) {
                                pass = ""
                                status = "Zapisano ✓"
                            } else status = "Nie udało się zapisać (brak dostępu do magazynu kluczy)."
                        } else status = "Anulowano."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz dane") }
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Wyczyść dane") }
            if (status.isNotEmpty()) SubText(status)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Usunąć zapisane dane logowania Asaj?") },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.clearAsajCreds(ctx)
                    user = ""
                    pass = ""
                    status = "Wyczyszczono"
                    confirmClear = false
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } }
        )
    }
}
