@file:OptIn(ExperimentalMaterial3Api::class)

package pl.mdinstal.narzedzia

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
private fun PdfActions(jobName: String, landscape: Boolean, make: (android.content.Context) -> File) {
    val ctx = LocalContext.current
    fun safe(block: (File) -> Unit) {
        try { block(make(ctx)) } catch (e: Exception) {
            Toast.makeText(ctx, "Nie udało się utworzyć PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { safe { PdfUtil.print(ctx, it, jobName, landscape) } }, modifier = Modifier.weight(1f)) {
            Text("Drukuj / zapisz PDF")
        }
        OutlinedButton(onClick = { safe { PdfUtil.share(ctx, it) } }, modifier = Modifier.weight(1f)) {
            Text("Udostępnij PDF")
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ============================ GENERATOR SCHEMATÓW ============================

@Composable
fun SchematicScreen(onBack: () -> Unit) {
    val s = FormStore.schematic
    fun set(n: SchematicState) { FormStore.schematic = n }

    AppScaffold("Generator schematów", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = maxOf(maxWidth, 640.dp)
                Box(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Canvas(Modifier.width(w).aspectRatio(SchematicDraw.PW / SchematicDraw.PH).background(Color.White)) {
                        drawIntoCanvas { cv ->
                            val n = cv.nativeCanvas
                            n.save()
                            n.scale(size.width / SchematicDraw.PW, size.height / SchematicDraw.PH)
                            SchematicDraw.drawPage(n, FormStore.schematic)
                            n.restore()
                        }
                    }
                }
            }
            SubText("Podgląd strony A4 poziomo. Przesuń palcem, żeby zobaczyć szczegóły.")

            PdfActions("Schemat", landscape = true) { PdfUtil.schematic(it) }

            SectionTitle("Obiekt i złącze")
            LabeledField("Obiekt / Budynek:", s.objectName, { set(s.copy(objectName = it)) })
            LabeledField("Złącze (opis na schemacie):", s.connector, { set(s.copy(connector = it)) })

            SectionTitle("Zasilanie")
            Text("Typ przyłącza:", color = MaterialTheme.colorScheme.onBackground)
            ChipRow(
                listOf("Ziemne (kabel)", "Napowietrzne (AsXSn)"),
                setOf(if (s.underground) "Ziemne (kabel)" else "Napowietrzne (AsXSn)")
            ) { set(s.copy(underground = it.startsWith("Ziemne"))) }
            if (s.underground) {
                Text("Typ sieci:", color = MaterialTheme.colorScheme.onBackground)
                ChipRow(listOf("TN-S", "TT"), setOf(if (s.tns) "TN-S" else "TT")) { set(s.copy(tns = it == "TN-S")) }
            } else {
                LabeledField("Przewód przyłącza napowietrznego:", s.overheadCable, { set(s.copy(overheadCable = it)) })
            }

            SectionTitle("Aparatura")
            SwitchRow("Zabezpieczenie przedlicznikowe", s.mcbOn) { set(s.copy(mcbOn = it)) }
            if (s.mcbOn) LabeledField("Symbol / wartość MCB:", s.mcb, { set(s.copy(mcb = it)) })
            LabeledField("Licznik energii:", s.meter, { set(s.copy(meter = it)) })
            SwitchRow("Rozłącznik zalicznikowy", s.isolatorOn) { set(s.copy(isolatorOn = it)) }
            if (s.isolatorOn) LabeledField("Symbol rozłącznika zalicznikowego:", s.isolator, { set(s.copy(isolator = it)) })
            LabeledField("Kabel do rozdzielnicy (WLZ):", s.cable, { set(s.copy(cable = it)) })

            SectionTitle("Tabelka rysunkowa")
            LabeledField("Tytuł rysunku:", s.title, { set(s.copy(title = it)) })
            LabeledField("Numer złącza:", s.connectorNo, { set(s.copy(connectorNo = it)) })
            LabeledField("Wykonawca:", s.designer, { set(s.copy(designer = it)) })
            LabeledField("Data:", s.date, { set(s.copy(date = it)) })

            PdfActions("Schemat", landscape = true) { PdfUtil.schematic(it) }
        }
    }
}

// ============================ PROTOKÓŁ POMPY CIEPŁA ============================

@Composable
fun ProtocolScreen(onBack: () -> Unit) {
    val data = FormStore.protocol
    var confirmClear by remember { mutableStateOf(false) }

    AppScaffold(
        "Protokół pompy ciepła", onBack,
        actions = { TextButton(onClick = { confirmClear = true }) { Text("Wyczyść") } }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PdfActions("Protokol_uruchomienia", landscape = false) { PdfUtil.protocol(it) }

            for (sec in ProtocolSpec.sections) {
                SectionTitle(sec.title.uppercase())
                for (f in sec.fields) {
                    when (f) {
                        is PText -> LabeledField(f.label, data[f.id].orEmpty(), { data[f.id] = it })
                        is PYesNo -> Column {
                            Text(f.label, color = MaterialTheme.colorScheme.onBackground)
                            ChipRow(listOf("Tak", "Nie"), setOf(data[f.id].orEmpty())) {
                                data[f.id] = if (data[f.id] == it) "" else it
                            }
                        }
                        is PMulti -> Column {
                            Text(f.label, color = MaterialTheme.colorScheme.onBackground)
                            val cur = data[f.id].orEmpty().split("|").filter { it.isNotEmpty() }.toSet()
                            ChipRow(f.options, cur) { o ->
                                val n = if (o in cur) cur - o else cur + o
                                data[f.id] = f.options.filter { it in n }.joinToString("|")
                            }
                        }
                        is PChoice -> Column {
                            Text(f.label, color = MaterialTheme.colorScheme.onBackground)
                            ChipRow(f.options, setOf(data[f.id].orEmpty())) {
                                data[f.id] = if (data[f.id] == it) "" else it
                            }
                        }
                    }
                }
            }
            SubText(ProtocolSpec.NOTE)

            SectionTitle("PODPISY")
            for (sg in ProtocolSpec.signatures) {
                LabeledField(sg.label, data[sg.id].orEmpty(), { data[sg.id] = it })
            }

            PdfActions("Protokol_uruchomienia", landscape = false) { PdfUtil.protocol(it) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Wyczyścić cały formularz?") },
            confirmButton = {
                TextButton(onClick = { FormStore.protocol.clear(); confirmClear = false }) { Text("Wyczyść") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } }
        )
    }
}
