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
     * The money picture split the way people think about it: spendable cash is
     * not the same as investments, and card or loan debt must stay visible
     * rather than hide inside an expense total. Values are per currency.
     */
    data class Position(
        val available: Map<String, Long>,
        val investments: Map<String, Long>,
        val debt: Map<String, Long>,
        val netWorth: Map<String, Long>,
    )

    val position: Position by lazy {
        val live = accounts.filter { it.includeInNetWorth && !it.archived }
        fun sum(filter: (app.kosh.data.db.AccountEntity, Long) -> Long?): Map<String, Long> =
            live.mapNotNull { a -> filter(a, balances[a.id] ?: a.openingBalance)?.let { a.currency to it } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.filterValues { it != 0L }
        Position(
            available = sum { a, b -> b.takeIf { a.type in setOf("cash", "bank", "wallet", "savings", "other") } },
            investments = sum { a, b -> b.takeIf { a.type == "investment" } },
            // Liabilities: what is owed on cards and loans (and any overdrawn account), shown positive.
            debt = sum { a, b -> if (a.type == "credit_card" || a.type == "loan") (-b).coerceAtLeast(0) else null },
            netWorth = netWorth,
        )
    }

    /** Net worth per currency over accounts that count towards it. */
    val netWorth: Map<String, Long> by lazy {
        accounts.filter { it.includeInNetWorth && !it.archived }
            .groupBy { it.currency }
            .mapValues { (_, list) -> list.sumOf { balances[it.id] ?: it.openingBalance } }
    }
}
