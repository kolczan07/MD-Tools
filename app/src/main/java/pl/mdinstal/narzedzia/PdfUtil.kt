package pl.mdinstal.narzedzia

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfUtil {
    fun write(ctx: Context, name: String, w: Int, h: Int, draw: (Canvas) -> Unit): File {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            draw(page.canvas)
            doc.finishPage(page)
            val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
            val f = File(dir, name)
            FileOutputStream(f).use { doc.writeTo(it) }
            return f
        } finally {
            doc.close()
        }
    }

    fun schematic(ctx: Context): File =
        write(ctx, "Schemat.pdf", SchematicDraw.PW.toInt(), SchematicDraw.PH.toInt()) {
            SchematicDraw.drawPage(it, FormStore.schematic)
        }

    fun protocol(ctx: Context): File =
        write(ctx, "Protokol_uruchomienia.pdf", ProtocolDraw.PW.toInt(), ProtocolDraw.PH.toInt()) {
            ProtocolDraw.drawPage(ctx, it, FormStore.protocol.toMap())
        }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Udostępnij PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Systemowe okno drukowania (zawiera też "Zapisz jako PDF"). */
    fun print(ctx: Context, file: File, jobName: String, landscape: Boolean) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val media = if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4
        val attrs = PrintAttributes.Builder().setMediaSize(media).build()
        pm.print(jobName, FileAdapter(file, jobName), attrs)
    }

    private class FileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?
        ) {
            if (cancel?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { i -> FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
