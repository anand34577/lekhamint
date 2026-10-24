package app.kosh.data

import android.content.Context
import androidx.room.withTransaction
import app.kosh.core.Dates
import app.kosh.core.HlcClock
import app.kosh.core.Ids
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.BudgetEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.DebtEntity
import app.kosh.data.db.Dirty
import app.kosh.data.db.KoshDatabase
import app.kosh.data.db.RecurringRuleEntity
import app.kosh.data.db.ReportRow
import app.kosh.data.db.RuleEntity
import app.kosh.data.db.SplitEntity
import app.kosh.data.db.Synced
import app.kosh.data.db.TagEntity
import app.kosh.data.db.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The single place that writes user data. Every write stamps a fresh HLC and
 * marks the row dirty in the same statement, so sync always sees a consistent
 * view (docs/03, "Android").
 */
class Repository(
    private val context: Context,
    val db: KoshDatabase,
    val tables: SyncTables,
    val clock: HlcClock,
    val settings: Settings,
    private val onLocalChange: () -> Unit,
) {
    // --- Observations -------------------------------------------------------

    val accounts: Flow<List<AccountEntity>> = db.accounts().observeAll()
    val balances = db.accounts().observeBalances()
    val categories: Flow<List<CategoryEntity>> = db.categories().observeAll()
    val tags: Flow<List<TagEntity>> = db.tags().observeAll()
    val budgets: Flow<List<BudgetEntity>> = db.budgets().observeAll()
    val recurringRules: Flow<List<RecurringRuleEntity>> = db.recurring().observeAll()
    val debts: Flow<List<DebtEntity>> = db.debts().observeAll()
    val debtMoves: Flow<List<TransactionEntity>> = db.transactions().observeDebtMoves()
    val rules: Flow<List<RuleEntity>> = db.rules().observeAll()
    val allSplits: Flow<List<SplitEntity>> = db.splits().observeAllLive()
    val pendingCount = db.stats().observePending()
    val rejectedCount = db.stats().observeRejected()

    fun transactionsBetween(from: LocalDate, to: LocalDate) =
        db.transactions().observeRange(from.toString(), to.toString())

    fun recentTransactions(limit: Int = 30) = db.transactions().observeRecent(limit)
    fun accountTransactions(accountId: String, limit: Int = 300) = db.transactions().observeForAccount(accountId, limit)
    fun search(q: String) = db.transactions().search(escapeLike(q))
    /** Reportable rows with split transactions expanded into one row per split. */
    fun reportRows(from: LocalDate, to: LocalDate): Flow<List<ReportRow>> = combine(
        db.transactions().observeReportRows(from.toString(), to.toString()),
        db.splits().observeInRange(from.toString(), to.toString()),
    ) { rows, splits -> expandSplits(rows, splits) }
    fun transaction(id: String) = db.transactions().observe(id)
    fun account(id: String) = db.accounts().observe(id)
    val deletedTransactions = db.transactions().observeDeleted()
    val deletedAccounts = db.accounts().observeDeleted()
    val deletedCategories = db.categories().observeDeleted()

    suspend fun findTransaction(id: String) = db.transactions().find(id)
    suspend fun findAccount(id: String) = db.accounts().find(id)
    suspend fun findCategory(id: String) = db.categories().find(id)
    suspend fun findBudget(id: String) = db.budgets().find(id)
    suspend fun findRule(id: String) = db.recurring().find(id)
    suspend fun findDebt(id: String) = db.debts().find(id)

    suspend fun lastWithPayee(payee: String, type: String) = db.transactions().lastWithPayee(payee.trim(), type)
    suspend fun payeeSuggestions(prefix: String) =
        if (prefix.isBlank()) emptyList() else db.transactions().payees(escapeLike(prefix.trim()))

    suspend fun topCategories(type: String, limit: Int = 8): List<String> =
        db.transactions().topCategories(type, LocalDate.now().minusDays(90).toString(), limit).map { it.categoryId }

    suspend fun lastUsedAccountId() = db.transactions().lastAccountId()
    suspend fun transactionCountForAccount(id: String) = db.transactions().countForAccount(id)
    suspend fun transactionCountForCategory(id: String) = db.transactions().countForCategory(id)

    // --- Writes -------------------------------------------------------------

    private suspend fun <T : Synced<T>> write(table: SyncTable<T>, rows: List<T>, deleted: Boolean? = null) {
        if (rows.isEmpty()) return
        val stamped = rows.map { it.withMeta(clock.now(), deleted ?: it.deleted, Dirty.PENDING) }
        db.withTransaction { table.upsert(stamped) }
        onLocalChange()
    }

    suspend fun saveAccount(a: AccountEntity) = write(tables.accounts, listOf(a))
    suspend fun saveCategory(c: CategoryEntity) = write(tables.categories, listOf(c))
    suspend fun saveTag(t: TagEntity) = write(tables.tags, listOf(t))
    suspend fun saveTransaction(t: TransactionEntity) = write(tables.transactions, listOf(t))
    suspend fun saveTransactions(list: List<TransactionEntity>) = write(tables.transactions, list)
    suspend fun saveBudget(b: BudgetEntity) = write(tables.budgets, listOf(b))
    suspend fun saveRule(r: RecurringRuleEntity) = write(tables.recurring, listOf(r))
    suspend fun saveDebt(d: DebtEntity) = write(tables.debts, listOf(d))

    /** Saves a debt and, optionally, the money movement linked to it, atomically. */
    suspend fun saveDebt(d: DebtEntity, move: TransactionEntity?) = db.withTransaction {
        saveDebt(d)
        move?.let { saveTransaction(it) }
    }

    /** Moves a transaction to Trash. Returns it so the UI can offer Undo. */
    suspend fun deleteTransaction(id: String): TransactionEntity? {
        val t = db.transactions().find(id) ?: return null
        write(tables.transactions, listOf(t), deleted = true)
        return t
    }

    suspend fun restoreTransaction(id: String) {
        val t = db.transactions().find(id) ?: return
        write(tables.transactions, listOf(t), deleted = false)
    }

    suspend fun deleteAccount(id: String) = db.accounts().find(id)?.let { write(tables.accounts, listOf(it), deleted = true) }
    suspend fun restoreAccount(id: String) = db.accounts().find(id)?.let { write(tables.accounts, listOf(it), deleted = false) }

    /** Deletes a category and its sub-categories. Transactions keep their reference and show as uncategorised. */
    suspend fun deleteCategory(id: String) {
        val c = db.categories().find(id) ?: return
        write(tables.categories, listOf(c) + db.categories().children(id), deleted = true)
    }

    suspend fun restoreCategory(id: String) = db.categories().find(id)?.let { write(tables.categories, listOf(it), deleted = false) }
    suspend fun deleteBudget(id: String) = db.budgets().find(id)?.let { write(tables.budgets, listOf(it), deleted = true) }
    suspend fun deleteRule(id: String) = db.recurring().find(id)?.let { write(tables.recurring, listOf(it), deleted = true) }
    suspend fun deleteDebt(id: String) = db.debts().find(id)?.let { write(tables.debts, listOf(it), deleted = true) }

    /**
     * Brings an account's balance to [actual] with an adjustment transaction.
     * Adjustments move the balance but are left out of income/expense reports.
     */
    suspend fun reconcile(account: AccountEntity, current: Long, actual: Long): Boolean {
        val diff = actual - current
        if (diff == 0L) return false
        val now = System.currentTimeMillis()
        saveTransaction(
            TransactionEntity(
                id = Ids.newV7(now), type = if (diff > 0) "income" else "expense", accountId = account.id,
                amount = kotlin.math.abs(diff), payee = "Balance adjustment", occurredAt = now,
                localDate = Dates.today().toString(), tzOffsetMin = Dates.tzOffsetMinutes(now),
                isAdjustment = true, createdAt = now,
            ),
        )
        return true
    }


    // --- Tags, splits and rules ---------------------------------------------

    suspend fun deleteTag(id: String) = db.tags().find(id)?.let { write(tables.tags, listOf(it), deleted = true) }

    suspend fun splitsFor(txId: String): List<SplitEntity> = db.splits().forTransaction(txId)

    /**
     * Saves [tx] together with its split lines in one go. Lines that are no
     * longer present move to Trash; an empty list removes the split.
     */
    suspend fun saveTransactionWithSplits(tx: TransactionEntity, lines: List<SplitEntity>?) {
        val old = db.splits().forTransaction(tx.id)
        val stamp = { rows: List<SplitEntity>, deleted: Boolean -> rows.map { it.withMeta(clock.now(), deleted, Dirty.PENDING) } }
        db.withTransaction {
            tables.transactions.upsert(listOf(tx.withMeta(clock.now(), tx.deleted, Dirty.PENDING)))
            if (lines != null) {
                val keep = lines.map { it.id }.toSet()
                tables.splits.upsert(stamp(old.filter { it.id !in keep }, true))
                tables.splits.upsert(stamp(lines.mapIndexed { i, s -> s.copy(transactionId = tx.id, sortOrder = i.toLong()) }, false))
            }
        }
        onLocalChange()
    }

    suspend fun saveCategorizationRule(r: RuleEntity) = write(tables.rules, listOf(r))
    suspend fun deleteCategorizationRule(id: String) = db.rules().find(id)?.let { write(tables.rules, listOf(it), deleted = true) }

    /** Saves rules in the given order (sort_order = position). */
    suspend fun reorderRules(ordered: List<RuleEntity>) =
        write(tables.rules, ordered.mapIndexedNotNull { i, r -> if (r.sortOrder == i.toLong()) null else r.copy(sortOrder = i.toLong()) })

    /**
     * Moves records of entities that now have a typed table out of
     * `raw_records` (an older app version stored them there). A typed row that
     * is already newer wins. Safe to run on every start.
     */
    suspend fun promoteRawRecords(): Int {
        var moved = 0
        for (t in tables.typed) {
            val raws = db.raw().ofEntity(t.entity)
            if (raws.isEmpty()) continue
            db.withTransaction {
                for (raw in raws) if (promote(t, raw)) moved++
                db.raw().deleteEntity(t.entity)
            }
        }
        return moved
    }

    private suspend fun <T : Synced<T>> promote(t: SyncTable<T>, raw: app.kosh.data.db.RawRecordEntity): Boolean {
        val existing = t.find(raw.id)
        if (existing != null && existing.hlc >= raw.hlc) return false
        t.upsert(listOf(t.fromWire(tables.raw.toWire(raw), raw.dirty)))
        return true
    }

    // --- Ledger lifecycle ---------------------------------------------------

    @Serializable
    private data class SeedNode(
        val name: String,
        val icon: String = "",
        val color: String = "",
        val nature: String = "",
        val children: List<SeedNode> = emptyList(),
    )

    @Serializable
    private data class SeedFile(val expense: List<SeedNode>, val income: List<SeedNode>)

    /** Default categories from the shared seed file (server/internal/seed). */
    fun defaultCategories(now: Long = System.currentTimeMillis()): List<CategoryEntity> {
        val json = context.assets.open("default-categories.json").use { it.readBytes().decodeToString() }
        val seed = KoshJson.decodeFromString(SeedFile.serializer(), json)
        val out = ArrayList<CategoryEntity>()
        for ((kind, roots) in listOf("expense" to seed.expense, "income" to seed.income)) {
            roots.forEachIndexed { i, root ->
                val rootId = Ids.newV7(now)
                out += CategoryEntity(
                    id = rootId, name = root.name, kind = kind, icon = root.icon.ifEmpty { null },
                    color = root.color.ifEmpty { null }, nature = root.nature.ifEmpty { null }.takeIf { kind == "expense" },
                    sortOrder = i.toLong(), createdAt = now,
                )
                root.children.forEachIndexed { j, child ->
                    out += CategoryEntity(
                        id = Ids.newV7(now), name = child.name, kind = kind, parentId = rootId,
                        icon = child.icon.ifEmpty { root.icon }.ifEmpty { null },
                        color = child.color.ifEmpty { root.color }.ifEmpty { null },
                        nature = child.nature.ifEmpty { root.nature }.ifEmpty { null }.takeIf { kind == "expense" },
                        sortOrder = j.toLong(), createdAt = now,
                    )
                }
            }
        }
        return out
    }

    /** First launch: a new local ledger with a Cash account and the default categories. */
    suspend fun createLedger(name: String, currency: String) {
        val now = System.currentTimeMillis()
        settings.setLedger(Ids.newV7(now), name, currency)
        write(
            tables.accounts,
            listOf(AccountEntity(id = Ids.newV7(now), name = "Cash", type = "cash", currency = currency, icon = "cash", color = "#2E7D32", createdAt = now)),
        )
        write(tables.categories, defaultCategories(now))
        settings.setOnboarded()
    }

    /** Removes every local record (used before a replace-restore or joining another ledger). */
    suspend fun wipeAll() {
        db.withTransaction {
            tables.typed.forEach { it.wipe() }
            db.raw().wipe()
        }
    }

    suspend fun markEverythingDirty() {
        db.withTransaction {
            tables.typed.forEach { it.markAllDirty() }
            db.raw().markAllDirty()
        }
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
