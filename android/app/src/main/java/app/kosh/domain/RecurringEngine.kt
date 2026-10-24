package app.kosh.domain

import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Recurrence
import app.kosh.data.Repository
import app.kosh.data.db.RecurringRuleEntity
import app.kosh.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A bill or subscription that is due or coming up. */
data class Upcoming(val rule: RecurringRuleEntity, val date: LocalDate) {
    val due: Boolean get() = !date.isAfter(Dates.today())
}

/**
 * Generates transactions from recurring rules. Occurrence IDs are UUIDv5 of
 * "<rule id>:<date>", so two devices generating the same occurrence offline
 * produce the same record and sync merges them (docs/05).
 */
class RecurringEngine(private val repo: Repository) {

    fun ruleStart(r: RecurringRuleEntity): LocalDate = LocalDate.parse(r.startDate)

    fun dueDates(r: RecurringRuleEntity, until: LocalDate): List<LocalDate> {
        if (r.paused || r.deleted) return emptyList()
        return Recurrence.due(
            start = ruleStart(r), frequency = r.frequency, every = r.every,
            next = Dates.parse(r.nextDate), until = until, end = Dates.parse(r.endDate), maxOccurrences = r.maxOccurrences,
        ).map { it.date }
    }

    /** Next date the rule fires (today or later), or null if it has ended. */
    fun nextDate(r: RecurringRuleEntity): LocalDate? {
        val from = Dates.parse(r.nextDate) ?: ruleStart(r)
        return Recurrence.due(
            ruleStart(r), r.frequency, r.every, from, LocalDate.MAX.minusYears(1),
            Dates.parse(r.endDate), r.maxOccurrences, limit = 1,
        ).firstOrNull()?.date
    }

    /** Creates every due occurrence of auto-mode rules. Returns how many were added. */
    suspend fun runAuto(today: LocalDate = Dates.today()): Int {
        var created = 0
        for (rule in repo.db.recurring().active()) {
            if (rule.mode != "auto") continue
            val dates = dueDates(rule, today)
            if (dates.isEmpty()) continue
            val txs = dates.mapNotNull { d -> occurrenceIfMissing(rule, d) }
            repo.saveTransactions(txs)
            created += txs.size
            advance(rule, dates.last())
        }
        return created
    }

    /** Remind-mode occurrences that are due, plus everything due within [days]. */
    fun upcoming(rules: List<RecurringRuleEntity>, days: Long = 7, today: LocalDate = Dates.today()): List<Upcoming> =
        rules.filter { !it.paused }
            .flatMap { r -> dueDates(r, today.plusDays(days)).take(3).map { Upcoming(r, it) } }
            .sortedBy { it.date }

    /** Records the occurrence on [date] (remind mode "Add"). */
    suspend fun confirm(rule: RecurringRuleEntity, date: LocalDate, amount: Long = rule.amount) {
        occurrenceIfMissing(rule, date)?.let { t ->
            // A same-currency transfer moves the confirmed amount on both sides.
            val sameCurrency = t.type == "transfer" && rule.toAmount.let { it == null || it == rule.amount }
            repo.saveTransaction(t.copy(amount = amount, toAmount = if (sameCurrency) amount else t.toAmount))
        }
        advance(rule, date)
    }

    /** Skips the occurrence on [date] without recording it. */
    suspend fun skip(rule: RecurringRuleEntity, date: LocalDate) = advance(rule, date)

    private suspend fun advance(rule: RecurringRuleEntity, processed: LocalDate) {
        val fresh = repo.findRule(rule.id) ?: rule
        val next = Recurrence.nextAfter(
            ruleStart(fresh), fresh.frequency, fresh.every, processed, Dates.parse(fresh.endDate), fresh.maxOccurrences,
        )
        val current = Dates.parse(fresh.nextDate)
        // Never move backwards (another device may already have advanced the rule).
        if (current != null && next != null && !next.isAfter(current)) return
        repo.saveRule(fresh.copy(nextDate = (next ?: processed.plusDays(1)).toString()))
    }

    private suspend fun occurrenceIfMissing(rule: RecurringRuleEntity, date: LocalDate): TransactionEntity? {
        val id = Ids.recurringOccurrence(rule.id, date)
        // A deleted occurrence stays deleted: the user removed it on purpose.
        if (repo.findTransaction(id) != null) return null
        val zone = ZoneId.systemDefault()
        val at = date.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
        return TransactionEntity(
            id = id, type = rule.type, accountId = rule.accountId, amount = rule.amount,
            toAccountId = rule.toAccountId.takeIf { rule.type == "transfer" },
            toAmount = (rule.toAmount ?: rule.amount).takeIf { rule.type == "transfer" },
            categoryId = rule.categoryId.takeIf { rule.type != "transfer" }, payee = rule.payee ?: rule.name,
            note = rule.note, occurredAt = at, localDate = date.toString(), tzOffsetMin = Dates.tzOffsetMinutes(at, zone),
            tagIds = rule.tagIds, recurringRuleId = rule.id, createdAt = System.currentTimeMillis(),
        )
    }
}
