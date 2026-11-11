package pl.mdinstal.narzedzia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Przypisanie kontrahenta do dokumentu Asaj (WZ/FS). Pusty [contractor] to usunięcie przypisania
 * (znacznik zostaje, żeby usunięcie dotarło też na drugi telefon przy synchronizacji z Drive).
 */
class DocAssign(val number: String, val contractor: String, val date: String, val updated: Long)

/** Lokalny magazyn przypisań "numer dokumentu → kontrahent" + scalanie z kopią z Drive. */
object ContractorStore {
    private const val KEY = "doc_contractors"

    private fun sp(c: Context) = c.getSharedPreferences("md_instal", Context.MODE_PRIVATE)

    /** Wszystkie wpisy, razem ze znacznikami usunięcia. */
    @Synchronized
    fun all(c: Context): Map<String, DocAssign> = parse(sp(c).getString(KEY, null))

    /** Tylko aktywne przypisania: numer dokumentu → nazwa kontrahenta. */
    fun active(c: Context): Map<String, String> =
        all(c).filterValues { it.contractor.isNotEmpty() }.mapValues { it.value.contractor }

    /** Nazwy kontrahentów do podpowiedzi: unikalne, od ostatnio użytych. */
    fun names(c: Context): List<String> =
        all(c).values.filter { it.contractor.isNotEmpty() }
            .sortedByDescending { it.updated }
            .map { it.contractor }
            .distinctBy { it.lowercase() }

    /** Ustawia (lub, przy pustej nazwie, usuwa) kontrahenta dla dokumentu. */
    @Synchronized
    fun set(c: Context, number: String, date: String, contractor: String) {
        val m = all(c).toMutableMap()
        m[number] = DocAssign(number, contractor.trim(), date, System.currentTimeMillis())
        save(c, m)
    }

    @Synchronized
    fun replaceAll(c: Context, m: Map<String, DocAssign>) = save(c, m)

    private fun save(c: Context, m: Map<String, DocAssign>) {
        sp(c).edit().putString(KEY, toJson(m)).apply()
    }

    /** Scala dwa zbiory: dla każdego dokumentu wygrywa nowszy wpis. */
    fun merge(a: Map<String, DocAssign>, b: Map<String, DocAssign>): Map<String, DocAssign> {
        val out = HashMap<String, DocAssign>(a)
        for ((k, v) in b) {
            val cur = out[k]
            if (cur == null || v.updated > cur.updated) out[k] = v
        }
        return out
    }

    /** JSON w stałej kolejności (po numerze), żeby dało się porównać dwa zbiory jako tekst. */
    fun toJson(m: Map<String, DocAssign>): String {
        val arr = JSONArray()
        m.values.sortedBy { it.number }.forEach {
            arr.put(JSONObject().put("n", it.number).put("c", it.contractor).put("d", it.date).put("t", it.updated))
        }
        return JSONObject().put("v", 1).put("items", arr).toString()
    }

    fun parse(json: String?): Map<String, DocAssign> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONObject(json).optJSONArray("items") ?: return emptyMap()
            val out = HashMap<String, DocAssign>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val n = o.optString("n")
                if (n.isNotEmpty()) out[n] = DocAssign(n, o.optString("c"), o.optString("d"), o.optLong("t"))
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
