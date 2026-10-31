package pl.mdinstal.narzedzia

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen {
    Home, Radio, Network, Speedtest, Wifi, Weather, Iglutech,
    Shortcuts, Settings, IgluSettings, About, Schematic, Protocol
}

data class Station(val name: String, val url: String)
data class Shortcut(val pkg: String, val label: String)

fun todayPl(): String = SimpleDateFormat("dd.MM.yyyy", Locale("pl", "PL")).format(Date())

/** Parametry generatora schematu ZK-TL. */
data class SchematicState(
    val objectName: String = "Budynek Mieszkalny (dz. nr 461/14)",
    val title: String = "SCHEMAT JEDNOKRESKOWY ZK-TL",
    val connector: String = "Złącze kablowo-pomiarowe ZK-TL",
    val connectorNo: String = "06z04240",
    val underground: Boolean = true,
    val tns: Boolean = true,
    val overheadCable: String = "AsXSn 4x16mm²",
    val mcbOn: Boolean = true,
    val mcb: String = "S303 C16A",
    val meter: String = "kWh-3F",
    val isolatorOn: Boolean = true,
    val isolator: String = "FR 303 63A",
    val cable: String = "YKY 4x10mm² (0.4kV)",
    val designer: String = "",
    val date: String = todayPl()
)

/** Stan formularzy trzymany w pamięci, dzięki czemu wyjście z ekranu niczego nie kasuje. */
object FormStore {
    var schematic by mutableStateOf(SchematicState())
    val protocol = mutableStateMapOf<String, String>()
}
