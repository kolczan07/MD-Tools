package pl.mdinstal.narzedzia

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

/** Klient API Iglutech (https://api.iglutech.eu/api/v2), token Bearer bierzemy z sesji portalu w WebView. */
object IgluApi {
    private const val BASE = "https://api.iglutech.eu/api/v2"

    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
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
     * (serial, model, partner, status, since, email), więc PumpStore.merge działa bez zmian.
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
}

/**
 * Odczyt listy pomp: najpierw przez API (token z sesji portalu), a gdy się nie uda,
 * dotychczasowe czytanie tabeli ze strony. Zwraca tablicę JSON dla PumpStore.merge albo pusty tekst.
 * Wynik próby (skąd dane, albo dlaczego API nie zadziałało) zapisuje w PumpStore.setDiag,
 * żeby było widać go na ekranie "Pompy ciepła" (bez logcata). Wartość tokenu nigdy nie jest zapisywana.
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
