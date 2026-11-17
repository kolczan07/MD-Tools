package pl.mdinstal.narzedzia

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Wspólny podgląd PDF (Asaj, załączniki w Poczcie). */
/** Renderer stron PDF (android.graphics.pdf), jedna strona naraz. */
internal class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            val p = renderer.openPage(index)
            try {
                val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                p.close()
            }
        }
    }

    fun close() {
        try {
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
        }
    }
}

@Composable
internal fun PdfViewerScreen(title: String, file: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val pdf = remember(file) { try { PdfDoc(file) } catch (e: Exception) { null } }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    var zoom by remember { mutableStateOf(1f) }
    val screenW = LocalConfiguration.current.screenWidthDp

    AppScaffold(
        title,
        onClose,
        actions = {
            IconButton(onClick = { sharePdf(ctx, file) }) { Icon(Icons.Filled.Share, contentDescription = "Udostępnij") }
        }
    ) { pad ->
        if (pdf == null) {
            Text("Nie udało się otworzyć pliku PDF.", modifier = Modifier.padding(pad).padding(16.dp))
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .pointerInput(Unit) {
                        // Szczypnięcie dwoma palcami = zoom; jeden palec nadal przewija.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size >= 2 && event.changes.any { it.pressed }) {
                                    val z = event.calculateZoom()
                                    if (z != 1f) zoom = (zoom * z).coerceIn(1f, 3f)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .horizontalScroll(rememberScrollState())
            ) {
                LazyColumn(Modifier.width((screenW * zoom).dp)) {
                    items(pdf.pageCount) { i -> PdfPage(pdf, i) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDoc, index: Int) {
    val bmp by produceState<Bitmap?>(null, pdf, index) {
        value = try { pdf.render(index, 1600) } catch (e: Exception) { null }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )
    } else {
        Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

