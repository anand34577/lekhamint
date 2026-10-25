package app.kosh.domain

import app.kosh.data.db.AccountEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Credit card billing cycle: when the next payment is due and how soon. */
object CardBilling {
    /** The first date on or after [today] that falls on [day] of a month; short months use their last day. */
    fun nextOnDay(today: LocalDate, day: Int): LocalDate {
        fun at(m: YearMonth) = m.atDay(day.coerceIn(1, m.lengthOfMonth()))
        val here = at(YearMonth.from(today))
        return if (!here.isBefore(today)) here else at(YearMonth.from(today).plusMonths(1))
    }

    data class Due(val date: LocalDate, val daysLeft: Long, val owed: Long)

    /** The next payment of a card that has a due day and still owes money; null otherwise. */
    fun due(a: AccountEntity, balance: Long, today: LocalDate): Due? {
        val day = a.dueDay ?: return null
        if (a.type != "credit_card" || a.archived || a.deleted || balance >= 0) return null
        val date = nextOnDay(today, day)
        return Due(date, ChronoUnit.DAYS.between(today, date), -balance)
    }
}
