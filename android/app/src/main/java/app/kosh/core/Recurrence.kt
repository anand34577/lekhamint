package app.kosh.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Schedule arithmetic for recurring rules. Occurrence n is computed from the
 * start date (not chained), so a rule anchored on the 31st lands on the last
 * day of shorter months and returns to the 31st afterwards.
 */
object Recurrence {
    fun occurrence(start: LocalDate, frequency: String, every: Int, index: Int): LocalDate {
        val step = every.coerceAtLeast(1).toLong() * index
        return when (frequency) {
            "daily" -> start.plusDays(step)
            "weekly" -> start.plusWeeks(step)
            "yearly" -> start.plusYears(step)
            else -> start.plusMonths(step)
        }
    }

    /** Index of the first occurrence on or after [date]. */
    fun firstIndexOnOrAfter(start: LocalDate, frequency: String, every: Int, date: LocalDate): Int {
        if (!date.isAfter(start)) return 0
        val unit = when (frequency) {
            "daily" -> ChronoUnit.DAYS
            "weekly" -> ChronoUnit.WEEKS
            "yearly" -> ChronoUnit.YEARS
            else -> ChronoUnit.MONTHS
        }
        var i = (unit.between(start, date) / every.coerceAtLeast(1)).toInt().coerceAtLeast(0)
        while (i > 0 && !occurrence(start, frequency, every, i - 1).isBefore(date)) i--
        while (occurrence(start, frequency, every, i).isBefore(date)) i++
        return i
    }

    data class Due(val index: Int, val date: LocalDate)

    /**
     * Occurrences from [next] (inclusive, defaults to the start) up to [until]
     * (inclusive), honouring [end] and [maxOccurrences]. At most [limit] are returned.
     */
    fun due(
        start: LocalDate,
        frequency: String,
        every: Int,
        next: LocalDate?,
        until: LocalDate,
        end: LocalDate? = null,
        maxOccurrences: Int? = null,
        limit: Int = 400,
    ): List<Due> {
        val out = ArrayList<Due>()
        var i = firstIndexOnOrAfter(start, frequency, every, next ?: start)
        while (out.size < limit) {
            if (maxOccurrences != null && i >= maxOccurrences) break
            val d = occurrence(start, frequency, every, i)
            if (d.isAfter(until) || (end != null && d.isAfter(end))) break
            out += Due(i, d)
            i++
        }
        return out
    }

    /** The first occurrence strictly after [date], or null when the rule has ended. */
    fun nextAfter(
        start: LocalDate,
        frequency: String,
        every: Int,
        date: LocalDate,
        end: LocalDate? = null,
        maxOccurrences: Int? = null,
    ): LocalDate? {
        val i = firstIndexOnOrAfter(start, frequency, every, date.plusDays(1))
        if (maxOccurrences != null && i >= maxOccurrences) return null
        val d = occurrence(start, frequency, every, i)
        return if (end != null && d.isAfter(end)) null else d
    }

    fun describe(frequency: String, every: Int): String {
        val unit = when (frequency) {
            "daily" -> "day"
            "weekly" -> "week"
            "yearly" -> "year"
            else -> "month"
        }
        return if (every <= 1) "Every $unit" else "Every $every ${unit}s"
    }
}
