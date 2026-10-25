package app.kosh

import app.kosh.domain.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class FxTest {
    private fun rate(id: String, base: String, quote: String, r: String, date: String) = Fx.Rate(id, base, quote, BigDecimal(r), date)

    @Test fun `newest rate on or before the date wins`() {
        val rates = listOf(rate("1", "USD", "INR", "80", "2025-01-01"), rate("2", "USD", "INR", "83", "2025-06-01"))
        assertEquals(BigDecimal("83"), Fx.find(rates, "USD", "INR", "2025-07-01"))
        assertEquals(BigDecimal("80"), Fx.find(rates, "USD", "INR", "2025-03-01"))
        assertNull(Fx.find(rates, "USD", "INR", "2024-12-31"))
    }

    @Test fun `a reverse rate is inverted and a missing one is reported`() {
        val rates = listOf(rate("1", "INR", "USD", "0.0125", "2025-01-01"))
        val r = Fx.find(rates, "USD", "INR", "2025-02-01")!!
        assertEquals(0, BigDecimal("80").compareTo(r.setScale(6, java.math.RoundingMode.HALF_UP)))
        val t = Fx.total(mapOf("INR" to 100_00L, "USD" to 10_00L, "EUR" to 5_00L), "INR", rates, "2025-02-01")
        assertEquals(listOf("EUR"), t.missing)
        assertEquals(100_00L + 800_00L, t.total)
    }

    @Test fun `conversion respects different minor-unit sizes`() {
        // 1 USD = 150 JPY: 10.00 USD (1000 cents) is 1500 yen (0 decimals).
        assertEquals(1500L, Fx.convert(1000, "USD", "JPY", BigDecimal("150")))
        // 1500 yen back at 1/150: 10.00 USD = 1000 cents.
        assertEquals(1000L, Fx.convert(1500, "JPY", "USD", BigDecimal.ONE.divide(BigDecimal("150"), java.math.MathContext(20))))
        assertEquals(-415000L, Fx.convert(-5000, "USD", "INR", BigDecimal("83")))
    }
}

class PaceTest {
    private fun row(date: String, amount: Long, type: String = "expense") =
        app.kosh.data.db.ReportRow("i$date$amount", type, amount, null, "a", "INR", date, null)

    @Test fun `cumulative spending accumulates per day and ignores other months and income`() {
        val rows = listOf(row("2026-10-02", 100), row("2026-10-02", 50), row("2026-10-04", 25), row("2026-09-30", 999), row("2026-10-03", 500, "income"))
        val out = app.kosh.domain.Reports.dailyCumulative(rows, emptyMap(), "INR", "INR", java.time.YearMonth.of(2026, 10), 5)
        assertEquals(listOf(0L, 150L, 150L, 175L, 175L), out)
    }
}
