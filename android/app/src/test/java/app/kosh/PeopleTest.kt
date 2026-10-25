package app.kosh

import app.kosh.data.db.DebtEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Debts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeopleTest {
    private fun debt(id: String, person: String, dir: String, amount: Long, due: String? = null, closed: Boolean = false) =
        DebtEntity(id = id, person = person, direction = dir, amount = amount, currency = "INR", dueDate = due, closed = closed, createdAt = 0)

    private fun move(id: String, debt: String, type: String, amount: Long, date: String, part: String? = "principal") =
        TransactionEntity(id = id, type = type, accountId = "a", amount = amount, occurredAt = 0, localDate = date, debtId = debt, part = part)

    @Test fun `same person in different spelling is one row and nets lent against borrowed`() {
        val ds = listOf(debt("1", "Ravi ", "lent", 5000), debt("2", "ravi", "borrowed", 1500))
        val p = Debts.people(ds, emptyMap(), "2025-01-01").single()
        assertEquals(3500L, p.net)
        assertEquals(2, p.open)
    }

    @Test fun `a settled debt counts as zero and overdue is flagged`() {
        val ds = listOf(debt("1", "Asha", "lent", 1000, due = "2024-12-31"), debt("2", "Asha", "lent", 900, closed = true))
        val p = Debts.people(ds, emptyMap(), "2025-01-01").single()
        assertEquals(1000L, p.net)
        assertEquals(1, p.overdue)
        assertEquals("2024-12-31", p.nextDue)
    }

    @Test fun `overpaid debt flips the sign of what is owed`() {
        val d = debt("1", "Meera", "lent", 1000)
        val p = Debts.people(listOf(d), mapOf("1" to listOf(move("m", "1", "income", 1200, "2025-01-02"))), "2025-01-03").single()
        assertEquals(-200L, p.net)
    }

    @Test fun `repayment goes to the oldest debt first and reports what is left over`() {
        val old = debt("1", "X", "lent", 500)
        val new = debt("2", "X", "lent", 700)
        val (parts, rest) = Debts.allocate(900, listOf(Triple(new, 700L, "2025-02-01"), Triple(old, 500L, "2025-01-01")))
        assertEquals(listOf("1", "2"), parts.map { it.debt.id })
        assertEquals(listOf(500L, 400L), parts.map { it.amount })
        assertEquals(listOf(true, false), parts.map { it.settles })
        assertEquals(0L, rest)
        assertEquals(100L, Debts.allocate(1300, listOf(Triple(old, 500L, "a"), Triple(new, 700L, "b"))).second)
    }

    @Test fun `statement keeps a running balance and treats interest separately`() {
        val d = debt("1", "Nina", "lent", 1000)
        val moves = mapOf(
            "1" to listOf(
                move("a", "1", "expense", 1000, "2025-01-01"),
                move("b", "1", "income", 400, "2025-02-01"),
                move("c", "1", "income", 50, "2025-02-01", part = "interest"),
            ),
        )
        val rows = Debts.statement(listOf(d), moves)
        assertEquals(listOf(1000L, 600L, 600L), rows.map { it.balance })
        assertEquals(50L, rows.last().interest)
        assertEquals("a", rows.first().transactionId)
    }

    @Test fun `an old debt keeps its real start date`() {
        val d = debt("1", "Old", "lent", 100).copy(createdAt = LocalDate.parse("2020-03-04").atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        assertEquals("2020-03-04", Debts.start(d, emptyList()))
        assertTrue(Debts.statement(listOf(d), emptyMap()).single().text.contains("no account linked"))
        assertNull(Debts.statement(listOf(d), emptyMap()).single().transactionId)
    }
}
