package app.kosh.domain

import app.kosh.core.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Reading bank and card statements: CSV (and anything turned into a table: Excel, HTML "xls" files),
 * OFX, QIF and the text of PDF statements. The same rules as the web app's importer
 * (web/src/lib/csv.ts and ofx.ts), so a file imported on either gets the same IDs and duplicates are caught
 * across both. Everything here is pure; the file never leaves the phone.
 */
object StatementImport {
    data class Row(
        /** 1-based line or record in the file, for messages. */
        val line: Int,
        val date: LocalDate,
        val type: String,
        /** Positive minor units. */
        val amount: Long,
        val description: String,
        val note: String = "",
        val category: String = "",
        /** A bank's own ID for the transaction (OFX FITID): the most reliable fingerprint. */
        val ref: String? = null,
    )

    data class RowError(val line: Int, val message: String)
    data class Parsed(val rows: List<Row>, val errors: List<RowError>, val currency: String? = null, val account: String? = null)

    enum class Kind { OFX, QIF, CSV, XLSX, XLS, HTML, PDF }

    /** What a file holds, from its name and first bytes. */
    fun kind(name: String, head: ByteArray): Kind {
        val n = name.lowercase()
        if (head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()) return Kind.PDF
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return Kind.XLSX
        if (head.size >= 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()) return Kind.XLS
        val text = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val up = text.take(2000).uppercase()
        if (n.endsWith(".ofx") || n.endsWith(".qfx") || up.contains("<OFX>") || up.contains("OFXHEADER")) return Kind.OFX
        if (n.endsWith(".qif") || Regex("""^\s*!(TYPE|ACCOUNT|OPTION)""", RegexOption.IGNORE_CASE).containsMatchIn(text.take(200))) return Kind.QIF
        // Many banks' ".xls" downloads are really an HTML table.
        if (up.startsWith("<") && (up.contains("<TABLE") || up.contains("<HTML"))) return Kind.HTML
        if (n.endsWith(".pdf")) return Kind.PDF
        return Kind.CSV
    }

    // --- CSV --------------------------------------------------------------------------

    /** Picks the delimiter that splits the first lines most consistently. */
    fun detectDelimiter(text: String): Char {
        val lines = text.split(Regex("""\r?\n""")).filter { it.isNotBlank() }.take(10)
        var best = ','
        var bestScore = -1
        for (d in listOf(',', ';', '\t', '|')) {
            val counts = lines.map { (parseCsv(it, d).firstOrNull() ?: emptyList()).size }
            if (counts.isEmpty() || counts[0] < 2) continue
            val score = counts.count { it == counts[0] } * 100 + counts[0]
            if (score > bestScore) { best = d; bestScore = score }
        }
        return best
    }

    /** RFC 4180 CSV: quoted fields, doubled quotes, newlines inside quotes, CRLF or LF. */
    fun parseCsv(text: String, delimiter: Char = ','): List<List<String>> {
        val s = text.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endRow() {
            row.add(field.toString()); field.clear()
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = ArrayList()
        }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i += 2; continue }
                    quoted = false; i++; continue
                }
                field.append(c); i++; continue
            }
            when {
                c == '"' && field.isEmpty() -> { quoted = true; i++ }
                c == delimiter -> { row.add(field.toString()); field.clear(); i++ }
                c == '\r' || c == '\n' -> { endRow(); i += if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') 2 else 1 }
                else -> { field.append(c); i++ }
            }
        }
        endRow()
        return rows
    }

    // --- Columns ----------------------------------------------------------------------

    enum class Role(val label: String) {
        DATE("Date"), DESCRIPTION("Description / payee"), NOTE("Note"), AMOUNT("Amount (signed)"), DEBIT("Money out (debit)"),
        CREDIT("Money in (credit)"), CATEGORY("Category"), TYPE("Type (Dr / Cr)"), IGNORE("Ignore"),
    }

    private val GUESS = listOf(
        Role.DATE to Regex("""^(txn |tran |transaction |value |posting |posted |booking )?date|^date$|^when$|^txn dt""", RegexOption.IGNORE_CASE),
        Role.DEBIT to Regex("""debit|withdrawal|money out|paid out|^dr\.?$|^out$""", RegexOption.IGNORE_CASE),
        Role.CREDIT to Regex("""credit|deposit|money in|paid in|^cr\.?$|^in$""", RegexOption.IGNORE_CASE),
        Role.AMOUNT to Regex("""amount|^amt|^value$|^sum$""", RegexOption.IGNORE_CASE),
        Role.CATEGORY to Regex("""categor""", RegexOption.IGNORE_CASE),
        Role.TYPE to Regex("""^type$|^dr\s*/\s*cr$|transaction type|^cr/dr$""", RegexOption.IGNORE_CASE),
        Role.NOTE to Regex("""note|memo|comment|description 2|reference|ref\.?\s*no|cheque|chq""", RegexOption.IGNORE_CASE),
        Role.DESCRIPTION to Regex("""description|narration|details|payee|merchant|title|particulars|remarks|name""", RegexOption.IGNORE_CASE),
    )

    /** Guesses a role for each header; each role except note/ignore is used at most once. */
    fun guessRoles(headers: List<String>): List<Role> {
        val used = HashSet<Role>()
        return headers.map { h ->
            GUESS.firstOrNull { (role, re) -> re.containsMatchIn(h.trim()) && role !in used }?.first?.also { used += it } ?: Role.IGNORE
        }
    }

    /**
     * Bank exports often start with a few lines about the account before the real table.
     * The header is the first row (within the first 40) that names both a date and an amount-like column.
     */
    fun findHeader(table: List<List<String>>): Int {
        for ((i, r) in table.take(40).withIndex()) {
            val roles = guessRoles(r)
            if (Role.DATE in roles && (Role.AMOUNT in roles || Role.DEBIT in roles || Role.CREDIT in roles)) return i
        }
        return 0
    }

    // --- Dates ------------------------------------------------------------------------

    enum class DateFormat { YMD, DMY, MDY }
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Parses a date in the given order; month names ("24 Oct 2026", "Oct 24, 2026") work either way. Excel day numbers too. */
    fun parseDate(raw: String, fmt: DateFormat): LocalDate? {
        val s = raw.trim().replace(Regex("""[T ]\d{1,2}:\d{2}(:\d{2})?.*$"""), "").trim()
        if (s.isEmpty()) return null
        // Excel stores dates as days since 1899-12-30; a spreadsheet cell may arrive as "45567" or "45567.0".
        Regex("""^(\d{5})(\.0+)?$""").matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            if (n in 20000..80000) return LocalDate.of(1899, 12, 30).plusDays(n)
        }
        val named = Regex("""^(\d{1,2})[\s\-./]+([A-Za-z]{3,})[\s\-./,]+(\d{2,4})$""").matchEntire(s)
        val namedUS = Regex("""^([A-Za-z]{3,})[\s\-./]+(\d{1,2}),?[\s\-./]+(\d{2,4})$""").matchEntire(s)
        var y: Int
        val m: Int
        val d: Int
        if (named != null || namedUS != null) {
            val (day, mon, yr) = if (named != null) Triple(named.groupValues[1], named.groupValues[2], named.groupValues[3])
            else Triple(namedUS!!.groupValues[2], namedUS.groupValues[1], namedUS.groupValues[3])
            m = MONTHS.indexOf(mon.take(3).lowercase()) + 1
            d = day.toInt(); y = yr.toInt()
        } else {
            val parts = s.split(Regex("""[\-./\s]+""")).filter { it.isNotEmpty() }
            if (parts.size != 3 || parts.any { p -> !p.all(Char::isDigit) }) return null
            val n = parts.map { it.toInt() }
            when {
                parts[0].length == 4 -> { y = n[0]; m = n[1]; d = n[2] }
                fmt == DateFormat.MDY -> { m = n[0]; d = n[1]; y = n[2] }
                else -> { d = n[0]; m = n[1]; y = n[2] }
            }
        }
        if (y < 100) y += if (y >= 70) 1900 else 2000
        if (m !in 1..12 || d !in 1..31 || y !in 1900..2200) return null
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    /** Picks the order that reads every sample; day-first wins ties (common outside the US). */
    fun detectDateFormat(samples: List<String>, preferUS: Boolean = false): DateFormat {
        val order = if (preferUS) listOf(DateFormat.MDY, DateFormat.DMY, DateFormat.YMD) else listOf(DateFormat.DMY, DateFormat.MDY, DateFormat.YMD)
        val vals = samples.filter { it.isNotBlank() }
        // 2026-10-24 reads the same in every order; say so, rather than "month first".
        if (vals.isNotEmpty() && vals.all { Regex("""^\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}""").containsMatchIn(it) }) return DateFormat.YMD
        return order.firstOrNull { f -> vals.all { parseDate(it, f) != null } } ?: order[0]
    }

    // --- Amounts ----------------------------------------------------------------------

    /**
     * Parses "1,234.56", "-1 234,56", "(45.00)", "₹ 1,250", "120.00 DR" into signed minor units.
     * [decimalComma] reads "1.234,56" as 1234.56. Null if unreadable.
     */
    fun parseAmount(raw: String, currency: String, decimalComma: Boolean = false): Long? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        var sign = 1
        if (s.startsWith("(") && s.endsWith(")")) { sign = -1; s = s.substring(1, s.length - 1) }
        Regex("""\s*(dr|cr|-)\.?$""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            if (m.groupValues[1].lowercase() == "dr" || m.groupValues[1] == "-") sign = -sign
            s = s.substring(0, m.range.first)
        }
        s = s.replace(Regex("""[^\d.,\-+]"""), "")
        if (s.startsWith("-")) { sign = -sign; s = s.drop(1) } else if (s.startsWith("+")) s = s.drop(1)
        s = if (decimalComma) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        if (!Regex("""^\d*\.?\d+$""").matches(s) && !Regex("""^\d+\.$""").matches(s)) return null
        val minor = Money.parse(s, currency) ?: return null
        return sign * minor
    }

    // --- Rows -------------------------------------------------------------------------

    data class Options(
        val roles: List<Role>,
        /** Index of the header row; rows above it are skipped. -1 when the file has no header. */
        val headerRow: Int,
        val dateFormat: DateFormat,
        val currency: String,
        val decimalComma: Boolean = false,
        /** Card statements often list purchases as positive numbers. */
        val positiveIsExpense: Boolean = false,
    )

    fun toRows(table: List<List<String>>, o: Options): Parsed {
        fun col(r: Role) = o.roles.indexOf(r)
        val iDate = col(Role.DATE); val iDesc = col(Role.DESCRIPTION); val iNote = col(Role.NOTE); val iAmt = col(Role.AMOUNT)
        val iDebit = col(Role.DEBIT); val iCredit = col(Role.CREDIT); val iCat = col(Role.CATEGORY); val iType = col(Role.TYPE)
        val rows = ArrayList<Row>()
        val errors = ArrayList<RowError>()
        for ((k, cells) in table.withIndex()) {
            if (k <= o.headerRow) continue
            val line = k + 1
            fun get(i: Int) = if (i >= 0) cells.getOrNull(i)?.trim().orEmpty() else ""
            // Footer lines ("Closing balance", "Total") have no date: skip them quietly once the table has started.
            val rawDate = get(iDate)
            val date = parseDate(rawDate, o.dateFormat)
            if (date == null) {
                // Rows with no date and no amount are headings or totals; only a row that has money in it is worth a message.
                val hasMoney = listOf(iAmt, iDebit, iCredit).any { i -> i >= 0 && get(i).any(Char::isDigit) }
                if (rawDate.isNotBlank() && hasMoney) errors += RowError(line, "Unreadable date “$rawDate”")
                continue
            }
            var signed: Long?
            if (iDebit >= 0 || iCredit >= 0) {
                val out = get(iDebit).takeIf { it.isNotBlank() && it != "-" }?.let { parseAmount(it, o.currency, o.decimalComma) } ?: 0L
                val inn = get(iCredit).takeIf { it.isNotBlank() && it != "-" }?.let { parseAmount(it, o.currency, o.decimalComma) } ?: 0L
                if ((get(iDebit).isNotBlank() && get(iDebit) != "-" && parseAmount(get(iDebit), o.currency, o.decimalComma) == null) ||
                    (get(iCredit).isNotBlank() && get(iCredit) != "-" && parseAmount(get(iCredit), o.currency, o.decimalComma) == null)
                ) { errors += RowError(line, "Unreadable amount"); continue }
                signed = kotlin.math.abs(inn) - kotlin.math.abs(out)
            } else if (iAmt >= 0) {
                signed = parseAmount(get(iAmt), o.currency, o.decimalComma)
                if (signed == null) { errors += RowError(line, "Unreadable amount “${get(iAmt)}”"); continue }
                val t = get(iType).lowercase()
                signed = when {
                    Regex("""^(dr|debit|expense|withdrawal|out)""").containsMatchIn(t) -> -kotlin.math.abs(signed)
                    Regex("""^(cr|credit|income|deposit|in)""").containsMatchIn(t) -> kotlin.math.abs(signed)
                    o.positiveIsExpense -> -signed
                    else -> signed
                }
            } else {
                errors += RowError(line, "No amount column chosen"); continue
            }
            if (signed == 0L) continue
            rows += Row(
                line, date, if (signed < 0) "expense" else "income", kotlin.math.abs(signed),
                get(iDesc).replace(Regex("""\s+"""), " "), get(iNote), get(iCat),
            )
        }
        return Parsed(rows, errors)
    }

    // --- OFX and QIF ------------------------------------------------------------------

    /** OFX: every <STMTTRN> block, in SGML (tags left open) or XML form. */
    fun parseOfx(text: String, currency: String): Parsed {
        val rows = ArrayList<Row>()
        val errors = ArrayList<RowError>()
        val cur = Regex("""<CURDEF>\s*([A-Z]{3})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase()
        val acct = Regex("""<ACCTID>\s*([^<\r\n]+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val blocks = text.split(Regex("""<STMTTRN>""", RegexOption.IGNORE_CASE)).drop(1)
        for ((k, raw) in blocks.withIndex()) {
            val block = raw.split(Regex("""</STMTTRN>|</BANKTRANLIST>|</CCSTMTRS>""", RegexOption.IGNORE_CASE))[0]
            val f = HashMap<String, String>()
            for (m in Regex("""<([A-Z0-9.]+)>([^<\r\n]*)""", RegexOption.IGNORE_CASE).findAll(block)) {
                val v = decodeEntities(m.groupValues[2].trim())
                if (v.isNotEmpty()) f[m.groupValues[1].uppercase()] = v
            }
            val line = k + 1
            val dm = Regex("""^(\d{4})(\d{2})(\d{2})""").find(f["DTPOSTED"] ?: f["DTUSER"] ?: "")
            val date = dm?.let { runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull() }
            if (date == null) { errors += RowError(line, "No date"); continue }
            val amtRaw = f["TRNAMT"].orEmpty()
            val amount = parseAmount(amtRaw, cur ?: currency, Regex(""",\d{1,2}$""").containsMatchIn(amtRaw) && '.' !in amtRaw)
            if (amount == null || amount == 0L) { errors += RowError(line, "Unreadable amount “$amtRaw”"); continue }
            val name = f["NAME"] ?: f["PAYEE"] ?: ""
            val memo = f["MEMO"] ?: ""
            rows += Row(
                line, date, if (amount < 0) "expense" else "income", kotlin.math.abs(amount),
                name.ifEmpty { memo }.replace(Regex("""\s+"""), " "), if (name.isNotEmpty() && memo.isNotEmpty() && memo != name) memo else "", "",
                f["FITID"]?.let { "ofx:${acct.orEmpty()}:$it" },
            )
        }
        return Parsed(rows, errors, cur, acct)
    }

    /** QIF: records end with "^"; D date, T/U amount, P payee, M memo, L category. */
    fun parseQif(text: String, currency: String, preferUS: Boolean = false): Parsed {
        val records = ArrayList<Map<Char, String>>()
        var cur = LinkedHashMap<Char, String>()
        for (rawLine in text.split(Regex("""\r?\n"""))) {
            val line = rawLine.trimEnd()
            if (line.isEmpty() || line.startsWith("!")) continue
            if (line == "^") { if (cur.isNotEmpty()) records += cur; cur = LinkedHashMap(); continue }
            val code = line[0]
            if (code !in cur) cur[code] = line.drop(1).trim() // split lines (S, $, E) repeat; the first value is the transaction's
        }
        if (cur.isNotEmpty()) records += cur
        fun norm(d: String): String {
            val s = d.replace(Regex("""'\s*"""), "/").replace(Regex("""\s+"""), "")
            val p = s.split(Regex("""[/.\-]""")).toMutableList()
            if (p.size == 3 && p[2].length == 1) p[2] = "200" + p[2]
            return p.joinToString("/")
        }
        val fmt = detectDateFormat(records.mapNotNull { it['D']?.let(::norm) }, preferUS)
        val rows = ArrayList<Row>()
        val errors = ArrayList<RowError>()
        for ((k, r) in records.withIndex()) {
            val line = k + 1
            val date = parseDate(norm(r['D'].orEmpty()), fmt)
            if (date == null) { errors += RowError(line, "Unreadable date “${r['D'].orEmpty()}”"); continue }
            val amtRaw = r['T'] ?: r['U'] ?: ""
            val amount = parseAmount(amtRaw, currency, Regex(""",\d{1,2}$""").containsMatchIn(amtRaw) && '.' !in amtRaw)
            if (amount == null || amount == 0L) { errors += RowError(line, "Unreadable amount “$amtRaw”"); continue }
            val l = r['L']
            val transfer = l != null && Regex("""^\[.*]$""").matches(l)
            val cat = if (l != null && !transfer) l.replace(":", " · ") else ""
            rows += Row(
                line, date, if (amount < 0) "expense" else "income", kotlin.math.abs(amount),
                (r['P'] ?: r['M'] ?: "").replace(Regex("""\s+"""), " "),
                listOfNotNull(if (r['P'] != null) r['M'] else null, if (transfer) "Transfer $l" else null, r['N']?.let { "#$it" }).filter { it.isNotEmpty() }.joinToString(" · "),
                cat,
            )
        }
        return Parsed(rows, errors)
    }

    // --- HTML tables ("xls" files that are web pages) -------------------------------------

    /** The largest <table> in an HTML document, as rows of cell text. */
    fun parseHtmlTable(html: String): List<List<String>> {
        val tables = Regex("""<table\b[\s\S]*?</table>""", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
            .ifEmpty { listOf(html) }
        return tables.map { t ->
            Regex("""<tr\b[\s\S]*?(?=<tr\b|</table>|$)""", RegexOption.IGNORE_CASE).findAll(t).map { tr ->
                Regex("""<t[dh]\b[^>]*>([\s\S]*?)(?=<t[dh]\b|</tr>|$)""", RegexOption.IGNORE_CASE).findAll(tr.value).map { td ->
                    decodeEntities(td.groupValues[1].replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ").replace(Regex("""<[^>]+>"""), ""))
                        .replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()
                }.toList()
            }.filter { r -> r.any { it.isNotBlank() } }.toList()
        }.maxByOrNull { it.size } ?: emptyList()
    }

    // --- PDF statement text ------------------------------------------------------------------

    private val DATE_AT_START = Regex(
        """^(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}|\d{4}-\d{2}-\d{2}|\d{1,2}[\s\-]?[A-Za-z]{3}[\s\-,]*\d{2,4}|[A-Za-z]{3}\s+\d{1,2},?\s+\d{4})\b""",
    )
    private val MONEY = Regex("""(?<![\w.])(?:₹|Rs\.?|INR)?\s?(\(?-?\d{1,3}(?:,\d{2,3})*(?:,\d{3})?\.\d{2}\)?|\(?-?\d+\.\d{2}\)?)(\s?(?:Cr|CR|Dr|DR)\b)?(?![\w.])""")
    private val SKIP = Regex("""(?i)opening balance|closing balance|brought forward|carried forward|page \d+|statement of account|^total\b|balance as on""")
    private val DEBIT_WORDS = Regex("""(?i)\b(dr|debit|debited|withdrawal|wdl|atm|pos|purchase|paid|payment to|upi/dr|neft dr|imps/p2a|bill ?pay|emi|charges?|fee)\b""")
    private val CREDIT_WORDS = Regex("""(?i)\b(cr|credit|credited|deposit|salary|interest|refund|reversal|cashback|upi/cr|neft cr|payment received|received)\b""")

    /**
     * Rows from the text of a PDF statement. Each transaction starts with a date; narrations that wrap onto
     * more lines are joined. The last amount on a line is the running balance when there are two or more,
     * and its change from the previous line says whether money went out or came in. Without a balance,
     * "Cr"/"Dr" marks and words like "salary" or "ATM" decide, and spending is the default.
     */
    fun parseStatementText(text: String, currency: String, preferUS: Boolean = false): Parsed {
        data class Pending(val line: Int, val dateRaw: String, var desc: String, val amounts: List<Pair<Long, String?>>)
        val lines = text.split(Regex("""\r?\n""")).map { it.replace(' ', ' ').replace(Regex("""[ \t]+"""), " ").trim() }
        val items = ArrayList<Pending>()
        var openingBalance: Long? = null
        for ((i, l) in lines.withIndex()) {
            if (l.isEmpty()) continue
            if (Regex("""(?i)opening balance|brought forward""").containsMatchIn(l)) {
                MONEY.findAll(l).lastOrNull()?.let { m -> openingBalance = moneyValue(m, currency) }
                continue
            }
            val dm = DATE_AT_START.find(l)
            if (dm == null) {
                // A wrapped narration belongs to the open transaction, unless it is a page header or footer.
                val last = items.lastOrNull()
                if (last != null && !SKIP.containsMatchIn(l) && MONEY.findAll(l).none() && l.length < 120) last.desc = (last.desc + " " + l).trim()
                continue
            }
            if (SKIP.containsMatchIn(l)) continue
            val amounts = MONEY.findAll(l).mapNotNull { m -> moneyValue(m, currency)?.let { it to m.groupValues[2].trim().lowercase().ifEmpty { null } } }.toList()
            if (amounts.isEmpty()) continue
            var desc = l.substring(dm.range.last + 1)
            // Drop the amounts and a second (value) date from the narration.
            for (m in MONEY.findAll(desc).toList().reversed()) desc = desc.removeRange(m.range)
            desc = desc.replace(DATE_AT_START, "").replace(Regex("""^\s*\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}\s*"""), "").trim()
            items += Pending(i + 1, dm.value, desc, amounts)
        }
        val fmt = detectDateFormat(items.map { it.dateRaw }, preferUS)
        val hasBalance = items.count { it.amounts.size >= 2 } >= items.size / 2 && items.isNotEmpty()
        val rows = ArrayList<Row>()
        val errors = ArrayList<RowError>()
        var prevBalance = openingBalance
        for (p in items) {
            val date = parseDate(p.dateRaw, fmt)
            if (date == null) { errors += RowError(p.line, "Unreadable date “${p.dateRaw}”"); continue }
            val balance = if (hasBalance && p.amounts.size >= 2) p.amounts.last() else null
            val (amount, mark) = if (balance != null) p.amounts[p.amounts.size - 2] else p.amounts.first()
            val abs = kotlin.math.abs(amount)
            if (abs == 0L) continue
            val bal = balance?.let { (v, m) -> if (m == "dr") -kotlin.math.abs(v) else v }
            val type = when {
                bal != null && prevBalance != null && bal - prevBalance!! == abs -> "income"
                bal != null && prevBalance != null && prevBalance!! - bal == abs -> "expense"
                mark == "cr" -> "income"
                mark == "dr" -> "expense"
                amount < 0 -> "expense"
                CREDIT_WORDS.containsMatchIn(p.desc) && !DEBIT_WORDS.containsMatchIn(p.desc) -> "income"
                else -> "expense"
            }
            if (bal != null) prevBalance = bal
            rows += Row(p.line, date, type, abs, p.desc.replace(Regex("""\s+"""), " ").trim())
        }
        return Parsed(rows, errors)
    }

    private fun moneyValue(m: MatchResult, currency: String): Long? {
        var raw = m.groupValues[1]
        val neg = raw.startsWith("(") || raw.startsWith("-")
        raw = raw.trim('(', ')', '-')
        val v = Money.parse(raw.replace(",", ""), currency) ?: return null
        return if (neg) -v else v
    }

    // --- Payees from bank narrations ------------------------------------------------------

    /**
     * Turns a bank narration into a readable payee: "UPI/DR/412345678901/SWIGGY/YESB/swiggy@ybl/Payment" → "Swiggy",
     * "NEFT CR-HDFC0000001-ACME TECHNOLOGIES-…" → "Acme Technologies", "POS 4591XXXXXX1234 DMART AVENUE" → "Dmart Avenue".
     * The full narration is kept as the note, so nothing is lost.
     */
    fun payeeFrom(narration: String): String {
        val n = narration.trim().replace(Regex("""\s+"""), " ")
        if (n.isEmpty()) return n
        val u = n.uppercase()
        fun nice(s: String): String {
            val t = s.replace(Regex("""[_*]+"""), " ").replace(Regex("""\s+"""), " ").trim().trim('-', '/', '.', ' ')
            if (t.isEmpty()) return n
            return if (t == t.uppercase() || t == t.lowercase()) t.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } } else t
        }
        fun usable(p: String) = p.length >= 3 && p.any(Char::isLetter) && !Regex("""^[A-Z]{4}0[A-Z0-9]{6}$""").matches(p.uppercase()) &&
            !p.contains('@') && p.uppercase() !in setOf("UPI", "DR", "CR", "NEFT", "IMPS", "RTGS", "P2A", "P2M", "PAYMENT", "PAY", "TRANSFER", "MOB", "INB", "NA", "IFT", "BIL", "ONL")
        when {
            u.startsWith("UPI") -> {
                // UPI/DR/ref/NAME/BANK/vpa/note, UPI-NAME-vpa-IFSC-ref-note, UPI/P2M/ref/NAME/...
                val parts = n.split('/', '-').map { it.trim() }.filter { it.isNotEmpty() }
                parts.drop(1).firstOrNull { usable(it) && !it.all { c -> c.isDigit() } && it.length > 2 && !Regex("""(?i)^(yesb|hdfc|icic|sbin|utib|kkbk|axis|barb|punb|ybl|okaxis|oksbi|okicici|paytm)\w*$""").matches(it) }
                    ?.let { return nice(it) }
                Regex("""([a-z0-9.\-_]+)@[a-z]+""", RegexOption.IGNORE_CASE).find(n)?.let { return nice(it.groupValues[1].replace(Regex("""[.\-_]\d*$"""), "")) }
            }
            Regex("""^(NEFT|IMPS|RTGS)\b""").containsMatchIn(u) -> {
                val parts = n.split('/', '-', '*').map { it.trim() }.filter { it.isNotEmpty() }
                parts.drop(1).firstOrNull { usable(it) && !it.any(Char::isDigit) }?.let { return nice(it) }
            }
            Regex("""^(POS|PCD|VPS|ECOM|VIN)\b""").containsMatchIn(u) -> {
                return nice(n.replace(Regex("""^(?i)(POS|PCD|VPS|ECOM|VIN)\s*(PRCH|PUR|TXN)?\s*"""), "").replace(Regex("""\b[0-9X*]{8,}\b"""), "").replace(Regex("""\s\d{2}[/\-]\d{2}[/\-]?\d{0,4}.*$"""), ""))
            }
            Regex("""^(ATM|ATW|NWD|CASH WDL)""").containsMatchIn(u) -> return "ATM withdrawal"
            Regex("""\bINT(EREST)?\.?\s*(PD|PAID|CREDIT)|SB INT""").containsMatchIn(u) -> return "Interest"
            Regex("""^(ACH|NACH|ECS)\b""").containsMatchIn(u) -> {
                val parts = n.split('/', '-', '*', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                parts.drop(1).firstOrNull { usable(it) && !it.any(Char::isDigit) }?.let { return nice(it) }
            }
        }
        return nice(n.replace(Regex("""\b\d{9,}\b"""), "").replace(Regex("""\s{2,}"""), " ")).take(80)
    }

    // --- Duplicates and IDs ------------------------------------------------------------------

    data class Existing(val id: String, val type: String, val amount: Long, val date: LocalDate, val accountId: String)

    /**
     * Pairs each imported row with at most one existing entry on the same account with the same type and amount,
     * dated within ±2 days (closest first). Returns the matched existing ID per row index.
     */
    fun findDuplicates(rows: List<Row>, accountId: String, existing: List<Existing>): Map<Int, String> {
        val pool = existing.filter { it.accountId == accountId }
        val used = HashSet<String>()
        val out = HashMap<Int, String>()
        for ((i, r) in rows.withIndex()) {
            var best: Existing? = null
            var bestGap = 99L
            for (e in pool) {
                if (e.id in used || e.type != r.type || e.amount != r.amount) continue
                val gap = kotlin.math.abs(ChronoUnit.DAYS.between(e.date, r.date))
                if (gap <= 2 && gap < bestGap) { best = e; bestGap = gap }
            }
            if (best != null) { used += best.id; out[i] = best.id }
        }
        return out
    }

    /**
     * Stable fingerprints: identical rows in one file get #0, #1, … so a statement imported twice (or two
     * overlapping statements) maps to the same IDs. Same format as the web app.
     */
    fun fingerprints(rows: List<Row>): List<String> {
        val seen = HashMap<String, Int>()
        return rows.map { r ->
            r.ref ?: run {
                val key = "${r.date}|${r.type}|${r.amount}|${r.description.lowercase()}"
                val n = seen[key] ?: 0
                seen[key] = n + 1
                "$key#$n"
            }
        }
    }

    /** The ID a row gets in an account: the same on every device and in the web app. */
    fun importId(accountId: String, fingerprint: String): String = app.kosh.core.Ids.v5(app.kosh.core.Ids.RECURRING_NAMESPACE, "import:$accountId:$fingerprint")

    fun decodeEntities(s: String): String = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
}
