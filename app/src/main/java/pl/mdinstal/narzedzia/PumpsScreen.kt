@file:OptIn(ExperimentalMaterial3Api::class)

package pl.mdinstal.narzedzia

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val OnlineGreen = Color(0xFF16A34A)

/** Lista pomp pobrana z portalu Iglutech. Dotknięcie wiersza otwiera szczegóły pompy (z nich można przejść do portalu). */
@Composable
fun PumpsScreen(onBack: () -> Unit, onOpenIglutech: () -> Unit, onOpenPump: (String) -> Unit) {
    val ctx = LocalContext.current
    var pumps by remember { mutableStateOf(PumpStore.load(ctx)) }
    var updated by remember { mutableStateOf(PumpStore.updatedAt(ctx)) }
    var sync by remember { mutableStateOf(PumpStore.syncState(ctx)) }
    var diag by remember { mutableStateOf(PumpStore.diag(ctx)) }
    var query by remember { mutableStateOf("") }
    /** 0 = wszystkie, 1 = online, 2 = offline. */
    var statusFilter by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pump?>(null) }

    // Lista jest zasilana z ekranu Iglutech, więc odświeżamy ją na bieżąco.
    LaunchedEffect(Unit) {
        while (true) {
            pumps = PumpStore.load(ctx)
            updated = PumpStore.updatedAt(ctx)
            sync = PumpStore.syncState(ctx)
            diag = PumpStore.diag(ctx)
            delay(2000)
        }
    }

    val filtered = remember(pumps, query, statusFilter) {
        val q = query.trim().lowercase()
        pumps.filter { p ->
            (statusFilter == 0 || (statusFilter == 1 && p.online) || (statusFilter == 2 && !p.online)) &&
                (q.isEmpty() || p.serial.lowercase().contains(q) || p.model.lowercase().contains(q) ||
                    p.email.lowercase().contains(q))
        }
    }
    val onlineCount = pumps.count { it.online }
    val offlineCount = pumps.size - onlineCount

    val sel = selected
    if (sel != null) {
        // Cofnięcie wraca do listy, nie zamyka całego ekranu.
        BackHandler { selected = null }
        PumpDetailScreen(
            pump = sel,
            onBack = { selected = null },
            onOpenPortal = { onOpenPump(sel.serial) }
        )
    } else AppScaffold("Pompy ciepła", onBack) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(pumps.size.toString(), "pomp", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                StatTile(onlineCount.toString(), "online", OnlineGreen, Modifier.weight(1f))
                StatTile(offlineCount.toString(), "offline", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            }

            val syncText = when {
                sync.first == "running" && System.currentTimeMillis() - sync.second < 60_000 ->
                    "aktualizacja przy starcie trwa…"
                sync.first == "ok" -> "aktualizacja przy starcie zakończona"
                sync.first == "login" -> "aktualizacja wymaga zalogowania, otwórz Iglutech Service"
                sync.first == "timeout" -> "aktualizacja przy starcie nie udała się"
                else -> null
            }
            val metaParts = listOfNotNull(
                if (updated > 0L) "Ostatni odczyt: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(updated)) else null,
                syncText
            )
            if (metaParts.isNotEmpty()) {
                SubText(metaParts.joinToString(" · "), Modifier.padding(top = 8.dp, start = 2.dp))
            }
            // Skąd pochodzą dane (API albo tabela ze strony) i dlaczego API nie zadziałało. Da się zaznaczyć i skopiować.
            if (diag.isNotBlank()) {
                SelectionContainer {
                    Text(
                        "Źródło danych: $diag",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onOpenIglutech) { Text("Otwórz portal Iglutech") }
                TextButton(enabled = pumps.isNotEmpty(), onClick = { confirmClear = true }) { Text("Wyczyść") }
            }

            if (pumps.isEmpty()) {
                Text(
                    "Brak pomp na liście. Lista pobiera się sama przy każdym uruchomieniu aplikacji, o ile jesteś zalogowany w Iglutech. " +
                        "Jeśli jest pusta, otwórz Iglutech Service, zaloguj się i zostaw na chwilę stronę z listą urządzeń.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Szukaj (numer, model, e-mail)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = statusFilter == 0, onClick = { statusFilter = 0 }, label = { Text("Wszystkie ${pumps.size}") })
                    FilterChip(selected = statusFilter == 1, onClick = { statusFilter = 1 }, label = { Text("Online $onlineCount") })
                    FilterChip(selected = statusFilter == 2, onClick = { statusFilter = 2 }, label = { Text("Offline $offlineCount") })
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.serial }) { p ->
                        PumpRow(p) { selected = p }
                    }
                    if (filtered.isEmpty()) {
                        item { SubText("Nic nie pasuje do filtra.", Modifier.padding(top = 8.dp)) }
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
                    diag = ""
                    confirmClear = false
                }) { Text("Wyczyść") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = color)
            SubText(label)
        }
    }
}

/** Pigułka statusu pompy (online na zielono, reszta szara). Używana też w szczegółach pompy. */
@Composable
internal fun StatusPill(status: String, online: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (online) OnlineGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            if (status.isBlank()) "—" else status,
            color = if (online) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PumpRow(p: Pump, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (p.online) OnlineGreen else MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.serial, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (p.model.isNotBlank()) SubText(p.model)
                }
                StatusPill(p.status, p.online)
            }
            // Zamiast nazwy partnera (u nas zawsze ta sama) pokazujemy adresy e-mail.
            if (p.email.isNotBlank()) {
                Text(
                    p.email,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
