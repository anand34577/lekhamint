package app.kosh

import app.kosh.data.db.AccountEntity
import app.kosh.domain.CardBilling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CardBillingTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun card(day: Int?) = AccountEntity(id = "c", name = "Amex", type = "credit_card", currency = "INR", dueDay = day)

    @Test fun `next due date is today or later and clamps short months`() {
        assertEquals(d("2026-10-28"), CardBilling.nextOnDay(d("2026-10-25"), 28))
        assertEquals(d("2026-10-25"), CardBilling.nextOnDay(d("2026-10-25"), 25))
        assertEquals(d("2026-11-25"), CardBilling.nextOnDay(d("2026-10-26"), 25))
        assertEquals(d("2026-02-28"), CardBilling.nextOnDay(d("2026-02-10"), 31))
        assertEquals(d("2027-01-05"), CardBilling.nextOnDay(d("2026-12-30"), 5))
    }

    @Test fun `only a card that owes money and has a due day is due`() {
        val today = d("2026-10-25")
        assertEquals(2L, CardBilling.due(card(27), -500, today)?.daysLeft)
        assertEquals(500L, CardBilling.due(card(27), -500, today)?.owed)
        assertNull(CardBilling.due(card(27), 0, today))     // nothing owed
        assertNull(CardBilling.due(card(27), 300, today))   // in credit
        assertNull(CardBilling.due(card(null), -500, today)) // no due day set
        assertNull(CardBilling.due(card(27).copy(archived = true), -500, today))
    }
}
