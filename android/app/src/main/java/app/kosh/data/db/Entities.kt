package app.kosh.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Columns shared by every synced row (docs/04, "Sync columns").
 *
 * - [hlc]: hybrid logical clock of the last write; decides last-writer-wins.
 * - [deleted]: tombstone. Rows are never hard-deleted so deletes can sync.
 * - [dirty]: [Dirty.CLEAN], [Dirty.PENDING] (not pushed yet) or [Dirty.REJECTED].
 * - [extras]: JSON object of fields this app version does not know, kept so a
 *   newer client's data survives a round trip through this one.
 */
interface Synced<T> {
    val id: String
    val hlc: String
    val deleted: Boolean
    val dirty: Int
    val extras: String
    fun withMeta(hlc: String, deleted: Boolean, dirty: Int): T
}

object Dirty {
    const val CLEAN = 0
    const val PENDING = 1
    /** The server refused it (validation). Not retried until edited again. */
    const val REJECTED = 2
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey override val id: String,
    val name: String,
    val type: String,
    val currency: String,
    @ColumnInfo(name = "opening_balance") val openingBalance: Long = 0,
    @ColumnInfo(name = "credit_limit") val creditLimit: Long? = null,
    val color: String? = null,
    val icon: String? = null,
    @ColumnInfo(name = "include_in_net_worth") val includeInNetWorth: Boolean = true,
    val archived: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Long = 0,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<AccountEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey override val id: String,
    val name: String,
    val kind: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    val nature: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val archived: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<CategoryEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey override val id: String,
    val name: String,
    val color: String? = null,
    val archived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<TagEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

@Entity(
    tableName = "transactions",
    indices = [
        Index("local_date"), Index("account_id"), Index("to_account_id"), Index("category_id"),
        Index("payee"), Index("recurring_rule_id"),
    ],
)
data class TransactionEntity(
    @PrimaryKey override val id: String,
    val type: String,
    val status: String = "cleared",
    @ColumnInfo(name = "account_id") val accountId: String,
    val amount: Long,
    @ColumnInfo(name = "to_account_id") val toAccountId: String? = null,
    @ColumnInfo(name = "to_amount") val toAmount: Long? = null,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    val payee: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "tz_offset_min") val tzOffsetMin: Int = 0,
    @ColumnInfo(name = "tag_ids") val tagIds: List<String> = emptyList(),
    @ColumnInfo(name = "original_amount") val originalAmount: Long? = null,
    @ColumnInfo(name = "original_currency") val originalCurrency: String? = null,
    @ColumnInfo(name = "recurring_rule_id") val recurringRuleId: String? = null,
    @ColumnInfo(name = "debt_id") val debtId: String? = null,
    @ColumnInfo(name = "is_adjustment") val isAdjustment: Boolean = false,
    @ColumnInfo(name = "excluded_from_reports") val excludedFromReports: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<TransactionEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey override val id: String,
    val name: String,
    val amount: Long,
    val currency: String,
    val period: String,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String? = null,
    @ColumnInfo(name = "category_ids") val categoryIds: List<String> = emptyList(),
    @ColumnInfo(name = "account_ids") val accountIds: List<String> = emptyList(),
    val rollover: Boolean = false,
    @ColumnInfo(name = "alert_percent") val alertPercent: Int = 80,
    val archived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<BudgetEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey override val id: String,
    val name: String? = null,
    val type: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "to_account_id") val toAccountId: String? = null,
    val amount: Long,
    @ColumnInfo(name = "to_amount") val toAmount: Long? = null,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    val payee: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "tag_ids") val tagIds: List<String> = emptyList(),
    val frequency: String,
    val every: Int = 1,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String? = null,
    @ColumnInfo(name = "max_occurrences") val maxOccurrences: Int? = null,
    val mode: String = "remind",
    @ColumnInfo(name = "next_date") val nextDate: String? = null,
    val paused: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<RecurringRuleEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey override val id: String,
    val person: String,
    val direction: String,
    val amount: Long,
    val currency: String,
    @ColumnInfo(name = "due_date") val dueDate: String? = null,
    val note: String? = null,
    val closed: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<DebtEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}


/** One line of a split transaction (docs/04). Splits replace the transaction's category in reports when they add up to its amount. */
@Entity(tableName = "transaction_splits", indices = [Index("transaction_id")])
data class SplitEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val amount: Long,
    val note: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<SplitEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

/** A categorisation rule (docs/02, "Rules"): when a transaction matches, suggest a category, payee and tags. */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey override val id: String,
    val name: String? = null,
    @ColumnInfo(name = "match_field") val matchField: String = "payee",
    @ColumnInfo(name = "match_type") val matchType: String = "contains",
    val pattern: String,
    @ColumnInfo(name = "tx_type") val txType: String? = null,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    @ColumnInfo(name = "min_amount") val minAmount: Long? = null,
    @ColumnInfo(name = "max_amount") val maxAmount: Long? = null,
    @ColumnInfo(name = "set_category_id") val setCategoryId: String? = null,
    @ColumnInfo(name = "set_payee") val setPayee: String? = null,
    @ColumnInfo(name = "add_tag_ids") val addTagIds: List<String> = emptyList(),
    @ColumnInfo(name = "sort_order") val sortOrder: Long = 0,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<RuleEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

/**
 * Entities this app version has no screen for (exchange_rates, or ones added by
 * newer clients). Stored verbatim so they still sync, back up and restore without loss.
 */
@Entity(tableName = "raw_records", primaryKeys = ["entity", "id"])
data class RawRecordEntity(
    val entity: String,
    override val id: String,
    val data: String,
    override val hlc: String = "",
    override val deleted: Boolean = false,
    override val dirty: Int = Dirty.CLEAN,
    override val extras: String = "{}",
) : Synced<RawRecordEntity> {
    override fun withMeta(hlc: String, deleted: Boolean, dirty: Int) = copy(hlc = hlc, deleted = deleted, dirty = dirty)
}

/** Read models returned by aggregate queries. */
data class AccountBalance(
    @ColumnInfo(name = "account_id") val accountId: String,
    val balance: Long,
)

data class ReportRow(
    val id: String,
    val type: String,
    val amount: Long,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "account_id") val accountId: String,
    val currency: String?,
    @ColumnInfo(name = "local_date") val localDate: String,
    val payee: String?,
    @ColumnInfo(name = "tag_ids") val tagIds: List<String> = emptyList(),
)

data class CategoryUse(
    @ColumnInfo(name = "category_id") val categoryId: String,
    val uses: Int,
)
