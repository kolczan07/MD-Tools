package pl.mdinstal.narzedzia

import android.graphics.Color as AColor
import android.webkit.WebView
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private var themeMode by mutableStateOf("system")
    private var openMail by mutableStateOf(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_MAIL, false)) openMail = true
    }

    override fun onStart() {
        super.onStart()
        // Przy każdym wejściu do aplikacji: upewnij się, że usługa działa, i od razu sprawdź pocztę (z limitem raz na minutę).
        if (Prefs.mailNotify(this)) MailFast.ensure(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
        WebView.setWebContentsDebuggingEnabled(true)
        }
        enableEdgeToEdge()
        themeMode = Prefs.theme(this)
        if (intent?.getBooleanExtra(EXTRA_OPEN_MAIL, false) == true) openMail = true
        if (Prefs.mailNotify(this)) MailNotify.schedule(this)
        setContent {
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { dark }
                )
            }
            AppTheme(dark) {
                App(openMail = openMail, onMailOpened = { openMail = false }, themeMode = themeMode, onTheme = { m ->
                    themeMode = m
                    Prefs.setTheme(this@MainActivity, m)
                })
            }
        }
    }
}

@Composable
fun App(openMail: Boolean, onMailOpened: () -> Unit, themeMode: String, onTheme: (String) -> Unit) {
    val stack = remember { mutableStateListOf(Screen.Home) }
    var splash by remember { mutableStateOf(true) }
    val ctx = LocalContext.current
    var autoUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    // Ekran "Pompy ciepła" (lista z Iglutech) jest poza stosem ekranów.
    var pumpsOpen by remember { mutableStateOf(false) }
    // Gdy z listy pomp przechodzimy do Iglutech, po powrocie wracamy na listę.
    var pumpsFromIglu by remember { mutableStateOf(false) }
    // Numer seryjny pompy do otwarcia w Iglutech (po kliknięciu na liście).
    var pendingSerial by remember { mutableStateOf<String?>(null) }
    // Synchronizacja listy pomp przy każdym uruchomieniu aplikacji.
    var syncActive by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1000)
        splash = false
        // Automatyczne sprawdzenie wydań, najwyżej raz na 6 godzin.
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck(ctx) > 6 * 3600 * 1000L) {
            try {
                val info = withContext(Dispatchers.IO) { Updater.latest() }
                Prefs.setLastUpdateCheck(ctx, now)
                if (info != null && Updater.isNewer(info.version, appVersionName(ctx))) autoUpdate = info
            } catch (e: Exception) { }
        }
    }

    fun go(s: Screen) { stack.add(s) }
    // Kliknięcie w powiadomienie o poczcie otwiera ekran Poczty.
    LaunchedEffect(openMail, splash) {
        if (openMail && !splash) {
            if (stack.last() != Screen.Mail) stack.add(Screen.Mail)
            onMailOpened()
        }
    }
    fun back() {
        if (stack.size > 1) {
            val leaving = stack.removeAt(stack.lastIndex)
            if (leaving == Screen.Iglutech) {
                pendingSerial = null
                if (pumpsFromIglu) {
                    pumpsFromIglu = false
                    pumpsOpen = true
                }
            }
        }
    }

    BackHandler(enabled = stack.size > 1) { back() }
    BackHandler(enabled = pumpsOpen) { pumpsOpen = false }

    Box(Modifier.fillMaxSize()) {
        // Niewidoczna synchronizacja listy pomp: WebView pod interfejsem, krótko po starcie.
        if (syncActive && stack.last() != Screen.Iglutech) {
            PumpSyncView(onDone = { syncActive = false })
        }

        if (pumpsOpen) {
            PumpsScreen(
                onBack = { pumpsOpen = false },
                onOpenIglutech = {
                    pumpsOpen = false
                    pumpsFromIglu = true
                    pendingSerial = null
                    go(Screen.Iglutech)
                },
                onOpenPump = { serial ->
                    pumpsOpen = false
                    pumpsFromIglu = true
                    pendingSerial = serial
                    go(Screen.Iglutech)
                }
            )
        } else {
            // Ekrany są rozłączne: wyświetlany jest dokładnie jeden naraz.
            when (stack.last()) {
                Screen.Home -> HomeScreen(onOpen = { go(it) }, onOpenPumps = { pumpsOpen = true })
                Screen.Radio -> RadioScreen(onBack = { back() })
                Screen.Network -> NetworkScreen(onBack = { back() }, onOpen = { go(it) })
                Screen.Speedtest -> SpeedtestScreen(onBack = { back() })
                Screen.Wifi -> WifiScreen(onBack = { back() })
                Screen.Lan -> LanScreen(onBack = { back() })
                Screen.Mail -> MailScreen(onBack = { back() })
                Screen.MailSettings -> MailSettingsScreen(onBack = { back() })
                Screen.Sadp -> SadpScreen(onBack = { back() })
                Screen.Weather -> WeatherScreen(onBack = { back() })
                Screen.Iglutech -> IglutechScreen(onBack = { back() }, openSerial = pendingSerial)
                Screen.Shortcuts -> ShortcutsScreen(onBack = { back() })
                Screen.Settings -> SettingsScreen(themeMode, onTheme, onBack = { back() }, onOpen = { go(it) })
                Screen.IgluSettings -> IgluSettingsScreen(onBack = { back() })
                Screen.Asaj -> AsajScreen(onBack = { back() }, onOpenSettings = { go(Screen.AsajSettings) })
                Screen.UserSettings -> UserSettingsScreen(onBack = { back() }, onOpen = { go(it) })
                Screen.GoogleSettings -> GoogleSettingsScreen(onBack = { back() })
                Screen.AsajSettings -> AsajSettingsScreen(onBack = { back() })
                Screen.CamSettings -> CamSettingsScreen(onBack = { back() })
                Screen.About -> AboutScreen(onBack = { back() })
                Screen.Schematic -> SchematicScreen(onBack = { back() })
                Screen.Protocol -> ProtocolScreen(onBack = { back() })
            }
        }

        autoUpdate?.let { UpdateDialog(it) { autoUpdate = null } }

        AnimatedVisibility(visible = splash, exit = fadeOut()) {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("MD-Tools", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "PANEL TECHNICZNY",
                    color = Color(0xFF94A3B8),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
