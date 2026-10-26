package app.kosh.domain

import app.kosh.core.Calculator
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One-line entry: turns "450 swiggy dinner yesterday #work @hdfc" into a draft transaction.
 * The same rules as the web app's quick add (web/src/lib/quickadd.ts), so a line means the
 * same thing everywhere. Pure: the caller resolves the account, tags and category.
 */
object QuickEntry {
    data class Draft(
        /** Major units as typed ("450.5"), or null when no amount was found. */
        val amount: BigDecimal?,
        val type: String,
        val date: LocalDate,
        /** Tag names without "#". */
        val tags: List<String>,
        /** Text after "@", matched against account names by the caller. */
        val account: String?,
        /** What is left: the payee or description. */
        val text: String,
        /** True when the line named a day ("yesterday", "12 oct"), so the time should not be "now". */
        val datedByText: Boolean = false,
    )

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val INCOME_WORDS = setOf("income", "received", "receive", "got", "earned", "refund", "refunded", "cashback", "salary")
    private val FILLER = setOf("on", "for", "at", "paid", "spent", "spend", "to", "from", "via", "with", "rs", "rs.", "inr", "rupees", "bucks")
    private val ORDINAL = Regex("""^(\d{1,2})(st|nd|rd|th)?$""")
    private val AMOUNT = Regex("""^([+]?)(?:₹|\$|€|£|¥|rs\.?|inr)?([\d.,]+(?:[+\-*/×÷x][\d.,]+)*)(k)?$""", RegexOption.IGNORE_CASE)
    private val DAY_MONTH = Regex("""^(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?$""")

    private fun month(s: String): Int = if (s.length >= 3) MONTHS.indexOf(s.take(3).lowercase()) else -1

    /** A calendar date on or before [today] for day/month, or in [year] when given. */
    private fun pastDate(day: Int, mon: Int, today: LocalDate, year: Int? = null): LocalDate? {
        if (mon !in 0..11 || day !in 1..31) return null
        val make = { y: Int -> runCatching { LocalDate.of(y, mon + 1, day) }.getOrNull() }
        val d = make(year ?: today.year) ?: return null
        return if (year == null && d.isAfter(today)) make(today.year - 1) else d
    }

    fun parse(input: String, today: LocalDate): Draft {
        var amount: BigDecimal? = null
        var type = "expense"
        var date = today
        var dated = false
        var account: String? = null
        val tags = ArrayList<String>()
        val rest = ArrayList<String>()
        val words = input.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val lw = w.lowercase()
            val next = words.getOrNull(i + 1)?.lowercase()
            i++
            when {
                w.startsWith("#") && w.length > 1 -> { tags += w.drop(1); continue }
                w.startsWith("@") && w.length > 1 -> { account = w.drop(1); continue }
                lw == "today" -> { date = today; dated = true; continue }
                lw == "yesterday" || lw == "yday" -> { date = today.minusDays(1); dated = true; continue }
                // "3 days ago"
                Regex("""^\d{1,3}$""").matches(w) && (next == "days" || next == "day") && words.getOrNull(i + 1)?.lowercase() == "ago" -> {
                    date = today.minusDays(w.toLong()); dated = true; i += 2; continue
                }
            }
            // Weekday names: the most recent one (today counts).
            val wd = DAYS.indexOf(lw.take(3))
            if (wd >= 0 && Regex("""^(sun|mon|tue|wed|thu|fri|sat)[a-z]*$""").matches(lw) && (lw.length == 3 || lw.endsWith("day"))) {
                val target = DayOfWeek.of(wd + 1)
                val back = (today.dayOfWeek.value - target.value + 7) % 7
                date = today.minusDays(back.toLong()); dated = true; continue
            }
            if (Regex("""^\d{4}-\d{2}-\d{2}$""").matches(w)) {
                runCatching { LocalDate.parse(w) }.getOrNull()?.let { date = it; dated = true; continue }
            }
            // 24/10 or 24/10/2026 (day first), only once an amount was seen ("10/2" could be a sum otherwise).
            if (amount != null) {
                val m = DAY_MONTH.matchEntire(w)
                if (m != null) {
                    val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
                    val d = pastDate(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, today, y)
                    if (d != null) { date = d; dated = true; continue }
                }
            }
            // "12 oct" / "oct 12"
            if (amount != null && ORDINAL.matches(lw) && next != null && Regex("""^[a-z]+$""").matches(next) && month(next) >= 0 && MONTHS.any { next.startsWith(it) }) {
                pastDate(ORDINAL.matchEntire(lw)!!.groupValues[1].toInt(), month(next), today)?.let { date = it; dated = true; i++; continue }
            }
            if (Regex("""^[a-z]{3,9}$""").matches(lw) && MONTHS.any { lw.startsWith(it) } && next != null && ORDINAL.matches(next)) {
                pastDate(ORDINAL.matchEntire(next)!!.groupValues[1].toInt(), month(lw), today)?.let { date = it; dated = true; i++; continue }
            }
            // The first number-like token is the amount: "450", "₹1,250.50", "+500", "230+40", "2k".
            if (amount == null) {
                val m = AMOUNT.matchEntire(w)
                if (m != null && m.groupValues[2].any(Char::isDigit)) {
                    val expr = m.groupValues[2].replace(",", "")
                        .replace('-', '−').replace('*', '×').replace('x', '×').replace('/', '÷')
                    var v = Calculator.evaluate(expr)
                    if (v != null && m.groupValues[3].isNotEmpty()) v = v.multiply(BigDecimal(1000))
                    if (v != null && v.signum() > 0) {
                        // "450", not "4.5E+2": keep a plain scale so the amount reads and compares naturally.
                        amount = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
                        if (m.groupValues[1] == "+") type = "income"
                        continue
                    }
                }
            }
            if (lw in INCOME_WORDS) {
                type = "income"
                if (lw == "refund" || lw == "cashback" || lw == "salary") rest += w
                continue
            }
            rest += w
        }
        // Drop filler words at the edges ("spent 200 on coffee" → "coffee").
        while (rest.isNotEmpty() && rest.first().lowercase() in FILLER) rest.removeAt(0)
        while (rest.isNotEmpty() && rest.last().lowercase() in FILLER) rest.removeAt(rest.size - 1)
        return Draft(amount, type, date, tags, account, rest.joinToString(" "), dated)
    }

    /** The best match for [query] among [names]: exact, then prefix, then contains (case-insensitive). */
    fun <T> bestMatch(items: List<T>, query: String?, name: (T) -> String): T? {
        val q = query?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        return items.firstOrNull { name(it).lowercase() == q }
            ?: items.firstOrNull { name(it).lowercase().startsWith(q) }
            ?: items.firstOrNull { name(it).lowercase().contains(q) }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Everyday words for the default categories: "450 lunch" files under Dining out. Names the person changed fall through. */
    private val WORDS: Map<String, String> = listOf(
        "Dining out" to "lunch dinner breakfast brunch restaurant meal biryani pizza burger",
        "Food delivery" to "swiggy zomato",
        "Coffee & snacks" to "coffee tea chai snack snacks starbucks",
        "Groceries" to "grocery vegetables veggies milk fruits bigbasket blinkit zepto dmart kirana",
        "Fuel" to "petrol diesel cng",
        "Taxi & ride-hailing" to "uber ola cab taxi auto rickshaw rapido",
        "Public transport" to "metro bus",
        "Parking & tolls" to "parking toll fastag",
        "Flights & trains" to "flight flights train irctc",
        "Hotels" to "hotel",
        "Movies & events" to "movie movies cinema concert",
        "Subscriptions" to "netflix spotify hotstar subscription",
        "Pharmacy" to "medicine medicines chemist",
        "Doctor" to "clinic consultation",
        "Fitness" to "gym",
        "Salon & grooming" to "haircut salon barber",
        "Internet" to "wifi broadband",
        "Mobile" to "recharge",
        "Clothing" to "clothes shirt shoes",
        "Gifts" to "gift",
        "Charity" to "donation",
        "Books" to "book",
    ).flatMap { (cat, words) -> words.split(" ").map { it to cat } }.toMap()

    /** A category whose name appears as a word in [text] (longest name wins), else one an everyday word points to. */
    fun <T> categoryFromText(items: List<T>, text: String, name: (T) -> String): T? {
        val words = " " + norm(text) + " "
        return items.sortedByDescending { name(it).length }
            .firstOrNull { name(it).length > 2 && words.contains(" " + norm(name(it)) + " ") }
            ?: norm(text).split(" ").firstNotNullOfOrNull { w -> WORDS[w]?.let { cat -> items.firstOrNull { norm(name(it)) == norm(cat) } } }
    }

    /** The word in [text] that chose category [name]: the name itself, or an everyday word such as "lunch". */
    fun wordFor(text: String, name: String): String {
        if ((" " + norm(text) + " ").contains(" " + norm(name) + " ")) return name
        return norm(text).split(" ").firstOrNull { w -> WORDS[w]?.let { norm(it) == norm(name) } == true } ?: name
    }

    /** [text] without the words of [name]: "groceries big bazaar" − "Groceries" = "big bazaar". */
    fun withoutWords(text: String, name: String): String {
        val drop = norm(name).split(" ").filter { it.isNotEmpty() }.toSet()
        return text.split(Regex("""\s+""")).filter { norm(it) !in drop }.joinToString(" ").trim()
    }
}
