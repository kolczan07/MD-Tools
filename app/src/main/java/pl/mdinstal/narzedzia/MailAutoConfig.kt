package pl.mdinstal.narzedzia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gotowe ustawienia serwerów poczty dla znanego dostawcy. */
data class MailPreset(
    val name: String,
    val imapHost: String, val imapPort: Int, val imapSsl: Boolean,
    val smtpHost: String, val smtpPort: Int, val smtpSsl: Boolean,
    /** Co jeszcze trzeba zrobić po stronie dostawcy; pokazywane pod formularzem. */
    val note: String = "",
    /** Czy serwer sam zapisuje wysłane wiadomości (wtedy wyłączamy własną kopię w Wysłanych). */
    val savesSentItself: Boolean = false
)

/**
 * Autokonfiguracja IMAP/SMTP: najpierw po domenie adresu, a dla własnych domen po rekordach MX
 * (np. firmowa domena na Google Workspace albo na home.pl).
 */
object MailAutoConfig {
    private val GOOGLE = MailPreset(
        "Google (Gmail / Workspace)",
        "imap.gmail.com", 993, true, "smtp.gmail.com", 465, true,
        note = "Google wymaga hasła aplikacji (Konto Google → Bezpieczeństwo → Weryfikacja dwuetapowa → Hasła aplikacji), a nie zwykłego hasła. W Workspace administrator musi dopuścić dostęp IMAP.",
        savesSentItself = true
    )
    private val HOME = MailPreset(
        "home.pl",
        "mail.home.pl", 993, true, "mail.home.pl", 465, true,
        note = "Login to pełny adres e-mail. Jeśli połączenie się nie uda, sprawdź w panelu home.pl dane serwera poczty swojego konta."
    )

    private val DOMAINS = mapOf(
        "gmail.com" to GOOGLE, "googlemail.com" to GOOGLE, "google.pl" to GOOGLE, "google.com" to GOOGLE,
        "home.pl" to HOME
    )

    private fun fromMx(hosts: List<String>): MailPreset? {
        val h = hosts.map { it.lowercase().trimEnd('.') }
        return when {
            h.any { it.endsWith(".google.com") || it.endsWith(".googlemail.com") || it == "google.com" } -> GOOGLE
            h.any { it.endsWith(".home.pl") || it == "home.pl" } -> HOME
            else -> null
        }
    }

    /** Nazwy serwerów poczty (MX) domeny przez DNS-over-HTTPS Google. Pusta lista, gdy się nie uda. */
    private suspend fun mxHosts(domain: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://dns.google/resolve?type=MX&name=" + java.net.URLEncoder.encode(domain, "UTF-8"))
                .openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("Accept", "application/dns-json")
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val ans = JSONObject(txt).optJSONArray("Answer") ?: return@withContext emptyList()
            (0 until ans.length()).mapNotNull { i ->
                // "10 aspmx.l.google.com."
                ans.getJSONObject(i).optString("data").trim().split(" ").lastOrNull()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Ustawienia dla adresu [email] albo null, gdy dostawcy nie rozpoznajemy. */
    suspend fun detect(email: String): MailPreset? {
        val domain = email.substringAfter('@', "").trim().lowercase()
        if (!domain.contains('.')) return null
        DOMAINS[domain]?.let { return it }
        return fromMx(mxHosts(domain))
    }
}
