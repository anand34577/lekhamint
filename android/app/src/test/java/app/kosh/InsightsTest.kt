package app.kosh

import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Cadence
import app.kosh.domain.CashForecast
import app.kosh.domain.FlowEvent
import app.kosh.domain.Insights
import app.kosh.domain.SafeBasis
import app.kosh.domain.Subscriptions
import app.kosh.domain.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsTest {
    private val today = LocalDate.of(2026, 10, 26)

    private fun tx(id: String, date: LocalDate, amount: Long, payee: String?, type: String = "expense", rule: String? = null) =
        TransactionEntity(id = id, type = type, accountId = "a", amount = amount, payee = payee, occurredAt = 0, localDate = date.toString(), recurringRuleId = rule)

    @Test fun findsMonthlySubscriptionAndPriceRise() {
        val txs = (0..5).map { i -> tx("n$i", today.minusMonths(5L - i).withDayOfMonth(5), if (i < 5) 49900 else 64900, "NETFLIX.COM*${100 + i}") }
        val subs = Subscriptions.detect(txs, today)
        assertEquals(1, subs.size)
        val s = subs[0]
        assertEquals(Cadence.MONTHLY, s.cadence)
        assertEquals(64900, s.amount)
        assertEquals(49900L to 64900L, s.priceChange)
        assertEquals(64900L * 12, s.yearly)
        assertFalse(s.stale)
    }

    @Test fun skipsIrregularAndRuledPayees() {
        val random = listOf(3L, 10L, 41L, 44L, 90L).mapIndexed { i, d -> tx("r$i", today.minusDays(d), 1000L + i * 900, "Corner shop") }
        assertTrue(Subscriptions.detect(random, today).isEmpty())
        val gym = (0..3).map { i -> tx("g$i", today.minusMonths(4L - i), 150000, "Gold's Gym") }
        assertEquals(1, Subscriptions.detect(gym, today).size)
        assertTrue(Subscriptions.detect(gym, today, setOf(Subscriptions.payeeKey("GOLD'S GYM"))).isEmpty())
        // Rows a recurring rule made are not "found".
        assertTrue(Subscriptions.detect(gym.map { it.copy(recurringRuleId = "rule") }, today).isEmpty())
    }

    @Test fun staleWhenPaymentsStop() {
        val old = (0..3).map { i -> tx("o$i", today.minusMonths(9L - i), 19900, "Music Plus") }
        assertTrue(Subscriptions.detect(old, today).single().stale)
    }

    @Test fun forecastBandWidensAndFlagsShortfall() {
        val totals = (0 until 30).associate { today.minusDays(29L - it) to if (it % 2 == 0) 20000L else 0L }
        val spend = CashForecast.dailySpend(totals, today.minusDays(29), 30)
        assertEquals(10000.0, spend.mean, 0.01)
        val f = CashForecast.forecast(200000, listOf(FlowEvent(today.plusDays(5), -150000, "Rent")), spend, today, 30)
        assertEquals(31, f.points.size)
        assertEquals(200000, f.points[0].expected)
        assertEquals(200000 - 150000 - 50000L, f.points[5].expected)
        assertTrue(f.points[30].high - f.points[30].low > f.points[5].high - f.points[5].low)
        assertNotNull(f.shortBy)
        assertEquals(150000, f.knownOut)
    }

    @Test fun safeToSpendStopsBeforePayday() {
        val spend = CashForecast.dailySpend(emptyMap(), today, 14)
        val events = listOf(FlowEvent(today.plusDays(2), -30000, "Phone"), FlowEvent(today.plusDays(10), 500000, "Salary"), FlowEvent(today.plusDays(12), -90000, "Rent"))
        val s = CashForecast.safeToSpend(100000, events, spend, today)
        assertTrue(s.toPayday)
        assertEquals(today.plusDays(9), s.until)
        assertEquals(10, s.days)
        assertEquals(30000, s.billsOut)
        assertEquals(0, s.incomeIn)
        assertEquals(70000, s.free)
        assertEquals(7000, s.perDay)
        // No payday known: to the end of the month (26th to 31st is 6 days).
        val m = CashForecast.safeToSpend(60000, emptyList(), spend, today)
        assertFalse(m.toPayday)
        assertEquals(6, m.days)
        assertEquals(10000, m.perDay)
        // More bills than cash: nothing is safe.
        assertEquals(0, CashForecast.safeToSpend(10000, events, spend, today).perDay)
    }

    @Test fun safeToSpendPrefersBudgetThenIncome() {
        val spend = CashForecast.dailySpend(emptyMap(), today, 14)
        val bills = listOf(FlowEvent(today.plusDays(2), -12000, "Phone"))
        // ₹600 of budget left, ₹120 of bills still due, 6 days to month end: ₹80 a day.
        val b = CashForecast.safe(budgetLeft = 60000, incomeLeft = 300000, cash = 900000, events = bills, spend = spend, today = today)
        assertEquals(SafeBasis.BUDGET, b.basis)
        assertEquals(48000, b.free)
        assertEquals(8000, b.perDay)
        val i = CashForecast.safe(budgetLeft = null, incomeLeft = 30000, cash = 900000, events = bills, spend = spend, today = today)
        assertEquals(SafeBasis.INCOME, i.basis)
        assertEquals(18000, i.free)
        // Never more than the cash that is actually there.
        val capped = CashForecast.safe(budgetLeft = 500000, incomeLeft = null, cash = 30000, events = bills, spend = spend, today = today)
        assertEquals(18000, capped.free)
        assertTrue(capped.cappedByCash)
        assertEquals(SafeBasis.CASH, CashForecast.safe(null, null, 60000, emptyList(), spend, today).basis)
    }

    @Test fun insightsCompareSameDays() {
        val out = Insights.of(
            now = mapOf("food" to 60000L, "fuel" to 10000L), prev = mapOf("food" to 30000L, "fuel" to 20000L),
            income = 200000, expense = 70000, prevExpense = 50000, dayOfMonth = 12, name = { it ?: "Uncategorised" },
        )
        assertEquals(Tone.WARN, out.first().tone)
        assertTrue(out.any { it.title == "food is up 100%" })
        assertTrue(out.any { it.title == "fuel is down 50%" })
        assertTrue(out.any { it.title.startsWith("You kept 65%") })
        assertTrue(out.size <= 4)
        assertNull(Insights.of(emptyMap(), emptyMap(), 0, 0, 0, 3) { "" }.firstOrNull())
    }
}
