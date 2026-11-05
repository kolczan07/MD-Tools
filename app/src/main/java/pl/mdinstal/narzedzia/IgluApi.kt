package pl.mdinstal.narzedzia

import android.util.Base64
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val API_TAG = "IgluApi"

/**
 * Szuka w localStorage / sessionStorage strony portalu ważnego tokenu dostępu (JWT z zakresem api:device).
 * Zwraca tekst JSON: {"w":"local:klucz","t":"<token>"} albo {"w":"","keys":[...]} (same nazwy kluczy,
 * do diagnozy, gdy tokenu tam nie ma). Nic nie zmienia na stronie.
 */
internal const val TOKEN_JS = """
(function(){
  function b64(s){ s=s.replace(/-/g,'+').replace(/_/g,'/'); while(s.length%4) s+='='; return atob(s); }
  function okJwt(t){
    try{
      var p=JSON.parse(b64(t.split('.')[1]));
      if(p.exp && p.exp*1000 < Date.now()+30000) return false;
      var sc=p.scope; if(typeof sc==='string') sc=sc.split(' ');
      return !!(sc && sc.indexOf('api:device')>=0);
    }catch(e){ return false; }
  }
  var re=/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g;
  var keys=[];
  var stores=[['local',window.localStorage],['session',window.sessionStorage]];
  for(var s=0;s<stores.length;s++){
    var st=stores[s][1];
    try{
      for(var i=0;i<st.length;i++){
        var k=st.key(i); keys.push(stores[s][0]+':'+k);
        var v=st.getItem(k)||'';
        var m=v.match(re);
        if(m){ for(var j=0;j<m.length;j++){ if(okJwt(m[j])) return JSON.stringify({w:stores[s][0]+':'+k,t:m[j]}); } }
      }
    }catch(e){}
  }
  return JSON.stringify({w:'',keys:keys});
})();
"""

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

/**
 * Ostatni token dostępu pobrany z sesji portalu. Trzymany tylko w pamięci (znika po zamknięciu aplikacji).
 * Ważność bierzemy z pola exp w tokenie.
 */
object IgluToken {
    @Volatile var token: String? = null
        private set
    @Volatile private var expMs: Long = 0L

    fun set(t: String) {
        token = t
        expMs = try {
            val payload = t.split('.')[1]
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(json).optLong("exp") * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    fun clear() {
        token = null
        expMs = 0L
    }

    fun valid(): Boolean = token != null && System.currentTimeMillis() < expMs - 60_000L
}

/** Klient API Iglutech (https://api.iglutech.eu/api/v2), token Bearer bierzemy z sesji portalu w WebView. */
object IgluApi {
    private const val BASE = "https://api.iglutech.eu/api/v2"

    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MD-Tools/3.1")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Pobiera wszystkie strony listy pomp i zwraca tablicę JSON w tym samym formacie co SCRAPE_JS
     * (serial, model, partner, status, since, email) plus id, więc PumpStore.merge działa bez zmian.
     */
    fun fetchPumps(token: String): String {
        val out = JSONArray()
        var page = 1
        while (page in 1..20) {
            val o = JSONObject(get("$BASE/heatPumps?pageNumber=$page&pageSize=50", token))
            val data = o.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val d = data.getJSONObject(i)
                val sn = d.str("serialNo")
                if (sn.isBlank()) continue
                out.put(
                    JSONObject()
                        .put("id", d.optLong("id"))
                        .put("serial", sn)
                        .put("model", d.str("model"))
                        .put("partner", d.str("partnerName"))
                        .put("status", d.str("connectionStatus"))
                        .put("since", d.str("registeredAt").take(10))
                        // Usuń tę linijkę, jeśli nie chcesz trzymać adresów e-mail klientów w aplikacji.
                        .put("email", d.str("ownerEmail"))
                )
            }
            if (o.isNull("nextPageNumber")) break
            page = o.getInt("nextPageNumber")
        }
        return out.toString()
    }

    /** Szczegóły pompy (nazwa własna, status, wersje, dane systemowe). Po wewnętrznym id pompy. */
    fun heatPump(id: Long, token: String): JSONObject = JSONObject(get("$BASE/heatPumps/$id", token))

    /** Stan pracy: obiekt z currentState i desiredState (słowniki słowo kluczowe -> wartość). Po id. */
    fun state(id: Long, token: String): JSONObject =
        JSONObject(get("$BASE/devices/$id/state", token)).getJSONObject("data")

    /**
     * Zmiana jednego parametru: POST devices/{id}/state z treścią {"parameters":{"Słowo":wartość}}
     * (tak robi portal). Serwer odpowiada 200 bez treści, zadana wartość pojawia się w desiredState,
     * a currentState zmienia się, gdy pompa zastosuje zmianę. Wartość: Boolean albo liczba (Long / Double).
     */
    fun setParam(id: Long, keyword: String, value: Any, token: String) {
        val body = JSONObject().put("parameters", JSONObject().put(keyword, value)).toString()
        val c = URL("$BASE/devices/$id/state").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "MD-Tools/3.1")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
                throw IOException("HTTP ${c.responseCode}" + (if (err.isNullOrBlank()) "" else " " + err.take(200)))
            }
        } finally {
            c.disconnect()
        }
    }

    /** Opis parametrów (słowo kluczowe, jednostka, dostęp RW/R, grupa, lista wartości). Po numerze seryjnym. */
    fun config(serial: String, token: String): JSONArray =
        JSONObject(get("$BASE/devices/$serial/config/render", token)).getJSONArray("parameters")
}

/**
 * Odczyt listy pomp: najpierw przez API (token z sesji portalu), a gdy się nie uda,
 * dotychczasowe czytanie tabeli ze strony. Zwraca tablicę JSON dla PumpStore.merge albo pusty tekst.
 * Wynik próby (skąd dane, albo dlaczego API nie zadziałało) zapisuje w PumpStore.setDiag,
 * żeby było widać go na ekranie "Pompy ciepła" (bez logcata). Wartość tokenu nigdy nie jest zapisywana na dysku.
 */
internal suspend fun WebView.readPumps(): String? {
    val ctx = context.applicationContext
    var diag = ""
    try {
        val raw = evalJs(TOKEN_JS)
        if (raw.isNullOrBlank()) {
            diag = "Nie udało się odczytać pamięci strony (strona jeszcze się ładuje?)"
        } else {
            val o = JSONObject(raw)
            val token = o.optString("t")
            if (token.isNotBlank()) {
                IgluToken.set(token)
                val json = withContext(Dispatchers.IO) { IgluApi.fetchPumps(token) }
                if (json != "[]") {
                    Log.d(API_TAG, "lista z API, token z ${o.optString("w")}")
                    PumpStore.setDiag(ctx, "API OK (token z ${o.optString("w")}), pomp: ${JSONArray(json).length()}")
                    return json
                }
                diag = "API zwróciło pustą listę"
            } else {
                // Tylko nazwy kluczy, nigdy wartości.
                val keys = o.optJSONArray("keys")?.toString().orEmpty()
                Log.d(API_TAG, "brak tokenu w pamięci strony, klucze: $keys")
                diag = "Brak tokenu w pamięci strony. Klucze: " + keys.take(600)
            }
        }
    } catch (e: Exception) {
        Log.d(API_TAG, "API: ${e.message}")
        diag = "Błąd API: ${e.message}"
    }
    PumpStore.setDiag(ctx, "$diag. Dane z tabeli strony.")
    return evalJs(SCRAPE_JS)
}
