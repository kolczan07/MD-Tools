package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * Temperatura zewnętrzna z pompy Iglutech (parametr OutdoorT) do metadanych radia (trzecia linia w aucie).
 * Analogi nie są w /state, więc robimy to co ekran Live: token z sesji portalu (ukryty WebView), prośba
 * GET devices/{id}/request i jedna ramka ze strumienia SignalR. Wszystko wołać z wątku głównego.
 */
object OutdoorTemp {
    private const val PORTAL = "https://service.iglutech.eu"
    /** Fragment numeru seryjnego (albo id) pompy, z której bierzemy temperaturę. */
    private const val PUMP_MATCH = "5010"
    private const val KEYWORD = "OutdoorT"

    /** Ostatni odczyt jako tekst do wyświetlenia (np. "Zewn. 7,5 °C"); null, dopóki nie ma odczytu. */
    @Volatile var lastText: String? = null

    fun format(t: Double): String = String.format(Locale("pl", "PL"), "Zewn. %.1f °C", t)

    private fun findPump(ctx: Context): Pump? {
        val list = PumpStore.load(ctx)
        return list.firstOrNull { it.serial.contains(PUMP_MATCH) }
            ?: list.firstOrNull { it.id.toString() == PUMP_MATCH }
    }

    /** Zwraca temperaturę w °C albo null (brak pompy na liście, brak sesji portalu, timeout). */
    suspend fun fetch(ctx: Context): Double? {
        val app = ctx.applicationContext
        val pump = findPump(app)?.takeIf { it.id > 0L } ?: return null
        val token = ensureToken(app) ?: return null
        return readOutdoor(app, pump, token)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureToken(ctx: Context): String? {
        if (IgluToken.valid()) return IgluToken.token
        return withTimeoutOrNull(35_000) {
            val wv = WebView(ctx)
            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                wv.webViewClient = WebViewClient()
                wv.loadUrl(PORTAL)
                while (!IgluToken.valid()) {
                    delay(1500)
                    try {
                        val raw = wv.evalJs(TOKEN_JS)
                        if (!raw.isNullOrBlank()) {
                            val t = JSONObject(raw).optString("t")
                            if (t.isNotBlank()) IgluToken.set(t)
                        }
                    } catch (e: Exception) {
                    }
                }
                IgluToken.token
            } finally {
                wv.destroy()
            }
        }
    }

    private class Bridge(private val onParams: (String) -> Unit) {
        @JavascriptInterface fun data(json: String) = onParams(json)
        @JavascriptInterface fun status(s: String) { }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readOutdoor(ctx: Context, pump: Pump, token: String): Double? =
        withTimeoutOrNull(45_000) {
            val result = CompletableDeferred<Double>()
            val wv = WebView(ctx)
            try {
                wv.settings.javaScriptEnabled = true
                wv.addJavascriptInterface(Bridge { json ->
                    try {
                        val v = JSONObject(json).opt(KEYWORD)
                        if (v is Number) result.complete(v.toDouble())
                    } catch (e: Exception) {
                    }
                }, "Android")
                wv.webViewClient = WebViewClient()
                wv.loadDataWithBaseURL(PORTAL, streamHtml(pump.serial, token), "text/html", "utf-8", null)
                coroutineScope {
                    // Ramka z analogami przychodzi dopiero po GET devices/{id}/request.
                    val poller = launch(Dispatchers.IO) {
                        delay(1500)
                        while (isActive) {
                            try { IgluApi.requestData(pump.id, token) } catch (e: Exception) { }
                            delay(6000)
                        }
                    }
                    val v = result.await()
                    poller.cancel()
                    v
                }
            } finally {
                wv.destroy()
            }
        }

    private fun streamHtml(serial: String, token: String): String = """
<html><body><script>
(function(){
  var RS=String.fromCharCode(30);
  var TOKEN=${JSONObject.quote(token)};
  var SERIAL=${JSONObject.quote(serial)};
  var ws=null, timer=null;
  function send(o){ try{ ws.send(JSON.stringify(o)+RS); }catch(e){} }
  function connect(){
    try{
      ws=new WebSocket('wss://signalr.iglutech.eu/hubs/devicePayload-stream?access_token='+encodeURIComponent(TOKEN));
    }catch(e){ return; }
    ws.onopen=function(){ ws.send(JSON.stringify({protocol:'json',version:1})+RS); };
    ws.onmessage=function(ev){
      String(ev.data).split(RS).forEach(function(part){
        if(!part) return;
        var o; try{ o=JSON.parse(part); }catch(e){ return; }
        if(o.type===undefined){
          if(o.error) return;
          send({type:6});
          send({arguments:[SERIAL],target:'SubscribeToServiceDeviceStream',type:1});
        } else if(o.type===1 && o.target==='ServiceStreamData'){
          var a=(o.arguments||[])[0];
          if(a && a.parameters){ Android.data(JSON.stringify(a.parameters)); }
        } else if(o.type===6){
          send({type:6});
        }
      });
    };
    ws.onclose=function(){ clearTimeout(timer); timer=setTimeout(connect,5000); };
    ws.onerror=function(){};
  }
  connect();
  setInterval(function(){ if(ws && ws.readyState===1) send({type:6}); },15000);
})();
</script></body></html>
"""
}
