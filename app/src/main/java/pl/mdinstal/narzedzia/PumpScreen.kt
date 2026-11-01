package pl.mdinstal.narzedzia

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OnlineGreen = Color(0xFF16A34A)

/** Lista pomp pobrana z portalu Iglutech. Dotknięcie wiersza kopiuje numer seryjny. */
@Composable
fun PumpsScreen(onBack: () -> Unit, onOpenIglutech: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pumps by remember { mutableStateOf(PumpStore.load(ctx)) }
    var updated by remember { mutableStateOf(PumpStore.updatedAt(ctx)) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    // Lista jest zasilana z ekranu Iglutech, więc odświeżamy ją na bieżąco.
    LaunchedEffect(Unit) {
        while (true) {
            pumps = PumpStore.load(ctx)
            updated = PumpStore.updatedAt(ctx)
            delay(2000)
        }
    }

    val filtered = remember(pumps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) pumps
        else pumps.filter {
            it.serial.contains(q) || it.model.lowercase().contains(q) || it.partner.lowercase().contains(q)
        }
    }
    val onlineCount = pumps.count { it.online }

    AppScaffold("Pompy ciepła", onBack) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                if (pumps.isEmpty()) "Brak pomp na liście" else "Pomp: ${pumps.size}, online: $onlineCount",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            if (updated > 0L) {
                Text(
                    "Ostatni odczyt: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(updated)),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onOpenIglutech) { Text("Odśwież z Iglutech") }
                TextButton(enabled = pumps.isNotEmpty(), onClick = { confirmClear = true }) { Text("Wyczyść") }
            }

            if (pumps.isEmpty()) {
                Text(
                    "Otwórz Iglutech Service, zaloguj się i zostaw na chwilę stronę z listą urządzeń (Devices). " +
                        "Lista pobierze się sama, a potem możesz wrócić tutaj.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Szukaj (numer, model, partner)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.serial }) { p ->
                        PumpRow(p) {
                            clipboard.setText(AnnotatedString(p.serial))
                            Toast.makeText(ctx, "Skopiowano ${p.serial}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Wyczyścić listę pomp?") },
            text = { Text("Lista zostanie pobrana ponownie po wejściu do Iglutech Service.") },
            confirmButton = {
                TextButton(onClick = {
                    PumpStore.clear(ctx)
                    pumps = emptyList()
                    updated = 0L
                    confirmClear = false
                }) { Text("Wyczyść") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun PumpRow(p: Pump, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.serial, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (p.model.isNotBlank()) Text(p.model, fontSize = 15.sp)
                if (p.partner.isNotBlank()) {
                    Text(p.partner, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = if (p.online) OnlineGreen else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    if (p.status.isBlank()) "—" else p.status,
                    color = if (p.online) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}
