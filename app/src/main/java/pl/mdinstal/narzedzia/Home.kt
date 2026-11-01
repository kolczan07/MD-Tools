package pl.mdinstal.narzedzia

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** target == null oznacza kafelek "Iglutech Service": otwiera listę pomp (ekran spoza stosu, obsługuje go MainActivity). */
private data class Tile(val icon: String, val title: String, val desc: String, val target: Screen?)

private val tools = listOf(
    Tile("📻", "Radio internetowe", "Wyszukiwarka stacji, ulubione, gra w tle", Screen.Radio),
    Tile("📶", "Sieć", "Speed Test, Skaner WiFi", Screen.Network),
    Tile("⛅", "Pogoda", "Prognoza 7-dniowa i godzinowa", Screen.Weather),
    Tile("🔧", "Iglutech Service", "Lista pomp, status Online, wejście do portalu", null),
    Tile("🔲", "Skróty", "Szybki dostęp do ulubionych aplikacji", Screen.Shortcuts)
)

private val docs = listOf(
    Tile("📄", "Generator schematów", "Schemat ZK-TL, eksport PDF", Screen.Schematic),
    Tile("🌡️", "Protokół pompy ciepła", "Tabele do wypełnienia, eksport PDF", Screen.Protocol)
)

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit, onOpenPumps: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("MD-Tools", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground)
                Text("Panel techniczny", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                Text("Narzędzia i serwis", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = { onOpen(Screen.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = cs.primary)
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Narzędzia")
        TileGrid(tools, onOpen, onOpenPumps)

        Spacer(Modifier.height(12.dp))
        SectionTitle("Dokumenty")
        TileGrid(docs, onOpen, onOpenPumps)

        Row(
            Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp).alpha(0.7f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MD-Tools", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = cs.onBackground)
            Box(Modifier.padding(horizontal = 8.dp).width(40.dp).height(2.dp).background(cs.primary))
            Text("TECH", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = cs.primary, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun TileGrid(items: List<Tile>, onOpen: (Screen) -> Unit, onPumps: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    ToolTile(t, Modifier.weight(1f)) {
                        val target = t.target
                        if (target != null) onOpen(target) else onPumps()
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolTile(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard(modifier = modifier.heightIn(min = 150.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Text(t.icon, fontSize = 22.sp) }
            Spacer(Modifier.height(12.dp))
            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(t.desc, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
    }
}
