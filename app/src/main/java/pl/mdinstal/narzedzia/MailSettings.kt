package pl.mdinstal.narzedzia

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ustawienia poczty: podpis i powiadomienia o nowych wiadomościach. */
@Composable
fun MailSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var sig by remember { mutableStateOf(Prefs.mailSignature(ctx)) }
    var notify by remember { mutableStateOf(Prefs.mailNotify(ctx)) }
    var status by remember { mutableStateOf("") }
    val trusted = remember { mutableStateListOf<String>().apply { addAll(Prefs.trustedSenders(ctx)) } }

    fun enable() {
        Prefs.setMailNotify(ctx, true)
        Prefs.setMailHistoryId(ctx, "") // pierwszy przebieg ustawia punkt odniesienia, bez powiadamiania o starych mailach
        MailNotify.schedule(ctx)
        notify = true
        status = "Włączone. Sprawdzam skrzynkę co ok. 15 minut."
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enable() else status = "Bez zgody na powiadomienia system nie pokaże nowych wiadomości."
    }

    AppScaffold("Poczta", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val acc = Prefs.mailAccount(ctx)
            SubText(if (acc.isBlank()) "Konto: jeszcze niepołączone (otwórz Pocztę z ekranu głównego)." else "Konto: $acc")

            SectionTitle("Powiadomienia")
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nowe wiadomości", fontWeight = FontWeight.Bold, color = cs.onSurface)
                        SubText("Aplikacja sprawdza skrzynkę w tle co ok. 15 minut. System może to opóźnić przy oszczędzaniu baterii.")
                    }
                    Switch(
                        checked = notify,
                        onCheckedChange = { on ->
                            if (!on) {
                                Prefs.setMailNotify(ctx, false)
                                MailNotify.cancel(ctx)
                                notify = false
                                status = "Wyłączone."
                            } else if (Prefs.mailAccount(ctx).isBlank()) {
                                status = "Najpierw otwórz Pocztę i połącz konto Google."
                            } else if (Build.VERSION.SDK_INT >= 33 && !canNotifyMail(ctx)) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                enable()
                            }
                        }
                    )
                }
            }

            SectionTitle("Zaufani nadawcy")
            SubText("Obrazy w wiadomościach od tych adresów ładują się od razu. Nadawcę dodasz przyciskiem „Ufaj nadawcy” pod treścią wiadomości.")
            if (trusted.isEmpty()) {
                SubText("Lista jest pusta.")
            } else {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        trusted.toList().forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a, modifier = Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                                TextButton(onClick = {
                                    trusted.remove(a)
                                    Prefs.setTrustedSenders(ctx, trusted.toList())
                                }) { Text("Usuń") }
                            }
                        }
                    }
                }
            }

            SectionTitle("Podpis")
            SubText("Dodawany na końcu nowych wiadomości i odpowiedzi, przed cytatem. W treści możesz go dowolnie zmienić lub usunąć.")
            OutlinedTextField(
                value = sig,
                onValueChange = { sig = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
            )
            Button(
                onClick = { Prefs.setMailSignature(ctx, sig.trim()); status = "Podpis zapisany ✓" },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz podpis") }
            if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
    }
}
