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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

class MailContact(val email: String, val name: String, val count: Int)

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
    contacts: List<MailContact>,
    onSend: suspend (ComposeData) -> Unit,
    onSaveDraft: suspend (ComposeData) -> Unit,
    onClose: (sent: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(init.to) }
    var cc by remember { mutableStateOf(init.cc) }
    var subject by remember { mutableStateOf(init.subject) }
    // Kursor na początku, żeby od razu pisać nad podpisem i cytatem.
    var bodyTf by remember { mutableStateOf(TextFieldValue(init.body, TextRange(0))) }
    val body = bodyTf.text
    var showCc by remember { mutableStateOf(init.cc.isNotBlank()) }
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

    val cs = MaterialTheme.colorScheme
    AppScaffold(
        if (init.threadId != null) "Odpowiedź" else "Nowa wiadomość",
        { tryClose() },
        actions = {
            Button(
                enabled = !busy,
                onClick = { send() },
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Wysyłanie…" else "Wyślij", fontWeight = FontWeight.Bold)
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                RecipientRow("Do", to, { to = it }, contacts) {
                    if (!showCc) {
                        Text(
                            "DW",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showCc = true }.padding(horizontal = 6.dp, vertical = 6.dp)
                        )
                    }
                }
                if (showCc) {
                    ComposeDivider()
                    RecipientRow("DW", cc, { cc = it }, contacts)
                }
                ComposeDivider()
                FieldRow("Temat", subject, { subject = it })
            }
            AppCard(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = bodyTf,
                    onValueChange = { bodyTf = it },
                    textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(14.dp)
                )
            }
            atts.toList().forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(cs.surfaceVariant).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        a.name,
                        modifier = Modifier.weight(1f, fill = false),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(kb(a.size), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { atts.remove(a) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Usuń załącznik", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(cs.primary.copy(alpha = 0.10f))
                    .clickable(enabled = !busy) { picker.launch(arrayOf("*/*")) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Dodaj załącznik", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            }
            if (error.isNotEmpty()) {
                Text(
                    error,
                    fontSize = 13.sp,
                    color = cs.error,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.error.copy(alpha = 0.10f)).padding(12.dp)
                )
            }
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

@Composable
private fun ComposeDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Wiersz karty: etykieta po lewej, pole bez ramki, opcjonalny element po prawej. */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(52.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp)
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Pole adresata z podpowiedziami z historii poczty (wg fragmentu po ostatnim przecinku). */
@Composable
private fun RecipientRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    contacts: List<MailContact>,
    trailing: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val cut = maxOf(value.lastIndexOf(','), value.lastIndexOf(';'))
    val prefix = if (cut >= 0) value.substring(0, cut + 1) + " " else ""
    val token = value.substring(cut + 1).trim().lowercase()
    val used = value.split(",", ";").map { it.trim().lowercase() }.toSet()
    val hits = if (token.length < 2) emptyList() else contacts
        .filter { it.email !in used && (it.email.contains(token) || it.name.lowercase().contains(token)) }
        .sortedByDescending { it.count }
        .take(4)
    Column {
        FieldRow(label, value, onChange, KeyboardType.Email, trailing)
        hits.forEach { h ->
            Row(
                Modifier.fillMaxWidth().clickable { onChange(prefix + h.email + ", ") }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (h.name.ifBlank { h.email }).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        h.name.ifBlank { h.email },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (h.name.isNotBlank()) Text(h.email, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
