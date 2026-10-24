package app.kosh

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kosh.core.HlcClock
import app.kosh.data.Repository
import app.kosh.data.Settings
import app.kosh.data.SyncTables
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.DebtEntity
import app.kosh.data.db.KoshDatabase
import app.kosh.data.db.SplitEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Debts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

private fun repo(db: KoshDatabase) = Repository(context, db, SyncTables(db), HlcClock("test-node", null), Settings(context), onLocalChange = {})

/** Room schema 1 → 2 on a real SQLite, then the start-up promotion of raw records. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KoshDatabase::class.java)

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun version1DataSurvivesAndRawSplitsBecomeTyped() = runBlocking {
        helper.createDatabase(name, 1).apply {
            // Version 1 had no typed table for splits or rules, so sync kept them as raw records.
            execSQL(
                "INSERT INTO raw_records (entity, id, data, hlc, deleted, dirty, extras) VALUES " +
                    "('transaction_splits', 's1', '{\"transaction_id\":\"t1\",\"category_id\":\"c1\",\"amount\":700,\"sort_order\":0}', '0001', 0, 0, '{}')," +
                    "('rules', 'r1', '{\"pattern\":\"swiggy\",\"match_field\":\"payee\",\"match_type\":\"contains\",\"set_category_id\":\"c1\",\"enabled\":true}', '0001', 0, 1, '{}')," +
                    "('exchange_rates', 'x1', '{\"base\":\"USD\",\"quote\":\"INR\",\"rate\":\"83.5\"}', '0001', 0, 0, '{}')",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true).close()

        val db = Room.databaseBuilder(context, KoshDatabase::class.java, name).build()
        try {
            val r = repo(db)
            assertEquals(2, r.promoteRawRecords())
            assertEquals(700L, db.splits().find("s1")!!.amount)
            // The rule was waiting to be uploaded; it must stay dirty after the move.
            assertEquals(1, db.rules().find("r1")!!.dirty)
            assertTrue(db.raw().ofEntity("transaction_splits").isEmpty())
            // Entities without a typed table stay raw.
            assertEquals(1, db.raw().ofEntity("exchange_rates").size)
            // Running it again is harmless.
            assertEquals(0, r.promoteRawRecords())
        } finally {
            db.close()
        }
    }
}

/** Repository writes against an in-memory Room database. */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java).allowMainThreadQueries().build()
    private val r = repo(db)
    private val day = LocalDate.of(2026, 10, 10)

    @After fun close() = db.close()

    private fun tx(id: String, amount: Long, category: String? = null) = TransactionEntity(
        id = id, type = "expense", accountId = "a1", amount = amount, categoryId = category,
        occurredAt = 1, localDate = day.toString(), createdAt = 1,
    )

    @Test fun splitsSaveAtomicallyAndExpandInReports() = runBlocking {
        r.saveAccount(AccountEntity(id = "a1", name = "Bank", type = "bank", currency = "INR"))
        r.saveTransactionWithSplits(tx("t1", 1000), listOf(
            SplitEntity(id = "s1", transactionId = "", categoryId = "food", amount = 600),
            SplitEntity(id = "s2", transactionId = "", categoryId = "rent", amount = 400),
        ))
        assertEquals(listOf("s1", "s2"), r.splitsFor("t1").map { it.id })

        // Editing drops s2 and adds s3; s2 goes to Trash so the removal syncs.
        r.saveTransactionWithSplits(tx("t1", 1000), listOf(
            SplitEntity(id = "s1", transactionId = "", categoryId = "food", amount = 700),
            SplitEntity(id = "s3", transactionId = "", categoryId = "fuel", amount = 300),
        ))
        assertEquals(listOf("s1", "s3"), r.splitsFor("t1").map { it.id })
        assertTrue(db.splits().find("s2")!!.deleted)

        val rows = r.reportRows(day, day).first()
        assertEquals(mapOf("food" to 700L, "fuel" to 300L), rows.associate { it.categoryId to it.amount })

        // An empty split list removes the split; the transaction reports whole again.
        r.saveTransactionWithSplits(tx("t1", 1000, category = "food"), emptyList())
        assertEquals(listOf(1000L), r.reportRows(day, day).first().map { it.amount })
    }

    @Test fun loansMoveBalancesButNotReports() = runBlocking {
        r.saveAccount(AccountEntity(id = "a1", name = "Bank", type = "bank", currency = "INR", openingBalance = 1_000_000))
        val debt = DebtEntity(id = "d1", person = "Ravi", direction = "lent", amount = 500_000, currency = "INR")
        r.saveDebt(debt, Debts.movement(debt, repayment = false, accountId = "a1", amount = 500_000, date = day))
        r.saveDebt(debt, Debts.movement(debt, repayment = true, accountId = "a1", amount = 200_000, date = day))

        assertEquals(300_000L, Debts.outstanding(debt, r.debtMoves.first()))
        assertEquals(700_000L, r.balances.first().first { it.accountId == "a1" }.balance)
        assertTrue(r.reportRows(day, day).first().isEmpty())
    }
}
