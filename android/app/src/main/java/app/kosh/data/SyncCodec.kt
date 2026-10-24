package app.kosh.data

import app.kosh.data.db.AccountEntity
import app.kosh.data.db.BudgetEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.DebtEntity
import app.kosh.data.db.DepositEntity
import app.kosh.data.db.LoanEntity
import app.kosh.data.db.KoshDatabase
import app.kosh.data.db.RawRecordEntity
import app.kosh.data.db.RecurringRuleEntity
import app.kosh.data.db.RuleEntity
import app.kosh.data.db.SplitEntity
import app.kosh.data.db.SyncDao
import app.kosh.data.db.Synced
import app.kosh.data.db.TagEntity
import app.kosh.data.db.TransactionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/** A record as it travels in sync, backups and exports (docs/05). */
@Serializable
data class WireRecord(
    val entity: String,
    val id: String,
    val hlc: String,
    val deleted: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
    val seq: Long = 0,
)

val KoshJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

/** Converts one entity type between its Room row and wire data. */
abstract class SyncTable<T : Synced<T>>(val entity: String, private val dao: SyncDao<T>) {
    /** Field names this app understands; anything else goes to `extras`. */
    abstract val fields: Set<String>
    protected abstract fun JsonObjectBuilder.encodeFields(row: T)
    protected abstract fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int): T

    fun encode(row: T): JsonObject = buildJsonObject {
        for ((k, v) in parseExtras(row.extras)) put(k, v)
        encodeFields(row)
    }

    fun decode(id: String, hlc: String, deleted: Boolean, data: JsonObject, dirty: Int): T {
        val extras = data.filterKeys { it !in fields }
        val extrasJson = if (extras.isEmpty()) "{}" else KoshJson.encodeToString(JsonObject.serializer(), JsonObject(extras))
        return decodeFields(id, data, extrasJson, hlc, deleted, dirty)
    }

    fun toWire(row: T) = WireRecord(entity, row.id, row.hlc, row.deleted, encode(row))
    fun fromWire(r: WireRecord, dirty: Int): T = decode(r.id, r.hlc, r.deleted, r.data, dirty)

    open suspend fun find(id: String): T? = dao.find(id)
    open suspend fun upsert(rows: List<T>) = dao.upsertAll(rows)
    open suspend fun dirty(limit: Int): List<T> = dao.dirty(limit)
    open suspend fun clearDirty(id: String, hlc: String) = dao.clearDirty(id, hlc)
    open suspend fun markRejected(id: String, hlc: String) = dao.markRejected(id, hlc)
    open suspend fun everything(): List<T> = dao.everything()
    open suspend fun markAllDirty() = dao.markAllDirty()
    open suspend fun wipe() = dao.wipe()
    open suspend fun countLive(): Int = dao.countLive()

    private fun parseExtras(s: String): JsonObject =
        if (s.isEmpty() || s == "{}") JsonObject(emptyMap())
        else runCatching { KoshJson.parseToJsonElement(s) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
}

// --- JSON helpers -------------------------------------------------------------

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonObject.int(k: String): Int? = long(k)?.toInt()
internal fun JsonObject.bool(k: String, default: Boolean): Boolean =
    (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default
internal fun JsonObject.ids(k: String): List<String> =
    (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

private fun JsonObjectBuilder.put(k: String, v: String?) { put(k, v?.let(::JsonPrimitive) ?: JsonNull) }
private fun JsonObjectBuilder.put(k: String, v: Long?) { put(k, v?.let(::JsonPrimitive) ?: JsonNull) }
private fun JsonObjectBuilder.put(k: String, v: Int?) { put(k, v?.let(::JsonPrimitive) ?: JsonNull) }
private fun JsonObjectBuilder.put(k: String, v: Boolean) { put(k, JsonPrimitive(v)) }
private fun JsonObjectBuilder.put(k: String, v: List<String>) { put(k, JsonArray(v.map(::JsonPrimitive))) }

// --- Tables -------------------------------------------------------------------

class AccountsTable(dao: SyncDao<AccountEntity>) : SyncTable<AccountEntity>("accounts", dao) {
    override val fields = setOf(
        "name", "type", "currency", "opening_balance", "credit_limit", "color", "icon",
        "include_in_net_worth", "archived", "sort_order", "note", "created_at",
    )

    override fun JsonObjectBuilder.encodeFields(row: AccountEntity) {
        put("name", row.name); put("type", row.type); put("currency", row.currency)
        put("opening_balance", row.openingBalance); put("credit_limit", row.creditLimit)
        put("color", row.color); put("icon", row.icon); put("include_in_net_worth", row.includeInNetWorth)
        put("archived", row.archived); put("sort_order", row.sortOrder); put("note", row.note)
        put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        AccountEntity(
            id = id, name = d.str("name") ?: "", type = d.str("type") ?: "other", currency = d.str("currency") ?: "XXX",
            openingBalance = d.long("opening_balance") ?: 0, creditLimit = d.long("credit_limit"),
            color = d.str("color"), icon = d.str("icon"), includeInNetWorth = d.bool("include_in_net_worth", true),
            archived = d.bool("archived", false), sortOrder = d.long("sort_order") ?: 0, note = d.str("note"),
            createdAt = d.long("created_at") ?: 0, hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class CategoriesTable(dao: SyncDao<CategoryEntity>) : SyncTable<CategoryEntity>("categories", dao) {
    override val fields = setOf("name", "kind", "parent_id", "nature", "color", "icon", "archived", "sort_order", "created_at")

    override fun JsonObjectBuilder.encodeFields(row: CategoryEntity) {
        put("name", row.name); put("kind", row.kind); put("parent_id", row.parentId); put("nature", row.nature)
        put("color", row.color); put("icon", row.icon); put("archived", row.archived)
        put("sort_order", row.sortOrder); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        CategoryEntity(
            id = id, name = d.str("name") ?: "", kind = d.str("kind") ?: "expense", parentId = d.str("parent_id"),
            nature = d.str("nature"), color = d.str("color"), icon = d.str("icon"), archived = d.bool("archived", false),
            sortOrder = d.long("sort_order") ?: 0, createdAt = d.long("created_at") ?: 0,
            hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class TagsTable(dao: SyncDao<TagEntity>) : SyncTable<TagEntity>("tags", dao) {
    override val fields = setOf("name", "color", "archived", "created_at")

    override fun JsonObjectBuilder.encodeFields(row: TagEntity) {
        put("name", row.name); put("color", row.color); put("archived", row.archived); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        TagEntity(
            id = id, name = d.str("name") ?: "", color = d.str("color"), archived = d.bool("archived", false),
            createdAt = d.long("created_at") ?: 0, hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class DebtsTable(dao: SyncDao<DebtEntity>) : SyncTable<DebtEntity>("debts", dao) {
    override val fields = setOf("person", "direction", "amount", "currency", "due_date", "note", "closed", "created_at")

    override fun JsonObjectBuilder.encodeFields(row: DebtEntity) {
        put("person", row.person); put("direction", row.direction); put("amount", row.amount)
        put("currency", row.currency); put("due_date", row.dueDate); put("note", row.note)
        put("closed", row.closed); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        DebtEntity(
            id = id, person = d.str("person") ?: "", direction = d.str("direction") ?: "lent",
            amount = d.long("amount") ?: 0, currency = d.str("currency") ?: "XXX", dueDate = d.str("due_date"),
            note = d.str("note"), closed = d.bool("closed", false), createdAt = d.long("created_at") ?: 0,
            hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class LoansTable(dao: SyncDao<LoanEntity>) : SyncTable<LoanEntity>("loans", dao) {
    override val fields = setOf(
        "name", "lender", "kind", "account_id", "currency", "principal", "annual_rate", "rate_type", "start_date",
        "first_due_date", "tenure_months", "emi_amount", "paid_before", "pay_from_account_id", "interest_category_id",
        "processing_fee", "remind_days", "closed", "closed_date", "note", "created_at",
    )

    override fun JsonObjectBuilder.encodeFields(row: LoanEntity) {
        put("name", row.name); put("lender", row.lender); put("kind", row.kind); put("account_id", row.accountId)
        put("currency", row.currency); put("principal", row.principal); put("annual_rate", row.annualRate)
        put("rate_type", row.rateType); put("start_date", row.startDate); put("first_due_date", row.firstDueDate)
        put("tenure_months", row.tenureMonths); put("emi_amount", row.emiAmount); put("paid_before", row.paidBefore)
        put("pay_from_account_id", row.payFromAccountId); put("interest_category_id", row.interestCategoryId)
        put("processing_fee", row.processingFee); put("remind_days", row.remindDays); put("closed", row.closed)
        put("closed_date", row.closedDate); put("note", row.note); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        LoanEntity(
            id = id, name = d.str("name") ?: "", lender = d.str("lender"), kind = d.str("kind") ?: "personal",
            accountId = d.str("account_id") ?: "", currency = d.str("currency") ?: "XXX", principal = d.long("principal") ?: 0,
            annualRate = d.str("annual_rate"), rateType = d.str("rate_type") ?: "fixed",
            startDate = d.str("start_date") ?: "1970-01-01", firstDueDate = d.str("first_due_date") ?: d.str("start_date") ?: "1970-01-01",
            tenureMonths = d.int("tenure_months") ?: 1, emiAmount = d.long("emi_amount"), paidBefore = d.int("paid_before") ?: 0,
            payFromAccountId = d.str("pay_from_account_id"), interestCategoryId = d.str("interest_category_id"),
            processingFee = d.long("processing_fee"), remindDays = d.int("remind_days") ?: 3, closed = d.bool("closed", false),
            closedDate = d.str("closed_date"), note = d.str("note"), createdAt = d.long("created_at") ?: 0,
            hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class DepositsTable(dao: SyncDao<DepositEntity>) : SyncTable<DepositEntity>("deposits", dao) {
    override val fields = setOf(
        "name", "institution", "kind", "account_id", "currency", "amount", "annual_rate", "start_date", "maturity_date",
        "compounding", "payout", "payout_account_id", "maturity_amount", "tax_percent", "status", "closed_date",
        "auto_renew", "remind_days", "note", "created_at",
    )

    override fun JsonObjectBuilder.encodeFields(row: DepositEntity) {
        put("name", row.name); put("institution", row.institution); put("kind", row.kind); put("account_id", row.accountId)
        put("currency", row.currency); put("amount", row.amount); put("annual_rate", row.annualRate)
        put("start_date", row.startDate); put("maturity_date", row.maturityDate); put("compounding", row.compounding)
        put("payout", row.payout); put("payout_account_id", row.payoutAccountId); put("maturity_amount", row.maturityAmount)
        put("tax_percent", row.taxPercent); put("status", row.status); put("closed_date", row.closedDate)
        put("auto_renew", row.autoRenew); put("remind_days", row.remindDays); put("note", row.note); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        DepositEntity(
            id = id, name = d.str("name") ?: "", institution = d.str("institution"), kind = d.str("kind") ?: "fd",
            accountId = d.str("account_id") ?: "", currency = d.str("currency") ?: "XXX", amount = d.long("amount") ?: 0,
            annualRate = d.str("annual_rate") ?: "0", startDate = d.str("start_date") ?: "1970-01-01",
            maturityDate = d.str("maturity_date") ?: "1970-01-02", compounding = d.str("compounding") ?: "quarterly",
            payout = d.str("payout") ?: "cumulative", payoutAccountId = d.str("payout_account_id"), maturityAmount = d.long("maturity_amount"),
            taxPercent = d.str("tax_percent"), status = d.str("status") ?: "active", closedDate = d.str("closed_date"),
            autoRenew = d.bool("auto_renew", false), remindDays = d.int("remind_days") ?: 7, note = d.str("note"),
            createdAt = d.long("created_at") ?: 0, hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class RecurringTable(dao: SyncDao<RecurringRuleEntity>) : SyncTable<RecurringRuleEntity>("recurring_rules", dao) {
    override val fields = setOf(
        "name", "type", "account_id", "to_account_id", "amount", "to_amount", "category_id", "payee", "note",
        "tag_ids", "frequency", "every", "start_date", "end_date", "max_occurrences", "mode", "next_date",
        "paused", "created_at",
    )

    override fun JsonObjectBuilder.encodeFields(row: RecurringRuleEntity) {
        put("name", row.name); put("type", row.type); put("account_id", row.accountId)
        put("to_account_id", row.toAccountId); put("amount", row.amount); put("to_amount", row.toAmount)
        put("category_id", row.categoryId); put("payee", row.payee); put("note", row.note); put("tag_ids", row.tagIds)
        put("frequency", row.frequency); put("every", row.every); put("start_date", row.startDate)
        put("end_date", row.endDate); put("max_occurrences", row.maxOccurrences); put("mode", row.mode)
        put("next_date", row.nextDate); put("paused", row.paused); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        RecurringRuleEntity(
            id = id, name = d.str("name"), type = d.str("type") ?: "expense", accountId = d.str("account_id") ?: "",
            toAccountId = d.str("to_account_id"), amount = d.long("amount") ?: 0, toAmount = d.long("to_amount"),
            categoryId = d.str("category_id"), payee = d.str("payee"), note = d.str("note"), tagIds = d.ids("tag_ids"),
            frequency = d.str("frequency") ?: "monthly", every = d.int("every") ?: 1,
            startDate = d.str("start_date") ?: "1970-01-01", endDate = d.str("end_date"),
            maxOccurrences = d.int("max_occurrences"), mode = d.str("mode") ?: "remind", nextDate = d.str("next_date"),
            paused = d.bool("paused", false), createdAt = d.long("created_at") ?: 0,
            hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class TransactionsTable(dao: SyncDao<TransactionEntity>) : SyncTable<TransactionEntity>("transactions", dao) {
    override val fields = setOf(
        "type", "status", "account_id", "amount", "to_account_id", "to_amount", "category_id", "payee", "note",
        "occurred_at", "local_date", "tz_offset_min", "tag_ids", "original_amount", "original_currency",
        "recurring_rule_id", "debt_id", "loan_id", "deposit_id", "part", "is_adjustment", "excluded_from_reports", "created_at",
    )

    override fun JsonObjectBuilder.encodeFields(row: TransactionEntity) {
        put("type", row.type); put("status", row.status); put("account_id", row.accountId); put("amount", row.amount)
        put("to_account_id", row.toAccountId); put("to_amount", row.toAmount); put("category_id", row.categoryId)
        put("payee", row.payee); put("note", row.note); put("occurred_at", row.occurredAt)
        put("local_date", row.localDate); put("tz_offset_min", row.tzOffsetMin); put("tag_ids", row.tagIds)
        put("original_amount", row.originalAmount); put("original_currency", row.originalCurrency)
        put("recurring_rule_id", row.recurringRuleId); put("debt_id", row.debtId)
        put("loan_id", row.loanId); put("deposit_id", row.depositId); put("part", row.part)
        put("is_adjustment", row.isAdjustment); put("excluded_from_reports", row.excludedFromReports)
        put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        TransactionEntity(
            id = id, type = d.str("type") ?: "expense", status = d.str("status") ?: "cleared",
            accountId = d.str("account_id") ?: "", amount = d.long("amount") ?: 0, toAccountId = d.str("to_account_id"),
            toAmount = d.long("to_amount"), categoryId = d.str("category_id"), payee = d.str("payee"),
            note = d.str("note"), occurredAt = d.long("occurred_at") ?: 0, localDate = d.str("local_date") ?: "1970-01-01",
            tzOffsetMin = d.int("tz_offset_min") ?: 0, tagIds = d.ids("tag_ids"),
            originalAmount = d.long("original_amount"), originalCurrency = d.str("original_currency"),
            recurringRuleId = d.str("recurring_rule_id"), debtId = d.str("debt_id"),
            loanId = d.str("loan_id"), depositId = d.str("deposit_id"), part = d.str("part"),
            isAdjustment = d.bool("is_adjustment", false), excludedFromReports = d.bool("excluded_from_reports", false),
            createdAt = d.long("created_at") ?: 0, hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class BudgetsTable(dao: SyncDao<BudgetEntity>) : SyncTable<BudgetEntity>("budgets", dao) {
    override val fields = setOf(
        "name", "amount", "currency", "period", "start_date", "end_date", "category_ids", "account_ids",
        "rollover", "alert_percent", "archived", "created_at",
    )

    override fun JsonObjectBuilder.encodeFields(row: BudgetEntity) {
        put("name", row.name); put("amount", row.amount); put("currency", row.currency); put("period", row.period)
        put("start_date", row.startDate); put("end_date", row.endDate); put("category_ids", row.categoryIds)
        put("account_ids", row.accountIds); put("rollover", row.rollover); put("alert_percent", row.alertPercent)
        put("archived", row.archived); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        BudgetEntity(
            id = id, name = d.str("name") ?: "", amount = d.long("amount") ?: 0, currency = d.str("currency") ?: "XXX",
            period = d.str("period") ?: "monthly", startDate = d.str("start_date") ?: "1970-01-01",
            endDate = d.str("end_date"), categoryIds = d.ids("category_ids"), accountIds = d.ids("account_ids"),
            rollover = d.bool("rollover", false), alertPercent = d.int("alert_percent") ?: 80,
            archived = d.bool("archived", false), createdAt = d.long("created_at") ?: 0,
            hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}


class SplitsTable(dao: SyncDao<SplitEntity>) : SyncTable<SplitEntity>("transaction_splits", dao) {
    override val fields = setOf("transaction_id", "category_id", "amount", "note", "sort_order")

    override fun JsonObjectBuilder.encodeFields(row: SplitEntity) {
        put("transaction_id", row.transactionId); put("category_id", row.categoryId); put("amount", row.amount)
        put("note", row.note); put("sort_order", row.sortOrder)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        SplitEntity(
            id = id, transactionId = d.str("transaction_id") ?: "", categoryId = d.str("category_id") ?: "",
            amount = d.long("amount") ?: 0, note = d.str("note"), sortOrder = d.long("sort_order") ?: 0,
            hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

class RulesTable(dao: SyncDao<RuleEntity>) : SyncTable<RuleEntity>("rules", dao) {
    override val fields = setOf(
        "name", "match_field", "match_type", "pattern", "tx_type", "account_id", "min_amount", "max_amount",
        "set_category_id", "set_payee", "add_tag_ids", "sort_order", "enabled", "created_at",
    )

    override fun JsonObjectBuilder.encodeFields(row: RuleEntity) {
        put("name", row.name); put("match_field", row.matchField); put("match_type", row.matchType)
        put("pattern", row.pattern); put("tx_type", row.txType); put("account_id", row.accountId)
        put("min_amount", row.minAmount); put("max_amount", row.maxAmount); put("set_category_id", row.setCategoryId)
        put("set_payee", row.setPayee); put("add_tag_ids", row.addTagIds); put("sort_order", row.sortOrder)
        put("enabled", row.enabled); put("created_at", row.createdAt)
    }

    override fun decodeFields(id: String, d: JsonObject, extras: String, hlc: String, deleted: Boolean, dirty: Int) =
        RuleEntity(
            id = id, name = d.str("name"), matchField = d.str("match_field") ?: "payee",
            matchType = d.str("match_type") ?: "contains", pattern = d.str("pattern") ?: "",
            txType = d.str("tx_type"), accountId = d.str("account_id"), minAmount = d.long("min_amount"),
            maxAmount = d.long("max_amount"), setCategoryId = d.str("set_category_id"), setPayee = d.str("set_payee"),
            addTagIds = d.ids("add_tag_ids"), sortOrder = d.long("sort_order") ?: 0, enabled = d.bool("enabled", true),
            createdAt = d.long("created_at") ?: 0, hlc = hlc, deleted = deleted, dirty = dirty, extras = extras,
        )
}

/** Entities without a typed table: stored as JSON. */
class RawTable(private val db: KoshDatabase) {
    suspend fun find(entity: String, id: String) = db.raw().find(entity, id)
    suspend fun upsert(rows: List<RawRecordEntity>) = db.raw().upsertAll(rows)

    fun toWire(row: RawRecordEntity) = WireRecord(
        row.entity, row.id, row.hlc, row.deleted,
        runCatching { KoshJson.parseToJsonElement(row.data) as JsonObject }.getOrDefault(JsonObject(emptyMap())),
    )

    fun fromWire(r: WireRecord, dirty: Int) = RawRecordEntity(
        entity = r.entity, id = r.id, hlc = r.hlc, deleted = r.deleted, dirty = dirty,
        data = KoshJson.encodeToString(JsonObject.serializer(), r.data),
    )
}

/** All tables in dependency order (same order as the server's registry). */
class SyncTables(db: KoshDatabase) {
    val accounts = AccountsTable(db.accounts())
    val categories = CategoriesTable(db.categories())
    val tags = TagsTable(db.tags())
    val debts = DebtsTable(db.debts())
    val loans = LoansTable(db.loans())
    val deposits = DepositsTable(db.deposits())
    val recurring = RecurringTable(db.recurring())
    val transactions = TransactionsTable(db.transactions())
    val splits = SplitsTable(db.splits())
    val budgets = BudgetsTable(db.budgets())
    val rules = RulesTable(db.rules())
    val raw = RawTable(db)

    val typed: List<SyncTable<*>> = listOf(accounts, categories, tags, debts, loans, deposits, recurring, transactions, splits, budgets, rules)
    private val byName = typed.associateBy { it.entity }

    fun table(entity: String): SyncTable<*>? = byName[entity]
}
