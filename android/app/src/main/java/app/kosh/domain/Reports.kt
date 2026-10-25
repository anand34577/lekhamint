package app.kosh.domain

import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.ReportRow
import java.time.LocalDate
import java.time.YearMonth

/** Income and spending for one currency. Map keys are category IDs; null = uncategorised. */
data class CurrencySummary(
    val currency: String,
    val income: Long,
    val expense: Long,
    val expenseByCategory: Map<String?, Long>,
    val incomeByCategory: Map<String?, Long>,
) {
    val net: Long get() = income - expense
}

data class TrendPoint(val month: YearMonth, val income: Long, val expense: Long)

data class NatureSplit(val need: Long, val want: Long, val savings: Long, val unassigned: Long) {
    val total: Long get() = need + want + savings + unassigned
}

/**
 * Report maths. Same rules as the server (docs/02, server/internal/report):
 * a row counts in the bucket of its category's kind (or its own type when
 * uncategorised). Income in an expense category is a refund and reduces that
 * spending; an expense in an income category reduces that income. The caller
 * has already excluded transfers, adjustments, planned rows, debt movements
 * and user-excluded rows.
 */
object Reports {
    fun isRefund(row: ReportRow, categories: Map<String, CategoryEntity>): Boolean =
        row.type == "income" && row.categoryId?.let { categories[it]?.kind } == "expense"

    /** An expense booked against an income category, e.g. a salary clawback. */
    fun isReversal(row: ReportRow, categories: Map<String, CategoryEntity>): Boolean =
        row.type == "expense" && row.categoryId?.let { categories[it]?.kind } == "income"

    /** +amount for spending, −amount for refunds, 0 for rows that are not spending. */
    fun spending(row: ReportRow, categories: Map<String, CategoryEntity>): Long = when {
        row.type == "expense" && !isReversal(row, categories) -> row.amount
        isRefund(row, categories) -> -row.amount
        else -> 0
    }

    fun rootOf(categoryId: String?, categories: Map<String, CategoryEntity>): String? {
        if (categoryId == null) return null
        val c = categories[categoryId] ?: return categoryId
        return c.parentId?.takeIf { categories.containsKey(it) } ?: c.id
    }

    fun summarize(
        rows: List<ReportRow>,
        categories: Map<String, CategoryEntity>,
        fallbackCurrency: String,
        rollUp: Boolean = true,
    ): Map<String, CurrencySummary> {
        class Acc(var income: Long = 0, var expense: Long = 0) {
            val exp = HashMap<String?, Long>()
            val inc = HashMap<String?, Long>()
        }
        val acc = LinkedHashMap<String, Acc>()
        for (r in rows) {
            val a = acc.getOrPut(r.currency ?: fallbackCurrency) { Acc() }
            val key = if (rollUp) rootOf(r.categoryId, categories) else r.categoryId
            when {
                isReversal(r, categories) -> {
                    a.income -= r.amount
                    a.inc.merge(key, -r.amount, Long::plus)
                }
                r.type == "expense" -> {
                    a.expense += r.amount
                    a.exp.merge(key, r.amount, Long::plus)
                }
                isRefund(r, categories) -> {
                    a.expense -= r.amount
                    a.exp.merge(key, -r.amount, Long::plus)
                }
                r.type == "income" -> {
                    a.income += r.amount
                    a.inc.merge(key, r.amount, Long::plus)
                }
            }
        }
        return acc.mapValues { (cur, a) -> CurrencySummary(cur, a.income, a.expense, a.exp, a.inc) }
    }

    fun trend(
        rows: List<ReportRow>,
        categories: Map<String, CategoryEntity>,
        currency: String,
        fallbackCurrency: String,
        months: List<YearMonth>,
    ): List<TrendPoint> {
        val byMonth = rows.filter { (it.currency ?: fallbackCurrency) == currency }
            .groupBy { YearMonth.from(LocalDate.parse(it.localDate)) }
        return months.map { m ->
            val s = summarize(byMonth[m].orEmpty(), categories, fallbackCurrency)[currency]
            TrendPoint(m, s?.income ?: 0, s?.expense ?: 0)
        }
    }

    /** Running total of spending at the end of each day 1..[lastDay] of [month], in [currency]. */
    fun dailyCumulative(
        rows: List<ReportRow>, categories: Map<String, CategoryEntity>, currency: String, fallbackCurrency: String,
        month: YearMonth, lastDay: Int,
    ): List<Long> {
        val perDay = LongArray(lastDay + 1)
        for (r in rows) {
            if ((r.currency ?: fallbackCurrency) != currency) continue
            val d = LocalDate.parse(r.localDate)
            if (YearMonth.from(d) != month || d.dayOfMonth > lastDay) continue
            perDay[d.dayOfMonth] += spending(r, categories)
        }
        var run = 0L
        return (1..lastDay).map { run += perDay[it]; run }
    }

    /** Spending split by category nature (needs / wants / savings), for the 50/30/20 view. */
    fun natureSplit(summary: CurrencySummary?, rows: List<ReportRow>, categories: Map<String, CategoryEntity>, fallback: String): NatureSplit {
        if (summary == null) return NatureSplit(0, 0, 0, 0)
        var need = 0L; var want = 0L; var savings = 0L; var other = 0L
        for (r in rows) {
            if ((r.currency ?: fallback) != summary.currency) continue
            val amount = spending(r, categories)
            if (amount == 0L) continue
            val c = r.categoryId?.let { categories[it] }
            val nature = c?.nature ?: c?.parentId?.let { categories[it]?.nature }
            when (nature) {
                "need" -> need += amount
                "want" -> want += amount
                "savings" -> savings += amount
                else -> other += amount
            }
        }
        return NatureSplit(need, want, savings, other)
    }

    fun topPayees(rows: List<ReportRow>, categories: Map<String, CategoryEntity>, currency: String, fallback: String, n: Int = 5): List<Pair<String, Long>> =
        rows.filter { (it.currency ?: fallback) == currency && !it.payee.isNullOrBlank() }
            .groupBy { it.payee!!.trim() }
            .map { (p, list) -> p to list.sumOf { spending(it, categories) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(n)
}

/** Spending per tag (net of refunds). A transaction with two tags counts under both. */
data class TagSpend(val tagId: String, val spent: Long, val count: Int)

fun Reports.byTag(rows: List<ReportRow>, categories: Map<String, CategoryEntity>, currency: String, fallback: String): List<TagSpend> {
    val spent = HashMap<String, Long>()
    val txs = HashMap<String, MutableSet<String>>()
    for (r in rows) {
        if (r.tagIds.isEmpty() || (r.currency ?: fallback) != currency) continue
        val amount = Reports.spending(r, categories)
        if (amount == 0L) continue
        for (t in r.tagIds) {
            spent.merge(t, amount, Long::plus)
            txs.getOrPut(t) { HashSet() } += r.id
        }
    }
    return spent.map { (t, s) -> TagSpend(t, s, txs[t]?.size ?: 0) }.filter { it.spent != 0L }.sortedByDescending { it.spent }
}
