package app.kosh.data

import app.kosh.domain.StatementImport
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Reads the first sheet of an Excel file as rows of text, without any library: the newer .xlsx (zipped XML)
 * and the older binary .xls that many banks still hand out. Dates come out as YYYY-MM-DD, numbers as plain
 * decimals ("1250.5"), so the statement importer treats them exactly like a CSV.
 */
object Spreadsheets {
    class Unreadable(message: String) : Exception(message)

    private const val MAX_ROWS = 20_000

    // --- .xlsx -----------------------------------------------------------------------------

    fun readXlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            var total = 0L
            while (e != null) {
                val name = e.name.trimStart('/')
                if (name.startsWith("xl/") && (name.endsWith(".xml") || name.endsWith(".rels"))) {
                    val data = zip.readBytes()
                    total += data.size
                    // A statement is small; refuse zip bombs.
                    if (total > 200L * 1024 * 1024) throw Unreadable("This spreadsheet is too large to read.")
                    files[name] = data
                }
                e = zip.nextEntry
            }
        }
        val workbook = files["xl/workbook.xml"]?.toString(Charsets.UTF_8) ?: throw Unreadable("This doesn't look like an Excel file.")
        val rels = files["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8).orEmpty()
        val firstSheetRel = Regex("""<sheet\b[^>]*\br:id="([^"]+)"""").find(workbook)?.groupValues?.get(1)
        val target = firstSheetRel?.let { id -> Regex("""<Relationship\b[^>]*Id="$id"[^>]*Target="([^"]+)"""").find(rels)?.groupValues?.get(1)
            ?: Regex("""<Relationship\b[^>]*Target="([^"]+)"[^>]*Id="$id"""").find(rels)?.groupValues?.get(1) }
        val sheetPath = target?.let { if (it.startsWith("/")) it.trimStart('/') else "xl/" + it.removePrefix("./") }
            ?: files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
            ?: throw Unreadable("No sheet found in this file.")
        val sheet = files[sheetPath]?.toString(Charsets.UTF_8) ?: throw Unreadable("No sheet found in this file.")
        val shared = files["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { sst ->
            Regex("""<si\b[^>]*>([\s\S]*?)</si>""").findAll(sst).map { si ->
                Regex("""<t\b[^>]*>([\s\S]*?)</t>""").findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
            }.toList()
        }.orEmpty()
        val dateStyles = files["xl/styles.xml"]?.toString(Charsets.UTF_8)?.let(::xlsxDateStyles).orEmpty()

        val rows = ArrayList<List<String>>()
        for (rowMatch in Regex("""<row\b[^>]*>([\s\S]*?)</row>""").findAll(sheet)) {
            if (rows.size >= MAX_ROWS) break
            val cells = ArrayList<String>()
            for (c in Regex("""<c\b([^>]*?)(?:/>|>([\s\S]*?)</c>)""").findAll(rowMatch.groupValues[1])) {
                val attrs = c.groupValues[1]
                val body = c.groupValues[2]
                val ref = Regex("""\br="([A-Z]+)\d+"""").find(attrs)?.groupValues?.get(1)
                val type = Regex("""\bt="(\w+)"""").find(attrs)?.groupValues?.get(1)
                val style = Regex("""\bs="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toInt()
                val v = Regex("""<v>([\s\S]*?)</v>""").find(body)?.groupValues?.get(1)?.let(::unescape)
                val text = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> Regex("""<t\b[^>]*>([\s\S]*?)</t>""").findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                    "str", "e" -> v.orEmpty()
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v?.toDoubleOrNull()?.let { d -> if (style != null && style in dateStyles) serialToDate(d) else number(d) } ?: v.orEmpty()
                }
                val col = ref?.let(::columnIndex) ?: cells.size
                while (cells.size < col) cells += ""
                if (col == cells.size) cells += text else cells[col] = text
            }
            if (cells.any { it.isNotBlank() }) rows += cells
        }
        return rows
    }

    /** Style indexes (cellXfs order) whose number format is a date. */
    private fun xlsxDateStyles(styles: String): Set<Int> {
        val custom = Regex("""<numFmt\b[^>]*numFmtId="(\d+)"[^>]*formatCode="([^"]*)"""").findAll(styles)
            .associate { it.groupValues[1].toInt() to unescape(it.groupValues[2]) }
        val xfs = Regex("""<cellXfs\b[^>]*>([\s\S]*?)</cellXfs>""").find(styles)?.groupValues?.get(1) ?: return emptySet()
        return Regex("""<xf\b([^>]*)""").findAll(xfs).mapIndexedNotNull { i, m ->
            val id = Regex("""numFmtId="(\d+)"""").find(m.groupValues[1])?.groupValues?.get(1)?.toInt() ?: 0
            i.takeIf { isDateFormat(id, custom[id]) }
        }.toSet()
    }

    private fun columnIndex(letters: String): Int = letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    private fun unescape(s: String): String = StatementImport.decodeEntities(
        s.replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toInt(16).toChar().toString() },
    )

    // --- .xls (BIFF8 inside an OLE2 compound file) -----------------------------------------------

    fun readXls(bytes: ByteArray): List<List<String>> {
        val stream = Cfb(bytes).stream("Workbook") ?: Cfb(bytes).stream("Book") ?: throw Unreadable("This .xls file has no workbook inside.")
        return Biff(stream).firstSheet()
    }

    /** OLE2 compound file: just enough to pull one stream out by name. */
    private class Cfb(private val b: ByteArray) {
        private val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private val sectorSize: Int
        private val miniCutoff: Int
        private val fat: IntArray
        private val dir: ByteArray

        init {
            if (b.size < 512 || buf.getLong(0) != 0xE11AB1A1E011CFD0uL.toLong()) throw Unreadable("This .xls file is damaged or not an Excel file.")
            sectorSize = 1 shl buf.getShort(0x1E).toInt()
            if (sectorSize != 512 && sectorSize != 4096) throw Unreadable("This .xls file is damaged.")
            miniCutoff = buf.getInt(0x38)
            // Every FAT sector is listed in the header's 109 slots, then in a chain of DIFAT sectors.
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) buf.getInt(0x4C + i * 4).takeIf { it >= 0 }?.let { fatSectors += it }
            var difat = buf.getInt(0x44)
            var guard = 0
            while (difat >= 0 && guard++ < 10_000) {
                val off = offset(difat)
                for (i in 0 until sectorSize / 4 - 1) buf.getInt(off + i * 4).takeIf { it >= 0 }?.let { fatSectors += it }
                difat = buf.getInt(off + sectorSize - 4)
            }
            val per = sectorSize / 4
            fat = IntArray(fatSectors.size * per)
            for ((k, s) in fatSectors.withIndex()) {
                val off = offset(s)
                for (i in 0 until per) if (off + i * 4 + 4 <= b.size) fat[k * per + i] = buf.getInt(off + i * 4)
            }
            dir = chain(buf.getInt(0x30), Int.MAX_VALUE)
        }

        private fun offset(sector: Int) = (sector + 1) * sectorSize

        private fun chain(start: Int, size: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < fat.size && guard++ < 1_000_000 && out.size() < size) {
                val off = offset(s)
                if (off + sectorSize > b.size) break
                out.write(b, off, sectorSize)
                s = fat[s]
            }
            val all = out.toByteArray()
            return if (size < all.size) all.copyOf(size) else all
        }

        fun stream(name: String): ByteArray? {
            val entries = dir.size / 128
            var root: Pair<Int, Int>? = null
            val e = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until entries) {
                val nameLen = e.getShort(i * 128 + 0x40).toInt()
                val type = dir[i * 128 + 0x42].toInt()
                val start = e.getInt(i * 128 + 0x74)
                val size = e.getInt(i * 128 + 0x78)
                if (type == 5) root = start to size
                if (nameLen < 2 || type != 2) continue
                val n = String(dir, i * 128, nameLen - 2, Charsets.UTF_16LE)
                if (!n.equals(name, ignoreCase = true)) continue
                if (size >= miniCutoff) return chain(start, size)
                // Small streams live in the mini stream, in 64-byte sectors.
                val (rootStart, rootSize) = root ?: findRoot() ?: return null
                val mini = chain(rootStart, rootSize)
                val miniFatBytes = chain(buf.getInt(0x3C), Int.MAX_VALUE)
                val miniFat = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
                val out = java.io.ByteArrayOutputStream()
                var s = start
                var guard = 0
                while (s >= 0 && guard++ < 1_000_000 && out.size() < size) {
                    if (s * 64 + 64 > mini.size) break
                    out.write(mini, s * 64, 64)
                    s = if (s * 4 + 4 <= miniFatBytes.size) miniFat.getInt(s * 4) else -2
                }
                return out.toByteArray().copyOf(size)
            }
            return null
        }

        private fun findRoot(): Pair<Int, Int>? {
            for (i in 0 until dir.size / 128) {
                if (dir[i * 128 + 0x42].toInt() == 5) {
                    val e = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
                    return e.getInt(i * 128 + 0x74) to e.getInt(i * 128 + 0x78)
                }
            }
            return null
        }
    }

    /** BIFF8 records: shared strings, number formats, and the cells of the first worksheet. */
    private class Biff(private val b: ByteArray) {
        private val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private val sst = ArrayList<String>()
        private val formats = HashMap<Int, String>()
        private val xfFormat = ArrayList<Int>()
        private var firstSheetOffset = -1

        private fun u16(p: Int) = buf.getShort(p).toInt() and 0xFFFF

        fun firstSheet(): List<List<String>> {
            // Globals: from the start to the first EOF record.
            var p = 0
            var guard = 0
            while (p + 4 <= b.size && guard++ < 5_000_000) {
                val id = u16(p)
                val len = u16(p + 2)
                val data = p + 4
                when (id) {
                    // BOUNDSHEET: the first one that is a worksheet (type 0) is read.
                    0x85 -> if (firstSheetOffset < 0 && (b.getOrNull(data + 5)?.toInt() ?: 0) == 0) firstSheetOffset = buf.getInt(data)
                    0x41E -> formats[u16(data)] = readString(data + 2, data + len, twoByteLength = true).first
                    0xE0 -> xfFormat += u16(data + 2)
                    0xFC -> readSst(p)
                }
                p = data + len
                if (id == 0x0A) break
            }
            if (firstSheetOffset < 0) throw Unreadable("No worksheet found in this .xls file.")
            val cells = HashMap<Int, HashMap<Int, String>>()
            fun put(r: Int, c: Int, v: String) { if (r < MAX_ROWS) cells.getOrPut(r) { HashMap() }[c] = v }
            fun num(xf: Int, v: Double): String {
                val f = xfFormat.getOrNull(xf) ?: 0
                return if (isDateFormat(f, formats[f])) serialToDate(v) else number(v)
            }
            p = firstSheetOffset
            var pendingFormula: Pair<Int, Int>? = null
            guard = 0
            while (p + 4 <= b.size && guard++ < 5_000_000) {
                val id = u16(p)
                val len = u16(p + 2)
                val d = p + 4
                if (d + len > b.size) break
                when (id) {
                    0xFD -> put(u16(d), u16(d + 2), sst.getOrNull(buf.getInt(d + 6)).orEmpty())
                    0x204 -> put(u16(d), u16(d + 2), readString(d + 6, d + len, twoByteLength = true).first)
                    0x203 -> put(u16(d), u16(d + 2), num(u16(d + 4), buf.getDouble(d + 6)))
                    0x27E -> put(u16(d), u16(d + 2), num(u16(d + 4), rk(buf.getInt(d + 6))))
                    0xBD -> {
                        val row = u16(d)
                        val first = u16(d + 2)
                        val n = (len - 6) / 6
                        for (i in 0 until n) put(row, first + i, num(u16(d + 4 + i * 6), rk(buf.getInt(d + 6 + i * 6))))
                    }
                    0x06 -> {
                        val row = u16(d); val col = u16(d + 2); val xf = u16(d + 4)
                        if (u16(d + 12) == 0xFFFF) {
                            when (b[d + 6].toInt()) {
                                0 -> pendingFormula = row to col
                                1 -> put(row, col, if (b[d + 8].toInt() != 0) "TRUE" else "FALSE")
                            }
                        } else put(row, col, num(xf, buf.getDouble(d + 6)))
                    }
                    0x207 -> pendingFormula?.let { (r, c) -> put(r, c, readString(d, d + len, twoByteLength = true).first); pendingFormula = null }
                    0x0A -> break
                }
                p = d + len
            }
            val maxRow = cells.keys.maxOrNull() ?: return emptyList()
            return (0..maxRow).mapNotNull { r ->
                val row = cells[r] ?: return@mapNotNull null
                val maxCol = row.keys.max()
                (0..maxCol).map { row[it].orEmpty() }.takeIf { cols -> cols.any { it.isNotBlank() } }
            }
        }

        /** An RK number: a compressed integer or double, optionally divided by 100. */
        private fun rk(v: Int): Double {
            val value = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
            return if (v and 1 != 0) value / 100 else value
        }

        /** XLUnicodeString within one record: length, flags, then 8-bit or UTF-16 characters. */
        private fun readString(start: Int, end: Int, twoByteLength: Boolean): Pair<String, Int> {
            var p = start
            val cch = if (twoByteLength) u16(p).also { p += 2 } else (b[p].toInt() and 0xFF).also { p += 1 }
            val flags = b[p].toInt(); p++
            var runs = 0
            var ext = 0
            if (flags and 0x8 != 0) { runs = u16(p); p += 2 }
            if (flags and 0x4 != 0) { ext = buf.getInt(p); p += 4 }
            val wide = flags and 1 != 0
            val n = minOf(cch, if (wide) (end - p) / 2 else end - p).coerceAtLeast(0)
            val s = if (wide) String(b, p, n * 2, Charsets.UTF_16LE) else String(b, p, n, Charsets.ISO_8859_1)
            p += if (wide) n * 2 else n
            return s to (p + runs * 4 + ext)
        }

        /**
         * The shared-string table. Strings may be cut across CONTINUE records; at each cut the next record
         * starts with a fresh flags byte saying whether the rest is 8-bit or UTF-16.
         */
        private fun readSst(recordStart: Int) {
            // Gather the SST record and its CONTINUE records as segments.
            val segs = ArrayList<IntRange>()
            var p = recordStart
            var first = true
            while (p + 4 <= b.size) {
                val id = u16(p)
                val len = u16(p + 2)
                if (!first && id != 0x3C) break
                segs += (p + 4) until minOf(p + 4 + len, b.size)
                first = false
                p += 4 + len
            }
            var seg = 0
            var pos = segs[0].first + 8
            val unique = buf.getInt(segs[0].first + 4)
            fun ensure() { while (seg < segs.size && pos > segs[seg].last) { seg++; if (seg < segs.size) pos = segs[seg].first } }
            fun byte(): Int { ensure(); return (b[pos++].toInt() and 0xFF) }
            fun u16s(): Int { val lo = byte(); val hi = byte(); return lo or (hi shl 8) }
            fun u32s(): Int { val a = u16s(); val c = u16s(); return a or (c shl 16) }
            repeat(unique.coerceAtMost(1_000_000)) {
                if (seg >= segs.size) return
                val cch = u16s()
                var flags = byte()
                val runs = if (flags and 0x8 != 0) u16s() else 0
                val ext = if (flags and 0x4 != 0) u32s() else 0
                val sb = StringBuilder(cch)
                var left = cch
                while (left > 0 && seg < segs.size) {
                    if (pos > segs[seg].last) {
                        seg++
                        if (seg >= segs.size) break
                        pos = segs[seg].first
                        flags = b[pos++].toInt() // the cut: a new flags byte for the rest of the characters
                    }
                    val wide = flags and 1 != 0
                    val avail = if (wide) (segs[seg].last + 1 - pos) / 2 else segs[seg].last + 1 - pos
                    val n = minOf(left, avail)
                    sb.append(if (wide) String(b, pos, n * 2, Charsets.UTF_16LE) else String(b, pos, n, Charsets.ISO_8859_1))
                    pos += if (wide) n * 2 else n
                    left -= n
                }
                sst += sb.toString()
                // Skip formatting runs and phonetic data, which can also cross a cut.
                var skip = runs * 4 + ext
                while (skip > 0 && seg < segs.size) {
                    ensure()
                    if (seg >= segs.size) break
                    val n = minOf(skip, segs[seg].last + 1 - pos)
                    pos += n; skip -= n
                }
            }
        }
    }

    // --- Shared --------------------------------------------------------------------------------

    /** Excel's built-in date formats (14-22, 45-47) and custom ones that use day, month or year codes. */
    fun isDateFormat(id: Int, code: String?): Boolean {
        if (id in 14..22 || id in 45..47 || id in 27..36 || id in 50..58) return true
        val c = code?.replace(Regex(""""[^"]*"|\[[^\]]*]|\\."""), "")?.lowercase() ?: return false
        return c.any { it == 'd' || it == 'y' } || (c.contains('m') && !c.contains('0') && !c.contains('#'))
    }

    /** Days since 1899-12-30 (Excel's day 0, which absorbs its 1900 leap-year bug) as YYYY-MM-DD. */
    fun serialToDate(v: Double): String = LocalDate.of(1899, 12, 30).plusDays(kotlin.math.floor(v).toLong()).toString()

    /** 1250.5 as "1250.5", 3.0 as "3": no exponent, no binary noise. */
    fun number(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "" else BigDecimal(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
