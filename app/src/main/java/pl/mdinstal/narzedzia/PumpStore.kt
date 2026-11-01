package pl.mdinstal.narzedzia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Pump(
    val serial: String,
    val model: String,
    val partner: String,
    val status: String,
    val since: String,
    /** Kiedy ostatnio widziana na liście w portalu (ms). */
    val seen: Long
) {
    val online: Boolean get() = status.equals("online", ignoreCase = true)
}

/** Lista pomp odczytana z portalu Iglutech, trzymana lokalnie w aplikacji. */
object PumpStore {
    private const val PREFS = "iglutech_pumps"
    private const val KEY_LIST = "list"
    private const val KEY_UPDATED = "updated"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Pump> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Pump(
                    serial = o.optString("serial"),
                    model = o.optString("model"),
                    partner = o.optString("partner"),
                    status = o.optString("status"),
                    since = o.optString("since"),
                    seen = o.optLong("seen")
                )
            }.filter { it.serial.isNotBlank() }.sortedBy { it.serial }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updatedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_UPDATED, 0L)

    /**
     * Scala odczyt ze strony (tablica JSON) z zapisaną listą, po numerze seryjnym.
     * Nic nie usuwa: pompa, której chwilowo nie widać na stronie, zostaje z ostatnim statusem.
     * Zwraca liczbę pomp w odczycie.
     */
    fun merge(ctx: Context, json: String): Int {
        val incoming = try { JSONArray(json) } catch (e: Exception) { return 0 }
        val now = System.currentTimeMillis()
        val old = load(ctx).associateBy { it.serial }
        val map = LinkedHashMap<String, Pump>(old)
        var n = 0
        var changed = false
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val serial = o.optString("serial")
            if (serial.isBlank()) continue
            val p = Pump(
                serial = serial,
                model = o.optString("model"),
                partner = o.optString("partner"),
                status = o.optString("status"),
                since = o.optString("since"),
                seen = now
            )
            val prev = old[serial]
            if (prev == null || prev.copy(seen = 0) != p.copy(seen = 0)) changed = true
            map[serial] = p
            n++
        }
        if (n == 0) return 0
        // Zapisuj tylko przy zmianie albo co najwyżej raz na minutę (odczyt leci co kilka sekund).
        if (changed || now - updatedAt(ctx) > 60_000) write(ctx, map.values, now)
        return n
    }

    private fun write(ctx: Context, list: Collection<Pump>, now: Long) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject()
                    .put("serial", it.serial)
                    .put("model", it.model)
                    .put("partner", it.partner)
                    .put("status", it.status)
                    .put("since", it.since)
                    .put("seen", it.seen)
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, a.toString()).putLong(KEY_UPDATED, now).apply()
    }

    private const val KEY_SYNC = "sync_state"
    private const val KEY_SYNC_AT = "sync_at"

    /** Stan synchronizacji przy starcie aplikacji: running, ok, login, timeout. */
    fun setSync(ctx: Context, state: String) {
        prefs(ctx).edit().putString(KEY_SYNC, state).putLong(KEY_SYNC_AT, System.currentTimeMillis()).apply()
    }

    fun syncState(ctx: Context): Pair<String, Long> {
        val p = prefs(ctx)
        return Pair(p.getString(KEY_SYNC, "") ?: "", p.getLong(KEY_SYNC_AT, 0L))
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** Uproszczona lista (numer seryjny, model, status) jako JSON, np. dla protokołu uruchomienia. */
    fun toJson(ctx: Context): String {
        val a = JSONArray()
        load(ctx).forEach {
            a.put(
                JSONObject()
                    .put("serial", it.serial)
                    .put("model", it.model)
                    .put("status", it.status)
            )
        }
        return a.toString()
    }
}
