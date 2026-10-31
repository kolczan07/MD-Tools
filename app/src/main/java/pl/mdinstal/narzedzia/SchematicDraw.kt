package pl.mdinstal.narzedzia

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.util.Locale

/**
 * Rysuje stronę A4 poziomo (842 x 595 pt): schemat jednokreskowy ZK-TL + tabelka rysunkowa.
 * Te same współrzędne co w pierwotnym SVG (viewBox 1000 x 500), więc wygląd jest identyczny.
 * Jedna funkcja służy zarówno do podglądu na ekranie, jak i do PDF.
 */
object SchematicDraw {
    const val PW = 842f
    const val PH = 595f
    private const val MM = 72f / 25.4f

    private fun stroke(sw: Number, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sw.toFloat()
        this.color = color
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private fun line(c: Canvas, x1: Number, y1: Number, x2: Number, y2: Number, sw: Number, dash: FloatArray? = null) {
        val p = stroke(sw)
        if (dash != null) p.pathEffect = DashPathEffect(dash, 0f)
        c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
    }

    private fun rect(c: Canvas, x: Number, y: Number, w: Number, h: Number, sw: Number, fillColor: Int? = null, dash: FloatArray? = null) {
        val l = x.toFloat(); val t = y.toFloat()
        val r = l + w.toFloat(); val b = t + h.toFloat()
        if (fillColor != null) c.drawRect(l, t, r, b, fill(fillColor))
        val p = stroke(sw)
        if (dash != null) p.pathEffect = DashPathEffect(dash, 0f)
        c.drawRect(l, t, r, b, p)
    }

    private fun circle(c: Canvas, cx: Number, cy: Number, r: Number, sw: Number, fillColor: Int?) {
        if (fillColor != null) c.drawCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), fill(fillColor))
        if (sw.toFloat() > 0f) c.drawCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), stroke(sw))
    }

    private fun text(
        c: Canvas, s: String, x: Number, y: Number, size: Number,
        bold: Boolean = false, center: Boolean = false, color: Int = Color.BLACK, maxW: Float = 0f
    ) {
        if (s.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = size.toFloat()
        p.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        p.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        p.color = color
        if (maxW > 0f) {
            val w = p.measureText(s)
            if (w > maxW) p.textSize = p.textSize * maxW / w
        }
        c.drawText(s, x.toFloat(), y.toFloat(), p)
    }

    // ---------- symbole ----------

    private fun earth(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y)
        line(c, 0, 0, 0, 20, 2)
        line(c, -15, 20, 15, 20, 2)
        line(c, -10, 25, 10, 25, 2)
        line(c, -5, 30, 5, 30, 2)
        c.restore()
    }

    private fun mcb(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y)
        line(c, 0, 0, 15, 0, 2.5)
        circle(c, 15, 0, 2.5, 1.5, Color.WHITE)
        line(c, 45, 0, 60, 0, 2.5)
        line(c, 42, -3, 48, 3, 1.5)
        line(c, 42, 3, 48, -3, 1.5)
        c.save(); c.translate(15f, 0f); c.rotate(-30f)
        line(c, 0, 0, 34, 0, 2.5)
        val thermal = Path().apply { moveTo(12f, 0f); lineTo(12f, -6f); lineTo(18f, -6f); lineTo(18f, 0f) }
        c.drawPath(thermal, stroke(1.5))
        line(c, 26, 0, 26, -7, 1.5)
        val arrow = Path().apply { moveTo(26f, -7f); lineTo(23f, -3f); lineTo(29f, -3f); close() }
        c.drawPath(arrow, fill(Color.BLACK))
        c.restore()
        text(c, "1", 15, 14, 9, bold = true, center = true)
        text(c, "2", 45, 14, 9, bold = true, center = true)
        c.restore()
    }

    private fun isolator(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y)
        line(c, 0, 0, 15, 0, 2.5)
        line(c, 48, 0, 60, 0, 2.5)
        line(c, 48, -8, 48, 8, 2.5)
        circle(c, 44.5, 0, 3.5, 2, Color.WHITE)
        c.save(); c.translate(15f, 0f); c.rotate(-30f)
        line(c, 0, 0, 33, 0, 2.5)
        c.restore()
        c.restore()
    }

    private fun lz(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y)
        rect(c, -12, -12, 24, 24, 1.5, Color.WHITE)
        circle(c, 0, 0, 3, 0, Color.BLACK)
        c.restore()
    }

    // ---------- schemat (układ współrzędnych 1000 x 500) ----------

    private fun diagram(c: Canvas, s: SchematicState) {
        val gray = 0xFF444444.toInt()

        // RG
        rect(c, 740, 100, 210, 260, 2, dash = floatArrayOf(6f, 4f))
        text(c, "Rozdzielnica", 845, 210, 14, bold = true, center = true)
        text(c, "Główna (RG)", 845, 230, 14, bold = true, center = true)
        text(c, s.objectName, 845, 255, 11, center = true, maxW = 196f)

        // Obudowa złącza
        rect(c, 50, 80, 600, 340, 2)
        text(c, s.connector, 350, 105, 14, bold = true, center = true, maxW = 580f)

        if (s.underground) {
            line(c, 50, 270, 650, 270, 1.5, floatArrayOf(4f, 4f))
            text(c, "TL (Część licznikowa)", 65, 260, 12, bold = true)
            text(c, "ZK (Część złączowa)", 65, 290, 12, bold = true)
            line(c, 140, 270, 140, 170, 2.5)

            line(c, 80, 330, 620, 330, 5)
            text(c, if (s.tns) "Szyna PEN / N" else "Szyna N", 80, 318, 12, bold = true)
            if (s.tns) {
                line(c, 140, 330, 140, 360, 2)
                earth(c, 140f, 360f)
                text(c, "R < 10 Ω", 160, 385, 10)
            }
        } else {
            rect(c, 80, 20, 120, 40, 2, Color.WHITE)
            text(c, "Zaciski odgałęźne", 140, 38, 11, bold = true, center = true)
            text(c, "ASXSN", 140, 52, 10, center = true, color = gray)
            line(c, 140, 60, 140, 170, 2.5)
            text(c, s.overheadCable, 150, 75, 11, bold = true, maxW = 200f)
        }

        // tor główny y = 170
        line(c, 200, 170, 260, 170, 2.5)
        line(c, 360, 170, 420, 170, 2.5)
        line(c, 480, 170, 740, 170, 2.5)

        if (s.mcbOn) {
            mcb(c, 140f, 170f)
            text(c, s.mcb, 170, 126, 11, bold = true, center = true, maxW = 110f)
        } else {
            line(c, 140, 170, 200, 170, 2.5)
        }

        // licznik
        rect(c, 260, 130, 100, 80, 2, Color.WHITE)
        text(c, s.meter, 310, 165, 14, bold = true, center = true, maxW = 90f)
        text(c, "230/400V", 310, 185, 11, center = true, color = gray)

        if (s.isolatorOn) {
            isolator(c, 420f, 170f)
            text(c, s.isolator, 450, 126, 11, bold = true, center = true, maxW = 110f)
        } else {
            line(c, 420, 170, 480, 170, 2.5)
        }

        lz(c, 540f, 170f)
        text(c, "LZ2", 540, 148, 11, bold = true, center = true)

        text(c, s.cable, 640, 160, 11, bold = true, center = true, maxW = 190f)
    }

    // ---------- strona ----------

    fun drawPage(c: Canvas, s: SchematicState) {
        c.drawColor(Color.WHITE)
        val margin = 10 * MM
        val boxW = PW - 2 * margin
        val boxH = 155 * MM
        val tbH = 25 * MM

        c.drawRect(margin, margin, margin + boxW, margin + boxH, stroke(0.75))

        val sc = minOf(boxW / 1000f, boxH / 500f)
        val ox = margin + (boxW - 1000f * sc) / 2f
        val oy = margin + (boxH - 500f * sc) / 2f
        c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        diagram(c, s)
        c.restore()

        titleBlock(c, s, margin, PH - margin - tbH, boxW, tbH)
    }

    private fun titleBlock(c: Canvas, s: SchematicState, x: Float, y: Float, w: Float, h: Float) {
        val cw = floatArrayOf(w * 0.5f, w * 0.25f, w * 0.25f)
        val rh = h / 2f
        val cells = listOf(
            "Inwestycja / Obiekt:" to s.objectName,
            "Tytuł rysunku:" to s.title,
            "Data:" to s.date,
            "Wykonawca:" to s.designer,
            "Numer złącza:" to s.connectorNo,
            "Skala / Format:" to "PTS / A4 Poziomo"
        )
        for ((i, cell) in cells.withIndex()) {
            val col = i % 3
            val row = i / 3
            var cx = x
            for (k in 0 until col) cx += cw[k]
            val cy = y + row * rh
            rect(c, cx, cy, cw[col], rh, 0.75)
            val top = cy + (rh - 18.6f) / 2f
            text(c, cell.first.uppercase(Locale("pl", "PL")), cx + 4.5f, top + 6.5f, 6.5, bold = true, color = 0xFF555555.toInt(), maxW = cw[col] - 9f)
            text(c, cell.second, cx + 4.5f, top + 7.8f + 9f, 9, bold = true, maxW = cw[col] - 9f)
        }
        rect(c, x, y, w, h, 1.5)
    }
}
