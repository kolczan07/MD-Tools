package pl.mdinstal.narzedzia

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** target == null oznacza kafelek "Iglutech Service": otwiera listę pomp (ekran spoza stosu, obsługuje go MainActivity). */
private data class Tile(val icon: String, val title: String, val desc: String, val target: Screen?, val logo: Int? = null, val logoWide: Boolean = true)

private val tools = listOf(
    Tile("🎵", "Muzyka", "Radio internetowe i YT Music bez reklam", Screen.Music),
    Tile("✉️", "Poczta", "Gmail i konta IMAP", Screen.Mail),
    Tile("🔧", "Iglutech Service", "Lista pomp, status Online, wejście do portalu", null, R.drawable.logo_iglu_tile, logoWide = false),
    Tile("🏬", "Asaj", "Dokumenty WZ (PDF), limit kredytowy", Screen.Asaj, R.drawable.logo_asaj),
    Tile("📶", "Sieć", "Speedtest, Skaner WiFi, Skaner LAN", Screen.Network),
    Tile("⛅", "Pogoda", "Prognoza 7-dniowa i godzinowa", Screen.Weather)
)

private val docs = listOf(
    Tile("📄", "Generator schematów", "Schemat ZK-TL, eksport PDF", Screen.Schematic),
    Tile("🌡️", "Protokół pompy ciepła", "Tabele do wypełnienia, eksport PDF", Screen.Protocol)
)

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit, onOpenPumps: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val homeCtx = LocalContext.current
    // Najpierw ostatnia znana liczba, potem ciche odświeżenie z Gmaila (bez okien zgody).
    var mailUnread by remember { mutableIntStateOf(Prefs.mailUnread(homeCtx)) }
    LaunchedEffect(Unit) {
        val account = Prefs.mailAccount(homeCtx)
        if (account.isBlank()) return@LaunchedEffect
        try {
            if (MailApi.isImap(homeCtx)) {
                if (!Prefs.mailConfigured(homeCtx)) return@LaunchedEffect
                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImapApi.inboxUnread(homeCtx) }
                Prefs.setMailUnread(homeCtx, n)
                mailUnread = n
                return@LaunchedEffect
            }
            val auth = GmailApi.authorize(homeCtx, account)
            if (auth is DriveAuth.Token) {
                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GmailApi.inboxUnread(auth.token) }
                Prefs.setMailUnread(homeCtx, n)
                mailUnread = n
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
    }
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
                Text(
                    "Panel techniczny",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onBackground
                )
                Text("Narzędzia i serwis", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
            // Kafelek Ustawienia: zębatka nad napisem, mniejszy niż kafelki narzędzi.
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surface)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { onOpen(Screen.Settings) }
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = cs.primary, modifier = Modifier.size(26.dp))
                Text("Ustawienia", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Narzędzia")
        TileGrid(tools, onOpen, onOpenPumps, mailUnread)

        // Sekcja "Aplikacje" widoczna tylko wtedy, gdy w Ustawieniach → Skróty dodano jakąś aplikację.
        val ctx = LocalContext.current
        val apps = remember { Prefs.shortcuts(ctx).filter { ctx.packageManager.getLaunchIntentForPackage(it.pkg) != null } }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("Aplikacje")
            AppsGrid(apps)
        }

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
private fun AppsGrid(apps: List<Shortcut>) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        apps.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { s ->
                    AppCard(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ctx.packageManager.getLaunchIntentForPackage(s.pkg)?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(it)
                            }
                        }
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AppIcon(s.pkg, 56)
                            Text(
                                s.label,
                                modifier = Modifier.padding(top = 8.dp),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TileGrid(items: List<Tile>, onOpen: (Screen) -> Unit, onPumps: () -> Unit, mailUnread: Int = 0) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    ToolTile(t, Modifier.weight(1f), badge = if (t.target == Screen.Mail) mailUnread else 0) {
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
private fun ToolTile(t: Tile, modifier: Modifier, badge: Int = 0, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard(modifier = modifier.heightIn(min = 150.dp), onClick = onClick) {
        Box(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (t.logo != null && !t.logoWide) {
                // Kwadratowe logo z własnym tłem.
                Image(
                    painter = painterResource(t.logo),
                    contentDescription = t.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                )
            } else if (t.logo != null) {
                // Logo hurtowni na białym tle (jest zrobione pod jasne tło).
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(t.logo),
                        contentDescription = t.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            } else {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Text(t.icon, fontSize = 22.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(t.desc, fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .heightIn(min = 22.dp).widthIn(min = 22.dp)
                    .clip(RoundedCornerShape(11.dp)).background(cs.primary)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (badge > 99) "99+" else badge.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary)
            }
        }
        }
    }
}