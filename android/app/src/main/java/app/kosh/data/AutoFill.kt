package app.kosh.data

import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.RuleEntity
import app.kosh.data.db.TagEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Lookup
import app.kosh.domain.QuickEntry
import app.kosh.domain.RuleInput
import app.kosh.domain.Rules
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fills in what a person would otherwise pick by hand: the account, the category and a tidy payee.
 * Used wherever an entry is saved without the full form (a found payment saved from its notification,
 * a one-line entry, a scanned receipt), so all of them guess the same way the form does:
 * your rules first, then words in the text, then what this payee was filed under last time.
 */
class AutoFill(
    private val repo: Repository,
    private val lookup: () -> Lookup,
    private val rules: () -> List<RuleEntity>,
) {
    data class Guess(
        val account: AccountEntity?,
        val categoryId: String?,
        val payee: String?,
        val tagIds: List<String>,
        /** Where the category came from, to explain it ("your rule Swiggy", "last time"). */
        val categoryReason: String?,
    )

    /**
     * [accountQuery] is a name the person typed ("@hdfc"); [accountHint] the last digits a bank message named;
     * [text] free words that may name a category ("groceries big bazaar").
     */
    suspend fun guess(
        type: String,
        payee: String?,
        note: String? = null,
        amount: Long = 0,
        accountQuery: String? = null,
        accountHint: String? = null,
        text: String? = null,
        cardAlert: Boolean = false,
    ): Guess {
        val l = lookup()
        val account = pickAccount(l, accountQuery, accountHint, cardAlert)
        var payeeOut = payee?.trim()?.ifEmpty { null }
        if (type == "transfer") return Guess(account, null, payeeOut, emptyList(), null)
        val ruled = Rules.apply(rules(), RuleInput(payeeOut ?: text, note, type, account?.id, amount))
        var category = ruled.categoryId?.takeIf { fits(l, it, type) }
        var reason = ruled.matched.firstOrNull { it.setCategoryId == category }?.let { "your rule “${it.name ?: it.pattern}”" }
        if (category == null && !text.isNullOrBlank()) {
            val pool = l.categories.filter { !it.archived && !it.deleted }
            val hit = QuickEntry.categoryFromText(pool.filter { it.kind == type }, text) { it.name }
                ?: QuickEntry.categoryFromText(pool, text) { it.name }
            if (hit != null && fits(l, hit.id, type)) {
                category = hit.id
                reason = "the word “${QuickEntry.wordFor(text, hit.name)}”"
                if (payeeOut == null) payeeOut = QuickEntry.withoutWords(text, hit.name).ifEmpty { null }
            }
        }
        if (payeeOut == null && !text.isNullOrBlank()) payeeOut = text.trim()
        payeeOut = ruled.payee ?: payeeOut?.let(::capitalise)
        if (category == null && payeeOut != null) {
            repo.lastWithPayee(payeeOut, type)?.categoryId?.takeIf { fits(l, it, type) }?.let {
                category = it
                reason = "what $payeeOut was filed under last time"
            }
        }
        return Guess(account, category, payeeOut, ruled.tagIds.filter { l.tagById.containsKey(it) }, reason)
    }

    /** Named account, else the one a bank message pointed at, else the only card for a card alert, else the last used. */
    suspend fun pickAccount(l: Lookup, query: String?, hint: String?, cardAlert: Boolean = false): AccountEntity? {
        val active = l.activeAccounts
        QuickEntry.bestMatch(active, query) { it.name }?.let { return it }
        hint?.let { h -> active.firstOrNull { it.name.contains(h) || it.note?.contains(h) == true } }?.let { return it }
        if (cardAlert) active.filter { it.type == "credit_card" }.singleOrNull()?.let { return it }
        val last = repo.lastUsedAccountId()
        return active.firstOrNull { it.id == last } ?: active.firstOrNull()
    }

    /** Tag IDs for names typed as "#work": existing tags by name, new ones created. */
    suspend fun tagsFor(names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val l = lookup()
        return names.map { n ->
            l.activeTags.firstOrNull { it.name.equals(n, ignoreCase = true) }?.id
                ?: Ids.newV7().also { repo.saveTag(TagEntity(id = it, name = n, createdAt = System.currentTimeMillis())) }
        }.distinct()
    }

    /** A ready-to-save transaction (not saved). [at] is when it happened; a past date alone is taken as noon. */
    fun build(
        type: String,
        account: AccountEntity,
        amount: Long,
        categoryId: String?,
        payee: String?,
        note: String?,
        tagIds: List<String>,
        date: LocalDate = Dates.today(),
        at: Long? = null,
    ): TransactionEntity {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val occurred = at ?: if (date == Dates.today()) now else date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val localDate = java.time.Instant.ofEpochMilli(occurred).atZone(zone).toLocalDate()
        return TransactionEntity(
            id = Ids.newV7(now), type = type, accountId = account.id, amount = amount,
            categoryId = categoryId, payee = payee?.take(200), note = note?.take(2000),
            occurredAt = occurred, localDate = localDate.toString(), tzOffsetMin = Dates.tzOffsetMinutes(occurred, zone),
            tagIds = tagIds, createdAt = now,
        )
    }

    private fun fits(l: Lookup, categoryId: String, type: String): Boolean {
        val c = l.categoryById[categoryId] ?: return false
        if (c.archived || c.deleted) return false
        return c.kind == type || (type == "income" && c.kind == "expense") // income in an expense category is a refund
    }

    companion object {
        fun capitalise(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        /** "₹450 · Food · Swiggy" for a notification or a confirmation line. */
        fun summary(l: Lookup, amount: Long, currency: String, categoryId: String?, payee: String?): String =
            listOfNotNull(Money.ui(amount, currency), categoryId?.let { l.categoryPath(it) }, payee).joinToString(" · ")
    }
}
