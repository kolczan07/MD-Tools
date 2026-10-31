package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private const val IGLU_URL = "https://service.iglutech.eu"
private const val TAG = "IgluWV"

private fun autofillJs(user: String, pass: String, remember: Boolean): String {
    val u = JSONObject.quote(user)
    val p = JSONObject.quote(pass)
    return """
(function(u,p,r){
  var tries=0;
  function setVal(el,v){
    var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
    d.set.call(el,v);
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  }
  var t=setInterval(function(){
    var pw=document.querySelector('input[type=password]');
    if(pw){
      var inputs=Array.prototype.slice.call(document.querySelectorAll('input'));
      var idx=inputs.indexOf(pw), us=null;
      for(var i=idx-1;i>=0;i--){
        var ty=(inputs[i].type||'text').toLowerCase();
        if(ty==='text'||ty==='email'){us=inputs[i];break;}
      }
      if(us) setVal(us,u);
      setVal(pw,p);
      if(r){
        inputs.forEach(function(i){
          if((i.type||'').toLowerCase()==='checkbox'){
            var lab=(i.id||'')+' '+(i.name||'')+' '+((i.labels&&i.labels[0])?i.labels[0].textContent:'')+' '+(i.parentElement?i.parentElement.textContent:'');
            if(/remember/i.test(lab)&&!i.checked) i.click();
          }
        });
      }
      clearInterval(t);
    }
    if(++tries>20) clearInterval(t);
  },500);
})($u,$p,$remember);
""".trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IglutechScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    var ready by remember { mutableStateOf(false) }
    var creds by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        if (Prefs.hasCreds(ctx)) {
            authenticate(activity, "Logowanie do Iglutech", "Potwierdź tożsamość, aby wpisać zapisane dane") { ok ->
                if (ok) creds = Prefs.creds(ctx)
                ready = true
            }
        } else ready = true
    }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    AppScaffold("Iglutech Service", onBack) { pad ->
        if (!ready) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(pad),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                        // WebView dokleja do każdego żądania nagłówek X-Requested-With z ID aplikacji.
                        // Serwery i WAF potrafią przez niego odrzucać zapytania do API, a Chrome go nie wysyła.
                        // Pusta lista = nagłówek nie jest wysyłany do żadnego originu.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                        }

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                Log.d(TAG, "${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}")
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                val cr = creds ?: return
                                if (url != null && url.startsWith(IGLU_URL)) {
                                    view.evaluateJavascript(autofillJs(cr.first, cr.second, cr.third), null)
                                }
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                Log.e(TAG, "onReceivedError: ${error.description} for ${request.url}")
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                Log.e(TAG, "HTTP ${errorResponse.statusCode} for ${request.url}")
                            }
                        }

                        web = this
                        loadUrl(IGLU_URL)
                    }
                },
                onRelease = { it.destroy() }
            )
        }
    }
}
