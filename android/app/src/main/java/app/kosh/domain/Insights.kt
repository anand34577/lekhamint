package app.kosh.domain

import app.kosh.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Looking ahead and spotting patterns: plain-language insights, repeat payments found in past spending,
 * a cash forecast and "safe to spend". The same rules as the web app (web/src/lib/insights.ts,
 * subscriptions.ts, forecast.ts). Pure functions, so they are easy to test.
 */

enum class Tone { WARN, GOOD, INFO }

data class Insight(val tone: Tone, val title: String, val body: String, val categoryId: String? = null)

object Insights {
    private fun pct(x: Double) = (abs(x) * 100).roundToInt()

    /**
     * [now] is spending by top-level category this month so far; [prev] the same days of last month,
     * so a part month compares fairly. Returns at most four, warnings first.
     */
    fun of(
        now: Map<String?, Long>, prev: Map<String?, Long>, income: Long, expense: Long, prevExpense: Long,
        dayOfMonth: Int, name: (String?) -> String,
    ): List<Insight> {
        val scale = maxOf(expense, prevExpense, 1).toDouble()
        val changes = ArrayList<Pair<Insight, Long>>()
        for (id in now.keys + prev.keys) {
            val a = now[id] ?: 0L
            val b = prev[id] ?: 0L
            val diff = a - b
            // Uncategorised has its own prompt. Small bases and small moves are noise, not a trend.
            if (id == null || b < scale * 0.03 || abs(diff) < scale * 0.05) continue
            val ratio = diff.toDouble() / b
            if (abs(ratio) < 0.25) continue
            val n = name(id)
            val i = if (ratio > 0) Insight(Tone.WARN, "$n is up ${pct(ratio)}%", "Compared with the same days last month.", id)
            else Insight(Tone.GOOD, "$n is down ${pct(ratio)}%", "Compared with the same days last month.", id)
            changes += i to abs(diff)
        }
        changes.sortByDescending { it.second }
        val out = ArrayList<Insight>()
        out += changes.filter { it.first.tone == Tone.WARN }.take(2).map { it.first }
        out += changes.filter { it.first.tone == Tone.GOOD }.take(1).map { it.first }
        if (income > 0) {
            val kept = (income - expense).toDouble() / income
            out += if (kept >= 0) {
                Insight(
                    if (kept >= 0.2) Tone.GOOD else Tone.INFO, "You kept ${pct(kept)}% of your income",
                    if (kept >= 0.2) "Saving a fifth or more is a healthy habit." else "Many people aim to keep at least 20%.",
                )
            } else {
                Insight(Tone.WARN, "Spending is above income this month", "You have spent ${pct(-kept)}% more than you earned so far.")
            }
        }
        // Pace needs a real base: last month's spending so far must be at least a fifth of this month's.
        if (dayOfMonth >= 5 && prevExpense >= expense * 0.2 && expense > 0) {
            val ahead = expense.toDouble() / prevExpense - 1
            if (abs(ahead) >= 0.1) {
                out += Insight(
                    if (ahead > 0) Tone.WARN else Tone.GOOD,
                    "Spending is ${if (ahead > 0) "ahead of" else "behind"} last month's pace",
                    "${pct(ahead)}% ${if (ahead > 0) "more" else "less"} than by this day last month.",
                )
            }
        }
        return out.sortedBy { it.tone.ordinal }.take(4)
    }
}

// ---------------------------------------------------------------------------------------------
// Repeat payments

enum class Cadence(val label: String, val minGap: Int, val maxGap: Int, val perYear: Int, val ruleFrequency: String, val ruleEvery: Int) {
    WEEKLY("Every week", 6, 8, 52, "weekly", 1),
    MONTHLY("Every month", 26, 35, 12, "monthly", 1),
    QUARTERLY("Every 3 months", 85, 97, 4, "monthly", 3),
    YEARLY("Every year", 350, 380, 1, "yearly", 1),
}

data class Subscription(
    val key: String,
    val payee: String,
    val cadence: Cadence,
    /** Typical amount (the median of recent payments, or the new price after a change). */
    val amount: Long,
    val lastAmount: Long,
    val lastDate: LocalDate,
    val nextDate: LocalDate,
    val yearly: Long,
    val count: Int,
    val categoryId: String?,
    val accountId: String,
    /** The latest payment differs from the earlier typical amount by more than 5%: (from, to). */
    val priceChange: Pair<Long, Long>?,
    /** No payment for well past the expected date: perhaps cancelled. */
    val stale: Boolean,
)

object Subscriptions {
    /** A payee reduced to its stable part: "AMAZON PRIME*2K4" and "Amazon Prime" match. */
    fun payeeKey(p: String): String =
        p.lowercase().replace(Regex("[*#].*$"), "").replace(Regex("\\d+"), "").replace(Regex("[^a-z\\u0900-\\u097F]+"), " ").trim()

    private fun median(xs: List<Long>): Long {
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else ((s[m - 1] + s[m]) / 2.0).roundToLong()
    }

    /**
     * Repeating expenses among [txs]: the same payee at a steady interval for roughly the same amount.
     * Payees already handled by a recurring rule ([ruled] holds their keys) are left out.
     */
    fun detect(txs: List<TransactionEntity>, today: LocalDate, ruled: Set<String> = emptySet()): List<Subscription> {
        val groups = LinkedHashMap<String, MutableList<TransactionEntity>>()
        for (t in txs) {
            val p = t.payee ?: continue
            if (t.recurringRuleId != null || t.type != "expense") continue
            val k = payeeKey(p)
            if (k.length < 3 || k in ruled) continue
            groups.getOrPut(k) { ArrayList() } += t
        }
        val out = ArrayList<Subscription>()
        for ((key, list) in groups) {
            if (list.size < 3) continue
            val sorted = list.sortedBy { it.localDate }
            // One payment per day at most (a refund and re-charge on the same day count once).
            val days = sorted.filterIndexed { i, t -> i == 0 || t.localDate != sorted[i - 1].localDate }
            if (days.size < 3) continue
            val dates = days.map { LocalDate.parse(it.localDate) }
            val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
            val gap = median(gaps)
            val c = Cadence.entries.firstOrNull { gap in it.minGap..it.maxGap } ?: continue
            // Most gaps must fit the cadence (a missed payment is allowed once in a while).
            val fit = gaps.count { it >= c.minGap && it <= c.maxGap * 2 + 2 }
            if (fit < gaps.size * 0.75) continue
            val amounts = days.map { it.amount }
            val typical = median(amounts.takeLast(6))
            // Steady amounts only: utility bills vary more and are left out.
            if (amounts.count { abs(it - typical) <= typical * 0.25 } < amounts.size * 0.7) continue
            val last = days.last()
            val before = amounts.dropLast(1).takeLast(6)
            val prev = if (before.size >= 2) median(before) else null
            val change = prev?.takeIf { abs(last.amount - it) > it * 0.05 }?.let { it to last.amount }
            val lastDate = dates.last()
            val next = when (c) {
                Cadence.WEEKLY -> lastDate.plusDays(7)
                Cadence.MONTHLY -> lastDate.plusMonths(1)
                Cadence.QUARTERLY -> lastDate.plusMonths(3)
                Cadence.YEARLY -> lastDate.plusYears(1)
            }
            val amount = if (change != null) last.amount else typical
            out += Subscription(
                key, last.payee!!, c, amount, last.amount, lastDate, next, amount * c.perYear, days.size, last.categoryId, last.accountId,
                change, ChronoUnit.DAYS.between(next, today) > maxOf(10, (c.maxGap - c.minGap) * 2),
            )
        }
        return out.sortedWith(compareBy<Subscription> { it.stale }.thenByDescending { it.yearly })
    }
}

// ---------------------------------------------------------------------------------------------
// Cash forecast

/** A known movement on a date: income (positive) or a bill, EMI or instalment (negative). */
data class FlowEvent(val date: LocalDate, val amount: Long, val title: String)

data class DailySpend(val mean: Double, val sd: Double, val days: Int)

data class ForecastPoint(val date: LocalDate, val expected: Long, val low: Long, val high: Long)

data class Forecast(
    val points: List<ForecastPoint>,
    val lowest: ForecastPoint,
    /** First date the cautious line drops below zero, if it does. */
    val shortBy: LocalDate?,
    val knownIn: Long,
    val knownOut: Long,
    val everyday: Long,
)

/** What "safe to spend" is measured against, in order of preference. */
enum class SafeBasis(val label: String, val startLabel: String) {
    /** An overall monthly budget: what is left of it. */
    BUDGET("to stay within your monthly budget", "Budget left"),
    /** This month's income so far, less this month's spending. */
    INCOME("from this month's income", "Income left"),
    /** Cash, bank and wallet balances (when there is no budget and no income yet). */
    CASH("from your cash and bank balance", "You have"),
}

/** What can be spent each day until [until] without missing a known bill. */
data class SafeToSpend(
    val basis: SafeBasis,
    val until: LocalDate,
    val days: Int,
    /** What the sum starts from: budget left, income left or cash. */
    val start: Long,
    val incomeIn: Long,
    val billsOut: Long,
    /** After the known items; can be negative. Never more than the cash actually there. */
    val free: Long,
    val perDay: Long,
    /** Usual everyday spending per day, to compare with. */
    val usualPerDay: Long,
    /** True when [until] is the next payday rather than the end of the month. */
    val toPayday: Boolean,
    /** True when [free] was cut down to the money in cash and bank accounts. */
    val cappedByCash: Boolean = false,
)

object CashForecast {
    /** Usual everyday spending, from daily totals over [days] days starting at [from] (days with nothing count as 0). */
    fun dailySpend(totals: Map<LocalDate, Long>, from: LocalDate, days: Int): DailySpend {
        val vals = (0 until days).map { (totals[from.plusDays(it.toLong())] ?: 0L).toDouble() }
        val mean = vals.sum() / maxOf(1, days)
        val sd = sqrt(vals.sumOf { (it - mean) * (it - mean) } / maxOf(1, days - 1))
        return DailySpend(mean, sd, days)
    }

    /** Spending that a known bill, EMI, deposit or debt does not already cover. */
    fun isEveryday(t: TransactionEntity): Boolean =
        t.type == "expense" && t.recurringRuleId == null && t.loanId == null && t.depositId == null && t.debtId == null &&
            !t.isAdjustment && !t.excludedFromReports && t.status != "planned" && !t.deleted

    /**
     * Day by day from [today] for [horizon] days. Overdue [events] count on day 0. The range is an 80% band
     * (±1.28 standard deviations of the summed everyday spending), widening with time.
     */
    fun forecast(start: Long, events: List<FlowEvent>, spend: DailySpend, today: LocalDate, horizon: Int): Forecast {
        val byDay = HashMap<Int, Long>()
        var knownIn = 0L
        var knownOut = 0L
        for (e in events) {
            val d = maxOf(0, ChronoUnit.DAYS.between(today, e.date).toInt())
            if (d > horizon) continue
            byDay[d] = (byDay[d] ?: 0L) + e.amount
            if (e.amount > 0) knownIn += e.amount else knownOut -= e.amount
        }
        val points = ArrayList<ForecastPoint>(horizon + 1)
        var known = 0L
        for (t in 0..horizon) {
            known += byDay[t] ?: 0L
            val expected = (start + known - spend.mean * t).roundToLong()
            val band = 1.28 * spend.sd * sqrt(t.toDouble())
            points += ForecastPoint(today.plusDays(t.toLong()), expected, (expected - band).roundToLong(), (expected + band).roundToLong())
        }
        val lowest = points.minBy { it.expected }
        return Forecast(points, lowest, points.firstOrNull { it.low < 0 }?.date, knownIn, knownOut, (spend.mean * horizon).roundToLong())
    }

    /**
     * Safe to spend per day: cash now, plus income and minus bills due up to the next payday (when one is
     * known within 35 days) or the end of the month, spread over the days left. The day of the payday itself
     * is not counted: that income pays for what comes after.
     */
    fun safeToSpend(cash: Long, events: List<FlowEvent>, spend: DailySpend, today: LocalDate): SafeToSpend {
        val payday = events.filter { it.amount > 0 && it.date.isAfter(today) && !it.date.isAfter(today.plusDays(35)) }.minOfOrNull { it.date }
        val until = payday?.minusDays(1) ?: today.withDayOfMonth(today.lengthOfMonth())
        val days = daysTo(today, until)
        val inside = events.filter { !it.date.isAfter(until) }
        val incomeIn = inside.filter { it.amount > 0 }.sumOf { it.amount }
        val billsOut = -inside.filter { it.amount < 0 }.sumOf { it.amount }
        val free = cash + incomeIn - billsOut
        return SafeToSpend(SafeBasis.CASH, until, days, cash, incomeIn, billsOut, free, perDay(free, days), spend.mean.roundToLong(), payday != null)
    }

    /**
     * Safe to spend per day from an allowance for the rest of this month ([basis] BUDGET: what is left of an overall
     * monthly budget; INCOME: this month's income less its spending). Bills still due this month come off first.
     * It is never more than the cash and bank balance can cover after those bills.
     */
    fun safeFromAllowance(basis: SafeBasis, allowance: Long, cash: Long, events: List<FlowEvent>, spend: DailySpend, today: LocalDate): SafeToSpend {
        val until = today.withDayOfMonth(today.lengthOfMonth())
        val days = daysTo(today, until)
        val inside = events.filter { !it.date.isAfter(until) }
        val billsOut = -inside.filter { it.amount < 0 }.sumOf { it.amount }
        val incomeIn = inside.filter { it.amount > 0 }.sumOf { it.amount }
        val fromAllowance = allowance - billsOut
        val fromCash = cash + incomeIn - billsOut
        val free = minOf(fromAllowance, fromCash)
        return SafeToSpend(basis, until, days, allowance, 0, billsOut, free, perDay(free, days), spend.mean.roundToLong(), false, cappedByCash = fromCash < fromAllowance)
    }

    /**
     * Picks the most meaningful measure: an overall monthly budget when there is one, else this month's income
     * when some has come in, else the cash balance.
     */
    fun safe(budgetLeft: Long?, incomeLeft: Long?, cash: Long, events: List<FlowEvent>, spend: DailySpend, today: LocalDate): SafeToSpend = when {
        budgetLeft != null -> safeFromAllowance(SafeBasis.BUDGET, budgetLeft, cash, events, spend, today)
        incomeLeft != null -> safeFromAllowance(SafeBasis.INCOME, incomeLeft, cash, events, spend, today)
        else -> safeToSpend(cash, events, spend, today)
    }

    private fun daysTo(today: LocalDate, until: LocalDate) = (ChronoUnit.DAYS.between(today, until).toInt() + 1).coerceAtLeast(1)
    private fun perDay(free: Long, days: Int) = if (free > 0) free / days else 0
}
