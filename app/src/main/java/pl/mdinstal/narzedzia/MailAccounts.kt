package pl.mdinstal.narzedzia

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Konto pocztowe z listy. Gmail loguje się przez Google (bez hasła w aplikacji), IMAP przez serwery i hasło. */
data class MailAcct(
    val id: String,
    /** „gmail” albo „imap”. */
    val kind: String,
    val email: String,
    val fromName: String = "",
    val login: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapSec: String = "ssl",
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val smtpSec: String = "ssl",
    val saveSent: Boolean = true
)

/**
 * Lista kont pocztowych i przełączanie aktywnego. Aktywne konto jest przepisane pod stałe klucze w [Prefs],
 * dzięki czemu reszta aplikacji (lista, powiadomienia, kafelek) działa na nim bez zmian.
 */
object MailAccounts {
    /** Konto IMAP edytowane na ekranie [MailImapScreen]; null = nowe. */
    var editId: String? = null

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun parse(json: String): List<MailAcct> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            MailAcct(
                id = o.getString("id"),
                kind = o.optString("kind", "gmail"),
                email = o.optString("email"),
                fromName = o.optString("fromName"),
                login = o.optString("login"),
                imapHost = o.optString("imapHost"),
                imapPort = o.optInt("imapPort", 993),
                imapSec = o.optString("imapSec", "ssl"),
                smtpHost = o.optString("smtpHost"),
                smtpPort = o.optInt("smtpPort", 465),
                smtpSec = o.optString("smtpSec", "ssl"),
                saveSent = o.optBoolean("saveSent", true)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(ctx: Context, list: List<MailAcct>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("kind", it.kind).put("email", it.email).put("fromName", it.fromName)
                    .put("login", it.login).put("imapHost", it.imapHost).put("imapPort", it.imapPort).put("imapSec", it.imapSec)
                    .put("smtpHost", it.smtpHost).put("smtpPort", it.smtpPort).put("smtpSec", it.smtpSec).put("saveSent", it.saveSent)
            )
        }
        Prefs.setMailAccountsJson(ctx, a.toString())
    }

    /** Wszystkie konta. Dotychczasowe pojedyncze konto Gmail (sprzed listy) dopisujemy przy pierwszym odczycie. */
    fun all(ctx: Context): List<MailAcct> {
        val list = parse(Prefs.mailAccountsJson(ctx)).toMutableList()
        val cur = Prefs.mailAccount(ctx)
        if (Prefs.mailKind(ctx) == "gmail" && cur.isNotBlank() && list.none { it.kind == "gmail" && it.email.equals(cur, ignoreCase = true) }) {
            val a = MailAcct(newId(), "gmail", cur)
            list.add(0, a)
            write(ctx, list)
            Prefs.setMailActiveId(ctx, a.id)
        }
        return list
    }

    fun find(ctx: Context, id: String): MailAcct? = all(ctx).firstOrNull { it.id == id }

    fun isActive(ctx: Context, a: MailAcct): Boolean {
        val id = Prefs.mailActiveId(ctx)
        return if (id.isNotEmpty()) a.id == id else a.kind == Prefs.mailKind(ctx) && a.email.equals(Prefs.mailAccount(ctx), ignoreCase = true)
    }

    /** Przełącza aktywne konto. Zwraca false, gdy nie da się odczytać hasła z magazynu kluczy. */
    fun activate(ctx: Context, a: MailAcct): Boolean {
        val pass = if (a.kind == "imap") Prefs.mailSavedPassword(ctx, a.id) else ""
        return Prefs.activateMailAccount(ctx, a, pass)
    }

    /** Konto Google po udanym połączeniu w Poczcie: dopisuje je do listy, jeśli jeszcze go tam nie ma, i oznacza jako aktywne. */
    fun registerGoogle(ctx: Context, email: String) {
        val list = parse(Prefs.mailAccountsJson(ctx)).toMutableList()
        val existing = list.firstOrNull { it.kind == "gmail" && it.email.equals(email, ignoreCase = true) }
        if (existing != null) {
            if (Prefs.mailActiveId(ctx) != existing.id) Prefs.setMailActiveId(ctx, existing.id)
            return
        }
        val a = MailAcct(newId(), "gmail", email)
        list.add(a)
        write(ctx, list)
        Prefs.setMailActiveId(ctx, a.id)
    }

    /** Dodaje konto Google (adres służy jako podpowiedź dla okna wyboru konta) i robi z niego aktywne. */
    fun addGoogle(ctx: Context, email: String): String? {
        val mail = email.trim()
        if (!mail.contains("@")) return "Wpisz poprawny adres konta Google."
        val list = all(ctx).toMutableList()
        val existing = list.firstOrNull { it.kind == "gmail" && it.email.equals(mail, ignoreCase = true) }
        val a = existing ?: MailAcct(newId(), "gmail", mail).also { list.add(it); write(ctx, list) }
        activate(ctx, a)
        return null
    }

    /** Zapisuje konto IMAP (nowe albo zmienione) razem z hasłem; nowe i aktywne edytowane od razu staje się aktywne. */
    fun saveImap(ctx: Context, a: MailAcct, password: String): Boolean {
        if (!Prefs.setMailSavedPassword(ctx, a.id, password)) return false
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == a.id }
        val wasActive = idx >= 0 && isActive(ctx, list[idx])
        if (idx >= 0) list[idx] = a else list.add(a)
        write(ctx, list)
        if (idx < 0 || wasActive) return Prefs.activateMailAccount(ctx, a, password)
        return true
    }

    fun remove(ctx: Context, id: String) {
        val list = all(ctx).toMutableList()
        val gone = list.firstOrNull { it.id == id } ?: return
        val wasActive = isActive(ctx, gone)
        list.remove(gone)
        write(ctx, list)
        Prefs.removeMailSavedPassword(ctx, id)
        if (wasActive) {
            val next = list.firstOrNull()
            if (next != null) {
                activate(ctx, next)
            } else {
                MailNotify.cancel(ctx)
                Prefs.setMailNotify(ctx, false)
                Prefs.clearActiveMail(ctx)
            }
        }
    }

    fun describe(a: MailAcct): String = if (a.kind == "imap") "IMAP · ${a.imapHost}" else "Gmail · logowanie przez Google"
}

/** Lista kont pocztowych: przełączanie aktywnego, edycja, usuwanie i „Dodaj konto”. */
@Composable
fun MailAccountsScreen(onBack: () -> Unit, onOpenImap: () -> Unit, onOpenMail: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var version by remember { mutableIntStateOf(0) }
    val accounts = remember(version) { MailAccounts.all(ctx) }
    var status by remember { mutableStateOf("") }
    var addDialog by remember { mutableStateOf(false) }
    var googleDialog by remember { mutableStateOf(false) }
    var googleEmail by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<MailAcct?>(null) }

    AppScaffold("Konta pocztowe", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SubText("Poczta działa na jednym koncie naraz. Dotknij „Użyj”, żeby przełączyć. Powiadomienia i kafelek na ekranie głównym dotyczą aktywnego konta.")
            if (accounts.isEmpty()) SubText("Brak kont. Dodaj konto Google (Gmail) albo skrzynkę IMAP/SMTP.")
            accounts.forEach { a ->
                val active = MailAccounts.isActive(ctx, a)
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(a.email, fontWeight = FontWeight.Bold, color = cs.onSurface)
                        SubText(MailAccounts.describe(a) + if (active) "  ·  aktywne ✓" else "")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!active) {
                                Button(onClick = {
                                    if (MailAccounts.activate(ctx, a)) {
                                        status = "Aktywne konto: ${a.email}"
                                        version++
                                    } else {
                                        status = "Nie udało się odczytać hasła z magazynu kluczy."
                                    }
                                }) { Text("Użyj") }
                            } else {
                                Button(onClick = onOpenMail) { Text("Otwórz pocztę") }
                            }
                            if (a.kind == "imap") {
                                OutlinedButton(onClick = { MailAccounts.editId = a.id; onOpenImap() }) { Text("Edytuj") }
                            }
                            TextButton(onClick = { toDelete = a }) { Text("Usuń", color = cs.error) }
                        }
                    }
                }
            }
            Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Dodaj konto") }
            if (status.isNotEmpty()) SubText(status)
        }
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Dodaj konto") },
            text = { Text("Konto Google logujesz przez Google (bez hasła w aplikacji). Pozostałe skrzynki dodasz przez IMAP/SMTP.") },
            confirmButton = {
                TextButton(onClick = { addDialog = false; MailAccounts.editId = null; onOpenImap() }) { Text("Poczta IMAP/SMTP") }
            },
            dismissButton = {
                TextButton(onClick = { addDialog = false; googleEmail = ""; googleDialog = true }) { Text("Konto Google") }
            }
        )
    }

    if (googleDialog) {
        AlertDialog(
            onDismissRequest = { googleDialog = false },
            title = { Text("Konto Google") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubText("Adres konta Google (Gmail albo domena firmowa). Zgodę na dostęp zatwierdzisz po otwarciu Poczty.")
                    LabeledField("Adres e-mail:", googleEmail, { googleEmail = it }, keyboard = KeyboardType.Email)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = MailAccounts.addGoogle(ctx, googleEmail)
                    if (err == null) {
                        googleDialog = false
                        status = "Dodano ${googleEmail.trim()}. Otwórz pocztę i zatwierdź dostęp Google."
                        version++
                    } else {
                        status = err
                    }
                }) { Text("Dodaj") }
            },
            dismissButton = { TextButton(onClick = { googleDialog = false }) { Text("Anuluj") } }
        )
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Usunąć konto z listy?") },
            text = { Text("${a.email}: skasowane zostaną tylko dane zapisane w aplikacji. Wiadomości zostają na serwerze.") },
            confirmButton = {
                TextButton(onClick = {
                    MailAccounts.remove(ctx, a.id)
                    toDelete = null
                    status = "Konto usunięte."
                    version++
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anuluj") } }
        )
    }
}

/** Dodawanie i edycja konta IMAP/SMTP. Hasło trafia do szyfrowanego magazynu. */
@Composable
fun MailImapScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val initial = remember { MailAccounts.editId?.let { MailAccounts.find(ctx, it) }?.takeIf { it.kind == "imap" } }
    // Po pierwszym zapisie nowe konto staje się edytowanym, żeby kolejny zapis nie dodawał duplikatu.
    var editing by remember { mutableStateOf(initial) }

    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var fromName by remember { mutableStateOf(initial?.fromName.orEmpty()) }
    var login by remember { mutableStateOf(initial?.let { e -> e.login.takeIf { it != e.email } }.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf(initial?.imapHost.orEmpty()) }
    var imapPort by remember { mutableStateOf((initial?.imapPort ?: 993).toString()) }
    var imapSsl by remember { mutableStateOf((initial?.imapSec ?: "ssl") == "ssl") }
    var smtpHost by remember { mutableStateOf(initial?.smtpHost.orEmpty()) }
    var smtpPort by remember { mutableStateOf((initial?.smtpPort ?: 465).toString()) }
    var smtpSsl by remember { mutableStateOf((initial?.smtpSec ?: "ssl") == "ssl") }
    var saveSent by remember { mutableStateOf(initial?.saveSent ?: true) }
    var status by remember { mutableStateOf(if (initial != null) "Wpisz hasło ponownie tylko jeśli chcesz je zmienić." else "") }
    var busy by remember { mutableStateOf(false) }

    /** Podpowiada serwery z domeny adresu, jeśli pola są jeszcze puste. */
    fun guessHosts() {
        val d = email.substringAfter('@', "").trim().lowercase()
        if (d.isEmpty()) return
        if (imapHost.isBlank()) imapHost = "imap.$d"
        if (smtpHost.isBlank()) smtpHost = "smtp.$d"
    }

    fun save() {
        val cur = editing
        val mail = email.trim()
        if (!mail.contains("@")) { status = "Wpisz poprawny adres e-mail."; return }
        if (imapHost.isBlank() || smtpHost.isBlank()) { status = "Podaj serwery IMAP i SMTP."; return }
        val ip = imapPort.trim().toIntOrNull()
        val sp = smtpPort.trim().toIntOrNull()
        if (ip == null || sp == null) { status = "Porty muszą być liczbami (np. 993 i 465)."; return }
        if (cur == null && MailAccounts.all(ctx).any { it.kind == "imap" && it.email.equals(mail, ignoreCase = true) }) {
            status = "Konto $mail jest już na liście. Edytuj je z listy kont."
            return
        }
        // Puste hasło przy edycji = zostaje dotychczasowe.
        val password = if (pass.isEmpty() && cur != null) Prefs.mailSavedPassword(ctx, cur.id) else pass
        if (password.isEmpty()) { status = "Wpisz hasło do skrzynki."; return }
        val acct = MailAcct(
            id = cur?.id ?: MailAccounts.newId(), kind = "imap", email = mail, fromName = fromName.trim(),
            login = login.trim(), imapHost = imapHost.trim(), imapPort = ip, imapSec = if (imapSsl) "ssl" else "starttls",
            smtpHost = smtpHost.trim(), smtpPort = sp, smtpSec = if (smtpSsl) "ssl" else "starttls", saveSent = saveSent
        )
        if (!MailAccounts.saveImap(ctx, acct, password)) {
            status = "Nie udało się zapisać hasła (brak dostępu do magazynu kluczy)."
            return
        }
        MailAccounts.editId = acct.id
        editing = acct
        pass = ""
        val cfg = ImapCfg(
            acct.email, acct.login.ifBlank { acct.email }, password,
            acct.imapHost, acct.imapPort, acct.imapSec, acct.smtpHost, acct.smtpPort, acct.smtpSec
        )
        scope.launch {
            busy = true
            status = "Sprawdzam połączenie…"
            try {
                withContext(Dispatchers.IO) { ImapApi.verify(cfg) }
                status = "Połączono ✓ Konto zapisane."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Zapisano, ale połączenie się nie udało: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    AppScaffold(if (editing != null) "Edytuj konto IMAP" else "Dodaj konto IMAP", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SubText("Dane znajdziesz w ustawieniach skrzynki u dostawcy poczty (IMAP/SMTP). Hasło jest szyfrowane w pamięci telefonu. Dla Gmaila i niektórych innych serwisów potrzebne jest hasło aplikacji, a nie zwykłe hasło konta.")

            LabeledField("Adres e-mail:", email, { email = it }, keyboard = KeyboardType.Email)
            LabeledField("Nazwa nadawcy (opcjonalnie):", fromName, { fromName = it })
            LabeledField("Login (jeśli inny niż adres e-mail):", login, { login = it })
            LabeledField("Hasło:", pass, { pass = it }, password = true)

            SectionTitle("Odbieranie (IMAP)")
            LabeledField("Serwer IMAP:", imapHost, { imapHost = it }, keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Port:", imapPort, { imapPort = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                Column(Modifier.weight(2f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = imapSsl, onCheckedChange = { on ->
                            imapSsl = on
                            if (on && imapPort == "143") imapPort = "993"
                            if (!on && imapPort == "993") imapPort = "143"
                        })
                        Text(if (imapSsl) "  SSL/TLS (993)" else "  STARTTLS (143)", fontSize = 14.sp, color = cs.onSurface)
                    }
                }
            }

            SectionTitle("Wysyłanie (SMTP)")
            LabeledField("Serwer SMTP:", smtpHost, { smtpHost = it }, keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Port:", smtpPort, { smtpPort = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                Column(Modifier.weight(2f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = smtpSsl, onCheckedChange = { on ->
                            smtpSsl = on
                            if (on && smtpPort == "587") smtpPort = "465"
                            if (!on && smtpPort == "465") smtpPort = "587"
                        })
                        Text(if (smtpSsl) "  SSL/TLS (465)" else "  STARTTLS (587)", fontSize = 14.sp, color = cs.onSurface)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Zapisuj kopię w Wysłanych", fontWeight = FontWeight.Bold, color = cs.onSurface)
                    SubText("Wyłącz, jeśli serwer sam zapisuje wysłane wiadomości (np. Gmail) — inaczej będą podwójne.")
                }
                Switch(checked = saveSent, onCheckedChange = { saveSent = it })
            }

            Button(
                enabled = !busy,
                onClick = { guessHosts(); save() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz i sprawdź połączenie") }
            if (status.isNotEmpty()) SubText(status)
        }
    }
}
