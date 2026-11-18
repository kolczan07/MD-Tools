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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
    val count: Int,
    val hasAtt: Boolean = false
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
    val snippet: String,
    val cc: String = "",
    val replyTo: String = "",
    val msgIdHeader: String = "",
    val refs: String = ""
)

data class MailThread(val id: String, val subject: String, val messages: List<MailMessage>)

class MailNew(val row: MailRow, val fromEmail: String)

/** Token Gmail wygasł (HTTP 401) — trzeba poprosić Google o nowy. */
class MailUnauthorized : IOException("Token Gmail wygasł")

// ───────────────────────────── GMAIL API ─────────────────────────────

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitMail(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

object GmailApi {
    /** Pełny dostęp do Gmaila: tylko on pozwala trwale usuwać wiadomości (opróżnianie kosza). Obejmuje też gmail.modify. */
    const val SCOPE = "https://mail.google.com/"
    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
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

    fun listThreads(token: String, label: String, pageToken: String?, query: String = ""): Pair<List<String>, String?> {
        val u = "$API/threads".toHttpUrl().newBuilder()
            .addQueryParameter("maxResults", "20")
            .addQueryParameter("labelIds", label)
        if (query.isNotBlank()) u.addQueryParameter("q", query)
        if (label == "TRASH") u.addQueryParameter("includeSpamTrash", "true")
        if (!pageToken.isNullOrEmpty()) u.addQueryParameter("pageToken", pageToken)
        val j = JSONObject(exec(token, Request.Builder().url(u.build())))
        val arr = j.optJSONArray("threads") ?: JSONArray()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        return ids to j.optString("nextPageToken").ifEmpty { null }
    }

    /** Nagłówki zapisane surowym UTF-8 (np. nazwa kontaktu w „To”) Gmail oddaje jako Windows-1252 („MaÅ‚gorzata”) — naprawiamy. */
    private fun fixMojibake(s: String): String {
        if (s.none { it == 'Å' || it == 'Ä' || it == 'Ã' }) return s
        return try {
            val fixed = String(s.toByteArray(charset("windows-1252")), Charsets.UTF_8)
            if (fixed.contains('\uFFFD')) s else fixed
        } catch (e: Exception) {
            s
        }
    }

    private fun header(payload: JSONObject?, name: String): String {
        val hs = payload?.optJSONArray("headers") ?: return ""
        for (i in 0 until hs.length()) {
            val h = hs.getJSONObject(i)
            if (h.optString("name").equals(name, ignoreCase = true)) return fixMojibake(h.optString("value"))
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
        // W formacie metadata nie ma części, ale typ główny multipart/mixed oznacza załączniki.
        val hasAtt = (0 until ms.length()).any {
            ms.getJSONObject(it).optJSONObject("payload")?.optString("mimeType").orEmpty().startsWith("multipart/mixed")
        }
        return MailRow(
            id = id,
            who = who,
            subject = subject,
            snippet = unescapeHtml(last.optString("snippet")),
            time = last.optString("internalDate").toLongOrNull() ?: 0L,
            unread = unread,
            count = ms.length(),
            hasAtt = hasAtt
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
        val inline = HashMap<String, InlineImg>()
    }

    private class InlineImg(val attId: String, val mime: String, val size: Long)

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
        val cid = header(p, "Content-ID").trim().removePrefix("<").removeSuffix(">")
        if (cid.isNotEmpty() && attId.isNotEmpty() && mime.startsWith("image/", ignoreCase = true)) {
            out.inline[cid] = InlineImg(attId, mime, body?.optLong("size") ?: 0L)
        }
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
            var htmlOut = parsed.html
            val usedAtt = HashSet<String>()
            if (htmlOut != null && parsed.inline.isNotEmpty() && htmlOut.contains("cid:", ignoreCase = true)) {
                for ((cid, img) in parsed.inline) {
                    if (!htmlOut!!.contains("cid:$cid", ignoreCase = true)) continue
                    usedAtt.add(img.attId)
                    if (img.size > 800_000L) continue
                    val bytes = try { attachment(token, m.getString("id"), img.attId) } catch (e: Exception) { continue }
                    val uri = "data:${img.mime};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    htmlOut = htmlOut.replace("cid:$cid", uri, ignoreCase = true)
                }
            }
            list.add(
                MailMessage(
                    id = m.getString("id"),
                    from = header(payload, "From"),
                    to = header(payload, "To"),
                    time = m.optString("internalDate").toLongOrNull() ?: 0L,
                    html = htmlOut,
                    text = parsed.text,
                    attachments = parsed.atts.filterNot { it.attachmentId in usedAtt },
                    unread = "UNREAD" in labelsOf(m),
                    snippet = unescapeHtml(m.optString("snippet")),
                    cc = header(payload, "Cc"),
                    replyTo = header(payload, "Reply-To"),
                    msgIdHeader = header(payload, "Message-ID"),
                    refs = header(payload, "References")
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

    /** Liczba nieprzeczytanych wątków w Odebranych. */
    fun inboxUnread(token: String): Int =
        JSONObject(exec(token, Request.Builder().url("$API/labels/INBOX"))).optInt("threadsUnread")

    fun profileHistoryId(token: String): String =
        JSONObject(exec(token, Request.Builder().url("$API/profile"))).optString("historyId")

    /** Id nowych wiadomości w Odebranych od podanego punktu historii + nowy punkt odniesienia. */
    fun newInboxMessages(token: String, startHistoryId: String): Pair<List<String>, String> {
        val ids = ArrayList<String>()
        var page: String? = null
        var newest = startHistoryId
        do {
            val u = "$API/history".toHttpUrl().newBuilder()
                .addQueryParameter("startHistoryId", startHistoryId)
                .addQueryParameter("historyTypes", "messageAdded")
                .addQueryParameter("labelId", "INBOX")
                .addQueryParameter("maxResults", "100")
            if (page != null) u.addQueryParameter("pageToken", page)
            val j = JSONObject(exec(token, Request.Builder().url(u.build())))
            val h = j.optJSONArray("history") ?: JSONArray()
            for (i in 0 until h.length()) {
                val added = h.getJSONObject(i).optJSONArray("messagesAdded") ?: continue
                for (k in 0 until added.length()) {
                    val m = added.getJSONObject(k).getJSONObject("message")
                    val labels = labelsOf(m)
                    if ("INBOX" in labels && "UNREAD" in labels) ids.add(m.getString("id"))
                }
            }
            j.optString("historyId").takeIf { it.isNotEmpty() }?.let { newest = it }
            page = j.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return ids to newest
    }

    /** Pojedyncza wiadomość do powiadomienia (nagłówki From/Subject + fragment). */
    fun messageRow(token: String, id: String): MailNew {
        val u = "$API/messages/$id".toHttpUrl().newBuilder()
            .addQueryParameter("format", "metadata")
            .addQueryParameter("metadataHeaders", "From")
            .addQueryParameter("metadataHeaders", "Subject")
            .build()
        val m = JSONObject(exec(token, Request.Builder().url(u)))
        val p = m.optJSONObject("payload")
        val from = header(p, "From")
        val row = MailRow(
            id = m.optString("threadId", id),
            who = senderName(from),
            subject = header(p, "Subject").ifBlank { "(bez tematu)" },
            snippet = unescapeHtml(m.optString("snippet")),
            time = m.optString("internalDate").toLongOrNull() ?: 0L,
            unread = true,
            count = 1
        )
        return MailNew(row, senderEmail(from))
    }

    /** Wartości podanych nagłówków z ostatnich wiadomości w folderze (do podpowiedzi adresatów). */
    suspend fun recentHeaders(token: String, label: String, count: Int, names: List<String>): List<List<String>> = coroutineScope {
        val u = "$API/messages".toHttpUrl().newBuilder()
            .addQueryParameter("labelIds", label)
            .addQueryParameter("maxResults", count.toString())
            .build()
        val arr = JSONObject(exec(token, Request.Builder().url(u))).optJSONArray("messages") ?: JSONArray()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        val sem = Semaphore(6)
        ids.map { id ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val b = "$API/messages/$id".toHttpUrl().newBuilder().addQueryParameter("format", "metadata")
                    names.forEach { b.addQueryParameter("metadataHeaders", it) }
                    val p = JSONObject(exec(token, Request.Builder().url(b.build()))).optJSONObject("payload")
                    names.map { header(p, it) }
                }
            }
        }.awaitAll()
    }

    private fun post(token: String, path: String, body: String) {
        exec(token, Request.Builder().url("$API/$path").post(body.toRequestBody(json)))
    }

    fun markRead(token: String, threadId: String) =
        post(token, "threads/$threadId/modify", "{\"removeLabelIds\":[\"UNREAD\"]}")

    private fun rawBody(raw: String, threadId: String?): String {
        val o = JSONObject().put("raw", raw)
        if (!threadId.isNullOrEmpty()) o.put("threadId", threadId)
        return o.toString()
    }

    fun send(token: String, raw: String, threadId: String?) = post(token, "messages/send", rawBody(raw, threadId))

    fun saveDraft(token: String, raw: String, threadId: String?) =
        post(token, "drafts", JSONObject().put("message", JSONObject(rawBody(raw, threadId))).toString())

    fun trash(token: String, threadId: String) = post(token, "threads/$threadId/trash", "{}")

    fun untrash(token: String, threadId: String) = post(token, "threads/$threadId/untrash", "{}")

    /** Trwale usuwa wszystko z kosza (partiami po 500 wiadomości). Zwraca liczbę usuniętych wiadomości. */
    fun deleteAllTrash(token: String): Int {
        var total = 0
        repeat(40) {
            val u = "$API/messages".toHttpUrl().newBuilder()
                .addQueryParameter("labelIds", "TRASH")
                .addQueryParameter("includeSpamTrash", "true")
                .addQueryParameter("maxResults", "500")
                .build()
            val arr = JSONObject(exec(token, Request.Builder().url(u))).optJSONArray("messages") ?: JSONArray()
            if (arr.length() == 0) return total
            val ids = JSONArray()
            for (i in 0 until arr.length()) ids.put(arr.getJSONObject(i).getString("id"))
            post(token, "messages/batchDelete", JSONObject().put("ids", ids).toString())
            total += arr.length()
        }
        return total
    }
}

// ───────────────────────────── POMOCNICZE ─────────────────────────────

/** Odświeża książkę adresową z historii poczty: wysłane (ważniejsze) i odebrane. */
suspend fun refreshMailContacts(ctx: Context, token: String) {
    val me = Prefs.mailAccount(ctx).lowercase()
    val names = HashMap<String, String>()
    val counts = HashMap<String, Int>()
    fun take(h: String, w: Int) {
        for (a in splitAddrs(h)) {
            val e = senderEmail(a)
            if (!e.contains("@") || e == me || e.contains("noreply") || e.contains("no-reply") || e.contains("mailer-daemon")) continue
            counts[e] = (counts[e] ?: 0) + w
            val n = senderName(a)
            if (n.isNotBlank() && !n.contains("@") && names[e].isNullOrEmpty()) names[e] = n
        }
    }
    MailApi.recentHeaders(ctx, token, "SENT", 80, listOf("To", "Cc")).forEach { (to, cc) -> take(to, 3); take(cc, 2) }
    MailApi.recentHeaders(ctx, token, "INBOX", 80, listOf("From")).forEach { (f) -> take(f, 1) }
    Prefs.setMailContacts(ctx, counts.map { (e, c) -> MailContact(e, names[e].orEmpty(), c) })
}

/** Dzieli listę adresów po przecinkach, pomijając przecinki w cudzysłowie. */
internal fun splitAddrs(s: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var q = false
    for (ch in s) {
        if (ch == '"') q = !q
        if (ch == ',' && !q) { out.add(cur.toString().trim()); cur.clear() } else cur.append(ch)
    }
    out.add(cur.toString().trim())
    return out.filter { it.isNotEmpty() }
}

private fun replyInit(t: MailThread, myEmail: String, all: Boolean, sig: String): ComposeInit {
    val own = { m: MailMessage -> myEmail.isNotEmpty() && m.from.contains(myEmail, ignoreCase = true) }
    val last = t.messages.lastOrNull { !own(it) } ?: t.messages.last()
    val ref = t.messages.last()
    val to = if (own(last)) last.to else last.replyTo.ifBlank { last.from }
    val toMail = senderEmail(to)
    val cc = if (all) {
        splitAddrs(last.to + "," + last.cc)
            .filter { !(myEmail.isNotEmpty() && it.contains(myEmail, ignoreCase = true)) && senderEmail(it) != toMail }
            .joinToString(", ")
    } else ""
    val subj = if (t.subject.startsWith("re:", ignoreCase = true)) t.subject else "Re: ${t.subject}"
    val junk = Regex("<(?:mailto:|https?://|gg:)[^>]*>")
    val src = (last.text ?: last.snippet).replace("\r", "").replace(junk, "").lines().take(80).joinToString("\n") { "> $it" }
    val quote = "${mailTimeFull(last.time)}, ${senderName(last.from)} napisał(a):\n$src"
    return ComposeInit(
        to = to, cc = cc, subject = subj, body = "", signature = sig.trim(), quote = quote, threadId = t.id,
        inReplyTo = ref.msgIdHeader,
        references = (ref.refs + " " + ref.msgIdHeader).trim()
    )
}

private fun unescapeHtml(s: String): String =
    s.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

/** Sam adres e-mail z nagłówka From, małymi literami. */
internal fun senderEmail(h: String): String =
    (if (h.contains("<")) h.substringAfter("<").substringBefore(">") else h).trim().lowercase()

/** „Imię Nazwisko <adres>” → „Imię Nazwisko” (albo sam adres). */
internal fun senderName(h: String): String {
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

/** Nagłówek grupy na liście: Dzisiaj / Wczoraj / Ostatnie 7 dni / miesiąc (z rokiem, jeśli inny niż bieżący). */
private fun mailGroup(t: Long): String {
    if (t <= 0L) return ""
    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = Math.round((startOfDay(System.currentTimeMillis()) - startOfDay(t)) / 86_400_000.0).toInt()
    return when {
        days <= 0 -> "Dzisiaj"
        days == 1 -> "Wczoraj"
        days <= 7 -> "Ostatnie 7 dni"
        else -> {
            val sameYear = Calendar.getInstance().get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)
            SimpleDateFormat(if (sameYear) "LLLL" else "LLLL yyyy", Locale("pl", "PL")).format(Date(t))
                .replaceFirstChar { it.uppercase() }
        }
    }
}

private fun mailTimeFull(t: Long): String =
    if (t <= 0L) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale("pl", "PL")).format(Date(t))

private fun sizeText(b: Long): String = when {
    b >= 1_048_576 -> String.format(Locale("pl", "PL"), "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

private val DARK_MEDIA = Regex("@media[^{]*prefers-color-scheme\\s*:\\s*dark[^{]*\\{", RegexOption.IGNORE_CASE)

/** Usuwa bloki @media (prefers-color-scheme: dark) — mail ma być w swoim jasnym wydaniu, które potem odwracamy sami. */
private fun stripDarkMedia(html: String): String {
    var out = html
    while (true) {
        val m = DARK_MEDIA.find(out) ?: return out
        var depth = 1
        var i = m.range.last + 1
        while (i < out.length && depth > 0) {
            if (out[i] == '{') depth++ else if (out[i] == '}') depth--
            i++
        }
        out = out.removeRange(m.range.first, i)
    }
}

private fun wrapHtml(rawBody: String, dark: Boolean, textColor: Int, isHtml: Boolean): String {
    val body = if (dark && isHtml) stripDarkMedia(rawBody) else rawBody
    // Mail HTML w ciemnym motywie odwracamy filtrem, więc domyślny tekst ma być ciemny (po odwróceniu jasny).
    val col = if (dark && isHtml) "#111111" else String.format("#%06X", textColor and 0xFFFFFF)
    // Ciemny motyw: mail HTML (zwykle czarny tekst na białym) odwracamy filtrem, a obrazy przywracamy.
    val darkFix = if (dark && isHtml)
        "html{filter:invert(1) hue-rotate(180deg)}img,video{filter:invert(1) hue-rotate(180deg)}" else ""
    return "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>a{color:#4a8fe7}summary{list-style:none}summary::-webkit-details-marker{display:none}" +
        "body{font-family:sans-serif;font-size:15px;line-height:1.45;margin:0;padding:0;color:$col;word-wrap:break-word;overflow-wrap:anywhere}" +
        "img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}$darkFix</style></head><body>$body</body></html>"
}

private fun escapeText(t: String): String = plainToHtml(t)

private fun isPdf(a: MailAttachment): Boolean =
    a.mime.equals("application/pdf", ignoreCase = true) || a.name.endsWith(".pdf", ignoreCase = true)

private fun saveAttachment(ctx: Context, a: MailAttachment, bytes: ByteArray): File {
    val safe = a.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "zalacznik" }
    val dir = File(ctx.cacheDir, "mail/${a.messageId}").apply { mkdirs() }
    return File(dir, safe).also { it.writeBytes(bytes) }
}

private fun openFile(ctx: Context, a: MailAttachment, f: File) {
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
fun MailScreen(onBack: () -> Unit, onOpenAccounts: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    val multiAccounts = remember { MailAccounts.all(ctx).size > 1 }
    var folder by remember { mutableStateOf("INBOX") }
    var query by remember { mutableStateOf("") }
    var onlyUnread by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf<MailRow>() }
    var nextToken by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var thread by remember { mutableStateOf<MailThread?>(null) }
    var openingId by remember { mutableStateOf<String?>(null) }
    var needsConnect by remember { mutableStateOf(false) }
    val trusted = remember { mutableStateListOf<String>().apply { addAll(Prefs.trustedSenders(ctx)) } }
    var compose by remember { mutableStateOf<ComposeInit?>(null) }
    var contacts by remember { mutableStateOf(Prefs.mailContacts(ctx)) }
    val selected = remember { mutableStateListOf<String>() }
    var pdfView by remember { mutableStateOf<Pair<String, File>?>(null) }

    /** Wywołanie API z jednym ponowieniem po odświeżeniu wygasłego tokenu. */
    suspend fun <T> api(block: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        val t = token ?: throw IOException(if (MailApi.isImap(ctx)) "Brak połączenia z kontem pocztowym." else "Brak połączenia z Google.")
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

    /** Odświeża licznik nieprzeczytanych (kafelek na ekranie głównym). */
    suspend fun updateUnread() {
        try { Prefs.setMailUnread(ctx, api { t -> MailApi.inboxUnread(ctx, t) }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { }
    }

    /** Zapytanie Gmaila: filtr nieprzeczytanych (tylko w Odebranych) plus wpisany tekst. */
    fun searchQuery(): String =
        listOfNotNull(if (onlyUnread && folder == "INBOX") "is:unread" else null, query.trim().ifBlank { null }).joinToString(" ")

    fun load(more: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            status = ""
            try {
                val label = folder
                val q = searchQuery()
                val (fetched, next) = api { t -> MailApi.list(ctx, t, label, if (more) nextToken else null, query.trim(), onlyUnread && label == "INBOX") }
                if (label == folder && q == searchQuery()) {
                    if (!more) rows.clear()
                    // Wątek może zahaczać o dwie strony listy (IMAP) — nie dublujemy wierszy.
                    rows.addAll(fetched.filter { n -> rows.none { it.id == n.id } })
                    nextToken = next
                }
                if (label == "INBOX") updateUnread()
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
        if (!email.isNullOrBlank() && !MailApi.isImap(ctx)) {
            Prefs.setMailAccount(ctx, email)
            MailAccounts.registerGoogle(ctx, email)
        }
        load(false)
        // Książkę adresową odświeżamy w tle, najwyżej raz na dobę.
        if (Prefs.mailContactsAge(ctx) > 24L * 3600 * 1000) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { refreshMailContacts(ctx, t) }
                    contacts = Prefs.mailContacts(ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { }
            }
        }
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
        if (MailApi.isImap(ctx)) {
            // IMAP/SMTP: bez Google — wystarczy, że konto jest skonfigurowane (hasło leży w szyfrowanym magazynie).
            if (Prefs.mailConfigured(ctx)) onToken("imap", Prefs.mailAccount(ctx)) else needsConnect = true
            return
        }
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

    fun emptyTrash() {
        scope.launch {
            busy = true
            status = ""
            try {
                api { t -> MailApi.deleteAllTrash(ctx, t) }
                rows.clear()
                nextToken = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Nie udało się opróżnić kosza: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun openThread(id: String) {
        scope.launch {
            openingId = id
            try {
                val t = api { tk -> MailApi.getThread(ctx, tk, id) }
                thread = t
                val idx = rows.indexOfFirst { it.id == id }
                if (idx >= 0 && rows[idx].unread) {
                    rows[idx] = rows[idx].copy(unread = false)
                    try { api { tk -> MailApi.markRead(ctx, tk, id) } } catch (e: Exception) { }
                    updateUnread()
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

    /** Oznacza zaznaczone wątki jako przeczytane (tylko te, które są nieprzeczytane). */
    fun markSelectedRead() {
        val ids = selected.filter { id -> rows.any { it.id == id && it.unread } }
        selected.clear()
        if (ids.isEmpty()) return
        scope.launch {
            busy = true
            var failed = 0
            ids.forEach { id ->
                try {
                    api { tk -> MailApi.markRead(ctx, tk, id) }
                    val idx = rows.indexOfFirst { it.id == id }
                    if (idx >= 0) rows[idx] = rows[idx].copy(unread = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) status = "Nie udało się oznaczyć wiadomości: $failed"
            updateUnread()
            busy = false
        }
    }

    /** Przenosi zaznaczone wątki do kosza. */
    fun trashSelected() {
        val ids = selected.toList()
        selected.clear()
        if (ids.isEmpty()) return
        scope.launch {
            busy = true
            var failed = 0
            ids.forEach { id ->
                try {
                    api { tk -> MailApi.trash(ctx, tk, id) }
                    rows.removeAll { it.id == id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) status = "Nie udało się usunąć wiadomości: $failed"
            updateUnread()
            busy = false
        }
    }

    val comp = compose
    if (comp != null) {
        MailComposeScreen(
            init = comp,
            contacts = contacts,
            onSend = { d ->
                api { t -> MailApi.send(ctx, t, buildRaw(ctx, d), d.threadId) }
                // Adresaci wysłanej wiadomości trafiają od razu do podpowiedzi.
                val add = (splitAddrs(d.to.replace(";", ",")) + splitAddrs(d.cc.replace(";", ",")))
                    .map { senderEmail(it) }.filter { it.contains("@") }
                if (add.isNotEmpty()) {
                    val m = Prefs.mailContacts(ctx).associateBy { it.email }.toMutableMap()
                    add.forEach { e -> m[e] = MailContact(e, m[e]?.name.orEmpty(), (m[e]?.count ?: 0) + 3) }
                    Prefs.setMailContacts(ctx, m.values.toList())
                    contacts = Prefs.mailContacts(ctx)
                }
            },
            onSaveDraft = { d -> api { t -> MailApi.saveDraft(ctx, t, buildRaw(ctx, d), d.threadId) } },
            onClose = { sent ->
                compose = null
                if (sent) {
                    thread?.let { openThread(it.id) }
                    if (folder == "SENT") load(false)
                } else if (folder == "DRAFT") load(false)
            }
        )
        return
    }

    val pv = pdfView
    if (pv != null) {
        BackHandler { pdfView = null }
        PdfViewerScreen(pv.first, pv.second, onClose = { pdfView = null })
        return
    }

    val cur = thread
    if (cur != null) {
        BackHandler { thread = null }
        MailThreadView(
            thread = cur,
            folder = folder,
            myEmail = Prefs.mailAccount(ctx),
            trusted = trusted,
            onReply = { all -> compose = replyInit(cur, Prefs.mailAccount(ctx), all, Prefs.mailSignature(ctx)) },
            onTrust = { addr ->
                if (addr.isNotEmpty() && addr !in trusted) {
                    trusted.add(addr)
                    Prefs.setTrustedSenders(ctx, trusted.toList())
                }
            },
            onOpenPdf = { name, f -> pdfView = name to f },
            fetchAttachment = { a -> api { tk -> MailApi.attachment(ctx, tk, a.messageId, a.attachmentId) } },
            onTrash = {
                scope.launch {
                    try {
                        api { tk -> MailApi.trash(ctx, tk, cur.id) }
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
                        api { tk -> MailApi.untrash(ctx, tk, cur.id) }
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

    val selecting = selected.isNotEmpty()
    if (selecting) BackHandler { selected.clear() }
    AppScaffold(
        if (selecting) "Zaznaczono: ${selected.size}" else "Poczta",
        { if (selecting) selected.clear() else onBack() },
        onRefresh = { if (token != null && !selecting) load(false) },
        refreshing = busy,
        actions = {
            if (selecting) {
                TextButton(onClick = {
                    selected.clear()
                    selected.addAll(rows.map { it.id })
                }) { Text("Wszystkie") }
                IconButton(enabled = !busy, onClick = { markSelectedRead() }) {
                    Icon(Icons.Filled.Check, contentDescription = "Oznacz jako przeczytane")
                }
                if (folder != "TRASH") {
                    IconButton(enabled = !busy, onClick = { trashSelected() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Usuń")
                    }
                }
            } else {
                IconButton(enabled = token != null && !busy, onClick = { load(false) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Odśwież")
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FOLDERS.forEach { (id, name) ->
                    val unreadN = if (id == "INBOX") Prefs.mailUnread(ctx) else 0
                    FolderTab(if (unreadN > 0) "$name  $unreadN" else name, folder == id) {
                        if (folder != id && token != null) {
                            folder = id
                            selected.clear()
                            rows.clear()
                            nextToken = null
                            load(false)
                        }
                    }
                }
            }
            if (token != null && multiAccounts) {
                // Kilka kont: aktywne widać od razu, dotknięcie otwiera listę kont do przełączenia.
                TextButton(onClick = onOpenAccounts, modifier = Modifier.padding(top = 4.dp)) {
                    Text(Prefs.mailAccount(ctx) + "  ▾", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (token != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchField(query, { query = it }, "Szukaj", Modifier.weight(1f), onSearch = { load(false) })
                    if (folder == "INBOX") {
                        FilterChipSmall("Nieprzeczytane", onlyUnread) {
                            onlyUnread = !onlyUnread
                            selected.clear()
                            rows.clear()
                            nextToken = null
                            load(false)
                        }
                    }
                }
            }
            if (folder == "TRASH" && rows.isNotEmpty() && query.isBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = { confirmEmpty = true }) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Opróżnij kosz", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (needsConnect) {
                if (MailApi.isImap(ctx)) {
                    SubText(
                        "To konto pocztowe nie jest jeszcze w pełni skonfigurowane (brakuje serwerów albo hasła).",
                        Modifier.padding(top = 16.dp)
                    )
                    Button(enabled = !busy, onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("Konta pocztowe")
                    }
                } else {
                    SubText(
                        "Poczta wymaga zgody na dostęp do Gmaila na koncie firmowym. Użyj konta w domenie firmy.",
                        Modifier.padding(top = 16.dp)
                    )
                    Button(enabled = !busy, onClick = { connect() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("Połącz z Gmailem")
                    }
                    OutlinedButton(enabled = !busy, onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Inne konto pocztowe (IMAP)")
                    }
                }
            }
            if (status.isNotEmpty()) {
                Text(status, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
            if (busy && rows.isEmpty()) SubText("Wczytywanie…", Modifier.padding(top = 16.dp))
            if (!busy && !needsConnect && status.isEmpty() && rows.isEmpty() && token != null) {
                SubText(if (searchQuery().isNotEmpty()) "Brak wyników." else "Brak wiadomości w tym folderze.", Modifier.padding(top = 16.dp))
            }
            val sheetShape = RoundedCornerShape(16.dp)
            LazyColumn(
                Modifier.weight(1f).padding(top = 8.dp).clip(sheetShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, sheetShape)
            ) {
                rows.forEachIndexed { idx, r ->
                    val label = mailGroup(r.time)
                    if (label.isNotEmpty() && label != rows.getOrNull(idx - 1)?.let { mailGroup(it.time) }) {
                        item(key = "h$idx") {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                    }
                    item(key = r.id) {
                        MailRowCard(
                            r,
                            loading = openingId == r.id,
                            selected = r.id in selected,
                            onClick = {
                                if (selecting) {
                                    if (r.id in selected) selected.remove(r.id) else selected.add(r.id)
                                } else if (openingId == null) openThread(r.id)
                            },
                            onLongClick = { if (r.id !in selected) selected.add(r.id) }
                        )
                    }
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
                // Miejsce pod przyciskiem „Napisz”, żeby nie zasłaniał ostatniej wiadomości.
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
        if (token != null) {
            ExtendedFloatingActionButton(
                onClick = { compose = ComposeInit(signature = Prefs.mailSignature(ctx).trim()) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(pad).padding(16.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Napisz", fontWeight = FontWeight.Bold)
            }
        }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Opróżnić kosz?") },
            text = { Text("Wszystkie wiadomości z kosza zostaną usunięte na stałe. Tego nie da się cofnąć.") },
            confirmButton = { TextButton(onClick = { confirmEmpty = false; emptyTrash() }) { Text("Usuń na stałe") } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Anuluj") } }
        )
    }
}

/** Zakładka folderu: zwykły tekst, aktywna z delikatnym kolorowym tłem. */
@Composable
private fun FolderTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 14.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}

/** Mały filtr przy wyszukiwarce (np. „Nieprzeczytane”). */
@Composable
private fun FilterChipSmall(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}

@Composable
private fun PillChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 14.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant
        )
    }
}

private val AVATAR_COLORS = listOf(0xFF2563EB, 0xFF0D9488, 0xFFD97706, 0xFFDB2777, 0xFF7C3AED, 0xFF16A34A).map { Color(it) }

private fun avatarColor(s: String): Color = AVATAR_COLORS[(s.lowercase().hashCode() and 0x7fffffff) % AVATAR_COLORS.size]

@Composable
private fun MailRowCard(r: MailRow, loading: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ac = avatarColor(r.who)
    val dark = cs.background.luminance() < 0.5f
    val readWeight = if (dark) FontWeight.SemiBold else FontWeight.Bold
    val unreadWeight = FontWeight.ExtraBold
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, onLongClick = onLongClick, flat = true) {
        Row(
            Modifier.background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.size(38.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(if (selected) cs.primary else ac.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = "Zaznaczono", tint = cs.onPrimary, modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            r.who.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
                            fontWeight = FontWeight.Bold,
                            color = ac
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.who.ifBlank { "—" },
                        modifier = Modifier.weight(1f),
                        fontWeight = if (r.unread) unreadWeight else readWeight,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurface
                    )
                    if (r.hasAtt) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Załącznik", tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (r.count > 1) SubText("${r.count}  ")
                    Text(
                        if (loading) "…" else mailTime(r.time),
                        fontSize = 12.sp,
                        fontWeight = if (r.unread) FontWeight.Bold else FontWeight.Normal,
                        color = if (r.unread) cs.primary else cs.onSurfaceVariant
                    )
                }
                Text(
                    r.subject,
                    fontWeight = if (r.unread) unreadWeight else readWeight,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = cs.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.snippet,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurfaceVariant
                    )
                    if (r.unread && !selected) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(9.dp).clip(CircleShape).background(cs.primary))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(cs.outlineVariant))
    }
}

// ───────────────────────────── WĄTEK (ROZMOWA) ─────────────────────────────

@Composable
private fun MailThreadView(
    thread: MailThread,
    folder: String,
    myEmail: String,
    trusted: List<String>,
    onReply: (Boolean) -> Unit,
    onTrust: (String) -> Unit,
    fetchAttachment: suspend (MailAttachment) -> ByteArray,
    onOpenPdf: (String, File) -> Unit,
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
            Text(
                thread.subject,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
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
                            if (open) {
                                Text(
                                    "Do: " + splitAddrs(m.to).joinToString(", ") { senderName(it) }.ifBlank { "—" },
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = cs.onSurfaceVariant
                                )
                            } else SubText(m.snippet.take(80))
                        }
                        SubText(if (open) mailTimeFull(m.time).replace(" ${Calendar.getInstance().get(Calendar.YEAR)},", ",") else mailTime(m.time))
                    }
                    if (open) {
                        Spacer(Modifier.size(10.dp))
                        val showImages = images[m.id] == true || senderEmail(m.from) in trusted
                        val html = m.html
                        val body = when {
                            html != null -> html
                            m.text != null -> escapeText(m.text)
                            else -> escapeText(m.snippet)
                        }
                        MailBody(
                            wrapHtml(
                                body,
                                dark = cs.background.luminance() < 0.5f,
                                textColor = cs.onSurface.toArgb(),
                                isHtml = html != null
                            ),
                            showImages
                        )
                        if (html != null && !showImages && html.contains("<img", ignoreCase = true)) {
                            Row {
                                TextButton(onClick = { images[m.id] = true }) { Text("Pokaż obrazy") }
                                if (!own) TextButton(onClick = { onTrust(senderEmail(m.from)) }) { Text("Ufaj nadawcy") }
                            }
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
                                            val f = withContext(Dispatchers.IO) { saveAttachment(ctx, a, bytes) }
                                            if (isPdf(a)) onOpenPdf(a.name, f) else openFile(ctx, a, f)
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
            if (folder != "TRASH" && folder != "DRAFT") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onReply(false) }, modifier = Modifier.weight(1f)) { Text("Odpowiedz") }
                    OutlinedButton(onClick = { onReply(true) }, modifier = Modifier.weight(1f)) { Text("Wszystkim") }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Przenieść rozmowę do kosza?") },
            text = { Text(if (MailApi.isImap(LocalContext.current)) "Wiadomości trafią do kosza. Usuniesz je na stałe, opróżniając kosz." else "Wiadomości zostaną w koszu przez 30 dni, potem Gmail usunie je na stałe.") },
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
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    @Suppress("DEPRECATION")
                    settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                }
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
