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
import app.kosh.data.db.DepositEntity
import app.kosh.data.db.LoanEntity
import app.kosh.data.db.Dirty
import app.kosh.data.db.KoshDatabase
import app.kosh.data.db.RawRecordEntity
import app.kosh.data.db.RecurringRuleEntity
import app.kosh.data.db.ReportRow
import app.kosh.data.db.RuleEntity
import app.kosh.data.db.SplitEntity
import app.kosh.data.db.Synced
import app.kosh.data.db.TagEntity
import app.kosh.data.db.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    /** Attachment files on this phone. */
    val blobs = BlobStore(context)

    // --- Observations -------------------------------------------------------

    val accounts: Flow<List<AccountEntity>> = db.accounts().observeAll()
    val balances = db.accounts().observeBalances()
    val categories: Flow<List<CategoryEntity>> = db.categories().observeAll()
    val tags: Flow<List<TagEntity>> = db.tags().observeAll()
    val budgets: Flow<List<BudgetEntity>> = db.budgets().observeAll()
    val recurringRules: Flow<List<RecurringRuleEntity>> = db.recurring().observeAll()
    val debts: Flow<List<DebtEntity>> = db.debts().observeAll()
    val debtMoves: Flow<List<TransactionEntity>> = db.transactions().observeDebtMoves()
    val loans: Flow<List<LoanEntity>> = db.loans().observeAll()
    val loanMoves: Flow<List<TransactionEntity>> = db.transactions().observeLoanMoves()
    val deposits: Flow<List<DepositEntity>> = db.deposits().observeAll()
    val depositMoves: Flow<List<TransactionEntity>> = db.transactions().observeDepositMoves()
    val rules: Flow<List<RuleEntity>> = db.rules().observeAll()
    val allSplits: Flow<List<SplitEntity>> = db.splits().observeAllLive()
    /** Manual exchange rates, kept as raw records because only totals use them. */
    val exchangeRates: Flow<List<app.kosh.domain.Fx.Rate>> =
        db.raw().observeOfEntity(app.kosh.domain.Fx.ENTITY).map { rows -> rows.mapNotNull(app.kosh.domain.Fx::parse) }
    val goals: Flow<List<app.kosh.domain.Goal>> = db.raw().observeOfEntity(app.kosh.domain.Goal.ENTITY).map { it.mapNotNull(app.kosh.domain.Goal::parse) }
    val valuations: Flow<List<app.kosh.domain.Valuation>> = db.raw().observeOfEntity(app.kosh.domain.Valuation.ENTITY).map { it.mapNotNull(app.kosh.domain.Valuation::parse) }
    val attachments: Flow<List<app.kosh.domain.Attachment>> = db.raw().observeOfEntity(app.kosh.domain.Attachment.ENTITY).map { it.mapNotNull(app.kosh.domain.Attachment::parse) }

    /**
     * Market value of accounts that have a valuation (server report.applyValuations):
     * the latest valuation plus money moved in or out after it. Accounts without one are absent.
     */
    val accountValues: Flow<Map<String, Long>> = combine(valuations, balances) { vals, bals ->
        val latest = vals.groupBy { it.accountId }.mapValues { (_, l) -> l.maxWith(compareBy<app.kosh.domain.Valuation> { it.date }.thenBy { it.createdAt }) }
        latest.mapNotNull { (acc, v) ->
            val now = bals.firstOrNull { it.accountId == acc }?.balance ?: return@mapNotNull null
            val then = db.accounts().balanceAt(acc, v.date) ?: now
            acc to v.value + (now - then)
        }.toMap()
    }

    /** Creates or updates a record this app keeps as raw JSON; fields it does not set are kept. */
    suspend fun saveRaw(entity: String, id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>) {
        val old = db.raw().find(entity, id)
        val merged = (old?.let { runCatching { KoshJson.parseToJsonElement(it.data) as kotlinx.serialization.json.JsonObject }.getOrNull() } ?: emptyMap()) + fields
        db.raw().upsertAll(listOf(RawRecordEntity(entity = entity, id = id, hlc = clock.now(), deleted = false, dirty = Dirty.PENDING,
            data = KoshJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(merged)))))
        onLocalChange()
    }

    suspend fun deleteRaw(entity: String, id: String) {
        val row = db.raw().find(entity, id) ?: return
        db.raw().upsertAll(listOf(row.copy(hlc = clock.now(), deleted = true, dirty = Dirty.PENDING)))
        onLocalChange()
    }

    suspend fun restoreRaw(entity: String, id: String) {
        val row = db.raw().find(entity, id) ?: return
        db.raw().upsertAll(listOf(row.copy(hlc = clock.now(), deleted = false, dirty = Dirty.PENDING)))
        onLocalChange()
    }

    val pendingCount = db.stats().observePending()
    val rejectedCount = db.stats().observeRejected()

    fun transactionsBetween(from: LocalDate, to: LocalDate) =
        db.transactions().observeRange(from.toString(), to.toString())

    fun recentTransactions(limit: Int = 30) = db.transactions().observeRecent(limit)
    /** Entries from the last [days] days that still need a category. */
    fun uncategorized(days: Long = 30) = db.transactions().observeUncategorized(LocalDate.now().minusDays(days).toString())
    fun accountTransactions(accountId: String, limit: Int = 300) = db.transactions().observeForAccount(accountId, limit)
    fun search(q: String, limit: Int = 200): Flow<List<TransactionEntity>> {
        // "1,299" or "486.50" also finds that exact amount in the main currency.
        val minor = if (q.any(Char::isDigit) && q.all { it.isDigit() || it in ".,₹$€£ " }) {
            app.kosh.core.Money.parse(q.filter { it.isDigit() || it == '.' || it == ',' }, settings.current.baseCurrency) ?: 0L
        } else 0L
        return db.transactions().search(escapeLike(q), minor, limit)
    }
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
    suspend fun findLoan(id: String) = db.loans().find(id)
    suspend fun findDeposit(id: String) = db.deposits().find(id)

    /** Another entry with the same day, account, amount and type, if any. */
    suspend fun likelyDuplicate(t: TransactionEntity): TransactionEntity? =
        db.transactions().sameDay(t.accountId, t.localDate, t.amount, t.type, t.id)

    suspend fun lastWithPayee(payee: String, type: String) = db.transactions().lastWithPayee(payee.trim(), type)
    suspend fun payeeSuggestions(prefix: String) =
        if (prefix.isBlank()) emptyList() else db.transactions().payees(escapeLike(prefix.trim()))

    suspend fun topCategories(type: String, limit: Int = 8): List<String> =
        db.transactions().topCategories(type, LocalDate.now().minusDays(90).toString(), limit).map { it.categoryId }

    suspend fun lastUsedAccountId() = db.transactions().lastAccountId()
    /** What still points at an account besides transactions: recurring rules, loans and deposits. */
    suspend fun accountUses(id: String): List<String> = buildList {
        val rules = db.recurring().active().count { it.accountId == id || it.toAccountId == id }
        if (rules > 0) add("$rules recurring ${if (rules == 1) "rule" else "rules"}")
        val loans = db.loans().everything().count { !it.deleted && !it.closed && (it.accountId == id || it.payFromAccountId == id) }
        if (loans > 0) add("$loans ${if (loans == 1) "loan" else "loans"}")
        val deps = db.deposits().everything().count { !it.deleted && it.status == "active" && (it.accountId == id || it.payoutAccountId == id) }
        if (deps > 0) add("$deps ${if (deps == 1) "deposit" else "deposits"}")
    }

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

    /** Sets (or with null clears) the income-tax section a category counts for, kept in its extras. */
    suspend fun setTaxSection(c: CategoryEntity, code: String?) {
        val extras = runCatching { KoshJson.parseToJsonElement(c.extras) as kotlinx.serialization.json.JsonObject }.getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap()))
        val next = kotlinx.serialization.json.JsonObject(extras + ("tax_section" to (code?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)))
        saveCategory(c.copy(extras = KoshJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), next)))
    }
    suspend fun saveTag(t: TagEntity) = write(tables.tags, listOf(t))
    suspend fun saveTransaction(t: TransactionEntity) = write(tables.transactions, listOf(t))
    suspend fun saveTransactions(list: List<TransactionEntity>) = write(tables.transactions, list)
    suspend fun saveBudget(b: BudgetEntity) = write(tables.budgets, listOf(b))
    suspend fun saveRule(r: RecurringRuleEntity) = write(tables.recurring, listOf(r))
    suspend fun saveDebt(d: DebtEntity) = write(tables.debts, listOf(d))

    /**
     * Saves several kinds of records in one database transaction (a loan with
     * its account and disbursal, an EMI with its parts, a deposit and its
     * funding). Either everything is written or nothing is.
     */
    suspend fun saveAll(
        accounts: List<AccountEntity> = emptyList(),
        loans: List<LoanEntity> = emptyList(),
        deposits: List<DepositEntity> = emptyList(),
        debts: List<DebtEntity> = emptyList(),
        transactions: List<TransactionEntity> = emptyList(),
        deletedTransactions: List<TransactionEntity> = emptyList(),
    ) {
        db.withTransaction {
            fun <T : Synced<T>> stamp(rows: List<T>, deleted: Boolean? = null) = rows.map { it.withMeta(clock.now(), deleted ?: it.deleted, Dirty.PENDING) }
            if (accounts.isNotEmpty()) tables.accounts.upsert(stamp(accounts))
            if (loans.isNotEmpty()) tables.loans.upsert(stamp(loans))
            if (deposits.isNotEmpty()) tables.deposits.upsert(stamp(deposits))
            if (debts.isNotEmpty()) tables.debts.upsert(stamp(debts))
            if (transactions.isNotEmpty()) tables.transactions.upsert(stamp(transactions))
            if (deletedTransactions.isNotEmpty()) tables.transactions.upsert(stamp(deletedTransactions, true))
        }
        onLocalChange()
    }

    suspend fun saveLoan(l: LoanEntity) = write(tables.loans, listOf(l))
    suspend fun saveDeposit(d: DepositEntity) = write(tables.deposits, listOf(d))
    suspend fun deleteLoan(id: String) = db.loans().find(id)?.let { write(tables.loans, listOf(it), deleted = true) }
    suspend fun deleteDeposit(id: String) = db.deposits().find(id)?.let { write(tables.deposits, listOf(it), deleted = true) }

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

    /** Moves several transactions to Trash in one go; returns the ones that were moved so the UI can offer Undo. */
    suspend fun deleteTransactions(ids: Collection<String>): List<TransactionEntity> {
        val rows = ids.mapNotNull { db.transactions().find(it) }.filter { !it.deleted }
        write(tables.transactions, rows, deleted = true)
        return rows
    }

    suspend fun restoreTransactions(ids: Collection<String>) {
        write(tables.transactions, ids.mapNotNull { db.transactions().find(it) }, deleted = false)
    }

    /** Gives every listed transaction a category; the previous categories are returned for Undo. */
    suspend fun recategorize(ids: Collection<String>, categoryId: String?): Map<String, String?> {
        val rows = ids.mapNotNull { db.transactions().find(it) }
        write(tables.transactions, rows.map { it.copy(categoryId = categoryId) })
        return rows.associate { it.id to it.categoryId }
    }

    suspend fun setCategories(previous: Map<String, String?>) {
        val rows = previous.keys.mapNotNull { db.transactions().find(it) }
        write(tables.transactions, rows.map { it.copy(categoryId = previous[it.id]) })
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


    /** Saves a rate (1 [base] = [rate] [quote]); a new ID is a new rate, the same ID edits it. */
    suspend fun saveRate(id: String, base: String, quote: String, rate: String, date: String) {
        val data = kotlinx.serialization.json.buildJsonObject {
            put("base", kotlinx.serialization.json.JsonPrimitive(base)); put("quote", kotlinx.serialization.json.JsonPrimitive(quote))
            put("rate", kotlinx.serialization.json.JsonPrimitive(rate)); put("effective_date", kotlinx.serialization.json.JsonPrimitive(date))
            put("source", kotlinx.serialization.json.JsonPrimitive("manual"))
        }
        val row = RawRecordEntity(
            entity = app.kosh.domain.Fx.ENTITY, id = id, hlc = clock.now(), deleted = false, dirty = Dirty.PENDING,
            data = KoshJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), data),
        )
        db.raw().upsertAll(listOf(row))
        onLocalChange()
    }

    suspend fun deleteRate(id: String) {
        val row = db.raw().find(app.kosh.domain.Fx.ENTITY, id) ?: return
        db.raw().upsertAll(listOf(row.copy(hlc = clock.now(), deleted = true, dirty = Dirty.PENDING)))
        onLocalChange()
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
        var moved = promoteTransactionExtras()
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

    /**
     * Version 2 kept loan_id, deposit_id and part in a transaction's extras.
     * Decoding the row again moves them into their columns; HLC and dirty
     * state are kept, so nothing is re-synced.
     */
    private suspend fun promoteTransactionExtras(): Int {
        val rows = db.transactions().withLinkExtras().filter { it.loanId == null && it.depositId == null && it.part == null }
        val fixed = rows.map { tables.transactions.fromWire(tables.transactions.toWire(it), it.dirty) }
            .filter { it.loanId != null || it.depositId != null || it.part != null }
        if (fixed.isNotEmpty()) db.withTransaction { tables.transactions.upsert(fixed) }
        return fixed.size
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
        // A new book starts simple: the everyday screens only, with the rest a tap away in Features.
        settings.setSimpleMode(true)
        settings.setOnboarded()
    }

    /** Removes every local record (used before a replace-restore or joining another ledger). */
    suspend fun wipeAll() {
        db.withTransaction {
            tables.typed.forEach { it.wipe() }
            db.raw().wipe()
            db.syncBase().wipe()
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
