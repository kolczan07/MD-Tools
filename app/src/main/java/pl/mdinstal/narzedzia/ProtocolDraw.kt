package pl.mdinstal.narzedzia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Locale

/**
 * Protokół uruchomienia pompy ciepła, A4 pionowo (595 x 842 pt).
 * Układ jest liczony z góry i automatycznie skalowany tak, żeby ZAWSZE mieścił się na jednej stronie.
 */
object ProtocolDraw {
    const val PW = 595f
    const val PH = 842f
    private const val MARGIN_V = 28f
    private const val MARGIN_H = 40f
    private val VALUE_BG = 0xFFF7F3E8.toInt()
    private val HEADER_BG = 0xFF9CB2C9.toInt()

    private fun tp(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    private fun stroke(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w
        color = Color.BLACK
    }

    private fun layoutOf(text: String, p: TextPaint, w: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, w.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    private fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float) {
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    private fun cell(c: Canvas, x: Float, y: Float, w: Float, h: Float, fillColor: Int?, k: Float) {
        if (fillColor != null) {
            val f = Paint().apply { style = Paint.Style.FILL; color = fillColor }
            c.drawRect(x, y, x + w, y + h, f)
        }
        c.drawRect(x, y, x + w, y + h, stroke(0.6f * k.coerceAtLeast(0.8f)))
    }

    private fun box(c: Canvas, x: Float, yMid: Float, size: Float, checked: Boolean) {
        val top = yMid - size / 2f
        c.drawRect(x, top, x + size, top + size, stroke(0.7f))
        if (checked) {
            val p = stroke(1.1f)
            c.drawLine(x + 1.2f, top + 1.2f, x + size - 1.2f, top + size - 1.2f, p)
            c.drawLine(x + 1.2f, top + size - 1.2f, x + size - 1.2f, top + 1.2f, p)
        }
    }

    /** Rysuje opcje "☐ Tak ☐ Nie" itp. od pozycji x; zwraca koniec. */
    private fun options(c: Canvas, x0: Float, yMid: Float, opts: List<String>, chosen: Set<String>, paint: TextPaint, k: Float): Float {
        var x = x0
        val bs = 7.5f * k
        for (o in opts) {
            box(c, x, yMid, bs, o in chosen)
            x += bs + 3f * k
            val fm = paint.fontMetrics
            c.drawText(o, x, yMid - (fm.ascent + fm.descent) / 2f, paint)
            x += paint.measureText(o) + 10f * k
        }
        return x
    }

    fun drawPage(ctx: Context, c: Canvas, data: Map<String, String>) {
        val logo = try { BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_iglutech) } catch (e: Exception) { null }
        val scratch = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        var k = 1.0f
        while (k > 0.6f && layout(scratch, logo, data, k) > PH - MARGIN_V) k -= 0.02f
        c.drawColor(Color.WHITE)
        layout(c, logo, data, k)
    }

    /** Zwraca dolną krawędź treści (współrzędna y). */
    private fun layout(c: Canvas, logo: Bitmap?, data: Map<String, String>, k: Float): Float {
        val left = MARGIN_H
        val right = PW - MARGIN_H
        val width = right - left
        val lw = width * 0.73f
        val vw = width - lw
        val pad = 3f * k
        val fs = 8.6f * k
        val minRow = 14.5f * k
        val label = tp(fs)
        val bold = tp(fs, true)
        var y = MARGIN_V

        // nagłówek: logo po lewej, dane firmy po prawej
        val logoW = 118f * k
        val logoH = if (logo != null) logoW * logo.height / logo.width else 0f
        val cp = tp(7.2f * k, false, 0xFF777777.toInt())
        val cLine = 7.2f * k * 1.3f
        val cH = cLine * ProtocolSpec.contact.size
        val headerH = maxOf(logoH, cH)
        if (logo != null) {
            c.drawBitmap(logo, null, RectF(left, y, left + logoW, y + logoH), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val cw = ProtocolSpec.contact.maxOf { cp.measureText(it) }
        val cx = right - cw
        var cy = y + headerH - cH
        for (line in ProtocolSpec.contact) {
            c.drawText(line, cx, cy + 7.2f * k, cp)
            cy += cLine
        }
        y += headerH + 4f * k

        // tytuł
        val tpaint = tp(11.5f * k, true).apply { textAlign = Paint.Align.CENTER }
        c.drawText(ProtocolSpec.TITLE, PW / 2f, y + 11.5f * k, tpaint)
        y += 11.5f * k * 1.2f + 6f * k

        val tableTop = y

        for (sec in ProtocolSpec.sections) {
            // nagłówek sekcji
            val hh = maxOf(minRow, fs * 1.2f + 2 * pad)
            cell(c, left, y, width, hh, HEADER_BG, k)
            val hp = tp(fs, true, Color.WHITE)
            val fm = hp.fontMetrics
            c.drawText(sec.title.uppercase(Locale("pl", "PL")), left + 4f * k, y + hh / 2f - (fm.ascent + fm.descent) / 2f, hp)
            y += hh

            for (f in sec.fields) {
                val ll = layoutOf(f.label, label, lw - 2 * pad)
                when (f) {
                    is PText -> {
                        val v = data[f.id].orEmpty()
                        val vl = if (v.isEmpty()) null else layoutOf(v, label, vw - 2 * pad)
                        val h = maxOf(minRow, maxOf(ll.height.toFloat(), vl?.height?.toFloat() ?: 0f) + 2 * pad)
                        cell(c, left, y, lw, h, null, k)
                        cell(c, left + lw, y, vw, h, VALUE_BG, k)
                        drawLayout(c, ll, left + pad, y + (h - ll.height) / 2f)
                        if (vl != null) drawLayout(c, vl, left + lw + pad, y + (h - vl.height) / 2f)
                        y += h
                    }
                    is PYesNo -> {
                        val h = maxOf(minRow, ll.height + 2 * pad)
                        cell(c, left, y, lw, h, null, k)
                        cell(c, left + lw, y, vw, h, VALUE_BG, k)
                        drawLayout(c, ll, left + pad, y + (h - ll.height) / 2f)
                        val sel = data[f.id].orEmpty()
                        options(c, left + lw + pad + 4f * k, y + h / 2f, listOf("Tak", "Nie"), setOf(sel), label, k)
                        y += h
                    }
                    is PMulti, is PChoice -> {
                        val opts = if (f is PMulti) f.options else (f as PChoice).options
                        val chosen: Set<String> = if (f is PMulti) data[f.id].orEmpty().split("|").filter { it.isNotEmpty() }.toSet()
                        else setOf(data[f.id].orEmpty())
                        val h = minRow
                        cell(c, left, y, width, h, null, k)
                        val fm2 = label.fontMetrics
                        val base = y + h / 2f - (fm2.ascent + fm2.descent) / 2f
                        c.drawText(f.label, left + pad, base, label)
                        options(c, left + pad + label.measureText(f.label) + 14f * k, y + h / 2f, opts, chosen, label, k)
                        y += h
                    }
                }
            }
        }

        // nota
        run {
            val np = tp(7.2f * k)
            val h = minRow * 0.9f
            cell(c, left, y, width, h, null, k)
            val fm3 = np.fontMetrics
            c.drawText(ProtocolSpec.NOTE, left + pad, y + h / 2f - (fm3.ascent + fm3.descent) / 2f, np)
            y += h
        }

        // ramka tabeli
        c.drawRect(left, tableTop, right, y, stroke(1.4f))

        // podpisy
        val sigTop = y
        val rowH = 30f * k
        val lblPaint = tp(fs)
        val subPaint = tp(5.6f * k).apply { textAlign = Paint.Align.CENTER }
        val linePaint = stroke(0.7f)
        for ((i, s) in ProtocolSpec.signatures.withIndex()) {
            val lblW = lblPaint.measureText(s.label)
            val x1 = left + 5f * k + lblW + 6f * k
            val x2 = if (s.short) x1 + width * 0.4f else right - 8f * k
            val lineY = y + rowH - 9f * k
            c.drawText(s.label, left + 5f * k, lineY - 2f * k, lblPaint)
            c.drawLine(x1, lineY, x2, lineY, linePaint)
            val v = data[s.id].orEmpty()
            if (v.isNotEmpty()) {
                val vp = tp(fs)
                val avail = x2 - x1 - 4f
                if (vp.measureText(v) > avail) vp.textSize = vp.textSize * avail / vp.measureText(v)
                c.drawText(v, x1 + 2f, lineY - 2f * k, vp)
            }
            if (s.sub != null) c.drawText(s.sub, (x1 + x2) / 2f, y + rowH - 2.5f * k, subPaint)
            y += rowH
            if (i < ProtocolSpec.signatures.size - 1) c.drawLine(left, y, right, y, linePaint)
        }
        val bp = stroke(1.4f)
        c.drawLine(left, sigTop, left, y, bp)
        c.drawLine(right, sigTop, right, y, bp)
        c.drawLine(left, y, right, y, bp)
        return y
    }
}
