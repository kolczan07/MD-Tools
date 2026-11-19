package pl.mdinstal.narzedzia

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun themeLabel(mode: String): String = when (mode) {
    "light" -> "Jasny"
    "dark" -> "Ciemny"
    "oled" -> "Ciemny OLED"
    else -> "Systemowy"
}

/** Wybór motywu z miniaturami ekranu głównego w każdym wariancie. */
@Composable
fun ThemeScreen(themeMode: String, onTheme: (String) -> Unit, onBack: () -> Unit) {
    val options = listOf(
        Triple("light", "Jasny", "Zawsze jasny"),
        Triple("dark", "Ciemny", "Granatowe tło"),
        Triple("oled", "Ciemny OLED", "Czarne tło, oszczędza baterię na ekranach OLED"),
        Triple("system", "Systemowy", "Zgodnie z ustawieniami telefonu")
    )
    AppScaffold("Motyw", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.forEach { (key, title, desc) ->
                val selected = themeMode == key
                AppCard(Modifier.fillMaxWidth(), onClick = { onTheme(key) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(78.dp).height(140.dp).clip(RoundedCornerShape(12.dp))
                                .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                        ) {
                            if (key == "system") {
                                Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1f).fillMaxHeight()) { MiniScreen(LightColors) }
                                    Box(Modifier.weight(1f).fillMaxHeight()) { MiniScreen(DarkColors) }
                                }
                            } else {
                                MiniScreen(when (key) { "light" -> LightColors; "oled" -> OledColors; else -> DarkColors })
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            SubText(desc)
                        }
                        RadioButton(selected = selected, onClick = { onTheme(key) })
                    }
                }
            }
        }
    }
}

/** Uproszczony rysunek ekranu głównego w danej palecie: tło, pasek tytułu i kafelki. */
@Composable
private fun MiniScreen(cs: ColorScheme) {
    Column(Modifier.fillMaxSize().background(cs.background).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth(0.55f).height(7.dp).clip(RoundedCornerShape(3.dp)).background(cs.onBackground))
        repeat(3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(2) {
                    Box(
                        Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(5.dp)).background(cs.surface)
                            .border(BorderStroke(0.5.dp, cs.outlineVariant), RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(width = 14.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(if (it == 0) cs.primary else cs.onSurfaceVariant))
                    }
                }
            }
        }
    }
}
