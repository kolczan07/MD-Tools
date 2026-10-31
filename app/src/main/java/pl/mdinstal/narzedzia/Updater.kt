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

/** Aktualizacje z GitHub Releases (repo publiczne — na sztywno kolczan07/MD-Tools). */
object Updater {
    private const val REPO = "kolczan07/MD-Tools"

    /** Blokujące: wywołuj na wątku IO. null = brak wydania z plikiem APK. */
    fun latest(): UpdateInfo? {
        val json = JSONObject(Http.get("https://api.github.com/repos/$REPO/releases/latest"))
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return UpdateInfo(tag, a.getString("browser_download_url"), json.optString("body"), a.optLong("size"))
            }
        }
        return null
    }

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
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "update.apk")
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
