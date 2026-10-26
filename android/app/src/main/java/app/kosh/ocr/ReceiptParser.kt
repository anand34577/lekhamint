package app.kosh.ocr

import java.math.BigDecimal
import java.time.LocalDate

/** One printed line of a receipt as read, top to bottom; [height] is the text size in pixels. */
data class OcrLine(val text: String, val top: Float, val height: Float, val confidence: Float = 1f)

/** What a receipt says: the amount paid, the day and the shop. Any of them may be missing. */
data class ReceiptGuess(
    val total: BigDecimal?,
    val date: LocalDate?,
    val merchant: String?,
    /** ISO code when the receipt shows a currency (₹, Rs, $, €, £, PLN…). */
    val currency: String? = null,
    /** Why the total was chosen, e.g. the line "GRAND TOTAL 1,234.00". */
    val totalLine: String? = null,
) {
    val isEmpty: Boolean get() = total == null && date == null && merchant == null
}

/**
 * Reads the useful parts of a receipt from its lines of text. The total is the amount on the strongest
 * "total" line (Grand Total over Net Amount over Total; never Sub Total, tax, savings or change), falling back
 * to the largest amount printed more than once. Works for Indian receipts (₹, Rs., GST lines, lakh grouping)
 * and for most others (decimal commas, SUMA, TOTALE, Summe…).
 */
object ReceiptParser {
    private val MONEY = Regex("""(?<![\d.,/:-])(?:₹|Rs\.?|INR|\$|€|£|PLN|EUR|USD)?\s?(\d{1,3}(?:[, ](?=\d{2,3}\b)\d{2,3})+(?:[.,]\d{1,2})?|\d+[.,]\d{1,2}|\d+)(?![\d%])(?!\s?%)(?:\s?(?:[A-Z]{1,2}\b))?""")

    private val STRONG = listOf(
        10 to Regex("""(?i)grand\s*total|g\.?\s*total|total\s*amount\s*payable|net\s*payable|amount\s*payable|total\s*payable"""),
        9 to Regex("""(?i)net\s*amount|net\s*amt|bill\s*amount|bill\s*total|total\s*amount|total\s*amt|amount\s*due|balance\s*due|total\s*due|to\s*pay|invoice\s*total|net\s*total|total\s*to\s*pay|total\s*ttc|\bsuma\b|\btotale\b|\bsumme\b|gesamt|\bmontant\b|importe\s*total|valor\s*total"""),
        7 to Regex("""(?i)\btotal\b|\btot\b"""),
        4 to Regex("""(?i)amount\s*paid|\bpaid\b|card\s*(sale|payment)|\bupi\b|importo\s*pagato|\bpayment\b"""),
    )
    private val NOT_TOTAL = Regex(
        """(?i)sub\s*-?\s*total|total\s*(qty|quantity|items?|savings?|discount|tax|gst|vat|mrp|weight|pcs|nos)|item\s*total|""" +
            """\b(change|tendered|cash\s*given|you\s*saved|saving|discount|round(ed)?\s*off|qty|cgst|sgst|igst|utgst|cess|vat|ptu|mrp|tip|balance\s*returned)\b""",
    )
    private val SKIP_MERCHANT = Regex(
        """(?i)tax\s*invoice|invoice|receipt|\bbill\b|cash\s*memo|gstin|\bgst\b|\bcin\b|fssai|\bpan\b|phone|\btel\b|mob(ile)?|www\.|http|@|""" +
            """welcome|thank|order|table|\bdate\b|\btime\b|cashier|counter|\bpos\b|terminal|duplicate|original|customer|paragon|fiskal|documento|scontrino|""" +
            """road|\brd\b|street|\bst\.|nagar|floor|sector|\bnear\b|opp\.|plot|\bno\.|\bnip\b|\bvat\b|\bul\.|""" +
            """^\W*$""",
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parse(lines: List<OcrLine>, today: LocalDate = LocalDate.now(), monthFirst: Boolean = false): ReceiptGuess {
        val text = lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        val (total, why) = total(lines)
        return ReceiptGuess(total, date(text, today, monthFirst), merchant(lines), currency(text.joinToString(" ")), why)
    }

    // --- Amount ---------------------------------------------------------------------------

    data class Money(val value: BigDecimal, val decimals: Boolean)

    /** Every amount on a line, left to right. "1,23,456.00" and "8,27" (decimal comma) both read. */
    fun amounts(line: String): List<Money> = MONEY.findAll(line).mapNotNull { m ->
        val raw = m.groupValues[1]
        // A long run of digits with no separators is an ID, a phone number or a bill number, not money.
        if (raw.all(Char::isDigit) && raw.length > 6) return@mapNotNull null
        val commaDecimal = Regex("""^\d+,\d{1,2}$""").matches(raw) || Regex("""\.\d{3},\d{1,2}$""").containsMatchIn(raw)
        val clean = if (commaDecimal) raw.replace(".", "").replace(" ", "").replace(',', '.') else raw.replace(",", "").replace(" ", "")
        val v = clean.toBigDecimalOrNull() ?: return@mapNotNull null
        Money(v, clean.contains('.'))
    }.toList()

    private fun total(lines: List<OcrLine>): Pair<BigDecimal?, String?> {
        data class Cand(val value: BigDecimal, val score: Int, val index: Int, val line: String)
        val cands = ArrayList<Cand>()
        for ((i, l) in lines.withIndex()) {
            val t = l.text
            if (NOT_TOTAL.containsMatchIn(t) && STRONG.take(2).none { (_, re) -> re.containsMatchIn(t) }) continue
            val score = STRONG.firstOrNull { (_, re) -> re.containsMatchIn(t) }?.first ?: continue
            // The amount is on the same line (the rightmost one) or, on some receipts, on the next line.
            val here = amounts(t).lastOrNull()
            val next = lines.getOrNull(i + 1)?.takeIf { STRONG.none { (_, re) -> re.containsMatchIn(it.text) } }?.let { amounts(it.text).lastOrNull() }
            val m = here ?: next ?: continue
            if (m.value.signum() <= 0) continue
            // Bigger printed text is usually the total.
            val sizeBonus = if (l.height >= lines.map { it.height }.sorted().let { it[it.size * 3 / 4] }) 1 else 0
            cands += Cand(m.value, score * 10 + sizeBonus + (if (m.decimals) 1 else 0), i, t)
        }
        cands.maxWithOrNull(compareBy<Cand>({ it.score }, { it.index }, { it.value }))?.let { return it.value to it.line }
        // No total line: the largest amount with decimals printed at least twice (total and payment), else the largest one.
        val all = lines.filter { !NOT_TOTAL.containsMatchIn(it.text) }.flatMap { amounts(it.text) }.filter { it.decimals && it.value.signum() > 0 }
        val repeated = all.groupBy { it.value.stripTrailingZeros() }.filter { it.value.size >= 2 }.maxByOrNull { it.key }?.value?.first()?.value
        val fallback = repeated ?: all.maxOfOrNull { it.value }
        return fallback to null
    }

    // --- Date ---------------------------------------------------------------------------------

    fun date(text: List<String>, today: LocalDate, monthFirst: Boolean = false): LocalDate? {
        val earliest = today.minusYears(2)
        val latest = today.plusDays(1)
        fun ok(d: LocalDate?) = d != null && !d.isBefore(earliest) && !d.isAfter(latest)
        for (line in text) {
            Regex("""\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b""").findAll(line).forEach { m ->
                make(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.takeIf(::ok)?.let { return it }
            }
            Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""").findAll(line).forEach { m ->
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = year(m.groupValues[3])
                val first = if (monthFirst && a <= 12) make(y, a, b) else make(y, b, a)
                val second = if (monthFirst) make(y, b, a) else make(y, a, b)
                (first?.takeIf(::ok) ?: second?.takeIf(::ok))?.let { return it }
            }
            Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/.]*([a-z]{3})[a-z]*[\s\-/.,']*(\d{2,4})\b""").findAll(line).forEach { m ->
                val mon = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
                if (mon > 0) make(year(m.groupValues[3]), mon, m.groupValues[1].toInt())?.takeIf(::ok)?.let { return it }
            }
            Regex("""(?i)\b([a-z]{3})[a-z]*[\s\-.]+(\d{1,2}),?[\s\-.]+(\d{4})\b""").findAll(line).forEach { m ->
                val mon = MONTHS.indexOf(m.groupValues[1].lowercase()) + 1
                if (mon > 0) make(m.groupValues[3].toInt(), mon, m.groupValues[2].toInt())?.takeIf(::ok)?.let { return it }
            }
        }
        return null
    }

    private fun year(s: String) = s.toInt().let { if (s.length == 2) 2000 + it else it }
    private fun make(y: Int, m: Int, d: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    // --- Shop ------------------------------------------------------------------------------------

    /** The shop's name: large text near the top that is not an address, a tax number or a heading like "TAX INVOICE". */
    fun merchant(lines: List<OcrLine>): String? {
        if (lines.isEmpty()) return null
        val top = lines.take(maxOf(6, lines.size / 4))
        val heights = lines.map { it.height }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(1f)
        val best = top.withIndex().mapNotNull { (i, l) ->
            val t = l.text.trim()
            val letters = t.count(Char::isLetter)
            if (letters < 3 || letters < t.length / 2 || SKIP_MERCHANT.containsMatchIn(t) || amounts(t).any { it.decimals }) return@mapNotNull null
            val score = (l.height / median) * 2 - i * 0.35f + (if (t.length in 3..30) 0.5f else 0f) + l.confidence
            t to score
        }.maxByOrNull { it.second }?.first ?: return null
        return tidy(best)
    }

    /** "DMART AVENUE SUPERMARTS" → "Dmart Avenue Supermarts"; mixed case is left alone. */
    fun tidy(s: String): String {
        val t = s.replace(Regex("""[|_*~]+"""), " ").replace(Regex("""\s+"""), " ").trim().trim('-', '.', ',', ':')
        return if (t == t.uppercase()) t.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } } else t
    }

    // --- Currency -----------------------------------------------------------------------------------

    fun currency(all: String): String? = when {
        Regex("""₹|\bRs\.?\s?\d|\bINR\b|\bGSTIN\b|\bCGST\b""").containsMatchIn(all) -> "INR"
        Regex("""\bPLN\b|\bzł""").containsMatchIn(all) -> "PLN"
        all.contains('€') || Regex("""\bEUR\b""").containsMatchIn(all) -> "EUR"
        all.contains('£') || Regex("""\bGBP\b""").containsMatchIn(all) -> "GBP"
        Regex("""\$\s?\d|\bUSD\b""").containsMatchIn(all) -> "USD"
        else -> null
    }
}
