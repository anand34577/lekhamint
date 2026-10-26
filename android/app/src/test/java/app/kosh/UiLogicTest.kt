package app.kosh

import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.data.db.BudgetEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.BudgetStatus
import app.kosh.domain.BudgetWindow
import app.kosh.ui.components.niceTop
import app.kosh.ui.screens.balanceHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class UiLogicTest {
    private fun tx(id: String, type: String, amount: Long, date: LocalDate, account: String = "a", to: String? = null, status: String = "cleared") =
        TransactionEntity(id = id, type = type, accountId = account, amount = amount, toAccountId = to, toAmount = to?.let { amount },
            occurredAt = 0, localDate = date.toString(), status = status)

    @Test fun balanceHistoryWalksBackFromToday() {
        val today = Dates.today()
        val txs = listOf(
            tx("3", "expense", 300, today),
            tx("2", "transfer", 200, today.minusDays(1), account = "b", to = "a"),
            tx("p", "expense", 999, today.minusDays(1), status = "planned"), // never moved the balance
            tx("1", "income", 1000, today.minusDays(2)),
        )
        // Balance now is 5000. Before today's expense it was 5300, before yesterday's transfer in 5100,
        // before the income 4100.
        val h = balanceHistory(txs, "a", 5000, days = 4, complete = true)
        assertEquals(listOf(4100L, 5100L, 5300L, 5000L), h)
    }

    @Test fun balanceHistoryStopsAtOldestLoadedPage() {
        val today = Dates.today()
        val txs = listOf(tx("1", "expense", 100, today), tx("2", "expense", 100, today.minusDays(3)))
        val h = balanceHistory(txs, "a", 1000, days = 30, complete = false)
        assertEquals(4, h.size) // today back to the oldest loaded day, no guessing beyond it
    }

    @Test fun chartScaleUsesRoundSteps() {
        assertEquals(120_000L, niceTop(108_153))
        assertEquals(1_000L, niceTop(1_000))
        assertEquals(1_200L, niceTop(1_001))
        assertEquals(1L, niceTop(0))
        assertEquals(10L, niceTop(9))
    }

    @Test fun moneyOnScreenDropsOnlyAZeroFraction() {
        val us = Locale.US
        assertEquals("₹4,000", Money.ui(400000, "INR", locale = us))
        assertEquals("₹592.37", Money.ui(59237, "INR", locale = us))
        assertEquals("₹4,000.00", Money.format(400000, "INR", locale = us)) // exports keep every digit
    }

    @Test fun parsesBothDecimalStyles() {
        assertEquals(125050L, Money.parse("1,250.50", "INR"))
        assertEquals(125050L, Money.parse("1.250,50", "EUR"))
        assertEquals(1250L, Money.parse("12,50", "EUR"))
        assertEquals(125000L, Money.parse("1,250", "INR"))
    }

    @Test fun budgetAheadOfPace() {
        val start = LocalDate.of(2026, 10, 1)
        val b = BudgetEntity(id = "b", name = "Food", amount = 3100, currency = "INR", period = "monthly", startDate = start.toString())
        val window = BudgetWindow(start, LocalDate.of(2026, 10, 31))
        // Day 10 of 31 (about 32% of the month): 50% spent is well ahead, 30% is on pace.
        assertTrue(BudgetStatus(b, window, 3100, 1550, LocalDate.of(2026, 10, 10)).aheadOfPace)
        assertFalse(BudgetStatus(b, window, 3100, 930, LocalDate.of(2026, 10, 10)).aheadOfPace)
        // Over the limit is "over", not "ahead of pace".
        assertFalse(BudgetStatus(b, window, 3100, 4000, LocalDate.of(2026, 10, 10)).aheadOfPace)
    }

    @Test
    fun pluralUsesSingularOnlyForOne() {
        assertEquals("1 day", app.kosh.core.plural(1, "day"))
        assertEquals("0 days", app.kosh.core.plural(0, "day"))
        assertEquals("2 entries", app.kosh.core.plural(2L, "entry", "entries"))
    }
}
