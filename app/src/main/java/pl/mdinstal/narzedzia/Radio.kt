package pl.mdinstal.narzedzia

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/** Odtwarzanie w tle: usługa pierwszoplanowa z powiadomieniem i sterowaniem z ekranu blokady. */
class RadioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}

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
                if (url.startsWith("https://")) {
                    out.add(Station(o.optString("name").trim().ifEmpty { "Stacja" }, url))
                }
            }
            return out
        } catch (e: Exception) {
            // próbujemy kolejny serwer
        }
    }
    return null
}

@Composable
fun RadioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var onlyPl by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<Station>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    val favs = remember { mutableStateListOf<Station>().apply { addAll(Prefs.favorites(ctx)) } }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var nowName by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

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
            override fun onPlayerError(e: PlaybackException) { error = true }
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
        c.setMediaItem(
            MediaItem.Builder()
                .setUri(s.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.name).setArtist("MD-INSTAL Radio").build())
                .build()
        )
        c.prepare()
        c.play()
        nowName = s.name
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

    fun toggleFav(s: Station) {
        val existing = favs.firstOrNull { it.url == s.url }
        if (existing != null) favs.remove(existing) else favs.add(s)
        Prefs.setFavorites(ctx, favs.toList())
    }

    AppScaffold("Radio internetowe", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Szukaj stacji, np. Eska, RMF, Zet") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { search() }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Szukaj") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = onlyPl, onCheckedChange = { onlyPl = it })
                Text("Tylko Polska", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val name = nowName
            if (name != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (if (error) "⚠ Błąd: " else if (playing) "▶ " else "⏸ ") + name,
                        modifier = Modifier.weight(1f).clickable { togglePlay() },
                        color = Color.White, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { togglePlay() }) {
                        Text(if (playing) "❚❚" else "▶", color = Color.White, fontSize = 18.sp)
                    }
                    TextButton(onClick = { stop() }) { Text("STOP", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SectionTitle("Ulubione") }
                if (favs.isEmpty()) item { SubText("Brak. Dotknij ☆ przy stacji, żeby dodać.") }
                items(favs.toList()) { s ->
                    StationRow(s, isFav = true, onPlay = { play(s) }, onFav = { toggleFav(s) })
                }
                item { SectionTitle("Wyniki") }
                if (status.isNotEmpty()) item { SubText(status) }
                items(results) { s ->
                    StationRow(s, isFav = favs.any { it.url == s.url }, onPlay = { play(s) }, onFav = { toggleFav(s) })
                }
            }
        }
    }
}

@Composable
private fun StationRow(s: Station, isFav: Boolean, onPlay: () -> Unit, onFav: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onFav) {
                Text(if (isFav) "★" else "☆", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                s.name,
                modifier = Modifier.weight(1f).clickable { onPlay() }.padding(vertical = 12.dp),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
