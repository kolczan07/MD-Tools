package pl.mdinstal.narzedzia

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.view.View
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val IGLU_URL = "https://service.iglutech.eu"
private const val TAG = "IgluWV"

/** Skala strony w poziomie (0.70 = 70%). Mniej = więcej mieści się na ekranie, tekst drobniejszy. */
private const val LANDSCAPE_SCALE = 0.70f

/** Skala strony w pionie (1.0 = bez zmian). */
private const val VERTICAL_SCALE = 0.70f

/**
 * Daje WebView większy obszar (rozmiar / scale), a sam widok jest potem pomniejszany o scale
 * (scaleX/scaleY w update). Strona widzi więc większy ekran i mieści się na nim więcej, a całość
 * wypełnia dostępne miejsce. Dotyk jest przeliczany przez system widoków Androida.
 */
private fun Modifier.enlarge(scale: Float): Modifier = layout { measurable, constraints ->
    val w = (constraints.maxWidth / scale).roundToInt()
    val h = (constraints.maxHeight / scale).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
}

/** Strona należy do Iglutech (także inne subdomeny, np. przekierowanie na stronę logowania). */
private fun isIglu(url: String?): Boolean {
    val host = url?.let { Uri.parse(it).host } ?: return false
    return host == "iglutech.eu" || host.endsWith(".iglutech.eu")
}

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

/**
 * Gdy strona jest wyższa niż ekran, pod nią widać tło elementu html (jasne). Ustawiamy mu
 * ciemny kolor zbliżony do tła aplikacji strony, żeby nie było jasnego paska.
 */
private const val FIX_BG_JS = """
(function(){
  function apply(){
    document.documentElement.style.setProperty('background-color','#070b14','important');
  }
  apply(); setTimeout(apply,1500); setTimeout(apply,4000);
})();
"""

/**
 * Tylko poziomo: gdy strona mieści się na ekranie, blokuje przewijanie całej strony,
 * a przewijanie listy (wewnątrz strony) nie przenosi się na stronę po dojechaniu do końca.
 * Gdy strona jest wyraźnie wyższa niż ekran (np. inne podstrony), przewijanie zostaje.
 */
private const val LOCK_SCROLL_JS = """
(function(){
  if(!document.getElementById('igl-lock-style')){
    var st=document.createElement('style');
    st.id='igl-lock-style';
    st.textContent='*{overscroll-behavior-y:contain !important}html,body{overscroll-behavior:none !important}';
    (document.head||document.documentElement).appendChild(st);
  }
  if(window.__iglLock) return;
  window.__iglLock=true;
  function lock(){
    var de=document.documentElement, b=document.body;
    if(!b) return;
    var over=Math.max(de.scrollHeight,b.scrollHeight)-window.innerHeight;
    if(over<=40){
      de.style.setProperty('overflow','hidden','important');
      b.style.setProperty('overflow','hidden','important');
    } else {
      de.style.removeProperty('overflow');
      b.style.removeProperty('overflow');
    }
  }
  lock();
  setInterval(lock,1500);
})();
"""

/**
 * Odczyt listy pomp z tabeli urządzeń (numer seryjny, model, partner, status Online/Offline).
 * Zwraca tekst JSON (tablica) albo pusty tekst, gdy tabeli nie ma. Nic nie zmienia na stronie.
 */
private const val SCRAPE_JS = """
(function(){
  try{
    var out=[], seen={};
    var rows=document.querySelectorAll('tr');
    for(var i=0;i<rows.length;i++){
      var cells=rows[i].querySelectorAll('td');
      if(cells.length<3) continue;
      var t=[];
      for(var j=0;j<cells.length;j++){
        t.push((cells[j].innerText||cells[j].textContent||'').replace(/\s+/g,' ').trim());
      }
      var si=-1;
      for(var k=0;k<t.length;k++){ if(/^\d{6,12}$/.test(t[k])){ si=k; break; } }
      if(si<0) continue;
      var serial=t[si];
      if(seen[serial]) continue;
      seen[serial]=1;
      var status='';
      for(var m=si-1;m>=0;m--){ if(/^(online|offline)$/i.test(t[m])){ status=t[m]; break; } }
      if(!status){ for(var n=0;n<t.length;n++){ if(/^(online|offline)$/i.test(t[n])){ status=t[n]; break; } } }
      var since='';
      for(var q=0;q<t.length;q++){ if(/^\d{4}-\d{2}-\d{2}$/.test(t[q])){ since=t[q]; break; } }
      out.push({serial:serial,model:t[si+1]||'',partner:t[si+2]||'',status:status,since:since});
    }
    return out.length?JSON.stringify(out):'';
  }catch(e){ return ''; }
})();
"""

/** Wykonuje JS i zwraca wynik jako tekst (skrypty zwracają tekst). */
private suspend fun WebView.evalJs(js: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(js) { raw ->
        val s = try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
        if (cont.isActive) cont.resume(s)
    }
}

/**
 * Otwiera pompę o danym numerze seryjnym: znajduje wiersz tabeli z tym numerem i klika go
 * (link w wierszu albo komórkę z numerem). Zwraca "clicked", gdy wiersz znaleziono.
 */
private fun openPumpJs(serial: String): String {
    val sq = JSONObject.quote(serial)
    return """
(function(serial){
  try{
    var rows=document.querySelectorAll('tr');
    for(var i=0;i<rows.length;i++){
      var cells=rows[i].querySelectorAll('td');
      for(var j=0;j<cells.length;j++){
        var tx=(cells[j].innerText||cells[j].textContent||'').replace(/\s+/g,' ').trim();
        if(tx===serial){
          var a=rows[i].querySelector('a[href]');
          if(a){ a.click(); return 'clicked'; }
          cells[j].click();
          return 'clicked';
        }
      }
    }
    return '';
  }catch(e){ return ''; }
})($sq);
""".trimIndent()
}

/**
 * Autouzupełnianie logowania. Czeka (do ~30 s) na widoczne pole hasła, wpisuje login i hasło
 * tak, żeby frameworki (React/Vue/Angular) zauważyły zmianę, a po chwili sprawdza,
 * czy strona nie wyczyściła pól przy ponownym renderowaniu. Wyniki idą do logcat (tag IgluWV, prefiks IGL-AF).
 */
private fun autofillJs(user: String, pass: String, remember: Boolean, autoSubmit: Boolean = true): String {
    val u = JSONObject.quote(user)
    val p = JSONObject.quote(pass)
    return """
(function(u,p,r,s){
  if(window.__iglAF) return;
  window.__iglAF=true;
  var tries=0;
  var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
  function visible(el){
    var rc=el.getBoundingClientRect();
    return rc.width>0&&rc.height>0&&getComputedStyle(el).visibility!=='hidden';
  }
  function setVal(el,v){
    setter.call(el,v);
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
    el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));
  }
  function textLike(el){
    var ty=(el.type||'text').toLowerCase();
    return ty==='text'||ty==='email'||ty==='tel';
  }
  function findPw(){
    var byId=document.getElementById('Input_Password');
    if(byId) return byId;
    var all=document.querySelectorAll('input[type=password]');
    for(var k=0;k<all.length;k++){ if(visible(all[k])) return all[k]; }
    return null;
  }
  function findUser(pw){
    var byId=document.getElementById('Input_Username');
    if(byId) return byId;
    var inputs=Array.prototype.slice.call(document.querySelectorAll('input'));
    var idx=inputs.indexOf(pw);
    for(var i=idx-1;i>=0;i--){
      if(textLike(inputs[i])&&visible(inputs[i])) return inputs[i];
    }
    for(var j=0;j<inputs.length;j++){
      var el=inputs[j];
      var hint=((el.name||'')+' '+(el.id||'')+' '+(el.autocomplete||'')+' '+(el.placeholder||'')).toLowerCase();
      if(textLike(el)&&visible(el)&&/user|login|mail|nick/.test(hint)) return el;
    }
    return null;
  }
  function fillOnce(){
    var pw=findPw();
    if(!pw) return false;
    var us=findUser(pw);
    if(us&&!us.value) setVal(us,u);
    if(!pw.value) setVal(pw,p);
    return us?true:false;
  }
  function tickRemember(){
    var rb=document.getElementById('Input_RememberLogin');
    if(rb){ rb.checked=r; return; }
    if(!r) return;
    Array.prototype.slice.call(document.querySelectorAll('input')).forEach(function(i){
      if((i.type||'').toLowerCase()==='checkbox'){
        var lab=(i.id||'')+' '+(i.name||'')+' '+((i.labels&&i.labels[0])?i.labels[0].textContent:'')+' '+(i.parentElement?i.parentElement.textContent:'');
        if(/remember|zapamiętaj|zapamietaj/i.test(lab)&&!i.checked) i.click();
      }
    });
  }
  function submitForm(pw){
    // Bezpiecznik przed pętlą przy błędnym haśle: nie klikaj ponownie, jeśli ostatnie kliknięcie było < 60 s temu.
    try{
      var last=parseInt(sessionStorage.getItem('iglSubmitted')||'0',10);
      if(Date.now()-last<60000) return;
      sessionStorage.setItem('iglSubmitted',String(Date.now()));
    }catch(e){ return; }
    var f=pw.form;
    if(!f){ console.log('IGL-AF: no form to submit'); return; }
    var btns=f.querySelectorAll('button[type=submit],input[type=submit],button:not([type])');
    var b=null;
    for(var i=0;i<btns.length;i++){
      if((btns[i].value||'').toLowerCase()!=='cancel'){ b=btns[i]; break; }
    }
    if(b) b.click(); else if(f.requestSubmit) f.requestSubmit(); else f.submit();
    console.log('IGL-AF: submitted');
  }
  var t=setInterval(function(){
    tries++;
    var pw=findPw();
    if(pw){
      var gotUser=fillOnce();
      tickRemember();
      console.log('IGL-AF: filled, user field found='+gotUser);
      clearInterval(t);
      setTimeout(function(){
        fillOnce();
        if(s) submitForm(pw);
      },600);
      setTimeout(function(){ fillOnce(); window.__iglAF=false; },4000);
      return;
    }
    if(tries>=60){
      console.log('IGL-AF: no visible password field after 30s');
      clearInterval(t);
      window.__iglAF=false;
    }
  },500);
})($u,$p,$remember,$autoSubmit);
""".trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IglutechScreen(onBack: () -> Unit, openSerial: String? = null) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    var ready by remember { mutableStateOf(false) }
    var creds by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        if (Prefs.hasCreds(ctx)) {
            authenticate(activity, "Logowanie do Iglutech", "Potwierdź tożsamość, aby wpisać zapisane dane") { ok ->
                if (ok) creds = Prefs.creds(ctx)
                Log.d(TAG, "auth ok=$ok, creds loaded=${creds != null}")
                ready = true
            }
        } else {
            Log.d(TAG, "brak zapisanych danych logowania")
            ready = true
        }
    }

    // Co kilka sekund odczytuje listę pomp ze strony, gdy WebView jest na stronie Iglutech
    // i widać tabelę urządzeń. Dane trafiają do PumpStore (ekran "Pompy ciepła").
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            try {
                if (isIglu(w.url)) {
                    w.evaluateJavascript(SCRAPE_JS) { raw ->
                        val json = try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
                        if (!json.isNullOrBlank()) PumpStore.merge(ctx, json)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "odczyt pomp: ${e.message}")
            }
        }
    }

    // Gdy ekran otwarto z listy pomp: po zalogowaniu i pojawieniu się tabeli kliknij wybraną pompę.
    LaunchedEffect(web, openSerial) {
        val w = web ?: return@LaunchedEffect
        val serial = openSerial ?: return@LaunchedEffect
        repeat(60) {
            delay(1000)
            val res = try { w.evalJs(openPumpJs(serial)) } catch (e: Exception) { null }
            if (res == "clicked") return@LaunchedEffect
        }
    }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val body: @Composable (Modifier, Float) -> Unit = { mod, zoom ->
        if (!ready) {
            Box(mod, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = mod,
                factory = { c ->
                    WebView(c).apply {
                        // Jawne MATCH_PARENT: bez tego silnik WebView traktuje wysokość jak wrap_content
                        // i zeruje wysokość układu, przez co 100vh = 0 i strona zapada się do pustego okna.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // Ciemne tło zamiast jasnoszarego, gdy strona nie wypełnia całego okna
                        setBackgroundColor(0xFF070B14.toInt())
                        // Bez sprężystego przeciągania na końcu strony (to ono odsłaniało pasek)
                        overScrollMode = View.OVER_SCROLL_NEVER

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

                        val runAutofill: (WebView) -> Unit = { view ->
                            val cr = creds
                            if (cr == null) {
                                Log.d(TAG, "autofill pominięty: brak danych")
                            } else if (cr.first.isBlank() && cr.second.isBlank()) {
                                Log.d(TAG, "autofill pominięty: puste dane")
                            } else {
                                view.evaluateJavascript(autofillJs(cr.first, cr.second, cr.third), null)
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!isIglu(url)) return
                                view.evaluateJavascript(FIX_VH_JS, null)
                                view.evaluateJavascript(FIX_BG_JS, null)
                                if (landscape) view.evaluateJavascript(LOCK_SCROLL_JS, null)
                                runAutofill(view)
                            }

                            // Zmiany tras w aplikacji jednostronicowej (SPA) nie wywołują onPageFinished,
                            // a formularz logowania może pojawić się właśnie po takiej zmianie.
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                if (!isIglu(url)) return
                                runAutofill(view)
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
                update = { w ->
                    w.pivotX = 0f
                    w.pivotY = 0f
                    w.scaleX = zoom
                    w.scaleY = zoom
                },
                onRelease = { it.destroy() }
            )
        }
    }

    if (landscape) {
        // W poziomie: pełny ekran, bez paska tytułu (cofanie gestem/przyciskiem systemowym)
        body(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).background(Color(0xFF070B14)).enlarge(LANDSCAPE_SCALE),
            LANDSCAPE_SCALE
        )
    } else {
        AppScaffold("Iglutech Service", onBack) { pad -> body(Modifier.fillMaxSize().padding(pad).enlarge(VERTICAL_SCALE), VERTICAL_SCALE) }
    }
}


/**
 * Niewidoczna synchronizacja listy pomp po starcie aplikacji. Ładuje portal w WebView ukrytym
 * pod interfejsem (sesja z ciasteczek, bez wpisywania hasła), odczytuje tabelę urządzeń i kończy.
 * Gdy sesja wygasła i portal pokazuje logowanie, kończy ze stanem "login".
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PumpSyncView(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                CookieManager.getInstance().setAcceptCookie(true)
                setBackgroundColor(0xFF070B14.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                // Ukryta strona nie ma reagować na dotyk.
                setOnTouchListener { _, _ -> true }
                webViewClient = WebViewClient()
                web = this
                loadUrl(IGLU_URL)
            }
        },
        onRelease = { it.destroy() }
    )

    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        PumpStore.setSync(ctx, "running")
        val start = System.currentTimeMillis()
        var state = "timeout"
        while (System.currentTimeMillis() - start < 30_000) {
            delay(2000)
            val url = w.url ?: continue
            if (!isIglu(url)) continue
            if (url.contains("/Account/Login", ignoreCase = true)) {
                state = "login"
                break
            }
            val json = try { w.evalJs(SCRAPE_JS) } catch (e: Exception) { null }
            if (!json.isNullOrBlank() && PumpStore.merge(ctx, json) > 0) {
                state = "ok"
                break
            }
        }
        PumpStore.setSync(ctx, state)
        onDone()
    }
}
