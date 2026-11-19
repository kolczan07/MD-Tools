package pl.mdinstal.narzedzia

import android.content.Context
import android.util.Base64
import com.sun.mail.imap.IMAPFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Date
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.activation.CommandMap
import javax.activation.MailcapCommandMap
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import javax.mail.search.AndTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

/** Komplet danych logowania do konta IMAP/SMTP. Aktywne konto czytamy z [Prefs], a nowe sprawdzamy przed zapisem. */
class ImapCfg(
    val email: String,
    val login: String,
    val password: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSec: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSec: String
) {
    companion object {
        fun active(ctx: Context) = ImapCfg(
            Prefs.mailAccount(ctx), Prefs.mailLogin(ctx), Prefs.mailPassword(ctx),
            Prefs.mailImapHost(ctx), Prefs.mailImapPort(ctx), Prefs.mailImapSec(ctx),
            Prefs.mailSmtpHost(ctx), Prefs.mailSmtpPort(ctx), Prefs.mailSmtpSec(ctx)
        )
    }
}

/**
 * Poczta po IMAP (odczyt) i SMTP (wysyłanie). Każda operacja otwiera własne krótkie połączenie.
 *
 * IMAP nie ma wątków, więc rozmowy składamy po stronie aplikacji: wiadomości z tym samym tematem
 * (bez „Re:”/„Fwd:”) tworzą jeden wątek. Identyfikatory:
 *  - wątek: „ROLA|klucz” (klucz = znormalizowany temat albo „#UID” dla wiadomości bez tematu),
 *  - wiadomość: „ROLA|UID”,
 * gdzie ROLA to INBOX, SENT, DRAFT albo TRASH (prawdziwe nazwy folderów ustalamy z serwera).
 */
object ImapApi {
    private const val WINDOW = 60

    /** Wątek → UID-y jego wiadomości w folderze listy (zapamiętane przy listowaniu), żeby otwarcie nie szukało od nowa. */
    private val knownUids = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()

    init {
        // JavaMail na Androidzie potrzebuje jawnej rejestracji obsługi typów MIME.
        try {
            val mc = MailcapCommandMap()
            mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
            mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
            mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
            mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
            mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
            CommandMap.setDefaultCommandMap(mc)
        } catch (e: Throwable) {
        }
        System.setProperty("mail.mime.decodefilename", "true")
        System.setProperty("mail.mime.decodeparameters", "true")
    }

    // ───────────────────────── połączenie ─────────────────────────

    private fun protoImap(sec: String) = if (sec == "ssl") "imaps" else "imap"
    private fun protoSmtp(sec: String) = if (sec == "ssl") "smtps" else "smtp"

    private fun baseProps(proto: String, sec: String): Properties {
        val p = Properties()
        p["mail.$proto.connectiontimeout"] = "15000"
        p["mail.$proto.timeout"] = "45000"
        p["mail.$proto.writetimeout"] = "45000"
        // Treść wiadomości jednym żądaniem zamiast porcjami po 16 KB (każda porcja to osobna wymiana z serwerem).
        p["mail.$proto.partialfetch"] = "false"
        // Połączenie w puli trzymamy dłużej niż domyślne 45 s, żeby kolejne otwarcia nie logowały się od nowa.
        p["mail.$proto.connectionpooltimeout"] = "300000"
        p["mail.$proto.ssl.checkserveridentity"] = "true"
        if (sec == "starttls") {
            p["mail.$proto.starttls.enable"] = "true"
            p["mail.$proto.starttls.required"] = "true"
        }
        return p
    }

    private fun friendly(e: Throwable): String = when (e) {
        is AuthenticationFailedException -> "Serwer odrzucił login lub hasło." + (e.message?.let { " ($it)" } ?: "")
        is java.net.UnknownHostException -> "Nie znaleziono serwera: ${e.message}"
        is java.net.SocketTimeoutException -> "Serwer nie odpowiada (przekroczono czas)."
        is java.net.ConnectException -> "Nie można połączyć z serwerem: ${e.message}"
        is javax.net.ssl.SSLException -> "Błąd szyfrowanego połączenia (SSL): ${e.message}"
        else -> {
            val c = e.cause
            if (e is javax.mail.MessagingException && c != null && c !== e) friendly(c) else (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Skrót do diagnozy: klasa pierwotnego błędu i miejsce w kodzie aplikacji, np. „[MessagingException @ walk:381]”. */
    private fun diag(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val pkg = ImapApi::class.java.name.substringBeforeLast('.')
        val frame = chain.flatMap { it.stackTrace.asList() }.firstOrNull { it.className.startsWith(pkg) }
        return " [" + chain.last().javaClass.simpleName + (frame?.let { " @ ${it.methodName}:${it.lineNumber}" } ?: "") + "]"
    }

    private fun <T> guard(block: () -> T): T {
        try {
            return block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(friendly(e) + diag(e), e)
        } catch (e: Throwable) {
            // np. NoClassDefFoundError z JavaMail — pokazujemy komunikat zamiast zamykać aplikację.
            throw IOException("Błąd biblioteki poczty: " + e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), e)
        }
    }

    private fun openStore(ctx: Context): Store = openStore(ImapCfg.active(ctx))

    private fun openStore(cfg: ImapCfg): Store {
        val proto = protoImap(cfg.imapSec)
        val session = Session.getInstance(baseProps(proto, cfg.imapSec))
        val store = session.getStore(proto)
        store.connect(cfg.imapHost, cfg.imapPort, cfg.login, cfg.password)
        return store
    }

    // Jedno połączenie IMAP współdzielone przez kolejne operacje: logowanie przez TLS przy każdym otwarciu maila
    // to kilka sekund na telefonie. Połączenie odnawiamy, gdy konto się zmieni albo serwer je zerwał.
    private val storeLock = Any()
    private var cachedStore: Store? = null
    private var cachedKey = ""

    private fun acquireStore(cfg: ImapCfg): Store = synchronized(storeLock) {
        val key = cfg.imapHost + "|" + cfg.login + "|" + cfg.password.hashCode()
        val cur = cachedStore
        if (cur != null && cachedKey == key && cur.isConnected) return cur
        try { cur?.close() } catch (e: Exception) { }
        cachedStore = null
        val st = openStore(cfg)
        cachedStore = st
        cachedKey = key
        st
    }

    private fun dropStore() = synchronized(storeLock) {
        try { cachedStore?.close() } catch (e: Exception) { }
        cachedStore = null
        cachedKey = ""
    }

    private fun <T> withStore(ctx: Context, block: (Store) -> T): T = guard {
        val cfg = ImapCfg.active(ctx)
        try {
            block(acquireStore(cfg))
        } catch (e: Exception) {
            // Połączenie mogło wygasnąć po stronie serwera — jedna próba na świeżym.
            if (e is javax.mail.StoreClosedException || e is javax.mail.FolderClosedException ||
                e is java.net.SocketException || e.cause is java.net.SocketException || e.cause is java.io.EOFException
            ) {
                dropStore()
                block(acquireStore(cfg))
            } else {
                throw e
            }
        }
    }

    /** Sprawdza logowanie do IMAP i SMTP z podanymi ustawieniami. Rzuca IOException z czytelnym opisem. */
    fun verify(cfg: ImapCfg) {
        guard {
            val store = openStore(cfg)
            try {
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                inbox.close(false)
            } finally {
                try { store.close() } catch (e: Exception) { }
            }
        }
        guard {
            val proto = protoSmtp(cfg.smtpSec)
            val props = baseProps(proto, cfg.smtpSec)
            props["mail.$proto.auth"] = "true"
            val tr = Session.getInstance(props).getTransport(proto)
            try {
                tr.connect(cfg.smtpHost, cfg.smtpPort, cfg.login, cfg.password)
            } finally {
                try { tr.close() } catch (e: Exception) { }
            }
        }
    }

    // ───────────────────────── foldery ─────────────────────────

    private val resolved = ConcurrentHashMap<String, String>()

    private val NAME_GUESS = mapOf(
        "SENT" to listOf("Sent", "Sent Items", "Sent Messages", "Sent Mail", "Wysłane", "Elementy wysłane", "INBOX.Sent", "INBOX.Sent Items"),
        "DRAFT" to listOf("Drafts", "Draft", "Robocze", "Wersje robocze", "INBOX.Drafts"),
        "TRASH" to listOf("Trash", "Deleted Items", "Deleted Messages", "Kosz", "Elementy usunięte", "INBOX.Trash")
    )
    private val SPECIAL_USE = mapOf("SENT" to "\\Sent", "DRAFT" to "\\Drafts", "TRASH" to "\\Trash")

    /** Folder pod daną rolą: INBOX, SENT, DRAFT, TRASH. Nazwy rozpoznajemy po atrybutach serwera, a w razie braku po nazwie. */
    private fun folderFor(ctx: Context, store: Store, role: String): Folder {
        if (role == "INBOX") return store.getFolder("INBOX")
        val key = Prefs.mailImapHost(ctx) + "|" + Prefs.mailLogin(ctx) + "|" + role
        resolved[key]?.let { return store.getFolder(it) }
        var found: String? = null
        try {
            val all = store.defaultFolder.list("*")
            val attr = SPECIAL_USE[role]
            if (attr != null) {
                found = all.firstOrNull { f -> (f as? IMAPFolder)?.attributes?.any { it.equals(attr, ignoreCase = true) } == true }?.fullName
            }
            if (found == null) {
                val guess = NAME_GUESS[role].orEmpty()
                found = all.firstOrNull { f -> guess.any { it.equals(f.fullName, ignoreCase = true) || it.equals(f.name, ignoreCase = true) } }?.fullName
            }
        } catch (e: Exception) {
        }
        val name = found ?: NAME_GUESS[role]?.first() ?: role
        if (found != null) resolved[key] = name
        return store.getFolder(name)
    }

    private fun openFolder(ctx: Context, store: Store, role: String, write: Boolean): IMAPFolder {
        val f = folderFor(ctx, store, role) as IMAPFolder
        if (!f.exists() && write) f.create(Folder.HOLDS_MESSAGES)
        f.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
        return f
    }

    // ───────────────────────── pomocnicze ─────────────────────────

    private val PREFIX = Regex("^\\s*(re|fw|fwd|odp|pd|aw|wg|sv)\\s*(\\[\\d+\\])?\\s*:\\s*", RegexOption.IGNORE_CASE)

    private fun normSubject(s: String?): String {
        var t = (s ?: "").trim()
        while (true) {
            val m = PREFIX.find(t) ?: break
            t = t.substring(m.range.last + 1)
        }
        return t.trim().lowercase()
    }

    private fun addrStr(a: Array<Address>?): String = try {
        a?.joinToString(", ") { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun fromOf(m: Message): String = try { addrStr(m.from) } catch (e: Exception) { "" }
    private fun toOf(m: Message): String = try { addrStr(m.getRecipients(Message.RecipientType.TO)) } catch (e: Exception) { "" }
    private fun ccOf(m: Message): String = try { addrStr(m.getRecipients(Message.RecipientType.CC)) } catch (e: Exception) { "" }
    private fun subjectOf(m: Message): String = try { m.subject.orEmpty() } catch (e: Exception) { "" }
    private fun timeOf(m: Message): Long = try { (m.sentDate ?: m.receivedDate)?.time ?: 0L } catch (e: Exception) { 0L }
    private fun unreadOf(m: Message): Boolean = try { !m.isSet(Flags.Flag.SEEN) } catch (e: Exception) { false }
    private fun uidOf(f: Folder, m: Message): Long = (f as UIDFolder).getUID(m)

    private fun fetchEnvelopes(f: Folder, msgs: Array<Message>) {
        if (msgs.isEmpty()) return
        val fp = FetchProfile()
        fp.add(FetchProfile.Item.ENVELOPE)
        fp.add(FetchProfile.Item.FLAGS)
        fp.add(FetchProfile.Item.CONTENT_INFO)
        fp.add(UIDFolder.FetchProfileItem.UID)
        f.fetch(msgs, fp)
    }

    /** Lekkie pobranie samych kopert (temat, UID) — do szukania wątku, bez struktury MIME. */
    private fun fetchLight(f: Folder, msgs: Array<Message>) {
        if (msgs.isEmpty()) return
        val fp = FetchProfile()
        fp.add(FetchProfile.Item.ENVELOPE)
        fp.add(UIDFolder.FetchProfileItem.UID)
        f.fetch(msgs, fp)
    }

    private fun hasAttachment(m: Message): Boolean = try { m.isMimeType("multipart/mixed") } catch (e: Exception) { false }

    private fun threadKey(f: Folder, m: Message): String {
        val k = normSubject(subjectOf(m))
        return if (k.isEmpty()) "#" + uidOf(f, m) else k
    }

    // ───────────────────────── lista ─────────────────────────

    /**
     * Jedna strona listy wątków. [page] = null dla pierwszej strony; zwrócony token to początek następnej (albo null).
     * Z [text] lub [unreadOnly] folder jest przeszukiwany po stronie serwera.
     */
    fun list(ctx: Context, role: String, page: String?, text: String, unreadOnly: Boolean): Pair<List<MailRow>, String?> =
        withStore(ctx) { store ->
            val f = openFolder(ctx, store, role, false)
            try {
                val msgs: Array<Message>
                val next: String?
                if (text.isBlank() && !unreadOnly) {
                    val total = f.messageCount
                    val end = page?.toIntOrNull() ?: total
                    if (end < 1) return@withStore emptyList<MailRow>() to null
                    val start = maxOf(1, end - WINDOW + 1)
                    msgs = f.getMessages(start, end)
                    next = if (start > 1) (start - 1).toString() else null
                } else {
                    var term: SearchTerm? = null
                    if (text.isNotBlank()) {
                        term = OrTerm(
                            arrayOf<SearchTerm>(
                                SubjectTerm(text),
                                FromStringTerm(text),
                                RecipientStringTerm(Message.RecipientType.TO, text)
                            )
                        )
                    }
                    if (unreadOnly) {
                        val unseen = FlagTerm(Flags(Flags.Flag.SEEN), false)
                        term = if (term == null) unseen else AndTerm(term, unseen)
                    }
                    val all = f.search(term)
                    val end = page?.toIntOrNull() ?: all.size
                    val start = maxOf(0, end - WINDOW)
                    msgs = all.copyOfRange(start, end)
                    next = if (start > 0) start.toString() else null
                }
                fetchEnvelopes(f, msgs)
                val outgoing = role == "SENT" || role == "DRAFT"
                val groups = LinkedHashMap<String, MutableList<Message>>()
                for (m in msgs.reversed()) groups.getOrPut(threadKey(f, m)) { ArrayList() }.add(m)
                val rows = groups.map { (key, list) ->
                    // list jest od najnowszej do najstarszej
                    val last = list.first()
                    val first = list.last()
                    try { knownUids["$role|$key"] = list.map { uidOf(f, it) } } catch (e: Exception) { }
                    MailRow(
                        id = "$role|$key",
                        who = senderName(if (outgoing) toOf(last) else fromOf(last)),
                        subject = subjectOf(first).ifBlank { "(bez tematu)" },
                        snippet = "",
                        time = list.maxOf { timeOf(it) },
                        unread = list.any { unreadOf(it) },
                        count = list.size,
                        hasAtt = list.any { hasAttachment(it) }
                    )
                }.sortedByDescending { it.time }
                rows to next
            } finally {
                try { f.close(false) } catch (e: Exception) { }
            }
        }

    /** Wiadomości wątku w jednym folderze (pasujące do klucza). Folder musi być otwarty. */
    private fun findInFolder(f: IMAPFolder, key: String, primary: Boolean = true): List<Message> {
        if (key.startsWith("#")) {
            val uid = key.substring(1).toLongOrNull() ?: return emptyList()
            return listOfNotNull(f.getMessageByUID(uid))
        }
        var found: List<Message> = emptyList()
        var searched = false
        // Wyszukiwanie po temacie robi serwer — ale tylko dla ASCII. Przy polskich znakach JavaMail po cichu
        // przegląda całą skrzynkę po stronie telefonu (to były dziesiątki sekund), więc wtedy od razu skanujemy okno.
        if (key.all { it.code < 128 }) {
            try {
                val candidates = f.search(SubjectTerm(key))
                searched = true
                fetchLight(f, candidates)
                found = candidates.filter { normSubject(subjectOf(it)) == key }
            } catch (e: Exception) {
                searched = false
            }
        }
        // Skan ostatnich wiadomości (tylko koperty): gdy nie szukał serwer, albo w folderze głównym nic nie znalazł.
        // W folderze pobocznym (np. Wysłane) pusty wynik wyszukiwania jest normalny i nie skanujemy.
        if (found.isEmpty() && (!searched || primary)) {
            val n = f.messageCount
            if (n > 0) {
                val size = if (primary) 400 else 150
                val window = f.getMessages(maxOf(1, n - size + 1), n)
                fetchLight(f, window)
                found = window.filter { normSubject(subjectOf(it)) == key }
            }
        }
        return found
    }

    private fun rolesFor(role: String): List<String> = when (role) {
        "INBOX" -> listOf("INBOX", "SENT")
        "SENT" -> listOf("SENT", "INBOX")
        else -> listOf(role)
    }

    // ───────────────────────── wątek ─────────────────────────

    private class Parsed {
        var html: String? = null
        var text: String? = null
        val atts = ArrayList<Part>()
        val inline = HashMap<String, Part>()
    }

    private fun readText(p: Part): String {
        val bytes = p.inputStream.use { it.readBytes() }
        val cs = try {
            ContentType(p.contentType).getParameter("charset")?.let { java.nio.charset.Charset.forName(MimeUtility.javaCharset(it)) }
        } catch (e: Exception) {
            null
        } ?: Charsets.UTF_8
        return String(bytes, cs)
    }

    private fun walk(p: Part, out: Parsed) {
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            for (i in 0 until mp.count) walk(mp.getBodyPart(i), out)
            return
        }
        val mime = try { ContentType(p.contentType).baseType.lowercase() } catch (e: Exception) { "application/octet-stream" }
        val fname = try { p.fileName.orEmpty() } catch (e: Exception) { "" }
        val cid = try { p.getHeader("Content-ID")?.firstOrNull()?.trim()?.removePrefix("<")?.removeSuffix(">").orEmpty() } catch (e: Exception) { "" }
        val disp = try { p.disposition } catch (e: Exception) { null }
        if (mime.startsWith("image/") && cid.isNotEmpty()) out.inline[cid] = p
        val isAtt = fname.isNotEmpty() || Part.ATTACHMENT.equals(disp, ignoreCase = true) || mime == "message/rfc822"
        if (isAtt) {
            out.atts.add(p)
        } else if (mime == "text/html" && out.html == null) {
            out.html = readText(p)
        } else if (mime == "text/plain" && out.text == null) {
            out.text = readText(p)
        }
    }

    private fun attName(p: Part, index: Int): String {
        val n = try { p.fileName } catch (e: Exception) { null }
        if (!n.isNullOrBlank()) return n
        val mime = try { ContentType(p.contentType).baseType.lowercase() } catch (e: Exception) { "" }
        return if (mime == "message/rfc822") "wiadomość.eml" else "załącznik-${index + 1}"
    }

    private fun attMime(p: Part): String = try { ContentType(p.contentType).baseType } catch (e: Exception) { "application/octet-stream" }

    private fun partSize(p: Part): Long = try { maxOf(0, p.size).toLong() } catch (e: Exception) { 0L }

    private fun toMailMessage(role: String, f: Folder, m: Message): MailMessage {
        val parsed = Parsed()
        try { walk(m, parsed) } catch (e: Exception) { }
        var html = parsed.html
        val usedInline = HashSet<Part>()
        if (html != null && parsed.inline.isNotEmpty() && html.contains("cid:", ignoreCase = true)) {
            for ((cid, part) in parsed.inline) {
                if (!html!!.contains("cid:$cid", ignoreCase = true)) continue
                usedInline.add(part)
                if (partSize(part) > 800_000L) continue
                val bytes = try { part.inputStream.use { it.readBytes() } } catch (e: Exception) { continue }
                val uri = "data:${attMime(part)};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                html = html.replace("cid:$cid", uri, ignoreCase = true)
            }
        }
        val uid = uidOf(f, m)
        val mm = m as MimeMessage
        val attachments = parsed.atts.withIndex()
            .filter { it.value !in usedInline }
            .map { (i, p) -> MailAttachment("$role|$uid", i.toString(), attName(p, i), attMime(p), partSize(p)) }
        val text = parsed.text
        return MailMessage(
            id = "$role|$uid",
            from = fromOf(m),
            to = toOf(m),
            time = timeOf(m),
            html = html,
            text = text,
            attachments = attachments,
            unread = unreadOf(m),
            snippet = (text ?: "").trim().replace(Regex("\\s+"), " ").take(160),
            cc = ccOf(m),
            replyTo = try { addrStr(m.replyTo) } catch (e: Exception) { "" },
            msgIdHeader = try { mm.getHeader("Message-ID")?.firstOrNull().orEmpty() } catch (e: Exception) { "" },
            refs = try { mm.getHeader("References", " ").orEmpty() } catch (e: Exception) { "" }
        )
    }

    fun getThread(ctx: Context, id: String): MailThread = withStore(ctx) { store ->
        val role = id.substringBefore('|')
        val key = id.substringAfter('|')
        val collected = ArrayList<Pair<Long, MailMessage>>()
        var subject = ""
        for (r in rolesFor(role)) {
            val f = try { openFolder(ctx, store, r, false) } catch (e: Exception) { continue }
            try {
                val known: List<Message> = if (r == role) {
                    try {
                        knownUids[id]?.let { u -> f.getMessagesByUID(u.toLongArray()).filterNotNull() }.orEmpty()
                    } catch (e: Exception) { emptyList() }
                } else emptyList()
                val msgs = when {
                    // Znamy już wiadomości wątku z listy: jedno pobranie po UID, bez wyszukiwania.
                    known.isNotEmpty() && !key.all { it.code < 128 } -> known
                    known.isNotEmpty() -> {
                        // Dla tematów ASCII jedno szybkie wyszukiwanie na serwerze dorzuca starsze wiadomości spoza okna listy.
                        fetchLight(f, known.toTypedArray())
                        val extra = findInFolder(f, key, primary = false)
                        (known + extra).distinctBy { uidOf(f, it) }
                    }
                    else -> findInFolder(f, key, primary = r == role)
                }
                if (msgs.isEmpty()) continue
                val arr = msgs.toTypedArray()
                fetchEnvelopes(f, arr)
                for (m in arr) {
                    val mm = try {
                        toMailMessage(r, f, m)
                    } catch (e: Exception) {
                        // Jedna nieczytelna wiadomość nie może blokować całej rozmowy.
                        MailMessage(
                            id = "$r|" + (try { uidOf(f, m) } catch (x: Exception) { 0L }),
                            from = fromOf(m), to = toOf(m), time = timeOf(m), html = null,
                            text = "Nie udało się odczytać treści tej wiadomości: " + (e.message ?: e.javaClass.simpleName),
                            attachments = emptyList(), unread = false, snippet = ""
                        )
                    }
                    collected.add(mm.time to mm)
                }
                if (subject.isEmpty()) subject = subjectOf(arr.minByOrNull { timeOf(it) } ?: arr.first())
            } finally {
                try { f.close(false) } catch (e: Exception) { }
            }
        }
        if (collected.isEmpty()) throw IOException("Nie znaleziono tej wiadomości na serwerze (mogła zostać przeniesiona albo usunięta). Odśwież listę.")
        val list = collected.sortedBy { it.first }.map { it.second }
        MailThread(id, subject.ifBlank { "(bez tematu)" }, list)
    }

    /** Bajty załącznika o numerze [attachmentId] z wiadomości [messageId] („ROLA|UID”). */
    fun attachment(ctx: Context, messageId: String, attachmentId: String): ByteArray = withStore(ctx) { store ->
        val role = messageId.substringBefore('|')
        val uid = messageId.substringAfter('|').toLong()
        val f = openFolder(ctx, store, role, false)
        try {
            val m = f.getMessageByUID(uid) ?: throw IOException("Wiadomość już nie istnieje na serwerze.")
            val parsed = Parsed()
            walk(m, parsed)
            val p = parsed.atts.getOrNull(attachmentId.toInt()) ?: throw IOException("Nie znaleziono załącznika.")
            p.inputStream.use { it.readBytes() }
        } finally {
            try { f.close(false) } catch (e: Exception) { }
        }
    }

    // ───────────────────────── liczniki i powiadomienia ─────────────────────────

    /** Liczba nieprzeczytanych wiadomości w Odebranych. */
    fun inboxUnread(ctx: Context): Int = withStore(ctx) { store ->
        val f = store.getFolder("INBOX")
        f.open(Folder.READ_ONLY)
        try { f.unreadMessageCount } finally { try { f.close(false) } catch (e: Exception) { } }
    }

    /** Punkt odniesienia „UIDVALIDITY:ostatni UID” — po nim rozpoznajemy nowe wiadomości. */
    fun historyMark(ctx: Context): String = withStore(ctx) { store ->
        val f = store.getFolder("INBOX") as IMAPFolder
        f.open(Folder.READ_ONLY)
        try { "${f.uidValidity}:${f.uidNext - 1}" } finally { try { f.close(false) } catch (e: Exception) { } }
    }

    /** Nowe, nieprzeczytane wiadomości w Odebranych od punktu [last] + nowy punkt odniesienia. */
    fun newInbox(ctx: Context, last: String): Pair<List<MailNew>, String> = withStore(ctx) { store ->
        val f = store.getFolder("INBOX") as IMAPFolder
        f.open(Folder.READ_ONLY)
        try {
            val validity = f.uidValidity
            val mark = "$validity:${f.uidNext - 1}"
            val lastV = last.substringBefore(':').toLongOrNull()
            val lastU = last.substringAfter(':').toLongOrNull()
            if (lastV == null || lastU == null || lastV != validity) return@withStore emptyList<MailNew>() to mark
            val msgs = f.getMessagesByUID(lastU + 1, UIDFolder.LASTUID).filter { f.getUID(it) > lastU }.toTypedArray()
            fetchEnvelopes(f, msgs)
            val out = msgs.filter { unreadOf(it) }.map { m ->
                val from = fromOf(m)
                MailNew(
                    MailRow(
                        id = "INBOX|" + threadKey(f, m),
                        who = senderName(from),
                        subject = subjectOf(m).ifBlank { "(bez tematu)" },
                        snippet = "",
                        time = timeOf(m),
                        unread = true,
                        count = 1
                    ),
                    senderEmail(from)
                )
            }
            out to mark
        } finally {
            try { f.close(false) } catch (e: Exception) { }
        }
    }

    /** Wartości nagłówków („From”, „To”, „Cc”) z ostatnich wiadomości w folderze — do podpowiedzi adresatów. */
    fun recentHeaders(ctx: Context, role: String, count: Int, names: List<String>): List<List<String>> = withStore(ctx) { store ->
        val f = openFolder(ctx, store, role, false)
        try {
            val n = f.messageCount
            if (n < 1) return@withStore emptyList<List<String>>()
            val msgs = f.getMessages(maxOf(1, n - count + 1), n)
            fetchEnvelopes(f, msgs)
            msgs.map { m ->
                names.map {
                    when (it) {
                        "From" -> fromOf(m)
                        "To" -> toOf(m)
                        "Cc" -> ccOf(m)
                        else -> ""
                    }
                }
            }
        } finally {
            try { f.close(false) } catch (e: Exception) { }
        }
    }

    // ───────────────────────── operacje na wątkach ─────────────────────────

    private fun <T> onThread(ctx: Context, id: String, write: Boolean, block: (IMAPFolder, List<Message>, Store) -> T): T =
        withStore(ctx) { store ->
            val role = id.substringBefore('|')
            val key = id.substringAfter('|')
            val f = openFolder(ctx, store, role, write)
            try {
                block(f, findInFolder(f, key), store)
            } finally {
                try { f.close(false) } catch (e: Exception) { }
            }
        }

    fun markRead(ctx: Context, id: String) {
        onThread(ctx, id, true) { f, msgs, _ ->
            if (msgs.isNotEmpty()) f.setFlags(msgs.toTypedArray(), Flags(Flags.Flag.SEEN), true)
            Unit
        }
    }

    private fun moveTo(ctx: Context, store: Store, f: IMAPFolder, msgs: List<Message>, targetRole: String) {
        if (msgs.isEmpty()) return
        val arr = msgs.toTypedArray()
        val target = folderFor(ctx, store, targetRole)
        if (!target.exists()) target.create(Folder.HOLDS_MESSAGES)
        f.copyMessages(arr, target)
        f.setFlags(arr, Flags(Flags.Flag.DELETED), true)
        try { f.expunge(arr) } catch (e: Exception) { f.expunge() }
    }

    fun trash(ctx: Context, id: String) {
        onThread(ctx, id, true) { f, msgs, store -> moveTo(ctx, store, f, msgs, "TRASH") }
    }

    fun untrash(ctx: Context, id: String) {
        onThread(ctx, id, true) { f, msgs, store -> moveTo(ctx, store, f, msgs, "INBOX") }
    }

    /** Trwale usuwa wszystko z kosza. Zwraca liczbę usuniętych wiadomości. */
    fun deleteAllTrash(ctx: Context): Int = withStore(ctx) { store ->
        val f = openFolder(ctx, store, "TRASH", true)
        try {
            val n = f.messageCount
            if (n > 0) {
                f.setFlags(1, n, Flags(Flags.Flag.DELETED), true)
                f.expunge()
            }
            n
        } finally {
            try { f.close(false) } catch (e: Exception) { }
        }
    }

    // ───────────────────────── wysyłanie ─────────────────────────

    private fun rawToMessage(ctx: Context, raw: String): MimeMessage {
        val bytes = Base64.decode(raw, Base64.URL_SAFE)
        val msg = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(bytes))
        val addr = Prefs.mailAccount(ctx)
        val name = Prefs.mailFromName(ctx)
        msg.setFrom(if (name.isBlank()) InternetAddress(addr) else InternetAddress(addr, name, "UTF-8"))
        msg.setSentDate(Date())
        if (msg.getHeader("Message-ID") == null) {
            msg.setHeader("Message-ID", "<" + UUID.randomUUID() + "@" + addr.substringAfter('@', "localhost") + ">")
        }
        return msg
    }

    /** Wysyła wiadomość przez SMTP i (jeśli włączono) zapisuje kopię w Wysłanych. [raw] to MIME w base64url z [buildRaw]. */
    fun send(ctx: Context, raw: String) {
        val msg = rawToMessage(ctx, raw)
        val rcpt = msg.allRecipients
        if (rcpt == null || rcpt.isEmpty()) throw IOException("Brak adresata.")
        guard {
            val cfg = ImapCfg.active(ctx)
            val proto = protoSmtp(cfg.smtpSec)
            val props = baseProps(proto, cfg.smtpSec)
            props["mail.$proto.auth"] = "true"
            val tr = Session.getInstance(props).getTransport(proto)
            try {
                tr.connect(cfg.smtpHost, cfg.smtpPort, cfg.login, cfg.password)
                tr.sendMessage(msg, rcpt)
            } finally {
                try { tr.close() } catch (e: Exception) { }
            }
        }
        if (Prefs.mailSaveSent(ctx)) {
            try { append(ctx, "SENT", msg, Flags.Flag.SEEN) } catch (e: Exception) { }
        }
    }

    fun saveDraft(ctx: Context, raw: String) {
        append(ctx, "DRAFT", rawToMessage(ctx, raw), Flags.Flag.DRAFT)
    }

    private fun append(ctx: Context, role: String, msg: MimeMessage, flag: Flags.Flag) {
        // Kopia z własnym buforem, bo wiadomość po wysłaniu bywa już zużyta.
        val bos = ByteArrayOutputStream()
        msg.writeTo(bos)
        val copy = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(bos.toByteArray()))
        copy.setFlag(flag, true)
        withStore(ctx) { store ->
            val f = openFolder(ctx, store, role, true)
            try {
                f.appendMessages(arrayOf<Message>(copy))
            } finally {
                try { f.close(false) } catch (e: Exception) { }
            }
        }
    }
}
