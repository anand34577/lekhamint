package app.kosh.domain

import app.kosh.data.db.AccountEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.TagEntity

/**
 * Reference data most screens need, kept in memory and refreshed whenever the
 * underlying tables change. Missing references are tolerated everywhere
 * (records sync in any order, docs/04).
 */
data class Lookup(
    val accounts: List<AccountEntity> = emptyList(),
    val balances: Map<String, Long> = emptyMap(),
    val categories: List<CategoryEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val baseCurrency: String = "USD",
    /** Category IDs of each split transaction, by transaction ID. */
    val splitCategories: Map<String, List<String>> = emptyMap(),
    /** Open personal debts per currency: what others owe you, and what you owe them (Debts.totals). */
    val receivable: Map<String, Long> = emptyMap(),
    val payable: Map<String, Long> = emptyMap(),
) {
    val tagById: Map<String, TagEntity> = tags.associateBy { it.id }
    val activeTags: List<TagEntity> = tags.filter { !it.archived }

    fun tagNames(ids: List<String>): List<String> = ids.mapNotNull { tagById[it]?.name }

    fun isSplit(txId: String): Boolean = splitCategories.containsKey(txId)

    val accountById: Map<String, AccountEntity> = accounts.associateBy { it.id }
    val categoryById: Map<String, CategoryEntity> = categories.associateBy { it.id }
    val activeAccounts: List<AccountEntity> = accounts.filter { !it.archived }

    private val childrenByParent: Map<String?, List<CategoryEntity>> =
        categories.filter { !it.archived }.groupBy { it.parentId?.takeIf { p -> categoryById.containsKey(p) } }

    fun roots(kind: String): List<CategoryEntity> =
        childrenByParent[null].orEmpty().filter { it.kind == kind }.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))

    fun children(parentId: String): List<CategoryEntity> =
        childrenByParent[parentId].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))

    fun currencyOf(accountId: String?): String = accountId?.let { accountById[it]?.currency } ?: baseCurrency

    fun accountName(id: String?): String = id?.let { accountById[it]?.name } ?: "Unknown account"

    /** "Food · Groceries" for a sub-category, "Food" for a root. */
    fun categoryPath(id: String?): String {
        val c = id?.let { categoryById[it] } ?: return "Uncategorised"
        val parent = c.parentId?.let { categoryById[it] }
        return if (parent != null) "${parent.name} · ${c.name}" else c.name
    }

    fun categoryName(id: String?): String = id?.let { categoryById[it]?.name } ?: "Uncategorised"

    /** Icon and colour, inheriting the parent's colour when the child has none. */
    fun categoryLook(id: String?): Pair<String?, String?> {
        val c = id?.let { categoryById[it] } ?: return "dots" to null
        val parent = c.parentId?.let { categoryById[it] }
        return (c.icon ?: parent?.icon) to (c.color ?: parent?.color)
    }

    /**
     * The money picture split the way people think about it, computed exactly
     * like the server's report.Position (docs/02, "Net worth"):
     *   net worth = cash + investments + owed to you − cards & loans − you owe people.
     * Credit limits are never assets; money lent is still yours. Per currency.
     */
    data class Position(
        val available: Map<String, Long>,
        val investments: Map<String, Long>,
        val debt: Map<String, Long>,
        val receivable: Map<String, Long>,
        val payable: Map<String, Long>,
        val netWorth: Map<String, Long>,
        /** Unused credit on cards: borrowing power, not money you have. */
        val creditAvailable: Map<String, Long>,
    )

    val position: Position by lazy {
        val live = accounts.filter { it.includeInNetWorth && !it.archived }
        fun sum(filter: (app.kosh.data.db.AccountEntity, Long) -> Long?): Map<String, Long> =
            live.mapNotNull { a -> filter(a, balances[a.id] ?: a.openingBalance)?.let { a.currency to it } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.filterValues { it != 0L }
        Position(
            available = sum { a, b -> b.takeIf { !isLiability(a.type) && !isInvestment(a.type) } },
            investments = sum { a, b -> b.takeIf { isInvestment(a.type) } },
            // What is owed on cards and loans, shown positive (a card in credit reduces it).
            debt = sum { a, b -> if (isLiability(a.type)) -b else null },
            receivable = receivable.filterValues { it != 0L },
            payable = payable.filterValues { it != 0L },
            netWorth = netWorth,
            creditAvailable = sum { a, b -> if (a.type == "credit_card" && a.creditLimit != null) (a.creditLimit - (-b).coerceAtLeast(0)).coerceAtLeast(0) else null },
        )
    }

    /** Net worth per currency: accounts that count towards it, plus open personal debts. */
    val netWorth: Map<String, Long> by lazy {
        val m = accounts.filter { it.includeInNetWorth && !it.archived }
            .groupBy { it.currency }
            .mapValues { (_, list) -> list.sumOf { balances[it.id] ?: it.openingBalance } }.toMutableMap()
        for ((c, v) in receivable) m[c] = (m[c] ?: 0) + v
        for ((c, v) in payable) m[c] = (m[c] ?: 0) - v
        m
    }

    /** Archived or excluded accounts that still hold money, so screens can say they are not counted. */
    val excluded: List<app.kosh.data.db.AccountEntity> by lazy {
        accounts.filter { (it.archived || !it.includeInNetWorth) && (balances[it.id] ?: it.openingBalance) != 0L }
    }

    companion object {
        fun isLiability(type: String) = type == "credit_card" || type == "loan"
        fun isInvestment(type: String) = type == "investment" || type == "deposit"
    }
}
