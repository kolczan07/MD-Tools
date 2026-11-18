package pl.mdinstal.narzedzia

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("md_instal", Context.MODE_PRIVATE)

    fun theme(c: Context): String = sp(c).getString("theme", "system") ?: "system"
    fun setTheme(c: Context, v: String) { sp(c).edit().putString("theme", v).apply() }

    fun lastUpdateCheck(c: Context): Long = sp(c).getLong("upd_check", 0L)
    fun setLastUpdateCheck(c: Context, t: Long) { sp(c).edit().putLong("upd_check", t).apply() }

    fun favorites(c: Context): List<Station> =
        readList(sp(c).getString("fav", "[]")) { Station(it.optString("name"), it.optString("url"), it.optString("logo")) }
            .filter { it.url.isNotEmpty() }

    fun setFavorites(c: Context, l: List<Station>) {
        sp(c).edit().putString("fav", toJson(l) { JSONObject().put("name", it.name).put("url", it.url).put("logo", it.logo) }).apply()
    }

    fun shortcuts(c: Context): List<Shortcut> =
        readList(sp(c).getString("shortcuts", "[]")) { Shortcut(it.optString("pkg"), it.optString("label")) }
            .filter { it.pkg.isNotEmpty() }

    fun setShortcuts(c: Context, l: List<Shortcut>) {
        sp(c).edit().putString("shortcuts", toJson(l) { JSONObject().put("pkg", it.pkg).put("label", it.label) }).apply()
    }

    private fun <T> readList(json: String?, f: (JSONObject) -> T): List<T> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { f(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun <T> toJson(l: List<T>, f: (T) -> JSONObject): String {
        val a = JSONArray()
        l.forEach { a.put(f(it)) }
        return a.toString()
    }

    // ---- Dane logowania Iglutech: szyfrowane kluczem z Android Keystore ----
    private fun secure(c: Context): SharedPreferences? = try {
        val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            c, "md_secure_v3", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    fun hasCreds(c: Context): Boolean =
        secure(c)?.let { !it.getString("user", null).isNullOrEmpty() && !it.getString("pass", null).isNullOrEmpty() } ?: false

    fun credsUser(c: Context): String = secure(c)?.getString("user", "") ?: ""

    fun credsRemember(c: Context): Boolean = secure(c)?.getBoolean("remember", false) ?: false

    /** user, pass, remember — albo null, gdy nic nie zapisano. */
    fun creds(c: Context): Triple<String, String, Boolean>? {
        val s = secure(c) ?: return null
        val u = s.getString("user", null) ?: return null
        val p = s.getString("pass", null) ?: return null
        if (u.isEmpty() || p.isEmpty()) return null
        return Triple(u, p, s.getBoolean("remember", false))
    }

    fun saveCreds(c: Context, user: String, pass: String, remember: Boolean): Boolean {
        val s = secure(c) ?: return false
        s.edit().putString("user", user).putString("pass", pass).putBoolean("remember", remember).apply()
        return true
    }

    fun clearCreds(c: Context) {
        secure(c)?.edit()?.remove("user")?.remove("pass")?.remove("remember")?.apply()
    }

    // ---- Dane logowania Asaj (asaj.pl): ten sam szyfrowany magazyn, osobne klucze ----
    fun asajUser(c: Context): String = secure(c)?.getString("asaj_user", "") ?: ""

    /** login, hasło — albo null, gdy nic nie zapisano. */
    fun asajCreds(c: Context): Pair<String, String>? {
        val s = secure(c) ?: return null
        val u = s.getString("asaj_user", null) ?: return null
        val p = s.getString("asaj_pass", null) ?: return null
        if (u.isEmpty() || p.isEmpty()) return null
        return u to p
    }

    fun saveAsajCreds(c: Context, user: String, pass: String): Boolean {
        val s = secure(c) ?: return false
        s.edit().putString("asaj_user", user).putString("asaj_pass", pass).apply()
        return true
    }

    fun clearAsajCreds(c: Context) {
        secure(c)?.edit()?.remove("asaj_user")?.remove("asaj_pass")?.apply()
    }

    // ---- Kamery (SADP): jedne domyślne dane logowania dla wszystkich kamer, szyfrowane ----
    fun camCreds(c: Context): Pair<String, String>? {
        val s = secure(c) ?: return null
        val u = s.getString("cam_user", null) ?: return null
        val p = s.getString("cam_pass", null) ?: return null
        if (u.isEmpty() || p.isEmpty()) return null
        return u to p
    }

    fun camUser(c: Context): String = secure(c)?.getString("cam_user", "admin") ?: "admin"

    /** Zapis nowych danych kasuje znacznik odrzucenia. */
    fun saveCamCreds(c: Context, user: String, pass: String): Boolean {
        val s = secure(c) ?: return false
        s.edit().putString("cam_user", user).putString("cam_pass", pass).putBoolean("cam_bad", false).apply()
        return true
    }

    fun clearCamCreds(c: Context) {
        secure(c)?.edit()?.remove("cam_user")?.remove("cam_pass")?.remove("cam_bad")?.apply()
    }

    /** Kamera odrzuciła zapisane dane — nie próbujemy ich więcej, dopóki użytkownik nie wpisze nowych. */
    fun camBad(c: Context): Boolean = secure(c)?.getBoolean("cam_bad", false) ?: false

    fun setCamBad(c: Context, v: Boolean) { secure(c)?.edit()?.putBoolean("cam_bad", v)?.apply() }

    // ---- Konto Google do kopii zapasowej na Drive ----
    /** Połączone konto Google (e-mail). Puste = kopia na Drive wyłączona. */
    fun googleAccount(c: Context): String = sp(c).getString("google_account", "") ?: ""

    fun driveLinked(c: Context): Boolean = googleAccount(c).isNotEmpty()

    fun setGoogleAccount(c: Context, email: String) { sp(c).edit().putString("google_account", email).apply() }

    fun mailAccount(c: Context): String = sp(c).getString("mail_account", "") ?: ""

    fun setMailAccount(c: Context, email: String) { sp(c).edit().putString("mail_account", email).apply() }

    // ---- Poczta: aktywne konto (Gmail albo IMAP/SMTP) ----
    // Konto aktywne leży pod stałymi kluczami, więc reszta aplikacji czyta je jak dotąd. Lista wszystkich kont
    // (hasła w szyfrowanym magazynie) i przełączanie są w MailAccounts.kt.

    /** Rodzaj aktywnego konta: „gmail” (domyślnie, dotychczasowe zachowanie) albo „imap”. */
    fun mailKind(c: Context): String = sp(c).getString("mail_kind", "gmail") ?: "gmail"

    /** Nazwa nadawcy dla kont IMAP. */
    fun mailFromName(c: Context): String = sp(c).getString("mail_from_name", "") ?: ""
    fun mailImapHost(c: Context): String = sp(c).getString("mail_imap_host", "") ?: ""
    fun mailImapPort(c: Context): Int = sp(c).getInt("mail_imap_port", 993)
    /** „ssl” albo „starttls”. */
    fun mailImapSec(c: Context): String = sp(c).getString("mail_imap_sec", "ssl") ?: "ssl"
    fun mailSmtpHost(c: Context): String = sp(c).getString("mail_smtp_host", "") ?: ""
    fun mailSmtpPort(c: Context): Int = sp(c).getInt("mail_smtp_port", 465)
    fun mailSmtpSec(c: Context): String = sp(c).getString("mail_smtp_sec", "ssl") ?: "ssl"
    /** Login do serwera; gdy pusty, używany jest adres e-mail. */
    fun mailLogin(c: Context): String = (sp(c).getString("mail_login", "") ?: "").ifBlank { mailAccount(c) }
    fun mailPassword(c: Context): String = secure(c)?.getString("mail_pass", "") ?: ""
    /** Czy po wysłaniu zapisywać kopię w Wysłanych (wyłącz, jeśli serwer robi to sam, np. Gmail). */
    fun mailSaveSent(c: Context): Boolean = sp(c).getBoolean("mail_save_sent", true)

    /** Czy aktywne konto jest gotowe do użycia (Gmail: znany adres; IMAP: komplet danych i hasło). */
    fun mailConfigured(c: Context): Boolean =
        if (mailKind(c) == "imap") {
            mailAccount(c).isNotBlank() && mailImapHost(c).isNotBlank() && mailSmtpHost(c).isNotBlank() && mailPassword(c).isNotEmpty()
        } else {
            mailAccount(c).isNotBlank()
        }

    fun mailActiveId(c: Context): String = sp(c).getString("mail_active_id", "") ?: ""
    fun setMailActiveId(c: Context, id: String) { sp(c).edit().putString("mail_active_id", id).apply() }

    /** Lista kont jako JSON (bez haseł). */
    fun mailAccountsJson(c: Context): String = sp(c).getString("mail_accounts", "[]") ?: "[]"
    fun setMailAccountsJson(c: Context, v: String) { sp(c).edit().putString("mail_accounts", v).apply() }

    /** Hasło konta z listy (szyfrowany magazyn). */
    fun mailSavedPassword(c: Context, id: String): String = secure(c)?.getString("mail_pass_$id", "") ?: ""
    fun setMailSavedPassword(c: Context, id: String, pass: String): Boolean {
        val s = secure(c) ?: return false
        s.edit().putString("mail_pass_$id", pass).apply()
        return true
    }
    fun removeMailSavedPassword(c: Context, id: String) { secure(c)?.edit()?.remove("mail_pass_$id")?.apply() }

    /** Robi z konta [a] konto aktywne: przepisuje jego dane pod stałe klucze i czyści stan poprzedniego konta. */
    fun activateMailAccount(c: Context, a: MailAcct, password: String): Boolean {
        val s = secure(c)
        if (a.kind == "imap") {
            if (s == null) return false
            s.edit().putString("mail_pass", password).apply()
        } else {
            s?.edit()?.remove("mail_pass")?.apply()
        }
        sp(c).edit()
            .putString("mail_kind", a.kind)
            .putString("mail_active_id", a.id)
            .putString("mail_account", a.email.trim())
            .putString("mail_from_name", a.fromName.trim())
            .putString("mail_login", a.login.trim())
            .putString("mail_imap_host", a.imapHost.trim())
            .putInt("mail_imap_port", a.imapPort)
            .putString("mail_imap_sec", a.imapSec)
            .putString("mail_smtp_host", a.smtpHost.trim())
            .putInt("mail_smtp_port", a.smtpPort)
            .putString("mail_smtp_sec", a.smtpSec)
            .putBoolean("mail_save_sent", a.saveSent)
            // Punkt odniesienia powiadomień, książka adresowa i licznik dotyczyły poprzedniego konta.
            .remove("mail_history_id")
            .remove("mail_contacts")
            .remove("mail_contacts_ts")
            .apply()
        setMailUnread(c, 0)
        return true
    }

    /** Czyści aktywne konto (po usunięciu ostatniego konta z listy). */
    fun clearActiveMail(c: Context) {
        secure(c)?.edit()?.remove("mail_pass")?.apply()
        sp(c).edit()
            .putString("mail_kind", "gmail")
            .remove("mail_active_id").remove("mail_account").remove("mail_from_name").remove("mail_login")
            .remove("mail_imap_host").remove("mail_imap_port").remove("mail_imap_sec")
            .remove("mail_smtp_host").remove("mail_smtp_port").remove("mail_smtp_sec")
            .remove("mail_history_id").remove("mail_contacts").remove("mail_contacts_ts")
            .apply()
        setMailUnread(c, 0)
    }

    /** Nadawcy (adresy e-mail, małe litery), od których poczta od razu ładuje obrazy. */
    fun trustedSenders(c: Context): List<String> =
        (sp(c).getString("mail_trusted", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun setTrustedSenders(c: Context, list: List<String>) {
        sp(c).edit().putString("mail_trusted", list.distinct().joinToString("\n")).apply()
    }

    // ── Poczta: podpis, powiadomienia, książka adresowa ──
    /** Kto korzysta z aplikacji (Marcin / Daniel) — pytamy przy pierwszym uruchomieniu. Pusty = jeszcze nie wybrano. */
    fun appUser(c: Context): String = sp(c).getString("app_user", "") ?: ""
    fun setAppUser(c: Context, v: String) {
        val prev = appUser(c)
        sp(c).edit().putString("app_user", v).apply()
        // Podpis zapisany z poprzednim imieniem przepisujemy na nowe, reszta podpisu zostaje bez zmian.
        val stored = sp(c).getString("mail_signature", "") ?: ""
        if (prev.isNotBlank() && prev != v && stored.startsWith("Pozdrawiam, $prev")) {
            setMailSignature(c, stored.replaceFirst("Pozdrawiam, $prev", "Pozdrawiam, $v"))
        }
    }

    /** Wspólny podpis firmowy; różni się tylko imieniem na początku. */
    fun defaultSignature(user: String): String =
        if (user.isBlank()) "" else
            "Pozdrawiam, $user\n\nMD-INSTAL Marcin Kołak Daniel Ostolski s.c.\n08-112 Myrcha 13\nNIP: 8212683539\n" +
                "instalacje.md-instal.com\nMarcin: 517273620\nDaniel: 517944083"

    /** Podpis zapisany w ustawieniach poczty, a jeśli go nie ma — domyślny z imieniem użytkownika. */
    fun mailSignature(c: Context): String =
        (sp(c).getString("mail_signature", "") ?: "").ifBlank { defaultSignature(appUser(c)) }
    fun setMailSignature(c: Context, v: String) { sp(c).edit().putString("mail_signature", v).apply() }

    fun mailNotify(c: Context): Boolean = sp(c).getBoolean("mail_notify", false)
    fun setMailNotify(c: Context, v: Boolean) { sp(c).edit().putBoolean("mail_notify", v).apply() }

    /** Szybkie sprawdzanie poczty (usługa pierwszoplanowa, 8–17). Domyślnie włączone. */
    fun mailFast(c: Context): Boolean = sp(c).getBoolean("mail_fast", true)
    fun setMailFast(c: Context, v: Boolean) { sp(c).edit().putBoolean("mail_fast", v).apply() }

    /** Ostatnio znana liczba nieprzeczytanych wątków w Odebranych (plakietka na kafelku Poczta). */
    fun mailUnread(c: Context): Int = sp(c).getInt("mail_unread", 0)
    fun setMailUnread(c: Context, v: Int) { sp(c).edit().putInt("mail_unread", v).apply(); Widgets.updateMail(c) }

    fun mailHistoryId(c: Context): String = sp(c).getString("mail_history_id", "") ?: ""
    fun setMailHistoryId(c: Context, v: String) { sp(c).edit().putString("mail_history_id", v).apply() }

    fun mailContacts(c: Context): List<MailContact> =
        (sp(c).getString("mail_contacts", "") ?: "").lines().mapNotNull { l ->
            val p = l.split("\t")
            if (p.size >= 3 && p[0].isNotBlank()) MailContact(p[0], p[1], p[2].toIntOrNull() ?: 1) else null
        }

    fun setMailContacts(c: Context, list: List<MailContact>) {
        val top = list.sortedByDescending { it.count }.take(600)
        sp(c).edit()
            .putString("mail_contacts", top.joinToString("\n") { it.email + "\t" + it.name.replace("\t", " ").replace("\n", " ") + "\t" + it.count })
            .putLong("mail_contacts_ts", System.currentTimeMillis())
            .apply()
    }

    fun mailContactsAge(c: Context): Long = System.currentTimeMillis() - sp(c).getLong("mail_contacts_ts", 0L)

    fun clearGoogleAccount(c: Context) { sp(c).edit().remove("google_account").apply() }

    /** Domyślne konto Google: login do Asaj, jeśli jest adresem e-mail (jedno konto firmowe). */
    fun googleHint(c: Context): String {
        val saved = googleAccount(c)
        if (saved.isNotEmpty()) return saved
        val u = asajUser(c).trim()
        return if (u.contains("@")) u else ""
    }

    fun lastSync(c: Context): Long = sp(c).getLong("drive_last_sync", 0L)
    fun setLastSync(c: Context, t: Long) { sp(c).edit().putLong("drive_last_sync", t).apply() }
}
