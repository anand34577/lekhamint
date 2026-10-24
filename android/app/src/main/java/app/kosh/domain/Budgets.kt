package app.kosh.domain

import app.kosh.core.Recurrence
import app.kosh.data.db.BudgetEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.ReportRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class BudgetWindow(val start: LocalDate, val end: LocalDate) {
    fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1
}

data class BudgetStatus(
    val budget: BudgetEntity,
    val window: BudgetWindow,
    /** Budget amount plus anything rolled over from earlier periods. */
    val limit: Long,
    val spent: Long,
    val today: LocalDate,
) {
    val remaining: Long get() = limit - spent
    val fraction: Float get() = if (limit <= 0) 1f else (spent.toFloat() / limit).coerceAtLeast(0f)
    val daysLeft: Long get() = if (today.isAfter(window.end)) 0 else ChronoUnit.DAYS.between(today, window.end) + 1
    val perDayLeft: Long get() = if (daysLeft <= 0 || remaining <= 0) 0 else remaining / daysLeft
    /** Where spending "should" be by today if spread evenly over the period. */
    val paceFraction: Float get() {
        if (!window.contains(today)) return 1f
        return (ChronoUnit.DAYS.between(window.start, today) + 1).toFloat() / window.days
    }
    val overAlert: Boolean get() = fraction * 100 >= budget.alertPercent
    val over: Boolean get() = spent > limit
}

object Budgets {
    private const val MAX_ROLLOVER_PERIODS = 24

    private fun step(period: String): Pair<String, Int> = when (period) {
        "weekly" -> "weekly" to 1
        "quarterly" -> "monthly" to 3
        "yearly" -> "yearly" to 1
        else -> "monthly" to 1
    }

    /** The budget period containing [date], or null if the budget has not started or has ended. */
    fun window(b: BudgetEntity, date: LocalDate): BudgetWindow? {
        val start = LocalDate.parse(b.startDate)
        val end = b.endDate?.let(LocalDate::parse)
        if (b.period == "custom") return BudgetWindow(start, end ?: start)
        if (date.isBefore(start)) return null
        val (freq, every) = step(b.period)
        var i = Recurrence.firstIndexOnOrAfter(start, freq, every, date)
        if (Recurrence.occurrence(start, freq, every, i).isAfter(date)) i--
        val ws = Recurrence.occurrence(start, freq, every, i)
        val we = Recurrence.occurrence(start, freq, every, i + 1).minusDays(1)
        if (end != null && ws.isAfter(end)) return null
        return BudgetWindow(ws, if (end != null && we.isAfter(end)) end else we)
    }

    /** Earliest date whose transactions are needed to compute [b]'s status today. */
    fun neededFrom(b: BudgetEntity, today: LocalDate): LocalDate {
        val w = window(b, today) ?: return LocalDate.parse(b.startDate)
        if (!b.rollover || b.period == "custom") return w.start
        val start = LocalDate.parse(b.startDate)
        val (freq, every) = step(b.period)
        val idx = Recurrence.firstIndexOnOrAfter(start, freq, every, w.start)
        return Recurrence.occurrence(start, freq, every, (idx - MAX_ROLLOVER_PERIODS).coerceAtLeast(0))
    }

    /** Expands category IDs to include their sub-categories. */
    fun expand(ids: List<String>, categories: Collection<CategoryEntity>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val set = ids.toMutableSet()
        categories.filter { it.parentId in ids }.forEach { set += it.id }
        return set
    }

    fun spent(
        b: BudgetEntity,
        w: BudgetWindow,
        rows: List<ReportRow>,
        categories: Map<String, CategoryEntity>,
        fallbackCurrency: String,
    ): Long {
        val cats = expand(b.categoryIds, categories.values)
        val accounts = b.accountIds.toSet()
        var total = 0L
        for (r in rows) {
            if ((r.currency ?: fallbackCurrency) != b.currency) continue
            if (accounts.isNotEmpty() && r.accountId !in accounts) continue
            if (cats.isNotEmpty() && r.categoryId !in cats) continue
            if (!w.contains(LocalDate.parse(r.localDate))) continue
            total += Reports.spending(r, categories)
        }
        return total
    }

    fun status(
        b: BudgetEntity,
        rows: List<ReportRow>,
        categories: Map<String, CategoryEntity>,
        fallbackCurrency: String,
        today: LocalDate,
    ): BudgetStatus? {
        val w = window(b, today) ?: return null
        var carry = 0L
        if (b.rollover && b.period != "custom") {
            val start = LocalDate.parse(b.startDate)
            val (freq, every) = step(b.period)
            val current = Recurrence.firstIndexOnOrAfter(start, freq, every, w.start)
            for (i in (current - MAX_ROLLOVER_PERIODS).coerceAtLeast(0) until current) {
                val ws = Recurrence.occurrence(start, freq, every, i)
                val we = Recurrence.occurrence(start, freq, every, i + 1).minusDays(1)
                carry += b.amount - spent(b, BudgetWindow(ws, we), rows, categories, fallbackCurrency)
            }
        }
        return BudgetStatus(b, w, b.amount + carry, spent(b, w, rows, categories, fallbackCurrency), today)
    }
}
