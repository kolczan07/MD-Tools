package pl.mdinstal.narzedzia

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import java.net.URLEncoder

class RadioService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastFetch = 0L

    private var dsFactory: androidx.media3.datasource.DataSource.Factory? = null
    private var tail: ExoPlayer? = null
    private var tailId: String? = null
    private var tailStartedAt = 0L   // 0 = ogon tylko przygotowany, >0 = trwa crossfade
    private var tailFadeMs = 0L
    private var crossFailId: String? = null

    private fun releaseTail() {
        try { tail?.release() } catch (e: Exception) { }
        tail = null
        tailId = null
        tailStartedAt = 0L
    }

    private fun crossfadeTick(p: ExoPlayer) {
        val cur = p.currentMediaItem
        if (cur?.localConfiguration?.uri?.scheme != "ytm") {
            releaseTail()
            if (p.volume != 1f) p.volume = 1f
            return
        }
        val t = tail
        // ogon w trakcie crossfade: wyciszamy go, pilnujemy pauzy, sprzątamy po czasie
        if (t != null && tailStartedAt > 0L) {
            val k = ((android.os.SystemClock.elapsedRealtime() - tailStartedAt).toFloat() / tailFadeMs).coerceIn(0f, 1f)
            t.volume = 1f - k
            if (!p.playWhenReady && t.isPlaying) t.pause()
            else if (p.playWhenReady && !t.isPlaying && t.playbackState == Player.STATE_READY && k < 1f) t.play()
            if (k >= 1f || t.playbackState == Player.STATE_ENDED) releaseTail()
        } else if (t != null && cur.mediaId != tailId) {
            releaseTail() // użytkownik przeskoczył utwór, zanim zaczął się crossfade
        }
        if (!p.isPlaying) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = p.currentPosition
        val fade = minOf(4000L, dur / 3).coerceAtLeast(1L)
        val remaining = dur - pos
        val crossing = tail != null && tailStartedAt > 0L

        // wejście początku utworu (przy crossfade w tempie wyciszania ogona)
        val fin = (pos.toFloat() / (if (crossing) tailFadeMs else 1500L)).coerceIn(0f, 1f)

        if (p.hasNextMediaItem() && crossFailId != cur.mediaId) {
            if (tail == null && remaining in (fade + 1)..(fade + 3000)) {
                // przygotowanie: rozgrzewamy adres następnego utworu i buforujemy ogon bieżącego
                val nextId = p.getMediaItemAt(p.nextMediaItemIndex).mediaId
                scope.launch(Dispatchers.IO) { try { YtNp.streamUrl(nextId) } catch (e: Exception) { } }
                val f = dsFactory
                if (f != null) {
                    val ex = ExoPlayer.Builder(this)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(f))
                        .setAudioAttributes(
                            AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(),
                            false // fokus audio zostaje przy głównym odtwarzaczu
                        )
                        .build()
                    ex.setMediaItem(MediaItem.Builder().setUri("ytm://" + cur.mediaId).build())
                    ex.seekTo((dur - fade).coerceAtLeast(0L))
                    ex.volume = 1f
                    ex.playWhenReady = false
                    ex.prepare()
                    tail = ex
                    tailId = cur.mediaId
                    tailStartedAt = 0L
                }
            }
            val tt = tail
            if (tt != null && tailStartedAt == 0L && remaining <= fade) {
                if (tt.playbackState == Player.STATE_READY) {
                    tailFadeMs = remaining.coerceAtLeast(500L)
                    tailStartedAt = android.os.SystemClock.elapsedRealtime()
                    tt.volume = 1f
                    tt.play()
                    p.volume = 0f
                    p.seekToNextMediaItem()
                    return
                } else if (remaining < fade / 2) {
                    crossFailId = cur.mediaId // ogon nie zdążył się zbuforować: zwykłe wyciszenie
                    releaseTail()
                }
            }
        }

        val fout = if (p.hasNextMediaItem() && crossFailId == cur.mediaId) (remaining.toFloat() / fade).coerceIn(0f, 1f) else 1f
        p.volume = minOf(fin, fout)
    }

    /** Wpisuje temperaturę zewnętrzną w pole album bieżącej stacji (trzecia linia w radiu samochodowym). */
    private fun applyAlbum(p: androidx.media3.common.Player) {
        val txt = OutdoorTemp.lastText ?: return
        val cur = p.currentMediaItem ?: return
        if (cur.mediaMetadata.artist?.toString() != "MD-Radio") return  // utwory z YT Music mają własne metadane
        if (cur.mediaMetadata.albumTitle?.toString() == txt) return
        p.replaceMediaItem(
            p.currentMediaItemIndex,
            cur.buildUpon().setMediaMetadata(cur.mediaMetadata.buildUpon().setAlbumTitle(txt).build()).build()
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Wiele stacji (m.in. OpenFM) przekierowuje https -> http lub odrzuca domyślny UA ExoPlayera
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("User-Agent" to YtNp.userAgentFor("")))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        // OpenFM sprawdza Referer/Origin – dokładamy je tylko dla hostów open.fm
        val withHeaders = ResolvingDataSource.Factory(http) { spec ->
            val host = spec.uri.host.orEmpty()
            if (spec.uri.scheme == "ytm") {
                // Utwór z YT Music: adres strumienia wyciągamy dopiero przy odtwarzaniu (wygasa po kilku godzinach)
                val real = try {
                    YtNp.streamUrl(spec.uri.schemeSpecificPart.trimStart('/'))
                } catch (e: java.io.IOException) {
                    throw e
                } catch (e: Exception) {
                    throw java.io.IOException("YT Music: " + (e.message ?: e.javaClass.simpleName), e)
                }
                spec.withUri(android.net.Uri.parse(real))
                    .withAdditionalHeaders(mapOf("User-Agent" to YtNp.userAgentFor(real)))
            } else if (host.endsWith("open.fm") || host.endsWith("reconv.pl")) {
                spec.withAdditionalHeaders(mapOf("Referer" to "https://open.fm/", "Origin" to "https://open.fm"))
            } else spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(withHeaders))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, player).build()
        // Automatyczna playlista „Historia odtwarzania” (YT Music) — zapisujemy tu, żeby działało też w tle
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.toYtTrack()?.let { try { MusicStore.addHistory(applicationContext, it) } catch (e: Exception) { } }
            }
        })

        // Crossfade między utworami z playlisty YT Music (radio bez zmian).
        // Główny odtwarzacz trzyma kolejkę i sesję. Tuż przed końcem utworu drugi, tymczasowy odtwarzacz ("ogon")
        // dogrywa końcówkę poprzedniego utworu i się wycisza, a główny od razu przechodzi do następnego i się podgłaśnia.
        dsFactory = withHeaders
        scope.launch {
            while (isActive) {
                delay(100)
                try { crossfadeTick(player) } catch (e: Exception) { releaseTail() }
            }
        }

        // Co 5 min (tylko gdy gra radio) odświeżamy temperaturę z pompy; metadane aktualizujemy bez przerywania dźwięku.
        scope.launch {
            while (isActive) {
                delay(10_000)
                val p = session?.player ?: continue
                if (p.mediaItemCount == 0 || !p.playWhenReady) continue
                val now = System.currentTimeMillis()
                if (now - lastFetch > 5 * 60_000L) {
                    lastFetch = now
                    val t = try { OutdoorTemp.fetch(applicationContext) } catch (e: Exception) { null }
                    if (t != null) OutdoorTemp.lastText = OutdoorTemp.format(t)
                }
                try { applyAlbum(p) } catch (e: Exception) { }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        releaseTail()
        scope.cancel()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}

// Kolejność = kolejność zakładek na ekranie (Ulubione pierwsze, Szukaj druga)
private enum class RadioTab { FAVORITES, SEARCH }

private val RADIO_HOSTS = listOf(
    "https://de1.api.radio-browser.info",
    "https://nl1.api.radio-browser.info",
    "https://at1.api.radio-browser.info"
)

private fun searchStations(query: String, onlyPl: Boolean): List<Station>? {
    val p = StringBuilder("hidebroken=true&order=clickcount&reverse=true&limit=40")
    if (query.isNotBlank()) p.append("&name=").append(URLEncoder.encode(query.trim(), "UTF-8"))
    if (onlyPl) p.append("&countrycode=PL")
    for (host in RADIO_HOSTS) {
        try {
            val arr = JSONArray(Http.get("$host/json/stations/search?$p"))
            val out = ArrayList<Station>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url_resolved")
                if (url.startsWith("https://") || url.startsWith("http://stream.open.fm/")) {
                    val logo = o.optString("favicon").trim().takeIf { it.startsWith("http") }.orEmpty()
                    out.add(Station(o.optString("name").trim().ifEmpty { "Stacja" }, url, logo))
                }
            }
            return out
        } catch (e: Exception) {
            // próbujemy kolejny serwer
        }
    }
    return null
}


// getradio.reconv.pl odrzuca zapytania spoza przeglądarki (403) i dopiero przekierowuje na m3u8 z tokenem.
// Wykonujemy to przekierowanie w ukrytym WebView (jak Chrome) i przechwytujemy docelowy adres.
@SuppressLint("SetJavaScriptEnabled")
private suspend fun resolveRedirect(ctx: android.content.Context, url: String): String? =
    withTimeoutOrNull(12000) {
        suspendCancellableCoroutine<String?> { cont ->
            val wv = WebView(ctx)
            val main = Handler(Looper.getMainLooper())
            fun finish(result: String?) {
                if (cont.isActive) cont.resume(result)
                main.post { try { wv.stopLoading(); wv.destroy() } catch (e: Exception) { } }
            }
            wv.settings.javaScriptEnabled = true
            wv.setDownloadListener { u, _, _, _, _ -> finish(u) }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    if (u != url) { finish(u); return true }
                    return false
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) finish(null)
                }
            }
            cont.invokeOnCancellation { main.post { try { wv.destroy() } catch (e: Exception) { } } }
            wv.loadUrl(url)
        }
    }

@Composable
fun RadioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var onlyPl by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<Station>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(RadioTab.FAVORITES) }
    val favs = remember { mutableStateListOf<Station>().apply { addAll(Prefs.favorites(ctx)) } }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var nowName by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var errorInfo by remember { mutableStateOf("") }
    var nowUrl by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun search() {
        scope.launch {
            status = "Szukam…"
            val r = withContext(Dispatchers.IO) { searchStations(query, onlyPl) }
            if (r == null) {
                status = "Brak połączenia z bazą stacji."
                results = emptyList()
            } else {
                results = r
                status = if (r.isEmpty()) "Nic nie znaleziono." else ""
            }
        }
    }

    LaunchedEffect(Unit) { search() }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                nowName = mediaItem?.mediaMetadata?.title?.toString()
            }
            override fun onPlayerError(e: PlaybackException) {
                error = true
                errorInfo = e.errorCodeName + (e.cause?.message?.let { ": $it" } ?: "")
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) error = false }
        }
        val token = SessionToken(ctx, ComponentName(ctx, RadioService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                nowName = c.currentMediaItem?.mediaMetadata?.title?.toString()
                playing = c.isPlaying
                c.addListener(listener)
            } catch (e: Exception) {
                status = "Nie udało się połączyć z odtwarzaczem."
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            try { controller?.removeListener(listener) } catch (e: Exception) { }
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    fun play(s: Station) {
        val c = controller ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        error = false
        nowName = s.name
        nowUrl = s.url
        scope.launch {
            val host = try { android.net.Uri.parse(s.url).host.orEmpty() } catch (e: Exception) { "" }
            val real = if (host.endsWith("reconv.pl")) (resolveRedirect(ctx, s.url) ?: s.url) else s.url
            nowUrl = real
            val art: ByteArray? = if (s.logo.isBlank()) null else try {
                kotlinx.coroutines.withTimeoutOrNull(2000) { LogoCache.load(ctx, s.logo) }?.let { b ->
                    val sq = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(sq)
                    cv.drawColor(android.graphics.Color.WHITE)
                    val k = minOf(216f / b.width, 216f / b.height)
                    val w = (b.width * k).toInt().coerceAtLeast(1)
                    val h = (b.height * k).toInt().coerceAtLeast(1)
                    cv.drawBitmap(Bitmap.createScaledBitmap(b, w, h, true), (256 - w) / 2f, (256 - h) / 2f, null)
                    java.io.ByteArrayOutputStream().also { sq.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
            } catch (e: Exception) { null }
            c.setMediaItem(
                MediaItem.Builder()
                    .setUri(real)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s.name)
                            .setArtist("MD-Radio")
                            .setAlbumTitle(OutdoorTemp.lastText ?: "MD-Tools")
                            .also { if (art != null) it.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                            .build()
                    )
                    .build()
            )
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (error || c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun stop() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        nowName = null
        error = false
    }

    // Ulubione trzymają adres z chwili dodania – odświeżamy go z radio-browser po nazwie stacji
    fun refreshFavs() {
        scope.launch {
            status = "Odświeżam ulubione…"
            var updated = 0
            var logos = 0
            val newList = withContext(Dispatchers.IO) {
                favs.toList().map { f ->
                    val found = searchStations(f.name, false)
                        ?.firstOrNull { it.name.equals(f.name, ignoreCase = true) }
                    if (found == null) f
                    else if (found.url != f.url) { updated++; found }
                    else if (f.logo.isEmpty() && found.logo.isNotEmpty()) { logos++; f.copy(logo = found.logo) }
                    else f
                }
            }
            favs.clear()
            favs.addAll(newList)
            Prefs.setFavorites(ctx, newList)
            status = when {
                updated > 0 && logos > 0 -> "Zaktualizowano adresy: $updated, pobrano logo: $logos"
                updated > 0 -> "Zaktualizowano adresy: $updated"
                logos > 0 -> "Pobrano logo: $logos"
                else -> "Adresy są aktualne."
            }
        }
    }

    fun toggleFav(s: Station) {
        val existing = favs.firstOrNull { it.url == s.url }
        if (existing != null) favs.remove(existing) else favs.add(s)
        Prefs.setFavorites(ctx, favs.toList())
    }

    val nowStation = nowName?.let { n -> (favs + results).firstOrNull { it.name == n } ?: Station(n, "") }

    AppScaffold(
        "Radio internetowe",
        onBack,
        actions = {
            if (activeTab == RadioTab.FAVORITES && favs.isNotEmpty()) {
                IconButton(onClick = { refreshFavs() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Odśwież adresy i logo stacji")
                }
            }
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .consumeWindowInsets(pad)
                .imePadding()
                .padding(horizontal = 14.dp)
        ) {
            // ── ZAKŁADKI ──────────────────────────────────────────
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RadioTabChip("Ulubione", Icons.Filled.Star, activeTab == RadioTab.FAVORITES) { activeTab = RadioTab.FAVORITES }
                RadioTabChip("Szukaj", Icons.Filled.Search, activeTab == RadioTab.SEARCH) { activeTab = RadioTab.SEARCH }
            }

            if (error && errorInfo.isNotEmpty()) {
                Text(
                    errorInfo + "\n" + nowUrl,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // ── ZAWARTOŚĆ ZAKŁADEK ────────────────────────────────
            val sheetShape = RoundedCornerShape(16.dp)
            val sheet = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, sheetShape)
            when (activeTab) {
                RadioTab.FAVORITES -> {
                    if (status.isNotEmpty()) SubText(status, Modifier.padding(bottom = 6.dp))
                    if (favs.isEmpty()) {
                        SubText("Brak ulubionych. Dotknij gwiazdki przy stacji w zakładce Szukaj, żeby dodać.")
                        Spacer(Modifier.weight(1f))
                    } else {
                        LazyColumn(modifier = sheet.weight(1f)) {
                            items(favs.toList()) { s ->
                                StationRow(s, isFav = true, current = nowName == s.name, onPlay = { play(s) }, onFav = { toggleFav(s) })
                            }
                        }
                    }
                }

                RadioTab.SEARCH -> {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SearchField(
                            value = query,
                            onChange = { query = it },
                            placeholder = "Szukaj stacji, np. Eska, RMF, Zet",
                            modifier = Modifier.weight(1f),
                            onSearch = { search() }
                        )
                        RadioFilterChip("Tylko Polska", onlyPl) { onlyPl = !onlyPl; search() }
                    }
                    if (status.isNotEmpty()) SubText(status, Modifier.padding(bottom = 6.dp))
                    LazyColumn(modifier = sheet.weight(1f)) {
                        items(results) { s ->
                            StationRow(
                                s,
                                isFav = favs.any { it.url == s.url },
                                current = nowName == s.name,
                                onPlay = { play(s) },
                                onFav = { toggleFav(s) }
                            )
                        }
                    }
                }
            }

            // ── MINI ODTWARZACZ ───────────────────────────────────
            val cur = nowStation
            if (cur != null) {
                val cs = MaterialTheme.colorScheme
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surface)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StationAvatar(cur, 42.dp, active = playing && !error)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                        Text(
                            when {
                                error -> "Błąd odtwarzania"
                                playing -> "Odtwarzanie"
                                else -> "Wstrzymano"
                            },
                            fontSize = 12.sp,
                            color = if (error) cs.error else cs.onSurfaceVariant
                        )
                    }
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(cs.primary).clickable { togglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Wstrzymaj" else "Odtwarzaj",
                            tint = cs.onPrimary
                        )
                    }
                    IconButton(onClick = { stop() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Zatrzymaj", tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val RADIO_AVATAR = listOf(0xFF2563EB, 0xFF0D9488, 0xFFD97706, 0xFFDB2777, 0xFF7C3AED, 0xFF16A34A).map { Color(it) }

/** Logo stacji pobierane z adresu podanego przez radio-browser; trzymane w pamięci i w pamięci podręcznej telefonu. */
internal object LogoCache {
    private val mem = android.util.LruCache<String, Bitmap>(80)
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun load(ctx: android.content.Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        mem.get(url)?.let { return@withContext it }
        if (url in failed) return@withContext null
        try {
            val dir = File(ctx.cacheDir, "radio_logos").apply { mkdirs() }
            val f = File(dir, Integer.toHexString(url.hashCode()) + ".img")
            val bytes = if (f.exists() && f.length() > 0) f.readBytes() else {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("User-Agent", "MD-Tools/3.1")
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    val b = c.inputStream.use { it.readBytes() }
                    if (b.size > 400_000) throw IOException("za duży")
                    f.writeBytes(b)
                    b
                } finally {
                    c.disconnect()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val side = maxOf(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = maxOf(1, side / 160) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp == null) { failed.add(url); null } else { mem.put(url, bmp); bmp }
        } catch (e: Exception) {
            failed.add(url)
            null
        }
    }
}

/** Kółko stacji: logo (jeśli jest), a w zapasie pierwsza litera; podczas grania ikona fal. */
@Composable
private fun StationAvatar(s: Station, size: Dp, active: Boolean) {
    val ctx = LocalContext.current
    val tint = RADIO_AVATAR[(s.name.lowercase().hashCode() and 0x7fffffff) % RADIO_AVATAR.size]
    val bmp by produceState<Bitmap?>(null, s.logo) {
        value = if (s.logo.isNotBlank()) LogoCache.load(ctx, s.logo) else null
    }
    val logo = bmp
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (logo != null) Color.White else tint.copy(alpha = 0.15f))
            .border(1.dp, if (logo != null) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (logo != null) {
            Image(logo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(5.dp))
        } else if (active) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = tint)
        } else {
            Text(s.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?", fontWeight = FontWeight.Bold, color = tint)
        }
    }
}

@Composable
private fun RadioTabChip(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}

@Composable
private fun RadioFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}

@Composable
private fun StationRow(s: Station, isFav: Boolean, current: Boolean, onPlay: () -> Unit, onFav: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (current) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
                .clickable { onPlay() }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StationAvatar(s, 40.dp, active = current)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = cs.onSurface
                )
                if (current) Text("Teraz gra", fontSize = 12.sp, color = cs.primary)
            }
            IconButton(onClick = onFav) {
                Icon(
                    if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFav) "Usuń z ulubionych" else "Dodaj do ulubionych",
                    tint = if (isFav) cs.primary else cs.onSurfaceVariant
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(cs.outlineVariant))
    }
}
