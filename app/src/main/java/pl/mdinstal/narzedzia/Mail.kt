package pl.mdinstal.narzedzia

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ───────────────────────────── MODEL ─────────────────────────────

data class MailRow(
    val id: String,
    val who: String,
    val subject: String,
    val snippet: String,
    val time: Long,
    val unread: Boolean,
    val count: Int
)

data class MailAttachment(val messageId: String, val attachmentId: String, val name: String, val mime: String, val size: Long)

data class MailMessage(
    val id: String,
    val from: String,
    val to: String,
    val time: Long,
    val html: String?,
    val text: String?,
    val attachments: List<MailAttachment>,
    val unread: Boolean,
    val snippet: String
)

data class MailThread(val id: String, val subject: String, val messages: List<MailMessage>)

/** Token Gmail wygasł (HTTP 401) — trzeba poprosić Google o nowy. */
class MailUnauthorized : IOException("Token Gmail wygasł")

// ───────────────────────────── GMAIL API ─────────────────────────────

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitMail(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

object GmailApi {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.modify"
    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()

    /** Prosi Google o token do Gmaila. Przy pierwszym razie zwraca ekran zgody (NeedsUi). */
    suspend fun authorize(ctx: Context, account: String?): DriveAuth {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (!account.isNullOrBlank()) b.setAccount(Account(account, "com.google"))
        val r = Identity.getAuthorizationClient(ctx).authorize(b.build()).awaitMail()
        val pi: PendingIntent? = r.pendingIntent
        return if (r.hasResolution() && pi != null) {
            DriveAuth.NeedsUi(pi)
        } else {
            val t = r.accessToken ?: throw IOException("Google nie zwrócił tokenu dostępu.")
            DriveAuth.Token(t, r.toGoogleSignInAccount()?.email)
        }
    }

    private fun errText(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun exec(token: String, b: Request.Builder): String {
        http.newCall(b.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (r.code == 401) throw MailUnauthorized()
            if (!r.isSuccessful) throw IOException("Gmail (HTTP ${r.code}) ${errText(body)}".trim())
            return body
        }
    }

    fun listThreads(token: String, label: String, pageToken: String?): Pair<List<String>, String?> {
        val u = "$API/threads".toHttpUrl().newBuilder()
            .addQueryParameter("maxResults", "20")
            .addQueryParameter("labelIds", label)
        if (label == "TRASH") u.addQueryParameter("includeSpamTrash", "true")
        if (!pageToken.isNullOrEmpty()) u.addQueryParameter("pageToken", pageToken)
        val j = JSONObject(exec(token, Request.Builder().url(u.build())))
        val arr = j.optJSONArray("threads") ?: JSONArray()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        return ids to j.optString("nextPageToken").ifEmpty { null }
    }

    private fun header(payload: JSONObject?, name: String): String {
        val hs = payload?.optJSONArray("headers") ?: return ""
        for (i in 0 until hs.length()) {
            val h = hs.getJSONObject(i)
            if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
        }
        return ""
    }

    private fun labelsOf(m: JSONObject): Set<String> {
        val a = m.optJSONArray("labelIds") ?: return emptySet()
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    /** Skrót wątku do listy (nagłówki From/To/Subject, bez treści). */
    fun threadRow(token: String, id: String, label: String): MailRow {
        val u = "$API/threads/$id".toHttpUrl().newBuilder()
            .addQueryParameter("format", "metadata")
            .addQueryParameter("metadataHeaders", "From")
            .addQueryParameter("metadataHeaders", "To")
            .addQueryParameter("metadataHeaders", "Subject")
            .build()
        val j = JSONObject(exec(token, Request.Builder().url(u)))
        val ms = j.optJSONArray("messages") ?: JSONArray()
        if (ms.length() == 0) return MailRow(id, "", "(pusty wątek)", "", 0L, false, 0)
        val first = ms.getJSONObject(0)
        val last = ms.getJSONObject(ms.length() - 1)
        val outgoing = label == "SENT" || label == "DRAFT"
        val who = senderName(header(last.optJSONObject("payload"), if (outgoing) "To" else "From"))
        val subject = header(first.optJSONObject("payload"), "Subject").ifBlank { "(bez tematu)" }
        val unread = (0 until ms.length()).any { "UNREAD" in labelsOf(ms.getJSONObject(it)) }
        return MailRow(
            id = id,
            who = who,
            subject = subject,
            snippet = unescapeHtml(last.optString("snippet")),
            time = last.optString("internalDate").toLongOrNull() ?: 0L,
            unread = unread,
            count = ms.length()
        )
    }

    suspend fun rows(token: String, ids: List<String>, label: String): List<MailRow> = coroutineScope {
        val sem = Semaphore(6)
        ids.map { id -> async(Dispatchers.IO) { sem.withPermit { threadRow(token, id, label) } } }.awaitAll()
    }

    private class Parsed {
        var html: String? = null
        var text: String? = null
        val atts = ArrayList<MailAttachment>()
    }

    private fun decode(data: String, contentType: String): String {
        val bytes = Base64.decode(data, Base64.URL_SAFE)
        val utf = String(bytes, Charsets.UTF_8)
        if (!utf.contains('�')) return utf
        // Część wiadomości nie jest w UTF-8 (np. ISO-8859-2) — bierzemy kodowanie z nagłówka części.
        val cs = Regex("charset=\"?([\\w\\-]+)\"?", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        return try {
            if (cs != null) String(bytes, charset(cs)) else utf
        } catch (e: Exception) {
            utf
        }
    }

    private fun walk(p: JSONObject, msgId: String, out: Parsed) {
        val mime = p.optString("mimeType")
        val body = p.optJSONObject("body")
        val filename = p.optString("filename")
        val attId = body?.optString("attachmentId").orEmpty()
        if (filename.isNotEmpty() && attId.isNotEmpty()) {
            out.atts.add(MailAttachment(msgId, attId, filename, mime, body?.optLong("size") ?: 0L))
        } else if (body != null && body.has("data")) {
            val ct = header(p, "Content-Type")
            if (mime == "text/html" && out.html == null) out.html = decode(body.getString("data"), ct)
            else if (mime == "text/plain" && out.text == null) out.text = decode(body.getString("data"), ct)
        }
        val parts = p.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) walk(parts.getJSONObject(i), msgId, out)
    }

    fun getThread(token: String, id: String): MailThread {
        val u = "$API/threads/$id".toHttpUrl().newBuilder().addQueryParameter("format", "full").build()
        val j = JSONObject(exec(token, Request.Builder().url(u)))
        val ms = j.optJSONArray("messages") ?: JSONArray()
        val list = ArrayList<MailMessage>()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val payload = m.optJSONObject("payload")
            val parsed = Parsed()
            if (payload != null) walk(payload, m.getString("id"), parsed)
            list.add(
                MailMessage(
                    id = m.getString("id"),
                    from = header(payload, "From"),
                    to = header(payload, "To"),
                    time = m.optString("internalDate").toLongOrNull() ?: 0L,
                    html = parsed.html,
                    text = parsed.text,
                    attachments = parsed.atts,
                    unread = "UNREAD" in labelsOf(m),
                    snippet = unescapeHtml(m.optString("snippet"))
                )
            )
        }
        val subject = if (ms.length() > 0) header(ms.getJSONObject(0).optJSONObject("payload"), "Subject") else ""
        return MailThread(id, subject.ifBlank { "(bez tematu)" }, list)
    }

    fun attachment(token: String, messageId: String, attachmentId: String): ByteArray {
        val j = JSONObject(exec(token, Request.Builder().url("$API/messages/$messageId/attachments/$attachmentId")))
        return Base64.decode(j.getString("data"), Base64.URL_SAFE)
    }

    private fun post(token: String, path: String, body: String) {
        exec(token, Request.Builder().url("$API/$path").post(body.toRequestBody(json)))
    }

    fun markRead(token: String, threadId: String) =
        post(token, "threads/$threadId/modify", "{\"removeLabelIds\":[\"UNREAD\"]}")

    fun trash(token: String, threadId: String) = post(token, "threads/$threadId/trash", "{}")

    fun untrash(token: String, threadId: String) = post(token, "threads/$threadId/untrash", "{}")
}

// ───────────────────────────── POMOCNICZE ─────────────────────────────

private fun unescapeHtml(s: String): String =
    s.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

/** „Imię Nazwisko <adres>” → „Imię Nazwisko” (albo sam adres). */
private fun senderName(h: String): String {
    if (h.isBlank()) return ""
    val name = h.substringBefore("<").trim().trim('"').trim()
    if (name.isNotEmpty() && h.contains("<")) return name
    return h.substringAfter("<", h).substringBefore(">").trim()
}

private fun mailTime(t: Long): String {
    if (t <= 0L) return ""
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    val f = when {
        sameDay -> "HH:mm"
        sameYear -> "d MMM"
        else -> "dd.MM.yyyy"
    }
    return SimpleDateFormat(f, Locale("pl", "PL")).format(Date(t))
}

private fun mailTimeFull(t: Long): String =
    if (t <= 0L) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale("pl", "PL")).format(Date(t))

private fun sizeText(b: Long): String = when {
    b >= 1_048_576 -> String.format(Locale("pl", "PL"), "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

private fun wrapHtml(body: String): String =
    "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{font-family:sans-serif;font-size:15px;margin:0;padding:0;word-wrap:break-word;overflow-wrap:anywhere}" +
        "img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}</style></head><body>$body</body></html>"

private fun escapeText(t: String): String =
    "<pre>" + t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"

private fun openAttachment(ctx: Context, a: MailAttachment, bytes: ByteArray) {
    val safe = a.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "zalacznik" }
    val dir = File(ctx.cacheDir, "mail/${a.messageId}").apply { mkdirs() }
    val f = File(dir, safe)
    f.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, a.mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(i, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ───────────────────────────── EKRAN ─────────────────────────────

private val FOLDERS = listOf("INBOX" to "Odebrane", "SENT" to "Wysłane", "DRAFT" to "Robocze", "TRASH" to "Kosz")

@Composable
fun MailScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf("INBOX") }
    val rows = remember { mutableStateListOf<MailRow>() }
    var nextToken by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var thread by remember { mutableStateOf<MailThread?>(null) }
    var openingId by remember { mutableStateOf<String?>(null) }
    var needsConnect by remember { mutableStateOf(false) }

    /** Wywołanie API z jednym ponowieniem po odświeżeniu wygasłego tokenu. */
    suspend fun <T> api(block: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        val t = token ?: throw IOException("Brak połączenia z Google.")
        try {
            block(t)
        } catch (e: MailUnauthorized) {
            val a = GmailApi.authorize(ctx, Prefs.mailAccount(ctx).ifEmpty { null })
            if (a is DriveAuth.Token) {
                token = a.token
                block(a.token)
            } else {
                throw e
            }
        }
    }

    fun load(more: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            status = ""
            try {
                val label = folder
                val (ids, next) = api { t -> GmailApi.listThreads(t, label, if (more) nextToken else null) }
                val fetched = api { t -> GmailApi.rows(t, ids, label) }
                if (label == folder) {
                    if (!more) rows.clear()
                    rows.addAll(fetched)
                    nextToken = next
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Błąd: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun onToken(t: String, email: String?) {
        token = t
        needsConnect = false
        if (!email.isNullOrBlank()) Prefs.setMailAccount(ctx, email)
        load(false)
    }

    var hintUsed by remember { mutableStateOf("") }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(
                res.data ?: throw IllegalStateException("brak odpowiedzi z okna zgody")
            )
            val t = r.accessToken
            if (t != null) onToken(t, r.toGoogleSignInAccount()?.email ?: hintUsed) else status = "Nie otrzymano zgody."
        } catch (e: Exception) {
            status = "Anulowano albo błąd zgody: " + (e.message ?: e.javaClass.simpleName)
            needsConnect = true
        }
    }

    fun connect() {
        scope.launch {
            busy = true
            status = ""
            try {
                val hint = Prefs.mailAccount(ctx).ifEmpty { Prefs.googleHint(ctx) }
                hintUsed = hint
                val a = try {
                    GmailApi.authorize(ctx, hint.ifEmpty { null })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (hint.isNotEmpty()) GmailApi.authorize(ctx, null) else throw e
                }
                when (a) {
                    is DriveAuth.Token -> { busy = false; onToken(a.token, a.email ?: hint) }
                    is DriveAuth.NeedsUi -> consent.launch(IntentSenderRequest.Builder(a.intent).build())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Nie udało się połączyć: " + (e.message ?: e.javaClass.simpleName)
                needsConnect = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        File(ctx.cacheDir, "mail").deleteRecursively()
        connect()
    }

    fun openThread(id: String) {
        scope.launch {
            openingId = id
            try {
                val t = api { tk -> GmailApi.getThread(tk, id) }
                thread = t
                val idx = rows.indexOfFirst { it.id == id }
                if (idx >= 0 && rows[idx].unread) {
                    rows[idx] = rows[idx].copy(unread = false)
                    try { api { tk -> GmailApi.markRead(tk, id) } } catch (e: Exception) { }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Nie udało się otworzyć wiadomości: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                openingId = null
            }
        }
    }

    val cur = thread
    if (cur != null) {
        BackHandler { thread = null }
        MailThreadView(
            thread = cur,
            folder = folder,
            myEmail = Prefs.mailAccount(ctx),
            fetchAttachment = { a -> api { tk -> GmailApi.attachment(tk, a.messageId, a.attachmentId) } },
            onTrash = {
                scope.launch {
                    try {
                        api { tk -> GmailApi.trash(tk, cur.id) }
                        rows.removeAll { it.id == cur.id }
                        thread = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Nie udało się usunąć: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            onRestore = {
                scope.launch {
                    try {
                        api { tk -> GmailApi.untrash(tk, cur.id) }
                        rows.removeAll { it.id == cur.id }
                        thread = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Nie udało się przywrócić: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            onBack = { thread = null }
        )
        return
    }

    AppScaffold(
        "Poczta",
        onBack,
        actions = {
            IconButton(enabled = token != null && !busy, onClick = { load(false) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Odśwież")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).verticalScroll(rememberScrollState(), enabled = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FOLDERS.forEach { (id, name) ->
                    FilterChip(
                        selected = folder == id,
                        onClick = {
                            if (folder != id && token != null) {
                                folder = id
                                rows.clear()
                                nextToken = null
                                load(false)
                            }
                        },
                        label = { Text(name) }
                    )
                }
            }
            if (needsConnect) {
                SubText(
                    "Poczta wymaga zgody na dostęp do Gmaila na koncie firmowym. Użyj konta w domenie firmy.",
                    Modifier.padding(top = 16.dp)
                )
                Button(enabled = !busy, onClick = { connect() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("Połącz z Gmailem")
                }
            }
            if (status.isNotEmpty()) {
                Text(status, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
            if (busy && rows.isEmpty()) SubText("Wczytywanie…", Modifier.padding(top = 16.dp))
            if (!busy && !needsConnect && status.isEmpty() && rows.isEmpty() && token != null) {
                SubText("Brak wiadomości w tym folderze.", Modifier.padding(top = 16.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(rows, key = { it.id }) { r ->
                    MailRowCard(r, loading = openingId == r.id, onClick = { if (openingId == null) openThread(r.id) })
                }
                if (nextToken != null) {
                    item {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { load(true) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) { Text(if (busy) "Wczytywanie…" else "Wczytaj starsze") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailRowCard(r: MailRow, loading: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(10.dp).padding(top = 0.dp).clip(CircleShape)
                    .background(if (r.unread) cs.primary else androidx.compose.ui.graphics.Color.Transparent)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.who.ifBlank { "—" },
                        modifier = Modifier.weight(1f),
                        fontWeight = if (r.unread) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurface
                    )
                    if (r.count > 1) SubText("${r.count}  ")
                    SubText(if (loading) "…" else mailTime(r.time))
                }
                Text(
                    r.subject,
                    fontWeight = if (r.unread) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = cs.onSurface
                )
                Text(r.snippet, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────────── WĄTEK (ROZMOWA) ─────────────────────────────

@Composable
private fun MailThreadView(
    thread: MailThread,
    folder: String,
    myEmail: String,
    fetchAttachment: suspend (MailAttachment) -> ByteArray,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var busyAtt by remember { mutableStateOf<String?>(null) }
    // Rozwinięta jest ostatnia wiadomość i nieprzeczytane; reszta zwinięta do jednej linii.
    val expanded = remember(thread.id) {
        mutableStateMapOf<String, Boolean>().apply {
            thread.messages.forEachIndexed { i, m -> put(m.id, i == thread.messages.lastIndex || m.unread) }
        }
    }
    val images = remember(thread.id) { mutableStateMapOf<String, Boolean>() }

    AppScaffold(
        thread.subject,
        onBack,
        actions = {
            if (folder == "TRASH") {
                TextButton(onClick = onRestore) { Text("Przywróć") }
            } else {
                IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "Usuń") }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            thread.messages.forEach { m ->
                val own = myEmail.isNotEmpty() && m.from.contains(myEmail, ignoreCase = true)
                val open = expanded[m.id] == true
                val cs = MaterialTheme.colorScheme
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(if (own) cs.primary.copy(alpha = 0.08f) else cs.surface)
                        .border(1.dp, cs.outlineVariant, shape)
                        .clickable { expanded[m.id] = !open }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                senderName(m.from).firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                fontWeight = FontWeight.Bold,
                                color = cs.primary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (own) "Ty" else senderName(m.from).ifBlank { "—" },
                                fontWeight = FontWeight.Bold,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (open) SubText("Do: " + m.to.ifBlank { "—" }, Modifier) else SubText(m.snippet.take(80))
                        }
                        SubText(mailTimeFull(m.time).substringBefore(",").let { if (open) mailTimeFull(m.time) else mailTime(m.time) })
                    }
                    if (open) {
                        Spacer(Modifier.size(10.dp))
                        val showImages = images[m.id] == true
                        val html = m.html
                        val body = when {
                            html != null -> html
                            m.text != null -> escapeText(m.text)
                            else -> escapeText(m.snippet)
                        }
                        MailBody(wrapHtml(body), showImages)
                        if (html != null && !showImages && html.contains("<img", ignoreCase = true)) {
                            TextButton(onClick = { images[m.id] = true }) { Text("Pokaż obrazy") }
                        }
                        m.attachments.forEach { a ->
                            val key = a.messageId + a.attachmentId
                            OutlinedButton(
                                enabled = busyAtt == null,
                                onClick = {
                                    scope.launch {
                                        busyAtt = key
                                        info = ""
                                        try {
                                            val bytes = withContext(Dispatchers.IO) { fetchAttachment(a) }
                                            withContext(Dispatchers.IO) { openAttachment(ctx, a, bytes) }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: android.content.ActivityNotFoundException) {
                                            info = "Brak aplikacji do otwarcia tego typu pliku."
                                        } catch (e: Exception) {
                                            info = "Nie udało się pobrać załącznika: " + (e.message ?: e.javaClass.simpleName)
                                        } finally {
                                            busyAtt = null
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                Text(
                                    (if (busyAtt == key) "Pobieranie… " else "📎 ") + a.name + "  (" + sizeText(a.size) + ")",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            if (info.isNotEmpty()) Text(info, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Przenieść rozmowę do kosza?") },
            text = { Text("Wiadomości zostaną w koszu przez 30 dni, potem Gmail usunie je na stałe.") },
            confirmButton = { TextButton(onClick = { confirm = false; onTrash() }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun MailBody(html: String, showImages: Boolean) {
    val ctx = LocalContext.current
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(0)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) { }
                        return true
                    }
                }
            }
        },
        update = { w ->
            w.settings.blockNetworkImage = !showImages
            if (w.tag != html) {
                w.tag = html
                w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
