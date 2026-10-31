package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
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
import org.json.JSONObject

private const val IGLU_URL = "https://service.iglutech.eu"
private const val TAG = "IgluWV"

/**
 * Bezpiecznik: jeśli w tym WebView jednostki vh dają 0, przelicza je w stylach strony na piksele.
 * Gdy vh działa normalnie, skrypt nic nie robi.
 */
private const val FIX_VH_JS = """
(function(){
  if (window.__vhFix) return; window.__vhFix = true;
  var props=['height','min-height','max-height','top','bottom','margin-top','margin-bottom','padding-top','padding-bottom'];
  var re=/(-?\d*\.?\d+)(d|s|l)?vh\b/g;
  function broken(){
    var t=document.createElement('div');
    t.style.cssText='position:fixed;height:100vh;width:1px;visibility:hidden';
    document.body.appendChild(t);
    var z=t.offsetHeight===0;
    t.remove();
    return z;
  }
  function conv(v){var H=window.innerHeight;return v.replace(re,function(m,n){return (parseFloat(n)*H/100)+'px';});}
  function proc(r){
    if(r.cssRules){for(var i=0;i<r.cssRules.length;i++)proc(r.cssRules[i]);}
    if(r.style){
      props.forEach(function(p){
        var v=r.style.getPropertyValue(p);
        if(v&&/vh/.test(v))r.style.setProperty(p,conv(v),r.style.getPropertyPriority(p));
      });
    }
  }
  function fix(){
    if(!document.body||!broken())return;
    for(var s=0;s<document.styleSheets.length;s++){
      try{var rules=document.styleSheets[s].cssRules;for(var i=0;i<rules.length;i++)proc(rules[i]);}catch(e){}
    }
    document.querySelectorAll('[style*="vh"]').forEach(function(el){
      props.forEach(function(p){
        var v=el.style.getPropertyValue(p);
        if(v&&/vh/.test(v))el.style.setProperty(p,conv(v));
      });
    });
  }
  fix(); setTimeout(fix,1500); setTimeout(fix,4000);
  var tm;
  new MutationObserver(function(){clearTimeout(tm);tm=setTimeout(fix,200);}).observe(document.head,{childList:true});
})();
"""

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
                        // Jawne MATCH_PARENT: bez tego silnik WebView traktuje wysokość jak wrap_content
                        // i zeruje wysokość układu, przez co 100vh = 0 i strona zapada się do pustego okna.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

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
                                if (url == null || !url.startsWith(IGLU_URL)) return
                                view.evaluateJavascript(FIX_VH_JS, null)
                                val cr = creds ?: return
                                view.evaluateJavascript(autofillJs(cr.first, cr.second, cr.third), null)
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
