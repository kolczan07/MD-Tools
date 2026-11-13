package pl.mdinstal.narzedzia

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ustawienia użytkownika: wspólne miejsce na dane logowania do portali i konto Google. */
@Composable
fun UserSettingsScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    AppScaffold("Ustawienia użytkownika", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            SectionTitle("Konta i logowanie")
            SettingsNavCard(
                "Portal IGLU",
                if (Prefs.hasCreds(ctx)) "Login i hasło zapisane" else "Login i hasło do portalu Iglutech"
            ) { onOpen(Screen.IgluSettings) }
            SettingsNavCard(
                "Asaj.pl",
                if (Prefs.asajCreds(ctx) != null) "Login i hasło zapisane" else "Login i hasło do asaj.pl (konto firmowe)"
            ) { onOpen(Screen.AsajSettings) }
            SettingsNavCard(
                "Kamery Hikvision",
                if (Prefs.camCreds(ctx) != null) "Login i hasło zapisane" else "Domyślny login i hasło do kamer (SADP)"
            ) { onOpen(Screen.CamSettings) }
            SettingsNavCard(
                "Google",
                if (Prefs.driveLinked(ctx)) "Kopia na Drive: " + Prefs.googleAccount(ctx) else "Kopia przypisań kontrahentów na Google Drive"
            ) { onOpen(Screen.GoogleSettings) }
        }
    }
}

@Composable
private fun SettingsNavCard(title: String, desc: String, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
            SubText(desc)
        }
    }
}

private fun fmtSync(t: Long): String =
    if (t <= 0) "jeszcze nie wykonano" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL")).format(Date(t))

/** Połączenie z kontem Google i kopia przypisań kontrahentów na Drive (ukryty folder aplikacji). */
@Composable
fun GoogleSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(Prefs.googleAccount(ctx)) }
    var lastSync by remember { mutableStateOf(Prefs.lastSync(ctx)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun finish(token: String, email: String?, fallbackEmail: String) {
        scope.launch {
            busy = true
            try {
                val acc = email?.takeIf { it.isNotBlank() } ?: fallbackEmail
                if (acc.isNotBlank()) Prefs.setGoogleAccount(ctx, acc)
                val n = DriveBackup.sync(ctx, token)
                account = Prefs.googleAccount(ctx)
                lastSync = Prefs.lastSync(ctx)
                status = "Gotowe ✓ Przypisań kontrahentów: $n."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Błąd synchronizacji: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    var hintUsed by remember { mutableStateOf("") }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data ?: throw IllegalStateException("brak odpowiedzi z okna zgody"))
            val t = r.accessToken
            if (t != null) finish(t, r.toGoogleSignInAccount()?.email, hintUsed) else status = "Nie otrzymano zgody."
        } catch (e: Exception) {
            status = "Anulowano albo błąd zgody: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun connect(useHint: Boolean) {
        val hint = if (useHint) Prefs.googleHint(ctx) else ""
        hintUsed = hint
        scope.launch {
            busy = true
            status = ""
            try {
                val a = try {
                    DriveBackup.authorize(ctx, hint.ifEmpty { null })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Konto z podpowiedzi może nie być na tym telefonie: pokazujemy wybór konta.
                    if (hint.isNotEmpty()) DriveBackup.authorize(ctx, null) else throw e
                }
                when (a) {
                    is DriveAuth.Token -> finish(a.token, a.email, hint)
                    is DriveAuth.NeedsUi -> consent.launch(IntentSenderRequest.Builder(a.intent).build())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Nie udało się połączyć: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun syncNow() {
        scope.launch {
            busy = true
            status = ""
            try {
                when (val a = DriveBackup.authorize(ctx, Prefs.googleAccount(ctx))) {
                    is DriveAuth.Token -> finish(a.token, a.email, Prefs.googleAccount(ctx))
                    is DriveAuth.NeedsUi -> consent.launch(IntentSenderRequest.Builder(a.intent).build())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Błąd: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    AppScaffold("Google", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SubText(
                "Przypisania kontrahentów do WZ i FS są zapisywane w telefonie, a ich kopia trafia do ukrytego folderu " +
                    "aplikacji na Google Drive. Po wymianie telefonu wystarczy połączyć to samo konto, a dane wrócą. " +
                    "Aplikacja nie widzi innych plików na Drive, a hasła do Google nie zapisuje."
            )
            if (account.isNotEmpty()) {
                Text("Konto: $account", color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
                SubText("Ostatnia synchronizacja: " + fmtSync(lastSync))
                Button(enabled = !busy, onClick = { syncNow() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Chwila…" else "Synchronizuj teraz")
                }
                OutlinedButton(enabled = !busy, onClick = { connect(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Zmień konto")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        Prefs.clearGoogleAccount(ctx)
                        account = ""
                        status = "Rozłączono. Dane zostają na Drive i w telefonie."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Rozłącz") }
            } else {
                val hint = Prefs.googleHint(ctx)
                SubText(
                    if (hint.isNotEmpty()) "Podpowiedziane konto firmowe (z logowania Asaj): $hint"
                    else "Wybierzesz konto w oknie Google. Użyj konta firmowego."
                )
                Button(enabled = !busy, onClick = { connect(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Chwila…" else "Połącz z Google")
                }
                if (hint.isNotEmpty()) {
                    OutlinedButton(enabled = !busy, onClick = { connect(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Wybierz inne konto")
                    }
                }
            }
            if (status.isNotEmpty()) SubText(status)
        }
    }
}
