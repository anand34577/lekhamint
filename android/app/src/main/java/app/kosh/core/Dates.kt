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

    /**
     * Dates follow the phone's region: "Mon, 21 Oct" in the UK and India, "Mon, Oct 21" in the US, "21. Okt." in
     * Germany. Android picks the order for a set of fields; plain JVM unit tests fall back to day-first.
     */
    private fun pattern(skeleton: String, fallback: String): DateTimeFormatter {
        val locale = Locale.getDefault()
        val best = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
        return DateTimeFormatter.ofPattern(best?.takeIf { it.isNotBlank() } ?: fallback, locale)
    }
    private val dayFormat get() = pattern("EEEdMMM", "EEE, d MMM")
    private val dayYearFormat get() = pattern("EEEdMMMyyyy", "EEE, d MMM yyyy")
    private val monthFormat get() = pattern("MMMMyyyy", "MMMM yyyy")
    private val shortMonthFormat get() = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
    private val shortDate get() = pattern("dMMM", "d MMM")
    private val mediumDate get() = pattern("dMMMyyyy", "d MMM yyyy")

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

    /** A time of day in the phone's own clock style: "9:05 pm" or "21:05". */
    fun time(t: LocalTime, h24: Boolean): String = t.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.getDefault()))

    /**
     * The time of day of a saved moment, or null when only its date is known: imports and older
     * entries without a time were saved at exactly midday.
     */
    fun timeOf(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalTime? {
        val t = Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime()
        return if (t == LocalTime.NOON) null else t.withSecond(0).withNano(0)
    }

    /** [date] at [time]; without a time, now for today and midday for other days. */
    fun at(date: LocalDate, time: LocalTime?, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(time ?: if (date == today(zone)) LocalTime.now(zone) else LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
}
