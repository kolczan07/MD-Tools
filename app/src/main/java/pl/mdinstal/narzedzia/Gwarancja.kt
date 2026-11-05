package pl.mdinstal.narzedzia

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Certyfikat gwarancyjny IGLU: wypełnianie szablonu Word (.docx) danymi z aplikacji
 * i pakowanie go razem z protokołem w paczkę Imię_Nazwisko.zip.
 *
 * Szablon: app/src/main/assets/gwarancja_szablon.docx (oryginalny plik Word, może to być
 * dowolna wcześniej wypełniona gwarancja). Dokument jest przepisywany bajt w bajt,
 * zmienia się tylko tekst w czterech miejscach word/document.xml, więc style, tabela,
 * czcionki i układ zostają bez zmian.
 */
object Gwarancja {
    const val TEMPLATE = "gwarancja_szablon.docx"

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private val DATE_RE = Regex("""\d{2}\.\d{2}\.\d{4}""")

    fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())

    /** "Jan Kowalski" -> "Jan_Kowalski" (bez znaków niedozwolonych w nazwach plików). */
    fun fileBase(name: String): String {
        val s = name.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
        return s.ifEmpty { "Klient" }
    }

    // ---------- XML ----------

    private fun Element.all(tag: String): List<Element> {
        val l = getElementsByTagNameNS(W, tag)
        return (0 until l.length).map { l.item(it) as Element }
    }

    private fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == W && n.localName == tag) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun paraText(p: Element): String = p.all("t").joinToString("") { it.textContent }

    /**
     * Wstawia tekst do pierwszego przebiegu tekstowego akapitu (z jego formatowaniem),
     * pozostałe przebiegi opróżnia.
     */
    private fun setText(doc: Document, p: Element, text: String) {
        val ts = p.all("t")
        if (ts.isEmpty()) {
            val r = doc.createElementNS(W, "w:r")
            val t = doc.createElementNS(W, "w:t")
            t.setAttributeNS(XML_NS, "xml:space", "preserve")
            t.textContent = text
            r.appendChild(t)
            p.appendChild(r)
            return
        }
        ts[0].setAttributeNS(XML_NS, "xml:space", "preserve")
        ts[0].textContent = text
        for (i in 1 until ts.size) ts[i].textContent = ""
    }

    /** Podmienia datę dd.MM.rrrr w akapicie; gdy data jest rozbita na kilka przebiegów, składa tekst na nowo. */
    private fun replaceDate(doc: Document, p: Element, date: String): Boolean {
        val old = paraText(p)
        if (!DATE_RE.containsMatchIn(old)) return false
        var done = false
        for (t in p.all("t")) {
            val s = t.textContent
            if (DATE_RE.containsMatchIn(s)) {
                t.textContent = DATE_RE.replace(s) { date }
                done = true
            }
        }
        if (!done) setText(doc, p, DATE_RE.replace(old) { date })
        return true
    }

    private fun firstParaOf(cell: Element): Element? = cell.all("p").firstOrNull()

    private fun setCell(doc: Document, cell: Element, text: String) {
        val ps = cell.all("p")
        if (ps.isEmpty()) return
        setText(doc, ps[0], text)
        for (i in 1 until ps.size) for (t in ps[i].all("t")) t.textContent = ""
    }

    /** Wypełnia dokument; rzuca wyjątek, gdy w szablonie nie ma oczekiwanych pól. */
    private fun fillDocument(
        xml: ByteArray, name: String, serial: String, date: String, place: String
    ): ByteArray {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = doc.documentElement

        var gotName = false
        var gotSerial = false
        var gotIssue = false
        var gotInstall = false

        // Tabela: etykieta w pierwszej komórce wiersza, wartość w drugiej.
        for (tr in root.all("tr")) {
            val cells = tr.children("tc")
            if (cells.size < 2) continue
            val label = cells[0].all("p").joinToString(" ") { paraText(it) }.trim()
            when {
                label.startsWith("Kupujący") -> { setCell(doc, cells[1], name); gotName = true }
                label.startsWith("Model i numer seryjny") -> { setCell(doc, cells[1], serial); gotSerial = true }
                label.startsWith("Miejsce i data instalacji") -> {
                    val p = firstParaOf(cells[1])
                    if (p != null) {
                        if (place.isNotBlank()) {
                            setCell(doc, cells[1], "${date}r $place")
                            gotInstall = true
                        } else {
                            gotInstall = replaceDate(doc, p, date)
                        }
                    }
                }
            }
        }

        // Akapit "Data wydania: dd.MM.rrrr" nad tabelą.
        for (p in root.all("p")) {
            if (paraText(p).trim().startsWith("Data wydania")) {
                if (replaceDate(doc, p, date)) gotIssue = true
                break
            }
        }

        if (!gotName || !gotSerial || !gotIssue || !gotInstall) {
            val missing = buildList {
                if (!gotName) add("Kupujący")
                if (!gotSerial) add("numer seryjny")
                if (!gotIssue) add("Data wydania")
                if (!gotInstall) add("data instalacji")
            }
            throw IllegalStateException("W szablonie nie znaleziono pól: ${missing.joinToString(", ")}")
        }

        doc.xmlStandalone = true
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    // ---------- DOCX / ZIP ----------

    /** Kopiuje szablon wpis po wpisie i podmienia tylko word/document.xml. */
    fun fillDocx(ctx: Context, name: String, serial: String, date: String, place: String): ByteArray {
        val res = ByteArrayOutputStream()
        ctx.assets.open(TEMPLATE).use { tpl ->
            ZipInputStream(tpl).use { zin ->
                ZipOutputStream(res).use { zout ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val bytes = zin.readBytes()
                        val data = if (e.name == "word/document.xml") fillDocument(bytes, name, serial, date, place) else bytes
                        zout.putNextEntry(ZipEntry(e.name))
                        zout.write(data)
                        zout.closeEntry()
                        e = zin.nextEntry
                    }
                }
            }
        }
        return res.toByteArray()
    }

    /** Buduje Imię_Nazwisko.zip: gwarancja (Word) + protokół uruchomienia (PDF). */
    fun buildZip(ctx: Context, name: String, serial: String, date: String, place: String): File {
        val base = fileBase(name)
        val docx = fillDocx(ctx, name.trim(), serial.trim(), date.trim(), place.trim())
        val pdf = PdfUtil.protocol(ctx)
        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val zip = File(dir, "$base.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("Gwarancja_$base.docx"))
            z.write(docx)
            z.closeEntry()
            z.putNextEntry(ZipEntry("Protokol_uruchomienia_$base.pdf"))
            z.write(pdf.readBytes())
            z.closeEntry()
        }
        return zip
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Udostępnij paczkę").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
