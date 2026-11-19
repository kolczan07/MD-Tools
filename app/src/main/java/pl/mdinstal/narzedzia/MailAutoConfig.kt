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

    private fun httpGet(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3500
        c.readTimeout = 3500
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun serverBlock(xml: String, tag: String, type: String): Triple<String, Int, Boolean>? {
        val m = Regex("<$tag\\s+type=\"$type\"[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        val b = m.groupValues[1]
        val host = Regex("<hostname>\\s*([^<\\s]+)\\s*</hostname>").find(b)?.groupValues?.get(1) ?: return null
        val port = Regex("<port>\\s*(\\d+)\\s*</port>").find(b)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val sock = Regex("<socketType>\\s*([^<\\s]+)\\s*</socketType>").find(b)?.groupValues?.get(1)?.uppercase()
        return Triple(host, port, sock == "SSL")
    }

    /**
     * Standardowa autokonfiguracja Mozilli (to z niej korzysta Thunderbird): plik publikowany przez dostawcę
     * albo baza Thunderbirda. Daje m.in. indywidualny adres serwera home.pl (serwerNNNNNN.home.pl).
     */
    private suspend fun fromAutoconfig(domain: String, email: String, name: String, note: String): MailPreset? =
        withContext(Dispatchers.IO) {
            val mail = java.net.URLEncoder.encode(email, "UTF-8")
            val urls = listOf(
                "https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=$mail",
                "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml?emailaddress=$mail",
                "https://autoconfig.thunderbird.net/v1.1/$domain"
            )
            for (u in urls) {
                val xml = httpGet(u) ?: continue
                val imap = serverBlock(xml, "incomingServer", "imap") ?: continue
                val smtp = serverBlock(xml, "outgoingServer", "smtp") ?: continue
                return@withContext MailPreset(name, imap.first, imap.second, imap.third, smtp.first, smtp.second, smtp.third, note = note)
            }
            null
        }

    /** Ustawienia dla adresu [email] albo null, gdy dostawcy nie rozpoznajemy. */
    suspend fun detect(email: String): MailPreset? {
        val domain = email.substringAfter('@', "").trim().lowercase()
        if (!domain.contains('.')) return null
        DOMAINS[domain]?.let { return it }
        val mx = fromMx(mxHosts(domain))
        if (mx === HOME) {
            // home.pl: każde konto ma własny serwer (serwerNNNNNN.home.pl) — bierzemy go z autokonfiguracji.
            fromAutoconfig(domain, email, "home.pl", HOME.note)?.let { return it }
            return HOME.copy(
                imapHost = domain, smtpHost = domain,
                note = "Nie udało się pobrać adresu serwera automatycznie. Wpisz adres serwera poczty z Panelu klienta home.pl (Usługi WWW → nazwa serwera → Konta e-mail), zwykle serwerNNNNNN.home.pl. Login to pełny adres e-mail."
            )
        }
        if (mx != null) return mx
        return fromAutoconfig(domain, email, domain, "Ustawienia pobrane automatycznie. Login to zwykle pełny adres e-mail.")
    }
}
