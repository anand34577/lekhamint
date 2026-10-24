package app.kosh.backup

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.kosh.core.Money
import app.kosh.domain.ReportDoc
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [ReportDoc] as an A4 PDF or an Excel workbook. The PDF has a header
 * with the book and generation time, summary tiles, tables whose header repeats
 * on every page, totals kept with their table, notes, and "Page n of m".
 */
object ReportExport {
    private const val W = 595f
    private const val H = 842f
    private const val M = 36f
    private val brand = Color.rgb(15, 118, 110)
    private val ink = Color.rgb(20, 33, 32)
    private val muted = Color.rgb(93, 107, 105)
    private val line = Color.rgb(223, 229, 227)
    private val soft = Color.rgb(238, 246, 244)
    private val neg = Color.rgb(180, 35, 24)

    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    private fun text(v: Any?, kind: ReportDoc.Kind, cur: String): String = when {
        v == null -> ""
        kind == ReportDoc.Kind.MONEY && v is Long -> Money.format(v, cur)
        v is LocalDate -> v.format(dateFmt)
        else -> v.toString()
    }

    /** Lays the report out twice: once to count pages, then for real with "Page n of m" footers. */
    fun writePdf(doc: ReportDoc, book: String, out: OutputStream) {
        val probe = render(doc, book, null)
        val total = probe.pages.size
        probe.close()
        val pdf = render(doc, book, total)
        pdf.writeTo(out)
        pdf.close()
    }

    private fun render(doc: ReportDoc, book: String, total: Int?): PdfDocument {
        val pdf = PdfDocument()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand; textSize = 10.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.05f }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 8.5f }
        val bold = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 7.5f }
        val th = Paint(small).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val fill = Paint().apply { style = Paint.Style.FILL }
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = line }

        val pages = ArrayList<PdfDocument.Page>()
        var page: PdfDocument.Page? = null
        var y = 0f
        fun footer(p: PdfDocument.Page, n: Int) {
            val left = "$book · ${doc.title}"
            p.canvas.drawText(ellipsizeStatic(left, small, W / 2), M, H - M + 10, small)
            val right = if (total != null) "Page $n of $total" else "Page $n"
            p.canvas.drawText(right, W - M - small.measureText(right), H - M + 10, small)
        }
        fun newPage() {
            page?.let { footer(it, pages.size); pdf.finishPage(it) }
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pages.size + 1).create())
            pages += p
            page = p
            y = M
            val cv = p.canvas
            if (pages.size == 1) {
                cv.drawText("LekhaMint", M, y + 10, Paint(h2).apply { textSize = 12f; letterSpacing = 0f })
                val meta = "$book · generated ${LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))}"
                cv.drawText(meta, W - M - small.measureText(meta), y + 10, small)
                y += 16
                cv.drawRect(M, y, W - M, y + 1.5f, fill.apply { color = brand })
                y += 22
            }
        }
        fun ensure(h: Float, onNew: () -> Unit = {}) { if (y + h > H - M - 14) { newPage(); onNew() } }
        fun ellipsize(s: String, p: Paint, w: Float): String {
            if (p.measureText(s) <= w) return s
            var t = s
            while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1)
            return "$t…"
        }

        newPage()
        var cv = page!!.canvas
        cv.drawText(doc.title, M, y, title); y += 14
        val sub = listOfNotNull(doc.subtitle, doc.period?.let { "${it.first.format(dateFmt)} – ${it.second.format(dateFmt)}" }, "Amounts in ${doc.currency}").joinToString("  ·  ")
        cv.drawText(sub, M, y, small); y += 14
        if (doc.stats.isNotEmpty()) {
            val gap = 6f
            val tw = (W - 2 * M - gap * (doc.stats.size - 1)) / doc.stats.size
            doc.stats.forEachIndexed { i, s ->
                val x = M + i * (tw + gap)
                cv.drawRoundRect(x, y, x + tw, y + 38, 5f, 5f, fill.apply { color = soft })
                cv.drawText(ellipsize(s.label.uppercase(), small, tw - 12), x + 6, y + 13, small)
                cv.drawText(ellipsize(if (s.count) s.value.toString() else Money.format(s.value, doc.currency), bold, tw - 12), x + 6, y + 29, Paint(bold).apply { textSize = 11f })
            }
            y += 50
        }

        for (sec in doc.sections) {
            ensure(40f)
            cv = page!!.canvas
            cv.drawText(sec.title.uppercase(), M, y + 8, h2); y += 18
            if (sec.rows.isEmpty()) { cv.drawText(sec.empty, M, y + 6, small); y += 18; continue }
            val totalW = sec.columns.sumOf { it.weight.toDouble() }.toFloat()
            val widths = sec.columns.map { it.weight / totalW * (W - 2 * M) }
            val right = sec.columns.map { it.kind == ReportDoc.Kind.MONEY || it.kind == ReportDoc.Kind.NUMBER }
            fun header() {
                val c = page!!.canvas
                var x = M
                sec.columns.forEachIndexed { i, col ->
                    val t = ellipsize(col.label.uppercase(), th, widths[i] - 6)
                    c.drawText(t, if (right[i]) x + widths[i] - 3 - th.measureText(t) else x + 3, y + 8, th)
                    x += widths[i]
                }
                y += 12
                c.drawRect(M, y, W - M, y + 1f, fill.apply { color = ink }); y += 4
            }
            header()
            fun row(cells: List<Any?>, p: Paint, shade: Boolean) {
                ensure(14f) { header() }
                val c = page!!.canvas
                if (shade) c.drawRect(M, y - 1, W - M, y + 12, fill.apply { color = Color.rgb(250, 251, 251) })
                var x = M
                cells.forEachIndexed { i, v ->
                    val t = ellipsize(text(v, sec.columns[i].kind, doc.currency), p, widths[i] - 6)
                    val paint = if (sec.columns[i].kind == ReportDoc.Kind.MONEY && v is Long && v < 0) Paint(p).apply { color = neg } else p
                    c.drawText(t, if (right[i]) x + widths[i] - 3 - paint.measureText(t) else x + 3, y + 8.5f, paint)
                    x += widths[i]
                }
                y += 13
                c.drawLine(M, y - 1, W - M, y - 1, stroke)
            }
            sec.rows.forEachIndexed { i, r -> row(r, body, i % 2 == 1) }
            sec.total?.let {
                ensure(16f) { header() }
                page!!.canvas.drawRect(M, y - 1, W - M, y + 12, fill.apply { color = soft })
                row(it, bold, false)
            }
            y += 10
        }

        if (doc.notes.isNotEmpty()) {
            ensure(30f)
            page!!.canvas.drawText("NOTES", M, y + 8, Paint(small).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }); y += 14
            for (n in doc.notes + "Prepared with LekhaMint from your own records. It is not a bank or lender statement.") {
                for (ln in wrap(n, small, W - 2 * M)) { ensure(11f); page!!.canvas.drawText(ln, M, y + 7, small); y += 10 }
            }
        }
        page?.let { footer(it, pages.size); pdf.finishPage(it) }
        return pdf
    }

    private fun ellipsizeStatic(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    private fun wrap(s: String, p: Paint, w: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in s.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) > w && cur.isNotEmpty()) { out += cur; cur = word } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    // --- Excel ---------------------------------------------------------------------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    private fun col(i: Int): String { var n = i + 1; var s = ""; while (n > 0) { s = ('A' + (n - 1) % 26) + s; n = (n - 1) / 26 }; return s }

    private fun cell(ref: String, v: Any?, kind: ReportDoc.Kind, digits: Int, bold: Boolean): String = when {
        v == null -> ""
        kind == ReportDoc.Kind.MONEY && v is Long -> "<c r=\"$ref\" s=\"${if (bold) 4 else 3}\"><v>${java.math.BigDecimal.valueOf(v, digits).toPlainString()}</v></c>"
        v is LocalDate -> "<c r=\"$ref\" s=\"2\"><v>${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), v)}</v></c>"
        v is Long || v is Int -> "<c r=\"$ref\"${if (bold) " s=\"1\"" else ""}><v>$v</v></c>"
        else -> "<c r=\"$ref\" t=\"inlineStr\"${if (bold) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>"
    }

    fun writeXlsx(doc: ReportDoc, book: String, out: OutputStream) {
        val digits = app.kosh.core.Currencies.digits(doc.currency)
        val sheets = ArrayList<Pair<String, String>>()
        val intro = listOf("${doc.title} · $book", listOfNotNull(doc.period?.let { "${it.first} to ${it.second}" }, "Amounts in ${doc.currency}", "Generated ${LocalDateTime.now().withNano(0)}").joinToString(" · "))
        fun sheet(header: List<String>, rows: List<List<Any?>>, kinds: List<ReportDoc.Kind>, total: List<Any?>?): String {
            val sb = StringBuilder()
            var r = 1
            for (l in intro) { sb.append("<row r=\"$r\">${cell("A$r", l, ReportDoc.Kind.TEXT, digits, r == 1)}</row>"); r++ }
            r++
            val hr = r
            sb.append("<row r=\"$r\">"); header.forEachIndexed { i, h -> sb.append(cell("${col(i)}$r", h, ReportDoc.Kind.TEXT, digits, true)) }; sb.append("</row>"); r++
            for (row in rows) { sb.append("<row r=\"$r\">"); row.forEachIndexed { i, v -> sb.append(cell("${col(i)}$r", v, kinds[i], digits, false)) }; sb.append("</row>"); r++ }
            total?.let { sb.append("<row r=\"$r\">"); it.forEachIndexed { i, v -> sb.append(cell("${col(i)}$r", v, kinds[i], digits, true)) }; sb.append("</row>") }
            val cols = header.indices.joinToString("") { "<col min=\"${it + 1}\" max=\"${it + 1}\" width=\"${if (kinds[it] == ReportDoc.Kind.TEXT) 30 else 16}\" customWidth=\"1\"/>" }
            return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="$hr" topLeftCell="A${hr + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>$cols</cols><sheetData>$sb</sheetData></worksheet>"""
        }
        sheets += "Summary" to sheet(listOf("Measure", "Amount"), doc.stats.map { listOf(it.label, it.value) } + doc.notes.map { listOf(it, null) },
            listOf(ReportDoc.Kind.TEXT, ReportDoc.Kind.MONEY), null)
        for (s in doc.sections) sheets += s.title.take(31).replace(Regex("[\\\\/?*\\[\\]:]"), " ") to sheet(s.columns.map { it.label }, s.rows, s.columns.map { it.kind }, s.total)
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/><numFmt numFmtId="165" formatCode="#,##0.${"0".repeat(maxOf(digits, 0))};[Red]-#,##0.${"0".repeat(maxOf(digits, 0))}"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEEF2F1"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="1" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.mapIndexed { i, s -> "<sheet name=\"${esc(s.first)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("")}</sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }}<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to styles,
        )
        sheets.forEachIndexed { i, s -> files["xl/worksheets/sheet${i + 1}.xml"] = s.second }
        ZipOutputStream(out).use { z ->
            for ((name, content) in files) {
                val bytes = content.toByteArray()
                z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); crc = CRC32().apply { update(bytes) }.value })
                z.write(bytes)
                z.closeEntry()
            }
        }
    }
}
