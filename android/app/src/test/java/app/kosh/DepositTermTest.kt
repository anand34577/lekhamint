package app.kosh

import app.kosh.domain.Deposits
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Short and mixed deposit terms, as banks sell them (mirrors web/src/lib/finance.test.ts). */
class DepositTermTest {
    private val start = LocalDate.parse("2026-01-15")

    @Test fun shortTerms() {
        assertEquals("7 days", Deposits.termLabel(start, start.plusDays(7)))
        assertEquals("30 days", Deposits.termLabel(start, start.plusDays(30)))
    }

    @Test fun mixedTerm() {
        val end = start.plusMonths(16).plusDays(15)
        assertEquals(LocalDate.parse("2027-05-30"), end)
        assertEquals("1 year 4 months 15 days", Deposits.termLabel(start, end))
    }

    @Test fun shortFdEarnsSimpleInterest() {
        // 1,00,000 at 7% for 7 days with quarterly compounding: no full quarter, so simple interest only.
        val v = Deposits.fdValue(10_000_000, "7", start, start.plusDays(7), "quarterly")
        assertEquals(10_013_425L, v)
    }
}
