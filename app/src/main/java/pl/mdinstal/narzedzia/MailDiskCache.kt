package pl.mdinstal.narzedzia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Pamięć podręczna poczty IMAP na dysku (osobno dla każdego konta): ostatnia lista folderu i treść wątków.
 * Dzięki niej Poczta po uruchomieniu pokazuje się od razu, a otwarcie wiadomości nie czeka na serwer.
 * To tylko cache w katalogu podręcznym — system może go wyczyścić, a aplikacja pobierze dane od nowa.
 */
object MailDiskCache {
    private const val MAX_THREADS = 120

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(ctx: Context): File {
        val d = File(File(ctx.cacheDir, "mailstore"), sha(Prefs.mailAccount(ctx).lowercase()).take(12))
        d.mkdirs()
        return d
    }

    private fun rowToJson(r: MailRow) = JSONObject().put("id", r.id).put("who", r.who).put("subject", r.subject)
        .put("snippet", r.snippet).put("time", r.time).put("unread", r.unread).put("count", r.count).put("hasAtt", r.hasAtt)

    private fun rowFromJson(o: JSONObject) = MailRow(
        o.getString("id"), o.getString("who"), o.getString("subject"), o.optString("snippet"),
        o.getLong("time"), o.getBoolean("unread"), o.getInt("count"), o.optBoolean("hasAtt")
    )

    fun saveList(ctx: Context, role: String, rows: List<MailRow>, next: String?) {
        try {
            val a = JSONArray()
            rows.forEach { a.put(rowToJson(it)) }
            File(dir(ctx), "list_$role.json").writeText(JSONObject().put("rows", a).put("next", next ?: JSONObject.NULL).toString())
        } catch (e: Exception) { }
    }

    fun loadList(ctx: Context, role: String): Pair<List<MailRow>, String?>? = try {
        val f = File(dir(ctx), "list_$role.json")
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("rows")
            (0 until a.length()).map { rowFromJson(a.getJSONObject(it)) } to (if (o.isNull("next")) null else o.getString("next"))
        }
    } catch (e: Exception) {
        null
    }

    private fun threadFile(ctx: Context, id: String) = File(dir(ctx), "t_" + sha(id) + ".json")

    fun saveThread(ctx: Context, t: MailThread, rowTime: Long) {
        try {
            val msgs = JSONArray()
            t.messages.forEach { m ->
                val atts = JSONArray()
                m.attachments.forEach { a ->
                    atts.put(JSONObject().put("messageId", a.messageId).put("attachmentId", a.attachmentId)
                        .put("name", a.name).put("mime", a.mime).put("size", a.size))
                }
                msgs.put(
                    JSONObject().put("id", m.id).put("from", m.from).put("to", m.to).put("time", m.time)
                        .put("html", m.html ?: JSONObject.NULL).put("text", m.text ?: JSONObject.NULL)
                        .put("attachments", atts).put("unread", m.unread).put("snippet", m.snippet)
                        .put("cc", m.cc).put("replyTo", m.replyTo).put("msgIdHeader", m.msgIdHeader).put("refs", m.refs)
                )
            }
            threadFile(ctx, t.id).writeText(
                JSONObject().put("id", t.id).put("subject", t.subject).put("rowTime", rowTime).put("messages", msgs).toString()
            )
            trim(ctx)
        } catch (e: Exception) { }
    }

    /** Wątek z dysku razem z czasem wiersza listy, dla którego go zapisano (do rozpoznania nowych odpowiedzi). */
    fun loadThread(ctx: Context, id: String): Pair<MailThread, Long>? = try {
        val f = threadFile(ctx, id)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val ma = o.getJSONArray("messages")
            val msgs = (0 until ma.length()).map { i ->
                val m = ma.getJSONObject(i)
                val aa = m.getJSONArray("attachments")
                MailMessage(
                    id = m.getString("id"), from = m.getString("from"), to = m.getString("to"), time = m.getLong("time"),
                    html = if (m.isNull("html")) null else m.getString("html"),
                    text = if (m.isNull("text")) null else m.getString("text"),
                    attachments = (0 until aa.length()).map { j ->
                        val a = aa.getJSONObject(j)
                        MailAttachment(a.getString("messageId"), a.getString("attachmentId"), a.getString("name"), a.getString("mime"), a.getLong("size"))
                    },
                    unread = m.getBoolean("unread"), snippet = m.optString("snippet"), cc = m.optString("cc"),
                    replyTo = m.optString("replyTo"), msgIdHeader = m.optString("msgIdHeader"), refs = m.optString("refs")
                )
            }
            MailThread(o.getString("id"), o.getString("subject"), msgs) to o.optLong("rowTime", 0L)
        }
    } catch (e: Exception) {
        null
    }

    fun removeThread(ctx: Context, id: String) {
        try { threadFile(ctx, id).delete() } catch (e: Exception) { }
    }

    private fun trim(ctx: Context) {
        try {
            val files = dir(ctx).listFiles { f -> f.name.startsWith("t_") } ?: return
            if (files.size <= MAX_THREADS) return
            files.sortedBy { it.lastModified() }.take(files.size - MAX_THREADS).forEach { it.delete() }
        } catch (e: Exception) { }
    }
}
