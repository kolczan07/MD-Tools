package pl.mdinstal.narzedzia

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

fun appVersion(ctx: Context): String = try {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    @Suppress("DEPRECATION")
    val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    "${pi.versionName} ($code)"
} catch (e: Exception) {
    "?"
}

fun appVersionName(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
} catch (e: Exception) {
    "0"
}

@Composable
fun SettingsScreen(
    themeMode: String,
    onTheme: (String) -> Unit,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit
) {
    AppScaffold("Ustawienia", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            SectionTitle("Motyw aplikacji")
            listOf(
                Triple("light", "☀️ Jasny", "Zawsze jasny"),
                Triple("dark", "🌙 Ciemny", "Zawsze ciemny"),
                Triple("system", "⚙️ Systemowy", "Zgodnie z ustawieniami telefonu")
            ).forEach { (key, title, desc) ->
                AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { onTheme(key) }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            SubText(desc)
                        }
                        RadioButton(selected = themeMode == key, onClick = { onTheme(key) })
                    }
                }
            }

            SectionTitle("Logowanie")
            NavCard("Ustawienia logowania Iglutech", "Login i hasło do portalu") { onOpen(Screen.IgluSettings) }

            SectionTitle("Aktualizacje")
            UpdateCard()

            SectionTitle("O aplikacji")
            NavCard("O aplikacji", "Wersja, informacje, autor") { onOpen(Screen.About) }
        }
    }
}

@Composable
private fun NavCard(title: String, desc: String, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            SubText(desc)
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    AppScaffold("O aplikacji", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text("MD-INSTAL Narzędzia", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Wersja: ${appVersion(ctx)}",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Generator schematów, protokół uruchomienia pompy ciepła, radio internetowe, " +
                    "narzędzia sieciowe, pogoda i skrót do Iglutech.",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
            SubText("© MD-INSTAL", Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
fun IgluSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    var user by remember { mutableStateOf(Prefs.credsUser(ctx)) }
    var pass by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(Prefs.credsRemember(ctx)) }
    var status by remember { mutableStateOf(if (Prefs.hasCreds(ctx)) "Dane są zapisane. Wpisz nowe, żeby zmienić." else "") }
    var confirmClear by remember { mutableStateOf(false) }

    AppScaffold("Logowanie Iglutech", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            SubText("Login i hasło będą automatycznie wpisywane na stronie logowania Iglutech. Są szyfrowane w pamięci telefonu.")
            LabeledField("Login:", user, { user = it })
            LabeledField("Hasło:", pass, { pass = it }, password = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                Text("Zaznacz „Remember me” przy logowaniu", color = MaterialTheme.colorScheme.onBackground)
            }
            androidx.compose.material3.Button(
                onClick = {
                    if (user.isBlank()) { status = "Wpisz login."; return@Button }
                    if (pass.isEmpty()) { status = "Wpisz hasło."; return@Button }
                    authenticate(activity, "Zapis danych logowania", "Potwierdź tożsamość") { ok ->
                        if (ok) {
                            if (Prefs.saveCreds(ctx, user.trim(), pass, rememberMe)) {
                                pass = ""
                                status = "Zapisano ✓"
                            } else status = "Nie udało się zapisać (brak dostępu do magazynu kluczy)."
                        } else status = "Anulowano."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz dane") }
            androidx.compose.material3.OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Wyczyść dane") }
            if (status.isNotEmpty()) SubText(status)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Usunąć zapisane dane logowania?") },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.clearCreds(ctx)
                    user = ""; pass = ""; rememberMe = false
                    status = "Wyczyszczono"
                    confirmClear = false
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } }
        )
    }
}
