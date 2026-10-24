package app.kosh.domain

import app.kosh.data.db.RuleEntity
import java.util.Locale

/** What a rule looks at. Amount is in minor units; 0 means "not entered yet". */
data class RuleInput(
    val payee: String?,
    val note: String?,
    val type: String,
    val accountId: String?,
    val amount: Long,
)

/** The combined suggestion of every matching rule. */
data class RuleOutcome(
    val categoryId: String?,
    val payee: String?,
    val tagIds: List<String>,
    val matched: List<RuleEntity>,
) {
    val isEmpty: Boolean get() = matched.isEmpty()
}

/**
 * Categorisation rules (docs/02, "Rules"). The same algorithm runs in the web
 * app (web/src/lib/rules.ts), with the same test cases:
 *
 *  - Enabled, live rules are tried in `sort_order`, then creation order.
 *  - Text matching is case-insensitive on trimmed text: contains, equals or
 *    starts with. `any` checks the payee and the note.
 *  - Optional filters: transaction type, account, and an inclusive amount
 *    range. A rule with an amount range never matches before an amount is entered.
 *  - The first matching rule that sets a category decides the category, and
 *    likewise for the payee; tags from every matching rule are combined.
 */
object Rules {
    fun ordered(rules: List<RuleEntity>): List<RuleEntity> =
        rules.filter { it.enabled && !it.deleted && it.pattern.isNotBlank() }
            .sortedWith(compareBy<RuleEntity>({ it.sortOrder }, { it.createdAt }, { it.id }))

    fun matches(rule: RuleEntity, input: RuleInput): Boolean {
        if (!rule.enabled || rule.deleted) return false
        if (rule.txType != null && rule.txType != input.type) return false
        if (rule.accountId != null && rule.accountId != input.accountId) return false
        if (rule.minAmount != null || rule.maxAmount != null) {
            if (input.amount <= 0) return false
            if (rule.minAmount != null && input.amount < rule.minAmount) return false
            if (rule.maxAmount != null && input.amount > rule.maxAmount) return false
        }
        val pattern = norm(rule.pattern) ?: return false
        val texts = when (rule.matchField) {
            "note" -> listOf(input.note)
            "any" -> listOf(input.payee, input.note)
            else -> listOf(input.payee)
        }
        return texts.any { t ->
            val s = norm(t) ?: return@any false
            when (rule.matchType) {
                "equals" -> s == pattern
                "starts_with" -> s.startsWith(pattern)
                else -> s.contains(pattern)
            }
        }
    }

    fun apply(rules: List<RuleEntity>, input: RuleInput): RuleOutcome {
        var category: String? = null
        var payee: String? = null
        val tags = LinkedHashSet<String>()
        val matched = ArrayList<RuleEntity>()
        for (r in ordered(rules)) {
            if (!matches(r, input)) continue
            matched += r
            if (category == null) category = r.setCategoryId
            if (payee == null) payee = r.setPayee?.trim()?.ifEmpty { null }
            tags += r.addTagIds
        }
        return RuleOutcome(category, payee, tags.toList(), matched)
    }

    private fun norm(s: String?): String? = s?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }
}

/** Applies rules to transactions already in the book. */
class RuleService(private val repo: app.kosh.data.Repository) {
    /** Past transactions [rule] matches and would change. */
    suspend fun matches(rule: RuleEntity): List<app.kosh.data.db.TransactionEntity> =
        repo.db.transactions().everything().filter { t ->
            !t.deleted && t.type != "transfer" && !t.isAdjustment &&
                Rules.matches(rule.copy(enabled = true), RuleInput(t.payee, t.note, t.type, t.accountId, t.amount)) &&
                changed(rule, t) != t
        }

    /** Applies [rule] to every past transaction it matches. Returns how many changed. */
    suspend fun applyToPast(rule: RuleEntity): Int {
        val list = matches(rule).map { changed(rule, it) }
        repo.saveTransactions(list)
        return list.size
    }

    private fun changed(rule: RuleEntity, t: app.kosh.data.db.TransactionEntity) = t.copy(
        categoryId = rule.setCategoryId ?: t.categoryId,
        payee = rule.setPayee?.trim()?.ifEmpty { null } ?: t.payee,
        tagIds = (t.tagIds + rule.addTagIds).distinct(),
    )
}
