package app.kosh

import app.kosh.domain.Goal
import app.kosh.domain.Goals
import app.kosh.domain.SmsParser
import app.kosh.domain.TaxSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PlansTest {
    private fun goal(saved: Long = 0, ids: List<String> = listOf("acc"), date: String? = "2027-03-31") =
        Goal("g", "Goa trip", 6_000_000, "INR", date, "2026-03-31", ids, saved, null, false, null, null, 0)

    @Test fun goalProgressMatchesTheWeb() {
        val worth = mapOf("acc" to 1_000_000L, "fund" to 900_000L, "usd" to 50_000L)
        val cur = mapOf("acc" to "INR", "fund" to "INR", "usd" to "USD")
        val p = Goals.progress(goal(500_000, listOf("acc", "fund", "usd")), worth::get, cur::get, LocalDate.parse("2026-09-30"))
        assertEquals(2_400_000L, p.saved)
        assertEquals(3_600_000L, p.left)
        val half = Goals.progress(goal(), mapOf("acc" to 3_000_000L)::get, cur::get, LocalDate.parse("2026-09-30"))
        assertEquals(Goals.Status.ON_TRACK, half.status)
        assertEquals(kotlin.math.ceil(3_000_000 / (182 / 30.44)).toLong(), half.perMonth)
        assertEquals(Goals.Status.BEHIND, Goals.progress(goal(), mapOf("acc" to 1_000_000L)::get, cur::get, LocalDate.parse("2026-09-30")).status)
        assertEquals(Goals.Status.DONE, Goals.progress(goal(), mapOf("acc" to 7_000_000L)::get, cur::get).status)
        assertEquals(Goals.Status.NO_DATE, Goals.progress(goal(date = null), mapOf("acc" to 1L)::get, cur::get).status)
        assertEquals(Goals.Status.OVERDUE, Goals.progress(goal(), mapOf("acc" to 1L)::get, cur::get, LocalDate.parse("2027-05-01")).status)
    }

    @Test fun financialYear() {
        val (from, to) = TaxSections.yearOf(LocalDate.parse("2026-10-25"), 4)
        assertEquals("2026-04-01", from.toString()); assertEquals("2027-03-31", to.toString())
        assertEquals("FY 2026–27", TaxSections.label(from))
        assertEquals("2025-04-01", TaxSections.yearOf(LocalDate.parse("2026-02-01"), 4).first.toString())
        assertEquals("2026", TaxSections.label(TaxSections.yearOf(LocalDate.parse("2026-10-25"), 1).first))
    }

    @Test fun readsBankMessages() {
        val a = SmsParser.parse("Rs.450.00 debited from A/c XX1234 on 24-Oct-26 to VPA swiggy@icici (UPI Ref 1234). Not you? Call 1800")
        assertEquals("450.00", a.amount); assertEquals("expense", a.type); assertEquals("Swiggy", a.payee)
        val b = SmsParser.parse("INR 85,000.00 credited to your A/c XX9876 on 01-Oct by NEFT from ACME TECHNOLOGIES. Avl Bal INR 1,20,000")
        assertEquals("85000.00", b.amount); assertEquals("income", b.type)
        val c = SmsParser.parse("You spent ₹1,299 at DECATHLON on your HDFC Credit Card ending 4521.")
        assertEquals("1299", c.amount); assertEquals("DECATHLON", c.payee)
        assertNull(SmsParser.parse("Hello there").amount)
    }
}
