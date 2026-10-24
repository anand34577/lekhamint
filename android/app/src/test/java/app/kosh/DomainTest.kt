package app.kosh

import app.kosh.backup.BackupCrypto
import app.kosh.backup.BackupDecryptException
import app.kosh.backup.CsvExport
import app.kosh.data.AccountsTable
import app.kosh.data.KoshJson
import app.kosh.data.TransactionsTable
import app.kosh.data.WireRecord
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.BudgetEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.ReportRow
import app.kosh.data.db.SyncDao
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Budgets
import app.kosh.domain.Reports
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** A DAO that is never called: codec tests only use encode/decode. */
private class NoDao<T> : SyncDao<T> {
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

class CodecTest {
    @Test fun transactionRoundTripKeepsUnknownFields() {
        val table = TransactionsTable(NoDao())
        val wire = """
            {"type":"expense","status":"pending","account_id":"a1","amount":125050,"category_id":"c1","payee":"Swiggy",
             "note":null,"occurred_at":1729771234567,"local_date":"2026-10-24","tz_offset_min":330,"tag_ids":["t1","t2"],
             "is_adjustment":false,"excluded_from_reports":true,"created_at":1,"future_field":{"x":1}}
        """.trimIndent()
        val data = KoshJson.parseToJsonElement(wire).jsonObject
        val t = table.decode("id1", "1729771234567-0000-aaaaaaaaaaaaaaaa", false, data, dirty = 0)
        assertEquals(125050L, t.amount)
        assertEquals(listOf("t1", "t2"), t.tagIds)
        assertEquals(330, t.tzOffsetMin)
        assertTrue(t.excludedFromReports)
        val back = table.encode(t)
        assertEquals(JsonPrimitive(125050), back["amount"])
        assertEquals(JsonNull, back["to_account_id"]) // nulls are sent so clearing a field syncs
        assertEquals(data["future_field"], back["future_field"]) // a newer client's field survives
    }

    @Test fun accountDefaultsWhenFieldsMissing() {
        val a = AccountsTable(NoDao()).decode("id", "h", true, JsonObject(emptyMap()), 0)
        assertTrue(a.deleted)
        assertTrue(a.includeInNetWorth)
        assertEquals(0L, a.openingBalance)
    }

    @Test fun wireRecordJson() {
        val r = WireRecord("accounts", "id", "h", false, JsonObject(mapOf("name" to JsonPrimitive("Cash"))))
        val json = KoshJson.encodeToString(WireRecord.serializer(), r)
        assertEquals(r, KoshJson.decodeFromString(WireRecord.serializer(), json))
    }
}

class ReportsTest {
    private val food = CategoryEntity("food", "Food", "expense", nature = "need")
    private val dining = CategoryEntity("dining", "Dining", "expense", parentId = "food", nature = "want")
    private val salary = CategoryEntity("salary", "Salary", "income")
    private val cats = listOf(food, dining, salary).associateBy { it.id }

    private fun row(type: String, amount: Long, cat: String?, cur: String = "INR", date: String = "2026-10-05") =
        ReportRow("r$amount$type", type, amount, cat, "acc", cur, date, "p")

    @Test fun refundsReduceSpendingAndChildrenRollUp() {
        val rows = listOf(
            row("income", 10_000_000, "salary"),
            row("expense", 250_000, "dining"),
            row("expense", 100_000, "food"),
            row("income", 50_000, "dining"), // refund
            row("expense", 999, null, cur = "USD"),
        )
        val s = Reports.summarize(rows, cats, "INR")
        val inr = s.getValue("INR")
        assertEquals(10_000_000L, inr.income)
        assertEquals(300_000L, inr.expense)
        assertEquals(300_000L, inr.expenseByCategory["food"])
        assertEquals(999L, s.getValue("USD").expense)
        val split = Reports.natureSplit(inr, rows, cats, "INR")
        assertEquals(100_000L, split.need)
        assertEquals(200_000L, split.want)
    }
}

class BudgetsTest {
    private val cats = listOf(
        CategoryEntity("food", "Food", "expense"), CategoryEntity("dining", "Dining", "expense", parentId = "food"),
        CategoryEntity("fun", "Fun", "expense"),
    ).associateBy { it.id }

    private fun row(amount: Long, cat: String, date: String, type: String = "expense") =
        ReportRow("$date$amount", type, amount, cat, "acc", "INR", date, null)

    @Test fun monthlyWindowAndSpent() {
        val b = BudgetEntity("b", "Food", 1_000_000, "INR", "monthly", "2026-01-15", categoryIds = listOf("food"))
        val w = Budgets.window(b, LocalDate.of(2026, 10, 20))!!
        assertEquals(LocalDate.of(2026, 10, 15), w.start)
        assertEquals(LocalDate.of(2026, 11, 14), w.end)
        val rows = listOf(
            row(200_000, "dining", "2026-10-16"), row(100_000, "food", "2026-10-14"), // before window
            row(50_000, "fun", "2026-10-16"), row(20_000, "dining", "2026-10-17", type = "income"),
        )
        val st = Budgets.status(b, rows, cats, "INR", LocalDate.of(2026, 10, 20))!!
        assertEquals(180_000L, st.spent)
        assertEquals(820_000L, st.remaining)
        assertEquals(26L, st.daysLeft)
    }

    @Test fun rolloverCarriesRemainder() {
        val b = BudgetEntity("b", "All", 100_000, "INR", "monthly", "2026-09-01", rollover = true)
        val rows = listOf(row(60_000, "fun", "2026-09-10"), row(10_000, "fun", "2026-10-02"))
        val st = Budgets.status(b, rows, cats, "INR", LocalDate.of(2026, 10, 5))!!
        assertEquals(140_000L, st.limit) // 100k + 40k unspent in September
        assertEquals(10_000L, st.spent)
    }

    @Test fun notStartedYet() {
        val b = BudgetEntity("b", "All", 100, "INR", "monthly", "2027-01-01")
        assertNull(Budgets.window(b, LocalDate.of(2026, 12, 31)))
    }
}

class BackupCryptoTest {
    // Small Argon2 parameters keep the test fast; production uses 64 MiB.
    private fun key(p: String, salt: ByteArray) = BackupCrypto.deriveKey(p.toCharArray(), salt, memoryKib = 1024, iterations = 1)

    private fun roundTrip(size: Int) {
        val plain = ByteArray(size) { (it * 31 % 251).toByte() }
        val salt = BackupCrypto.randomBytes(16)
        val prefix = BackupCrypto.randomBytes(8)
        val sealed = ByteArrayOutputStream()
        BackupCrypto.encrypting(sealed, key("correct horse", salt), prefix).use { it.write(plain) }
        val out = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(sealed.toByteArray()), out, key("correct horse", salt), prefix)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test fun roundTripsSmallExactAndMultiChunk() {
        roundTrip(0)
        roundTrip(10)
        roundTrip(BackupCrypto.CHUNK)
        roundTrip(BackupCrypto.CHUNK * 2 + 123)
    }

    @Test fun wrongPasswordAndTruncationAreDetected() {
        val salt = BackupCrypto.randomBytes(16)
        val prefix = BackupCrypto.randomBytes(8)
        val sealed = ByteArrayOutputStream()
        BackupCrypto.encrypting(sealed, key("right", salt), prefix).use { it.write(ByteArray(BackupCrypto.CHUNK + 5)) }
        val bytes = sealed.toByteArray()
        try {
            BackupCrypto.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key("wrong", salt), prefix)
            fail("wrong password accepted")
        } catch (_: BackupDecryptException) {
        }
        // Drop the final chunk: the remaining chunk is not marked "last", so it must fail.
        val firstChunkLen = 4 + java.nio.ByteBuffer.wrap(bytes, 0, 4).int
        try {
            BackupCrypto.decrypt(ByteArrayInputStream(bytes.copyOf(firstChunkLen)), ByteArrayOutputStream(), key("right", salt), prefix)
            fail("truncated backup accepted")
        } catch (_: BackupDecryptException) {
        }
    }
}

class CsvTest {
    @Test fun escapesAndBlocksFormulas() {
        assertEquals("\"a,b\"", CsvExport.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.escape("say \"hi\""))
        assertEquals("'=SUM(A1)", CsvExport.escape("=SUM(A1)"))
        assertEquals("-12.50", CsvExport.escape("-12.50")) // negative numbers stay numbers
    }
}

@Suppress("unused")
private val keepImports = listOf(AccountEntity::class, TransactionEntity::class)
