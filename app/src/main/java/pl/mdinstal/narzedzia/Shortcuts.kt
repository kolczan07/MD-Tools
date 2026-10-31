package pl.mdinstal.narzedzia

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppInfo(val pkg: String, val label: String)

private fun launchableApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.pkg }
        .filter { it.pkg != ctx.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppIcon(pkg: String, size: Int) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        try {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(112, 112).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size.dp))
    else Box(Modifier.size(size.dp))
}

@Composable
fun ShortcutsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember { mutableStateListOf<Shortcut>().apply { addAll(Prefs.shortcuts(ctx)) } }
    var picking by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Shortcut?>(null) }
    var info by remember { mutableStateOf("") }

    AppScaffold("Moje skróty", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            if (list.isEmpty()) {
                SubText("Brak skrótów. Kliknij „Dodaj skrót”, żeby dodać pierwszą aplikację.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(list, key = { it.pkg }) { s ->
                        AppCard(
                            onClick = {
                                val li = ctx.packageManager.getLaunchIntentForPackage(s.pkg)
                                if (li != null) {
                                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(li)
                                } else info = "Nie można uruchomić: ${s.label}"
                            }
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(Modifier.fillMaxWidth()) {
                                    Box(Modifier.align(Alignment.Center)) { AppIcon(s.pkg, 56) }
                                    Text(
                                        "✕",
                                        modifier = Modifier.align(Alignment.TopEnd).clickable { toDelete = s }.padding(4.dp),
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
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
                }
            }
            if (info.isNotEmpty()) SubText(info, Modifier.padding(top = 8.dp))
            Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("+ Dodaj skrót")
            }
        }
    }

    if (picking) {
        var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { launchableApps(ctx) } }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Wybierz aplikację") },
            text = {
                val a = apps
                if (a == null) Text("Ładowanie…")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(a, key = { it.pkg }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (list.none { it.pkg == app.pkg }) {
                                    list.add(Shortcut(app.pkg, app.label))
                                    Prefs.setShortcuts(ctx, list.toList())
                                } else info = "Ta aplikacja jest już dodana."
                                picking = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app.pkg, 36)
                            Text(app.label, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Zamknij") } }
        )
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Usunąć skrót „${s.label}”?") },
            confirmButton = {
                TextButton(onClick = {
                    list.remove(s)
                    Prefs.setShortcuts(ctx, list.toList())
                    toDelete = null
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anuluj") } }
        )
    }
}
