package pl.mdinstal.narzedzia

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val YT_RED = Color(0xFFFF0000)

// ─────────────────────────────────────────────────────────────────────
//  DANE: utwór i playlisty (lokalne, osobne na każdym telefonie)
// ─────────────────────────────────────────────────────────────────────

data class Track(val id: String, val title: String, val artist: String, val thumb: String, val durationSec: Long)
data class Playlist(val id: String, val name: String, val tracks: List<Track>)

/** Playlisty trzymane w pamięci telefonu — każdy użytkownik apki (każdy telefon) ma własne. */
object MusicStore {
    private fun sp(c: Context) = c.getSharedPreferences("md_music", Context.MODE_PRIVATE)

    fun playlists(c: Context): List<Playlist> = try {
        val a = JSONArray(sp(c).getString("playlists", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val t = o.optJSONArray("tracks") ?: JSONArray()
            Playlist(
                o.optString("id"),
                o.optString("name"),
                (0 until t.length()).map { j ->
                    val x = t.getJSONObject(j)
                    Track(x.optString("id"), x.optString("title"), x.optString("artist"), x.optString("thumb"), x.optLong("dur"))
                }.filter { it.id.isNotEmpty() }
            )
        }.filter { it.id.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    const val HISTORY_ID = "__history__"
    private const val HISTORY_MAX = 100

    /** Automatyczna playlista „Historia odtwarzania”: najnowsze na górze. */
    fun history(c: Context): List<Track> = try {
        val t = JSONArray(sp(c).getString("history", "[]"))
        (0 until t.length()).map { j ->
            val x = t.getJSONObject(j)
            Track(x.optString("id"), x.optString("title"), x.optString("artist"), x.optString("thumb"), x.optLong("dur"))
        }.filter { it.id.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveHistory(c: Context, l: List<Track>) {
        val t = JSONArray()
        l.forEach { t.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("thumb", it.thumb).put("dur", it.durationSec)) }
        sp(c).edit().putString("history", t.toString()).apply()
    }

    @Synchronized
    fun addHistory(c: Context, t: Track) {
        saveHistory(c, (listOf(t) + history(c).filter { it.id != t.id }).take(HISTORY_MAX))
    }

    @Synchronized
    fun removeFromHistory(c: Context, id: String) { saveHistory(c, history(c).filter { it.id != id }) }

    @Synchronized
    fun clearHistory(c: Context) { saveHistory(c, emptyList()) }

    fun save(c: Context, l: List<Playlist>) {
        val a = JSONArray()
        l.forEach { p ->
            val t = JSONArray()
            p.tracks.forEach {
                t.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("thumb", it.thumb).put("dur", it.durationSec))
            }
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", t))
        }
        sp(c).edit().putString("playlists", a.toString()).apply()
    }
}

// ─────────────────────────────────────────────────────────────────────
//  YOUTUBE MUSIC przez NewPipeExtractor: strumienie audio bez reklam
// ─────────────────────────────────────────────────────────────────────

private class NpDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val url = request.url()
        val data = request.dataToSend()
        val body = if (data != null) data.toRequestBody() else null
        val b = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
        request.headers().forEach { (name, values) ->
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube prosi o weryfikację", url)
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

object YtNp {
    private var inited = false
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    @Synchronized
    private fun init() {
        if (inited) return
        NewPipe.init(NpDownloader(), Localization("pl", "PL"), ContentCountry("PL"))
        inited = true
    }

    private fun videoId(url: String): String? =
        Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    /** Wyszukuje utwory w YouTube Music (bez filmów i reklam). */
    fun search(query: String): List<Track> {
        init()
        val yt = ServiceList.YouTube
        val qh = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        val info = SearchInfo.getInfo(yt, qh)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it ->
            val id = videoId(it.url) ?: return@mapNotNull null
            Track(
                id,
                it.name.orEmpty(),
                it.uploaderName.orEmpty().removeSuffix(" - Topic"),
                it.thumbnails.maxByOrNull { t -> t.height }?.url.orEmpty(),
                it.duration
            )
        }
    }

    /** Adres strumienia audio (m4a, jeśli jest); adresy wygasają po kilku godzinach, więc trzymamy je krótko. */
    fun streamUrl(id: String): String {
        urlCache[id]?.let { if (System.currentTimeMillis() - it.second < 2 * 3600_000L) return it.first }
        init()
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        ex.fetchPage()
        val audio = ex.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val best = audio.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: audio.maxByOrNull { it.averageBitrate }
            ?: throw IOException("Brak strumienia audio")
        val url = best.content
        urlCache[id] = url to System.currentTimeMillis()
        return url
    }

    /** Nagłówek User-Agent zgodny z klientem, dla którego YouTube wystawił adres (inaczej bywa 403). */
    fun userAgentFor(url: String): String = when {
        url.contains("c=ANDROID") -> "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip"
        url.contains("c=IOS") -> "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
        else -> "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}

private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri("ytm://$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle("YouTube Music")
                .setExtras(android.os.Bundle().apply { putLong("dur", durationSec) })
                .also { if (thumb.startsWith("http")) it.setArtworkUri(Uri.parse(thumb)) }
                .build()
        )
        .build()

/** Odtwarza Track z MediaItem utworu z YT Music (null dla radia i innych źródeł). */
fun MediaItem.toYtTrack(): Track? {
    if (localConfiguration?.uri?.scheme != "ytm") return null
    return Track(
        mediaId,
        mediaMetadata.title?.toString().orEmpty(),
        mediaMetadata.artist?.toString().orEmpty(),
        mediaMetadata.artworkUri?.toString().orEmpty(),
        mediaMetadata.extras?.getLong("dur") ?: 0L
    )
}

// ─────────────────────────────────────────────────────────────────────
//  EKRAN GŁÓWNY KAFELKA MUZYKA: Radio + YT Music
// ─────────────────────────────────────────────────────────────────────

@Composable
fun MusicHubScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppScaffold("Muzyka", onBack) { pad ->
        Row(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            AppCard(Modifier.weight(1f).heightIn(min = 150.dp), onClick = { onOpen(Screen.Radio) }) {
                Column(Modifier.padding(16.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) { Text("📻", fontSize = 22.sp) }
                    Spacer(Modifier.height(12.dp))
                    Text("Radio", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("Stacje internetowe, ulubione", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            AppCard(Modifier.weight(1f).heightIn(min = 150.dp), onClick = { onOpen(Screen.YtMusic) }) {
                Column(Modifier.padding(16.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(YT_RED),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("YT Music", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("Wyszukiwarka, własne playlisty, bez reklam", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  EKRAN YT MUSIC
// ─────────────────────────────────────────────────────────────────────

// Kolejność = kolejność zakładek na ekranie (Playlisty pierwsze, Szukaj druga) — jak w radiu
private enum class YtTab { PLAYLISTS, SEARCH }

@Composable
fun YtMusicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val lists = remember { mutableStateListOf<Playlist>().apply { addAll(MusicStore.playlists(ctx)) } }
    // Gdy są jakieś playlisty, to one są widokiem głównym; bez playlist startujemy od wyszukiwarki
    var tab by remember { mutableStateOf(if (lists.isNotEmpty()) YtTab.PLAYLISTS else YtTab.SEARCH) }
    var history by remember { mutableStateOf(MusicStore.history(ctx)) }
    var clearHistoryAsk by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var status by remember { mutableStateOf("Wpisz wykonawcę lub tytuł i wyszukaj.") }
    var searching by remember { mutableStateOf(false) }

    var openList by remember { mutableStateOf<String?>(null) }

    // dialogi
    var addTarget by remember { mutableStateOf<Track?>(null) }
    var newListFor by remember { mutableStateOf<Track?>(null) }
    var newListOpen by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var deleteList by remember { mutableStateOf<Playlist?>(null) }

    // odtwarzacz (ta sama usługa co radio)
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var nowId by remember { mutableStateOf<String?>(null) }
    var nowTitle by remember { mutableStateOf("") }
    var nowArtist by remember { mutableStateOf("") }
    var nowThumb by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun persist() = MusicStore.save(ctx, lists.toList())

    fun addToList(listId: String, t: Track) {
        val i = lists.indexOfFirst { it.id == listId }
        if (i < 0) return
        val p = lists[i]
        if (p.tracks.any { it.id == t.id }) return
        lists[i] = p.copy(tracks = p.tracks + t)
        persist()
    }

    fun createList(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), emptyList())
        lists.add(p)
        persist()
        return p
    }

    fun runSearch() {
        if (query.isBlank() || searching) return
        scope.launch {
            searching = true
            status = "Szukam…"
            try {
                val r = withContext(Dispatchers.IO) { YtNp.search(query.trim()) }
                results = r
                status = if (r.isEmpty()) "Nic nie znaleziono." else ""
            } catch (e: Exception) {
                results = emptyList()
                status = "Błąd wyszukiwania: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                searching = false
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                history = MusicStore.history(ctx)
                nowId = mediaItem?.mediaId
                nowTitle = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
                nowArtist = mediaItem?.mediaMetadata?.artist?.toString().orEmpty()
                nowThumb = mediaItem?.mediaMetadata?.artworkUri?.toString().orEmpty()
            }
            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName + (e.cause?.message?.let { ": $it" } ?: "")
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) error = "" }
        }
        val token = SessionToken(ctx, ComponentName(ctx, RadioService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                // pokazujemy mini-odtwarzacz tylko dla utworów z YT Music (radio ma pusty mediaId bez ytm://)
                val cur = c.currentMediaItem
                if (cur?.localConfiguration?.uri?.scheme == "ytm") {
                    nowId = cur.mediaId
                    nowTitle = cur.mediaMetadata.title?.toString().orEmpty()
                    nowArtist = cur.mediaMetadata.artist?.toString().orEmpty()
                    nowThumb = cur.mediaMetadata.artworkUri?.toString().orEmpty()
                }
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

    fun playQueue(tracks: List<Track>, start: Int) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        error = ""
        val t = tracks[start]
        nowId = t.id; nowTitle = t.title; nowArtist = t.artist; nowThumb = t.thumb
        c.setMediaItems(tracks.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (error.isNotEmpty() || c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun stopPlayback() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        nowId = null
        error = ""
    }

    val currentList: Playlist? =
        if (openList == MusicStore.HISTORY_ID) Playlist(MusicStore.HISTORY_ID, "Historia odtwarzania", history)
        else lists.firstOrNull { it.id == openList }
    val isHistory = currentList?.id == MusicStore.HISTORY_ID

    // „wstecz” z widoku playlisty wraca do listy playlist, nie wychodzi z ekranu
    androidx.activity.compose.BackHandler(enabled = currentList != null) { openList = null }

    AppScaffold(
        if (currentList != null) currentList.name else "YT Music",
        if (currentList != null) ({ openList = null }) else onBack,
        actions = {
            if (currentList != null) {
                IconButton(onClick = { if (isHistory) clearHistoryAsk = true else deleteList = currentList }) {
                    Icon(Icons.Filled.Delete, contentDescription = if (isHistory) "Wyczyść historię" else "Usuń playlistę")
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
            if (currentList == null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    YtTabChip("Playlisty", Icons.Filled.QueueMusic, tab == YtTab.PLAYLISTS) { tab = YtTab.PLAYLISTS }
                    YtTabChip("Szukaj", Icons.Filled.Search, tab == YtTab.SEARCH) { tab = YtTab.SEARCH }
                }
            }

            if (error.isNotEmpty()) {
                Text(
                    error,
                    color = cs.error,
                    fontSize = 12.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            val sheetShape = RoundedCornerShape(16.dp)
            val sheet = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(cs.surface)
                .border(1.dp, cs.outlineVariant, sheetShape)

            when {
                // ── widok jednej playlisty ──
                currentList != null -> {
                    if (currentList.tracks.isEmpty()) {
                        SubText(if (isHistory) "Historia jest pusta. Zagraj coś, a pojawi się tutaj." else "Playlista jest pusta. W zakładce Szukaj dotknij „+” przy utworze, żeby ją uzupełnić.")
                        Spacer(Modifier.weight(1f))
                    } else {
                        Row(
                            Modifier
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(YT_RED)
                                .clickable { playQueue(currentList.tracks, 0) }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Odtwarzaj wszystko", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        LazyColumn(modifier = sheet.weight(1f)) {
                            items(currentList.tracks.size) { i ->
                                val t = currentList.tracks[i]
                                TrackRow(
                                    t,
                                    current = nowId == t.id,
                                    onPlay = { playQueue(currentList.tracks, i) },
                                    actionIcon = Icons.Filled.Delete,
                                    actionDesc = if (isHistory) "Usuń z historii" else "Usuń z playlisty",
                                    onAction = {
                                        if (isHistory) {
                                            MusicStore.removeFromHistory(ctx, t.id)
                                            history = MusicStore.history(ctx)
                                            return@TrackRow
                                        }
                                        val li = lists.indexOfFirst { it.id == currentList.id }
                                        if (li >= 0) {
                                            lists[li] = currentList.copy(tracks = currentList.tracks.filter { it.id != t.id })
                                            persist()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // ── wyszukiwarka ──
                tab == YtTab.SEARCH -> {
                    SearchField(
                        value = query,
                        onChange = { query = it },
                        placeholder = "Szukaj utworów, np. Dżem, Coldplay",
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        onSearch = { runSearch() }
                    )
                    if (status.isNotEmpty()) SubText(status, Modifier.padding(bottom = 6.dp))
                    if (results.isNotEmpty()) {
                        LazyColumn(modifier = sheet.weight(1f)) {
                            items(results.size) { i ->
                                val t = results[i]
                                TrackRow(
                                    t,
                                    current = nowId == t.id,
                                    onPlay = { playQueue(results, i) },
                                    actionIcon = Icons.Filled.Add,
                                    actionDesc = "Dodaj do playlisty",
                                    onAction = { addTarget = t }
                                )
                            }
                        }
                    } else Spacer(Modifier.weight(1f))
                }

                // ── lista playlist ──
                else -> {
                    Row(
                        Modifier
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(cs.primary.copy(alpha = 0.14f))
                            .clickable { newListFor = null; newListName = ""; newListOpen = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nowa playlista", color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    run {
                        LazyColumn(modifier = sheet.weight(1f)) {
                            item {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().clickable { history = MusicStore.history(ctx); openList = MusicStore.HISTORY_ID }.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(cs.primary.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Filled.History, contentDescription = null, tint = cs.primary) }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Historia odtwarzania", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface)
                                            Text(if (history.isEmpty()) "Pusta" else "${history.size} utw.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                                        }
                                    }
                                    Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(cs.outlineVariant))
                                }
                            }
                            items(lists.toList()) { p ->
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().clickable { openList = p.id }.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(YT_RED.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = YT_RED) }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                                            Text(
                                                when (p.tracks.size) { 0 -> "Pusta"; 1 -> "1 utwór"; else -> "${p.tracks.size} utw." },
                                                fontSize = 12.sp,
                                                color = cs.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(cs.outlineVariant))
                                }
                            }
                        }
                    }
                }
            }

            // ── MINI ODTWARZACZ ──
            if (nowId != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surface)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(nowThumb, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nowTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                        Text(
                            if (error.isNotEmpty()) "Błąd odtwarzania" else nowArtist,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (error.isNotEmpty()) cs.error else cs.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Poprzedni", tint = cs.onSurface)
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(YT_RED).clickable { togglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Wstrzymaj" else "Odtwarzaj",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Następny", tint = cs.onSurface)
                    }
                    IconButton(onClick = { stopPlayback() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Zamknij", tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // ── dialog: wybór playlisty dla utworu ──
    addTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { addTarget = null },
            title = { Text("Dodaj do playlisty") },
            text = {
                Column {
                    Text(t.title, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    lists.toList().forEach { p ->
                        Text(
                            p.name + if (p.tracks.any { it.id == t.id }) "  ✓" else "",
                            modifier = Modifier.fillMaxWidth().clickable { addToList(p.id, t); addTarget = null }.padding(vertical = 10.dp),
                            fontSize = 15.sp, color = cs.onSurface
                        )
                    }
                    Text(
                        "+ Nowa playlista",
                        modifier = Modifier.fillMaxWidth().clickable { newListFor = t; newListName = ""; newListOpen = true; addTarget = null }.padding(vertical = 10.dp),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.primary
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { addTarget = null }) { Text("Anuluj") } }
        )
    }

    // ── dialog: nowa playlista ──
    if (newListOpen) {
        AlertDialog(
            onDismissRequest = { newListOpen = false },
            title = { Text("Nowa playlista") },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    singleLine = true,
                    label = { Text("Nazwa") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newListName.isNotBlank(),
                    onClick = {
                        val p = createList(newListName)
                        newListFor?.let { addToList(p.id, it) }
                        newListFor = null
                        newListOpen = false
                        tab = YtTab.PLAYLISTS
                    }
                ) { Text("Utwórz") }
            },
            dismissButton = { TextButton(onClick = { newListOpen = false }) { Text("Anuluj") } }
        )
    }

    // ── dialog: czyszczenie historii ──
    if (clearHistoryAsk) {
        AlertDialog(
            onDismissRequest = { clearHistoryAsk = false },
            title = { Text("Wyczyścić historię?") },
            text = { Text("Lista ostatnio odtwarzanych utworów zostanie usunięta z tego telefonu.") },
            confirmButton = {
                TextButton(onClick = {
                    MusicStore.clearHistory(ctx)
                    history = emptyList()
                    clearHistoryAsk = false
                }) { Text("Wyczyść") }
            },
            dismissButton = { TextButton(onClick = { clearHistoryAsk = false }) { Text("Anuluj") } }
        )
    }

    // ── dialog: usuwanie playlisty ──
    deleteList?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteList = null },
            title = { Text("Usunąć playlistę?") },
            text = { Text("„${p.name}” zostanie usunięta z tego telefonu.") },
            confirmButton = {
                TextButton(onClick = {
                    lists.removeAll { it.id == p.id }
                    persist()
                    deleteList = null
                    openList = null
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { deleteList = null }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun Thumb(url: String, size: Dp) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val bmp by produceState<Bitmap?>(null, url) {
        value = if (url.startsWith("http")) LogoCache.load(ctx, url) else null
    }
    val b = bmp
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(YT_RED.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = YT_RED, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
private fun TrackRow(
    t: Track,
    current: Boolean,
    onPlay: () -> Unit,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionDesc: String,
    onAction: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (current) YT_RED.copy(alpha = 0.10f) else Color.Transparent)
                .clickable { onPlay() }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Thumb(t.thumb, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                val dur = if (t.durationSec > 0) "  ·  %d:%02d".format(t.durationSec / 60, t.durationSec % 60) else ""
                Text(t.artist + dur, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) YT_RED else cs.onSurfaceVariant)
            }
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = actionDesc, tint = cs.onSurfaceVariant)
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 70.dp).height(1.dp).background(cs.outlineVariant))
    }
}

@Composable
private fun YtTabChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) YT_RED.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) YT_RED else cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) YT_RED else cs.onSurfaceVariant
        )
    }
}
