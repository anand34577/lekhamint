package app.kosh

import app.kosh.data.KoshJson
import app.kosh.data.RulesTable
import app.kosh.data.SplitsTable
import app.kosh.data.db.BudgetEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.ReportRow
import app.kosh.data.db.RuleEntity
import app.kosh.data.db.SplitEntity
import app.kosh.data.db.SyncDao
import app.kosh.data.expandSplits
import app.kosh.domain.Budgets
import app.kosh.domain.Reports
import app.kosh.domain.RuleInput
import app.kosh.domain.Rules
import app.kosh.domain.byTag
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private class UnusedDao<T> : SyncDao<T> {
    override suspend fun upsertAll(rows: List<T>) = error("unused")
    override suspend fun find(id: String): T? = error("unused")
    override suspend fun dirty(limit: Int): List<T> = error("unused")
    override suspend fun clearDirty(id: String, hlc: String) = error("unused")
    override suspend fun markRejected(id: String, hlc: String) = error("unused")
    override suspend fun everything(): List<T> = error("unused")
    override suspend fun markAllDirty() = error("unused")
    override suspend fun wipe() = error("unused")
    override suspend fun countLive(): Int = error("unused")
}

/**
 * The same rules and cases are in web/src/lib/rules.test.ts, so both apps
 * suggest exactly the same thing for the same input.
 */
class RulesTest {
    private val rules = listOf(
        RuleEntity("r1", pattern = "swiggy", setCategoryId = "food", addTagIds = listOf("t1"), sortOrder = 0),
        RuleEntity("r2", pattern = "SWIGGY INSTAMART", matchType = "starts_with", setCategoryId = "groceries", setPayee = "Instamart", sortOrder = -1),
        RuleEntity("r3", pattern = "rent", matchType = "equals", matchField = "any", txType = "expense", setCategoryId = "rent", sortOrder = 1),
        RuleEntity("r4", pattern = "uber", minAmount = 10_000, maxAmount = 50_000, setCategoryId = "taxi", sortOrder = 2),
        RuleEntity("r5", pattern = "salary", txType = "income", setCategoryId = "salary", enabled = false, sortOrder = 3),
        RuleEntity("r6", pattern = "amzn", accountId = "card", setPayee = "Amazon", addTagIds = listOf("t2"), sortOrder = 4),
    )

    private fun run(payee: String?, note: String? = null, type: String = "expense", account: String = "bank", amount: Long = 0) =
        Rules.apply(rules, RuleInput(payee, note, type, account, amount))

    @Test fun firstCategoryWinsTagsCombine() {
        val a = run("Swiggy order 123")
        assertEquals("food", a.categoryId); assertNull(a.payee); assertEquals(listOf("t1"), a.tagIds)
        val b = run("  swiggy instamart BLR ")
        assertEquals("groceries", b.categoryId); assertEquals("Instamart", b.payee); assertEquals(listOf("t1"), b.tagIds)
        assertEquals(listOf("r2", "r1"), b.matched.map { it.id })
    }

    @Test fun fieldTypeAccountAndAmountFilters() {
        assertEquals("rent", run(null, note = "Rent").categoryId)
        assertTrue(run("rent", type = "income").isEmpty)
        assertTrue(run("Uber trip").isEmpty) // no amount yet
        assertEquals("taxi", run("Uber trip", amount = 25_000).categoryId)
        assertTrue(run("Uber trip", amount = 60_000).isEmpty)
        assertTrue(run("Salary Oct", type = "income").isEmpty) // disabled
        val amzn = run("AMZN Mktp", account = "card")
        assertNull(amzn.categoryId); assertEquals("Amazon", amzn.payee); assertEquals(listOf("t2"), amzn.tagIds)
        assertTrue(run("AMZN Mktp", account = "bank").isEmpty)
    }

    @Test fun codecRoundTrip() {
        val t = RulesTable(UnusedDao())
        val r = rules[3].copy(name = "Rides", addTagIds = listOf("x"))
        val back = t.decode(r.id, "h", false, t.encode(r), 0)
        assertEquals(r.copy(hlc = "h"), back)
        val minimal = t.decode("id", "h", false, KoshJson.parseToJsonElement("""{"pattern":"x","set_payee":"X"}""").jsonObject, 0)
        assertEquals("payee", minimal.matchField); assertEquals("contains", minimal.matchType); assertTrue(minimal.enabled)
    }
}

class SplitsAndReversalsTest {
    private val cats = listOf(
        CategoryEntity("food", "Food", "expense", nature = "need"), CategoryEntity("fun", "Fun", "expense", nature = "want"),
        CategoryEntity("salary", "Salary", "income"),
    ).associateBy { it.id }

    private fun row(id: String, type: String, amount: Long, cat: String?, tags: List<String> = emptyList()) =
        ReportRow(id, type, amount, cat, "acc", "INR", "2026-10-05", "p", tags)

    @Test fun splitsReplaceTheTransactionOnlyWhenTheyAddUp() {
        val rows = listOf(row("t1", "expense", 1000, null), row("t2", "expense", 500, "fun"))
        val splits = listOf(
            SplitEntity("s1", "t1", "food", 600, sortOrder = 0), SplitEntity("s2", "t1", "fun", 400, sortOrder = 1),
            SplitEntity("s3", "t2", "food", 100), // does not add up: ignored
            SplitEntity("s4", "t1", "food", 999, deleted = true),
        )
        val out = expandSplits(rows, splits)
        assertEquals(listOf("food" to 600L, "fun" to 400L, "fun" to 500L), out.map { it.categoryId to it.amount })
        val s = Reports.summarize(out, cats, "INR").getValue("INR")
        assertEquals(1500L, s.expense)
        assertEquals(600L, s.expenseByCategory["food"])
    }

    @Test fun expenseInIncomeCategoryReducesIncomeNotBudgets() {
        val rows = listOf(row("a", "income", 100_000, "salary"), row("b", "expense", 10_000, "salary"), row("c", "expense", 3_000, "food"))
        val s = Reports.summarize(rows, cats, "INR").getValue("INR")
        assertEquals(90_000L, s.income)
        assertEquals(3_000L, s.expense)
        assertEquals(90_000L, s.incomeByCategory["salary"])
        val b = BudgetEntity("b", "All", 50_000, "INR", "monthly", "2026-10-01")
        assertEquals(3_000L, Budgets.status(b, rows, cats, "INR", LocalDate.of(2026, 10, 20))!!.spent)
    }

    @Test fun spendingByTagNetsRefunds() {
        val rows = listOf(
            row("a", "expense", 3000, "fun", listOf("trip")), row("b", "expense", 1000, "food", listOf("trip", "work")),
            row("c", "income", 200, "fun", listOf("trip")), row("d", "income", 9000, "salary", listOf("work")),
        )
        val tags = Reports.byTag(rows, cats, "INR", "INR").associateBy { it.tagId }
        assertEquals(3800L, tags.getValue("trip").spent)
        assertEquals(3, tags.getValue("trip").count)
        assertEquals(1000L, tags.getValue("work").spent) // salary is not spending
    }

    @Test fun splitCodecRoundTrip() {
        val t = SplitsTable(UnusedDao())
        val s = SplitEntity("s", "tx", "food", 1234, note = "veg", sortOrder = 2, hlc = "h")
        val enc = t.encode(s)
        assertEquals(JsonPrimitive(1234), enc["amount"])
        assertEquals(s, t.decode("s", "h", false, enc, 0))
    }
}

class DebtsTest {
    private val lent = app.kosh.data.db.DebtEntity(id = "d1", person = "Ravi", direction = "lent", amount = 500000, currency = "INR")
    private val borrowed = lent.copy(id = "d2", direction = "borrowed")

    @Test fun loanMovesMoneyTheRightWay() {
        val day = LocalDate.of(2026, 10, 10)
        val out = app.kosh.domain.Debts.movement(lent, repayment = false, accountId = "bank", amount = 500000, date = day)
        assertEquals("expense", out.type)
        assertEquals("d1", out.debtId)
        assertEquals("2026-10-10", out.localDate)
        assertEquals("income", app.kosh.domain.Debts.movement(lent, repayment = true, accountId = "bank", amount = 1, date = day).type)
        assertEquals("income", app.kosh.domain.Debts.movement(borrowed, repayment = false, accountId = "bank", amount = 1, date = day).type)
        assertEquals("expense", app.kosh.domain.Debts.movement(borrowed, repayment = true, accountId = "bank", amount = 1, date = day).type)
    }

    @Test fun outstandingCountsOnlyRepayments() {
        val day = LocalDate.of(2026, 10, 10)
        val moves = listOf(
            app.kosh.domain.Debts.movement(lent, false, "bank", 500000, day),
            app.kosh.domain.Debts.movement(lent, true, "bank", 200000, day),
            app.kosh.domain.Debts.movement(lent, true, "cash", 50000, day).copy(deleted = true),
            app.kosh.domain.Debts.movement(borrowed, true, "bank", 99, day),
        )
        assertEquals(300000, app.kosh.domain.Debts.outstanding(lent, moves))
        assertEquals(0, app.kosh.domain.Debts.outstanding(lent, moves + app.kosh.domain.Debts.movement(lent, true, "bank", 900000, day)))
    }
}
