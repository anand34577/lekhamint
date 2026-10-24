package app.kosh.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Operations every synced table supports; used by sync, backup and restore. */
interface SyncDao<T> {
    suspend fun upsertAll(rows: List<T>)
    suspend fun find(id: String): T?
    suspend fun dirty(limit: Int): List<T>
    suspend fun clearDirty(id: String, hlc: String)
    suspend fun markRejected(id: String, hlc: String)
    suspend fun everything(): List<T>
    suspend fun markAllDirty()
    suspend fun wipe()
    suspend fun countLive(): Int
}

@Dao
interface AccountDao : SyncDao<AccountEntity> {
    @Upsert override suspend fun upsertAll(rows: List<AccountEntity>)
    @Query("SELECT * FROM accounts WHERE id = :id") override suspend fun find(id: String): AccountEntity?
    @Query("SELECT * FROM accounts WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<AccountEntity>
    @Query("UPDATE accounts SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE accounts SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM accounts") override suspend fun everything(): List<AccountEntity>
    @Query("UPDATE accounts SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM accounts") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM accounts WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY archived, sort_order, name COLLATE NOCASE")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deleted = 1 ORDER BY name COLLATE NOCASE")
    fun observeDeleted(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: String): Flow<AccountEntity?>

    /** Balance = opening + income − expense − transfers out + transfers in. Planned rows are not counted. */
    @Query(
        """
        SELECT a.id AS account_id,
          a.opening_balance
          + COALESCE((SELECT SUM(CASE t.type WHEN 'income' THEN t.amount ELSE -t.amount END)
                      FROM transactions t
                      WHERE t.account_id = a.id AND t.deleted = 0 AND t.status != 'planned'), 0)
          + COALESCE((SELECT SUM(t.to_amount) FROM transactions t
                      WHERE t.to_account_id = a.id AND t.type = 'transfer' AND t.deleted = 0
                        AND t.status != 'planned'), 0) AS balance
        FROM accounts a WHERE a.deleted = 0
        """,
    )
    fun observeBalances(): Flow<List<AccountBalance>>
}

@Dao
interface CategoryDao : SyncDao<CategoryEntity> {
    @Upsert override suspend fun upsertAll(rows: List<CategoryEntity>)
    @Query("SELECT * FROM categories WHERE id = :id") override suspend fun find(id: String): CategoryEntity?
    @Query("SELECT * FROM categories WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<CategoryEntity>
    @Query("UPDATE categories SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE categories SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM categories") override suspend fun everything(): List<CategoryEntity>
    @Query("UPDATE categories SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM categories") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM categories WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY kind, sort_order, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE deleted = 1 ORDER BY name COLLATE NOCASE")
    fun observeDeleted(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parent_id = :parentId AND deleted = 0")
    suspend fun children(parentId: String): List<CategoryEntity>
}

@Dao
interface TagDao : SyncDao<TagEntity> {
    @Upsert override suspend fun upsertAll(rows: List<TagEntity>)
    @Query("SELECT * FROM tags WHERE id = :id") override suspend fun find(id: String): TagEntity?
    @Query("SELECT * FROM tags WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<TagEntity>
    @Query("UPDATE tags SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE tags SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM tags") override suspend fun everything(): List<TagEntity>
    @Query("UPDATE tags SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM tags") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM tags WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM tags WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>
}

@Dao
interface TransactionDao : SyncDao<TransactionEntity> {
    @Upsert override suspend fun upsertAll(rows: List<TransactionEntity>)
    @Query("SELECT * FROM transactions WHERE id = :id") override suspend fun find(id: String): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<TransactionEntity>
    @Query("UPDATE transactions SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE transactions SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM transactions") override suspend fun everything(): List<TransactionEntity>
    @Query("UPDATE transactions SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM transactions") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0") override suspend fun countLive(): Int

    @Query(
        """SELECT * FROM transactions WHERE deleted = 0 AND local_date BETWEEN :from AND :to
           ORDER BY local_date DESC, occurred_at DESC, id DESC""",
    )
    fun observeRange(from: String, to: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY local_date DESC, occurred_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query(
        """SELECT * FROM transactions WHERE deleted = 0 AND (account_id = :accountId OR to_account_id = :accountId)
           ORDER BY local_date DESC, occurred_at DESC, id DESC LIMIT :limit""",
    )
    fun observeForAccount(accountId: String, limit: Int): Flow<List<TransactionEntity>>

    /** Case-insensitive search in payee and note across all time. */
    @Query(
        """SELECT * FROM transactions WHERE deleted = 0
           AND (payee LIKE '%' || :q || '%' ESCAPE '\' OR note LIKE '%' || :q || '%' ESCAPE '\')
           ORDER BY local_date DESC, occurred_at DESC, id DESC LIMIT 500""",
    )
    fun search(q: String): Flow<List<TransactionEntity>>

    /** Loans and repayments (docs/02 §9). */
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND debt_id IS NOT NULL ORDER BY local_date, occurred_at")
    fun observeDebtMoves(): Flow<List<TransactionEntity>>

    /** EMIs, prepayments, fees and disbursals of loans. */
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND loan_id IS NOT NULL ORDER BY local_date, occurred_at")
    fun observeLoanMoves(): Flow<List<TransactionEntity>>

    /** Funding, instalments, interest and maturity of deposits. */
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND deposit_id IS NOT NULL ORDER BY local_date, occurred_at")
    fun observeDepositMoves(): Flow<List<TransactionEntity>>

    /** Transactions that may still hold loan/deposit fields in extras (written by app version 2). */
    @Query("SELECT * FROM transactions WHERE extras LIKE '%loan_id%' OR extras LIKE '%deposit_id%' OR extras LIKE '%part%'")
    suspend fun withLinkExtras(): List<TransactionEntity>

    /** Same day, account, amount and type: a likely duplicate before saving. */
    @Query(
        """SELECT * FROM transactions WHERE deleted = 0 AND account_id = :accountId AND local_date = :date
           AND amount = :amount AND type = :type AND id != :exceptId LIMIT 1""",
    )
    suspend fun sameDay(accountId: String, date: String, amount: Long, type: String, exceptId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deleted = 1 ORDER BY hlc DESC LIMIT 500")
    fun observeDeleted(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observe(id: String): Flow<TransactionEntity?>

    @Query(
        """SELECT * FROM transactions WHERE deleted = 0 AND payee = :payee COLLATE NOCASE AND type = :type
           ORDER BY local_date DESC, occurred_at DESC LIMIT 1""",
    )
    suspend fun lastWithPayee(payee: String, type: String): TransactionEntity?

    @Query(
        """SELECT DISTINCT payee FROM transactions WHERE deleted = 0 AND payee LIKE :prefix || '%' ESCAPE '\'
           ORDER BY local_date DESC LIMIT 6""",
    )
    suspend fun payees(prefix: String): List<String>

    @Query(
        """SELECT category_id, COUNT(*) AS uses FROM transactions
           WHERE deleted = 0 AND type = :type AND category_id IS NOT NULL AND local_date >= :since
           GROUP BY category_id ORDER BY uses DESC LIMIT :limit""",
    )
    suspend fun topCategories(type: String, since: String, limit: Int): List<CategoryUse>

    @Query("SELECT account_id FROM transactions WHERE deleted = 0 ORDER BY created_at DESC LIMIT 1")
    suspend fun lastAccountId(): String?

    /**
     * Rows that count in income/expense reports, with their account currency.
     * Same exclusions as the server's summary: transfers, adjustments, planned,
     * user-excluded rows and debt movements.
     */
    @Query(
        """SELECT t.id, t.type, t.amount, t.category_id, t.account_id, a.currency, t.local_date, t.payee, t.tag_ids
           FROM transactions t LEFT JOIN accounts a ON a.id = t.account_id
           WHERE t.deleted = 0 AND t.type != 'transfer' AND t.status != 'planned' AND t.is_adjustment = 0
             AND t.excluded_from_reports = 0 AND (t.debt_id IS NULL OR t.part IN ('interest', 'fee'))
             AND t.local_date BETWEEN :from AND :to""",
    )
    fun observeReportRows(from: String, to: String): Flow<List<ReportRow>>

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0 AND (account_id = :accountId OR to_account_id = :accountId)")
    suspend fun countForAccount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0 AND category_id = :categoryId")
    suspend fun countForCategory(categoryId: String): Int
}

@Dao
interface BudgetDao : SyncDao<BudgetEntity> {
    @Upsert override suspend fun upsertAll(rows: List<BudgetEntity>)
    @Query("SELECT * FROM budgets WHERE id = :id") override suspend fun find(id: String): BudgetEntity?
    @Query("SELECT * FROM budgets WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<BudgetEntity>
    @Query("UPDATE budgets SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE budgets SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM budgets") override suspend fun everything(): List<BudgetEntity>
    @Query("UPDATE budgets SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM budgets") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM budgets WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM budgets WHERE deleted = 0 ORDER BY archived, name COLLATE NOCASE")
    fun observeAll(): Flow<List<BudgetEntity>>
}

@Dao
interface RecurringDao : SyncDao<RecurringRuleEntity> {
    @Upsert override suspend fun upsertAll(rows: List<RecurringRuleEntity>)
    @Query("SELECT * FROM recurring_rules WHERE id = :id") override suspend fun find(id: String): RecurringRuleEntity?
    @Query("SELECT * FROM recurring_rules WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<RecurringRuleEntity>
    @Query("UPDATE recurring_rules SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE recurring_rules SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM recurring_rules") override suspend fun everything(): List<RecurringRuleEntity>
    @Query("UPDATE recurring_rules SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM recurring_rules") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM recurring_rules WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM recurring_rules WHERE deleted = 0 ORDER BY paused, next_date, name COLLATE NOCASE")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE deleted = 0 AND paused = 0")
    suspend fun active(): List<RecurringRuleEntity>
}

@Dao
interface DebtDao : SyncDao<DebtEntity> {
    @Upsert override suspend fun upsertAll(rows: List<DebtEntity>)
    @Query("SELECT * FROM debts WHERE id = :id") override suspend fun find(id: String): DebtEntity?
    @Query("SELECT * FROM debts WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<DebtEntity>
    @Query("UPDATE debts SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE debts SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM debts") override suspend fun everything(): List<DebtEntity>
    @Query("UPDATE debts SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM debts") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM debts WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM debts WHERE deleted = 0 ORDER BY closed, due_date IS NULL, due_date, person COLLATE NOCASE")
    fun observeAll(): Flow<List<DebtEntity>>
}


@Dao
interface SplitDao : SyncDao<SplitEntity> {
    @Upsert override suspend fun upsertAll(rows: List<SplitEntity>)
    @Query("SELECT * FROM transaction_splits WHERE id = :id") override suspend fun find(id: String): SplitEntity?
    @Query("SELECT * FROM transaction_splits WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<SplitEntity>
    @Query("UPDATE transaction_splits SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE transaction_splits SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM transaction_splits") override suspend fun everything(): List<SplitEntity>
    @Query("UPDATE transaction_splits SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM transaction_splits") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM transaction_splits WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM transaction_splits WHERE transaction_id = :txId AND deleted = 0 ORDER BY sort_order, id")
    suspend fun forTransaction(txId: String): List<SplitEntity>

    /** Live splits of live transactions dated between [from] and [to]. */
    @Query(
        """SELECT s.* FROM transaction_splits s JOIN transactions t ON t.id = s.transaction_id
           WHERE s.deleted = 0 AND t.deleted = 0 AND t.local_date BETWEEN :from AND :to""",
    )
    fun observeInRange(from: String, to: String): Flow<List<SplitEntity>>

    @Query("SELECT * FROM transaction_splits WHERE deleted = 0")
    fun observeAllLive(): Flow<List<SplitEntity>>
}

@Dao
interface RuleDao : SyncDao<RuleEntity> {
    @Upsert override suspend fun upsertAll(rows: List<RuleEntity>)
    @Query("SELECT * FROM rules WHERE id = :id") override suspend fun find(id: String): RuleEntity?
    @Query("SELECT * FROM rules WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<RuleEntity>
    @Query("UPDATE rules SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE rules SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM rules") override suspend fun everything(): List<RuleEntity>
    @Query("UPDATE rules SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM rules") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM rules WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM rules WHERE deleted = 0 ORDER BY sort_order, created_at, id")
    fun observeAll(): Flow<List<RuleEntity>>
}

@Dao
interface LoanDao : SyncDao<LoanEntity> {
    @Upsert override suspend fun upsertAll(rows: List<LoanEntity>)
    @Query("SELECT * FROM loans WHERE id = :id") override suspend fun find(id: String): LoanEntity?
    @Query("SELECT * FROM loans WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<LoanEntity>
    @Query("UPDATE loans SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE loans SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM loans") override suspend fun everything(): List<LoanEntity>
    @Query("UPDATE loans SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM loans") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM loans WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM loans WHERE deleted = 0 ORDER BY closed, name COLLATE NOCASE")
    fun observeAll(): Flow<List<LoanEntity>>
}

@Dao
interface DepositDao : SyncDao<DepositEntity> {
    @Upsert override suspend fun upsertAll(rows: List<DepositEntity>)
    @Query("SELECT * FROM deposits WHERE id = :id") override suspend fun find(id: String): DepositEntity?
    @Query("SELECT * FROM deposits WHERE dirty = 1 ORDER BY hlc LIMIT :limit") override suspend fun dirty(limit: Int): List<DepositEntity>
    @Query("UPDATE deposits SET dirty = 0 WHERE id = :id AND hlc = :hlc") override suspend fun clearDirty(id: String, hlc: String)
    @Query("UPDATE deposits SET dirty = 2 WHERE id = :id AND hlc = :hlc") override suspend fun markRejected(id: String, hlc: String)
    @Query("SELECT * FROM deposits") override suspend fun everything(): List<DepositEntity>
    @Query("UPDATE deposits SET dirty = 1") override suspend fun markAllDirty()
    @Query("DELETE FROM deposits") override suspend fun wipe()
    @Query("SELECT COUNT(*) FROM deposits WHERE deleted = 0") override suspend fun countLive(): Int

    @Query("SELECT * FROM deposits WHERE deleted = 0 ORDER BY status, maturity_date, name COLLATE NOCASE")
    fun observeAll(): Flow<List<DepositEntity>>
}

@Dao
interface RawRecordDao {
    @Upsert suspend fun upsertAll(rows: List<RawRecordEntity>)
    @Query("SELECT * FROM raw_records WHERE entity = :entity AND id = :id") suspend fun find(entity: String, id: String): RawRecordEntity?
    @Query("SELECT * FROM raw_records WHERE dirty = 1 ORDER BY hlc LIMIT :limit") suspend fun dirty(limit: Int): List<RawRecordEntity>
    @Query("UPDATE raw_records SET dirty = 0 WHERE entity = :entity AND id = :id AND hlc = :hlc") suspend fun clearDirty(entity: String, id: String, hlc: String)
    @Query("UPDATE raw_records SET dirty = 2 WHERE entity = :entity AND id = :id AND hlc = :hlc") suspend fun markRejected(entity: String, id: String, hlc: String)
    @Query("SELECT * FROM raw_records") suspend fun everything(): List<RawRecordEntity>
    @Query("UPDATE raw_records SET dirty = 1") suspend fun markAllDirty()
    @Query("DELETE FROM raw_records") suspend fun wipe()
    @Query("SELECT COUNT(*) FROM raw_records WHERE deleted = 0") suspend fun countLive(): Int
    @Query("SELECT * FROM raw_records WHERE entity = :entity") suspend fun ofEntity(entity: String): List<RawRecordEntity>
    @Query("DELETE FROM raw_records WHERE entity = :entity") suspend fun deleteEntity(entity: String)
}

/** Totals for the sync status screen. */
@Dao
interface StatsDao {
    @Query(
        """SELECT (SELECT COUNT(*) FROM accounts WHERE dirty = 1) + (SELECT COUNT(*) FROM categories WHERE dirty = 1)
           + (SELECT COUNT(*) FROM tags WHERE dirty = 1) + (SELECT COUNT(*) FROM transactions WHERE dirty = 1)
           + (SELECT COUNT(*) FROM budgets WHERE dirty = 1) + (SELECT COUNT(*) FROM recurring_rules WHERE dirty = 1)
           + (SELECT COUNT(*) FROM debts WHERE dirty = 1) + (SELECT COUNT(*) FROM raw_records WHERE dirty = 1)
           + (SELECT COUNT(*) FROM transaction_splits WHERE dirty = 1) + (SELECT COUNT(*) FROM rules WHERE dirty = 1)
           + (SELECT COUNT(*) FROM loans WHERE dirty = 1) + (SELECT COUNT(*) FROM deposits WHERE dirty = 1)""",
    )
    fun observePending(): Flow<Int>

    @Query(
        """SELECT (SELECT COUNT(*) FROM accounts WHERE dirty = 2) + (SELECT COUNT(*) FROM categories WHERE dirty = 2)
           + (SELECT COUNT(*) FROM tags WHERE dirty = 2) + (SELECT COUNT(*) FROM transactions WHERE dirty = 2)
           + (SELECT COUNT(*) FROM budgets WHERE dirty = 2) + (SELECT COUNT(*) FROM recurring_rules WHERE dirty = 2)
           + (SELECT COUNT(*) FROM debts WHERE dirty = 2) + (SELECT COUNT(*) FROM raw_records WHERE dirty = 2)
           + (SELECT COUNT(*) FROM transaction_splits WHERE dirty = 2) + (SELECT COUNT(*) FROM rules WHERE dirty = 2)
           + (SELECT COUNT(*) FROM loans WHERE dirty = 2) + (SELECT COUNT(*) FROM deposits WHERE dirty = 2)""",
    )
    fun observeRejected(): Flow<Int>
}
