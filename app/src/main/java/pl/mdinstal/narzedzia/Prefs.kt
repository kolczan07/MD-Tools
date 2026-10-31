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
        readList(sp(c).getString("fav", "[]")) { Station(it.optString("name"), it.optString("url")) }
            .filter { it.url.isNotEmpty() }

    fun setFavorites(c: Context, l: List<Station>) {
        sp(c).edit().putString("fav", toJson(l) { JSONObject().put("name", it.name).put("url", it.url) }).apply()
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
        secure(c)?.edit()?.clear()?.apply()
    }
}
