package pl.mdinstal.narzedzia

import android.content.Context

/**
 * Jedno wejście do poczty niezależnie od rodzaju aktywnego konta: Gmail (API, [token] z Google) albo IMAP/SMTP
 * ([ImapApi], [token] jest wtedy pomijany). Identyfikatory wątków i wiadomości są wewnętrzne dla danego rodzaju konta.
 */
object MailApi {
    fun isImap(ctx: Context): Boolean = Prefs.mailKind(ctx) == "imap"

    /** Jedna strona listy wątków; [page] = null dla pierwszej. Zwraca wiersze i token następnej strony (albo null). */
    suspend fun list(ctx: Context, token: String, label: String, page: String?, text: String, unreadOnly: Boolean): Pair<List<MailRow>, String?> {
        if (isImap(ctx)) return ImapApi.list(ctx, label, page, text, unreadOnly)
        val q = listOfNotNull(if (unreadOnly) "is:unread" else null, text.trim().ifBlank { null }).joinToString(" ")
        val (ids, next) = GmailApi.listThreads(token, label, page, q)
        return GmailApi.rows(token, ids, label) to next
    }

    fun inboxUnread(ctx: Context, token: String): Int =
        if (isImap(ctx)) ImapApi.inboxUnread(ctx) else GmailApi.inboxUnread(token)

    fun getThread(ctx: Context, token: String, id: String): MailThread =
        if (isImap(ctx)) ImapApi.getThread(ctx, id) else GmailApi.getThread(token, id)

    fun attachment(ctx: Context, token: String, messageId: String, attachmentId: String): ByteArray =
        if (isImap(ctx)) ImapApi.attachment(ctx, messageId, attachmentId) else GmailApi.attachment(token, messageId, attachmentId)

    fun markRead(ctx: Context, token: String, id: String) {
        if (isImap(ctx)) ImapApi.markRead(ctx, id) else GmailApi.markRead(token, id)
    }

    fun trash(ctx: Context, token: String, id: String) {
        if (isImap(ctx)) ImapApi.trash(ctx, id) else GmailApi.trash(token, id)
    }

    fun untrash(ctx: Context, token: String, id: String) {
        if (isImap(ctx)) ImapApi.untrash(ctx, id) else GmailApi.untrash(token, id)
    }

    fun deleteAllTrash(ctx: Context, token: String): Int =
        if (isImap(ctx)) ImapApi.deleteAllTrash(ctx) else GmailApi.deleteAllTrash(token)

    fun send(ctx: Context, token: String, raw: String, threadId: String?) {
        if (isImap(ctx)) ImapApi.send(ctx, raw) else GmailApi.send(token, raw, threadId)
    }

    fun saveDraft(ctx: Context, token: String, raw: String, threadId: String?) {
        if (isImap(ctx)) ImapApi.saveDraft(ctx, raw) else GmailApi.saveDraft(token, raw, threadId)
    }

    suspend fun recentHeaders(ctx: Context, token: String, label: String, count: Int, names: List<String>): List<List<String>> =
        if (isImap(ctx)) ImapApi.recentHeaders(ctx, label, count, names) else GmailApi.recentHeaders(token, label, count, names)
}
