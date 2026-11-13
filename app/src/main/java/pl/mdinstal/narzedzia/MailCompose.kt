package pl.mdinstal.narzedzia

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Początkowa zawartość okna pisania (pusta, odpowiedź albo szkic). */
class ComposeInit(
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val threadId: String? = null,
    val inReplyTo: String = "",
    val references: String = ""
)

class OutAttachment(val uri: Uri, val name: String, val mime: String, val size: Long)

class ComposeData(
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val attachments: List<OutAttachment>,
    val threadId: String?,
    val inReplyTo: String,
    val references: String
)

/** Łączny rozmiar załączników; Gmail przyjmuje wiadomości do ok. 25 MB, a my jeszcze kodujemy je dwukrotnie. */
const val MAIL_ATT_LIMIT = 12L * 1024 * 1024

private const val CRLF = "\r\n"

private fun encWord(s: String): String =
    if (s.all { it.code in 32..126 }) s
    else "=?UTF-8?B?" + Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="

private fun b64Lines(b: ByteArray): String = Base64.encodeToString(b, Base64.CRLF)

/** Buduje wiadomość MIME i zwraca ją w base64url (pole `raw` w Gmail API). Wywoływać poza wątkiem UI. */
fun buildRaw(ctx: Context, d: ComposeData): String {
    val sb = StringBuilder()
    sb.append("MIME-Version: 1.0").append(CRLF)
    sb.append("To: ").append(d.to).append(CRLF)
    if (d.cc.isNotBlank()) sb.append("Cc: ").append(d.cc).append(CRLF)
    sb.append("Subject: ").append(encWord(d.subject)).append(CRLF)
    if (d.inReplyTo.isNotBlank()) sb.append("In-Reply-To: ").append(d.inReplyTo).append(CRLF)
    if (d.references.isNotBlank()) sb.append("References: ").append(d.references).append(CRLF)
    val bodyBytes = d.body.replace("\r\n", "\n").replace("\n", CRLF).toByteArray(Charsets.UTF_8)
    if (d.attachments.isEmpty()) {
        sb.append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
        sb.append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF)
        sb.append(b64Lines(bodyBytes))
    } else {
        val boundary = "----md" + UUID.randomUUID().toString().replace("-", "")
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"").append(CRLF).append(CRLF)
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
        sb.append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF)
        sb.append(b64Lines(bodyBytes))
        for (a in d.attachments) {
            val bytes = ctx.contentResolver.openInputStream(a.uri)?.use { it.readBytes() }
                ?: throw IOException("Nie można odczytać pliku ${a.name}")
            val fname = encWord(a.name).replace("\"", "'")
            sb.append("--").append(boundary).append(CRLF)
            sb.append("Content-Type: ${a.mime.ifBlank { "application/octet-stream" }}; name=\"$fname\"").append(CRLF)
            sb.append("Content-Disposition: attachment; filename=\"$fname\"").append(CRLF)
            sb.append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF)
            sb.append(b64Lines(bytes))
        }
        sb.append("--").append(boundary).append("--").append(CRLF)
    }
    return Base64.encodeToString(sb.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
}

private fun describe(ctx: Context, uri: Uri): OutAttachment {
    var name = "plik"
    var size = 0L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0) name = c.getString(ni) ?: name
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    return OutAttachment(uri, name, ctx.contentResolver.getType(uri) ?: "application/octet-stream", size)
}

private fun kb(b: Long): String = if (b >= 1_048_576) String.format("%.1f MB", b / 1_048_576.0) else "${b / 1024} KB"

@Composable
fun MailComposeScreen(
    init: ComposeInit,
    onSend: suspend (ComposeData) -> Unit,
    onSaveDraft: suspend (ComposeData) -> Unit,
    onClose: (sent: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(init.to) }
    var cc by remember { mutableStateOf(init.cc) }
    var subject by remember { mutableStateOf(init.subject) }
    var body by remember { mutableStateOf(init.body) }
    val atts = remember { mutableStateListOf<OutAttachment>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var askClose by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u ->
            try {
                atts.add(describe(ctx, u))
            } catch (e: Exception) {
                error = "Nie udało się dodać pliku."
            }
        }
    }

    fun data() = ComposeData(to.trim(), cc.trim(), subject.trim(), body, atts.toList(), init.threadId, init.inReplyTo, init.references)
    val dirty = to != init.to || cc != init.cc || subject != init.subject || body != init.body || atts.isNotEmpty()

    fun send() {
        if (busy) return
        val addrs = to.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        if (addrs.isEmpty() || addrs.any { !it.contains("@") }) {
            error = "Wpisz poprawny adres odbiorcy (w polu „Do”)."
            return
        }
        if (atts.sumOf { it.size } > MAIL_ATT_LIMIT) {
            error = "Załączniki są za duże (limit ${MAIL_ATT_LIMIT / 1_048_576} MB łącznie)."
            return
        }
        scope.launch {
            busy = true
            error = ""
            try {
                onSend(data())
                onClose(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Nie wysłano: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun saveDraft() {
        if (busy) return
        scope.launch {
            busy = true
            error = ""
            try {
                onSaveDraft(data())
                onClose(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Nie zapisano szkicu: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun tryClose() {
        if (busy) return
        if (dirty) askClose = true else onClose(false)
    }

    BackHandler { tryClose() }

    AppScaffold(
        if (init.threadId != null) "Odpowiedź" else "Nowa wiadomość",
        { tryClose() },
        actions = { TextButton(enabled = !busy, onClick = { send() }) { Text(if (busy) "Wysyłanie…" else "Wyślij") } }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LabeledField("Do", to, { to = it }, keyboard = KeyboardType.Email)
            LabeledField("DW (kopia)", cc, { cc = it }, keyboard = KeyboardType.Email)
            LabeledField("Temat", subject, { subject = it })
            Column {
                Text("Treść", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(top = 4.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
            }
            atts.toList().forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📎 ${a.name}  (${kb(a.size)})",
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { atts.remove(a) }) { Text("Usuń") }
                }
            }
            OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Dodaj załącznik")
            }
            if (error.isNotEmpty()) Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }

    if (askClose) {
        AlertDialog(
            onDismissRequest = { askClose = false },
            title = { Text("Zamknąć wiadomość?") },
            text = { Text("Możesz zapisać ją jako szkic w folderze Robocze albo odrzucić.") },
            confirmButton = { TextButton(onClick = { askClose = false; saveDraft() }) { Text("Zapisz szkic") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askClose = false }) { Text("Wróć") }
                    TextButton(onClick = { askClose = false; onClose(false) }) { Text("Odrzuć") }
                }
            }
        )
    }
}
