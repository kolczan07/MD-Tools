package pl.mdinstal.narzedzia

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val url: String, val notes: String, val size: Long)

// Usuwa z notatek wydania linię "Full Changelog: ..." dopisywaną automatycznie przez GitHub
private fun cleanNotes(body: String): String =
    body.lines()
        .filterNot { it.contains("Full Changelog", ignoreCase = true) }
        .joinToString("\n")
        .trim()

/** Aktualizacje z GitHub Releases (repo publiczne — na sztywno kolczan07/MD-Tools). */
object Updater {
    private const val REPO = "kolczan07/MD-Tools"

    /**
     * Wybiera plik APK z najnowszego wydania. debug = true: plik z "debug" w nazwie.
     * debug = false: zwykły plik (bez "debug" w nazwie); gdy takiego nie ma, pierwszy APK.
     */
    private fun pick(debug: Boolean): UpdateInfo? {
        if (debug) return pickDebug()
        // Zwykłe: najnowsze wydanie (GitHub pomija w "latest" wydania oznaczone jako pre-release).
        val json = JSONObject(Http.get("https://api.github.com/repos/$REPO/releases/latest"))
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: UpdateInfo? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val info = UpdateInfo(tag, a.getString("browser_download_url"), cleanNotes(json.optString("body")), a.optLong("size"))
            if (!name.contains("debug", ignoreCase = true)) return info
            if (fallback == null) fallback = info
        }
        return fallback
    }

    /** Z listy plików wydania wybiera archiwum ZIP (z APK w środku) albo bezpośrednio APK z "debug" w nazwie. */
    private fun debugAsset(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets") ?: return null
        var apk: UpdateInfo? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val info = UpdateInfo(tag, a.getString("browser_download_url"), cleanNotes(json.optString("body")), a.optLong("size"))
            if (name.endsWith(".zip", ignoreCase = true)) return info
            if (name.endsWith(".apk", ignoreCase = true) && name.contains("debug", ignoreCase = true) && apk == null) apk = info
        }
        return apk
    }

    /**
     * Debug: stałe wydanie z tagiem "debug" (workflow nadpisuje je przy każdym buildzie debug);
     * gdy go brak, najnowsze z ostatnich wydań (także pre-release), które ma archiwum ZIP.
     */
    private fun pickDebug(): UpdateInfo? {
        try {
            debugAsset(JSONObject(Http.get("https://api.github.com/repos/$REPO/releases/tags/debug")))?.let { return it }
        } catch (e: Exception) { }
        val list = org.json.JSONArray(Http.get("https://api.github.com/repos/$REPO/releases?per_page=10"))
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val assets = a.optJSONArray("assets") ?: continue
            val hasZip = (0 until assets.length()).any { assets.getJSONObject(it).optString("name").endsWith(".zip", true) }
            if (hasZip) debugAsset(a)?.let { return it }
        }
        return null
    }

    /** Blokujące: wywołuj na wątku IO. null = brak wydania z plikiem APK. */
    fun latest(): UpdateInfo? = pick(false)

    /** Blokujące: wywołuj na wątku IO. null = wydanie z tagiem "debug" nie ma pliku APK z "debug" w nazwie. */
    fun latestDebug(): UpdateInfo? = pick(true)

    /** Porównuje wersje semantycznie: "3.3" vs "3.2" → true. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } }
        val f = File(dir, if (info.url.substringBefore('?').endsWith(".zip", ignoreCase = true)) "update.zip" else "update.apk")
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
            c.inputStream.use { input ->
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return f
    }

    /**
     * Rozpakowuje archiwum ZIP do katalogu tymczasowego (cache/updates/temp) i zwraca znaleziony plik APK.
     * Chroni przed wyjściem poza katalog docelowy (zip-slip). Wyjątek, gdy w archiwum nie ma pliku APK.
     */
    fun extractApk(zip: File): File {
        val dest = File(zip.parentFile, "temp").apply { deleteRecursively(); mkdirs() }
        val root = dest.canonicalPath + File.separator
        var apk: File? = null
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Nieprawidłowa ścieżka w archiwum")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { zin.copyTo(it) }
                if (apk == null && out.name.endsWith(".apk", ignoreCase = true)) apk = out
            }
        }
        zip.delete()
        return apk ?: throw IOException("W archiwum nie ma pliku APK")
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Zwraca false, gdy trzeba najpierw zezwolić na instalację z tej aplikacji. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!canInstall(ctx)) {
            openInstallPermission(ctx)
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

@Composable
fun UpdateDialog(info: UpdateInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(-1) }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (progress < 0) onClose() },
        title = { Text("Dostępna wersja ${info.version}") },
        text = {
            Column {
                if (info.notes.isNotBlank()) Text(info.notes.take(600), modifier = Modifier.padding(bottom = 8.dp))
                if (progress >= 0) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Pobieranie… $progress%", modifier = Modifier.padding(top = 6.dp))
                }
                if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress < 0,
                onClick = {
                    if (!Updater.canInstall(ctx)) {
                        message = "Zezwól tej aplikacji na instalowanie aktualizacji, wróć i kliknij ponownie."
                        Updater.openInstallPermission(ctx)
                        return@TextButton
                    }
                    message = ""
                    progress = 0
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { Updater.download(ctx, info) { p -> progress = p } }
                            if (Updater.install(ctx, f)) onClose()
                            else {
                                progress = -1
                                message = "Zezwól na instalację i kliknij ponownie."
                            }
                        } catch (e: Exception) {
                            progress = -1
                            message = "Nie udało się pobrać aktualizacji."
                        }
                    }
                }
            ) { Text("Pobierz i zainstaluj") }
        },
        dismissButton = { TextButton(enabled = progress < 0, onClick = onClose) { Text("Później") } }
    )
}

/** Karta w Ustawieniach: ręczne sprawdzenie aktualizacji. */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Wersja ${appVersionName(ctx)}") }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }

    AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = {
        status = "Sprawdzam…"
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.latest() }
                if (info != null && Updater.isNewer(info.version, appVersionName(ctx))) {
                    found = info
                    status = "Dostępna wersja ${info.version}"
                } else status = "Masz najnowszą wersję (${appVersionName(ctx)})"
            } catch (e: Exception) {
                status = "Nie udało się sprawdzić (brak sieci albo brak wydań)."
            }
        }
    }) {
        Column(Modifier.padding(16.dp)) {
            Text("Sprawdź aktualizacje", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            SubText(status)
        }
    }
    found?.let { UpdateDialog(it) { found = null } }
}

/** Karta w "O aplikacji": pobiera ZIP (albo APK) debug z wydania, rozpakowuje do katalogu tymczasowego i uruchamia instalację. */
@Composable
fun DebugDownloadCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Najnowszy build debug z GitHuba (ZIP z APK)") }

    AppCard(modifier.fillMaxWidth(), onClick = {
        if (busy) return@AppCard
        if (!Updater.canInstall(ctx)) {
            status = "Zezwól tej aplikacji na instalowanie, wróć i kliknij ponownie."
            Updater.openInstallPermission(ctx)
            return@AppCard
        }
        busy = true
        status = "Szukam pliku debug…"
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.latestDebug() }
                if (info == null) {
                    status = "Nie znaleziono wydania debug z archiwum ZIP ani plikiem APK."
                } else {
                    status = "Pobieranie… 0%"
                    var f = withContext(Dispatchers.IO) {
                        Updater.download(ctx, info) { p -> status = "Pobieranie wersji ${info.version}… $p%" }
                    }
                    if (f.name.endsWith(".zip", ignoreCase = true)) {
                        status = "Rozpakowuję…"
                        f = withContext(Dispatchers.IO) { Updater.extractApk(f) }
                    }
                    status = if (Updater.install(ctx, f)) "Pobrano. Potwierdź instalację w oknie Androida."
                    else "Zezwól na instalację i kliknij ponownie."
                }
            } catch (e: Exception) {
                status = "Nie udało się pobrać (${e.message ?: "brak sieci"})."
            } finally {
                busy = false
            }
        }
    }) {
        Column(Modifier.padding(16.dp)) {
            Text("Pobierz wersję debug", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            SubText(status)
        }
    }
}
