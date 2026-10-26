package app.kosh.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Dates {
    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    fun parse(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Offset of the device time zone in minutes at [atMs]. */
    fun tzOffsetMinutes(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        zone.rules.getOffset(Instant.ofEpochMilli(atMs)).totalSeconds / 60

    /** Epoch ms for [date] at the current local wall time. */
    fun instantFor(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(LocalTime.now(zone)).atZone(zone).toInstant().toEpochMilli()

    fun monthRange(month: YearMonth): Pair<LocalDate, LocalDate> = month.atDay(1) to month.atEndOfMonth()

    fun weekStart(date: LocalDate, first: DayOfWeek = DayOfWeek.MONDAY): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(first))

    private val dayFormat get() = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
    private val dayYearFormat get() = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    private val monthFormat get() = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    private val shortMonthFormat get() = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
    private val shortDate get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    // Day first, like every other date in the app ("1 Oct 2025"), rather than the locale's "Oct 1, 2025".
    private val mediumDate get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /** "Today", "Yesterday", "Mon, 21 Oct" or "Mon, 21 Oct 2024". */
    fun relativeDay(date: LocalDate, today: LocalDate = today()): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> if (date.year == today.year) date.format(dayFormat) else date.format(dayYearFormat)
    }

    fun month(m: YearMonth): String = m.format(monthFormat)
    fun shortMonth(m: YearMonth): String = m.format(shortMonthFormat)
    fun short(date: LocalDate): String = date.format(shortDate)
    fun medium(date: LocalDate): String = date.format(mediumDate)
}
