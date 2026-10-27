package app.kosh.domain

import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.data.db.DebtEntity
import app.kosh.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Lent & borrowed (docs/02 §9). Money that leaves or enters an account is a
 * transaction linked to the debt (debt_id): it moves the balance but is never
 * spending or income. Interest linked to a debt (part = interest) is real
 * income or cost and is not a repayment. Mirrors web/src/lib/debts.ts and the
 * server's report.Debts.
 */
object Debts {
    /** Money moving back that is not interest or a fee: income on a loan you gave, an expense on one you took. */
    fun isRepayment(d: DebtEntity, t: TransactionEntity): Boolean =
        t.debtId == d.id && !t.deleted && t.type == (if (d.direction == "lent") "income" else "expense") &&
            (t.part == null || t.part == "principal" || t.part == "prepayment")

    fun repaid(d: DebtEntity, moves: List<TransactionEntity>): Long = moves.filter { isRepayment(d, it) }.sumOf { it.amount }

    /** Amount − repayments. Negative means it was overpaid (the other side now owes that back). */
    fun balance(d: DebtEntity, moves: List<TransactionEntity>): Long = d.amount - repaid(d, moves)

    /** What is still owed on this debt, never below zero (for "record a repayment"). */
    fun outstanding(d: DebtEntity, moves: List<TransactionEntity>): Long = balance(d, moves).coerceAtLeast(0)

    /**
     * Receivable and payable per currency across open debts, the same way the
     * server counts them in net worth: an overpaid loan flips sides.
     */
    fun totals(debts: List<DebtEntity>, moves: List<TransactionEntity>): Pair<Map<String, Long>, Map<String, Long>> {
        val owedToMe = HashMap<String, Long>()
        val iOwe = HashMap<String, Long>()
        for (d in debts) {
            if (d.deleted || d.closed) continue
            val b = balance(d, moves.filter { it.debtId == d.id })
            if (b == 0L) continue
            val toMe = (d.direction == "lent") == (b > 0)
            val target = if (toMe) owedToMe else iOwe
            target[d.currency] = (target[d.currency] ?: 0) + kotlin.math.abs(b)
        }
        return owedToMe to iOwe
    }

    fun isInterest(t: TransactionEntity): Boolean = t.part == "interest" || t.part == "fee"

    /** Same person regardless of case or stray spaces. */
    fun personKey(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

    /** The day a debt began: its first linked movement, else the day it was recorded. */
    fun start(d: DebtEntity, moves: List<TransactionEntity>, zone: ZoneId = ZoneId.systemDefault()): String =
        (moves.firstOrNull { !isRepayment(d, it) && !isInterest(it) } ?: moves.firstOrNull())?.localDate
            ?: java.time.Instant.ofEpochMilli(d.createdAt).atZone(zone).toLocalDate().toString()

    /** One person in one currency, with everything they owe or are owed. [net] > 0 means they owe you. */
    data class Person(
        val key: String,
        val name: String,
        val currency: String,
        val net: Long,
        val open: Int,
        val debts: List<DebtEntity>,
        val overdue: Int,
        val nextDue: String?,
        val lastActivity: String?,
    )

    /** People with open balances first, biggest amounts first. Settled debts count as zero. */
    fun people(debts: List<DebtEntity>, moves: Map<String, List<TransactionEntity>>, today: String): List<Person> {
        class Acc(val name: String, val currency: String) {
            val debts = ArrayList<DebtEntity>(); var net = 0L; var open = 0; var overdue = 0
            var nextDue: String? = null; var last: String? = null
        }
        val by = LinkedHashMap<String, Acc>()
        for (d in debts) {
            if (d.deleted) continue
            val key = personKey(d.person) + "|" + d.currency
            val a = by.getOrPut(key) { Acc(d.person.trim(), d.currency) }
            a.debts += d
            val ms = moves[d.id].orEmpty().sortedWith(compareBy({ it.localDate }, { it.occurredAt }))
            ms.lastOrNull()?.localDate?.let { if (a.last == null || it > a.last!!) a.last = it }
            if (d.closed) continue
            val left = d.amount - repaid(d, ms)
            if (left == 0L) continue
            a.open++
            a.net += if (d.direction == "lent") left else -left
            d.dueDate?.let { due ->
                if (due < today) a.overdue++
                if (a.nextDue == null || due < a.nextDue!!) a.nextDue = due
            }
        }
        return by.map { (k, a) -> Person(k, a.name, a.currency, a.net, a.open, a.debts, a.overdue, a.nextDue, a.last) }
            .sortedWith(compareByDescending<Person> { it.open > 0 }.thenByDescending { kotlin.math.abs(it.net) }.thenBy { it.name.lowercase() })
    }

    /** One line of a person's running account. [balance] > 0 means they owe you. */
    data class StatementRow(
        val date: String?, val text: String, val debtId: String, val change: Long, val interest: Long, val balance: Long,
        /** The linked transaction behind this line, when there is one, so the UI can open it. */
        val transactionId: String? = null,
    )

    fun statement(debts: List<DebtEntity>, moves: Map<String, List<TransactionEntity>>): List<StatementRow> {
        class Ev(val date: String?, val text: String, val debtId: String, val change: Long, val interest: Long, val tx: String?)
        val events = ArrayList<Ev>()
        for (d in debts) {
            val ms = moves[d.id].orEmpty().sortedWith(compareBy({ it.localDate }, { it.occurredAt }))
            val sign = if (d.direction == "lent") 1L else -1L
            val principal = ms.firstOrNull { !isRepayment(d, it) && !isInterest(it) }
            val label = d.note?.let { " · $it" }.orEmpty()
            events += Ev(
                start(d, ms), (if (d.direction == "lent") "You lent" else "You borrowed") + label + if (principal == null) " (no account linked)" else "",
                d.id, sign * d.amount, 0, principal?.id,
            )
            for (m in ms) {
                if (m === principal) continue
                events += when {
                    isInterest(m) -> Ev(m.localDate, if (m.part == "fee") "Fee" else if (d.direction == "lent") "Interest received" else "Interest paid", d.id, 0, m.amount, m.id)
                    isRepayment(d, m) -> Ev(m.localDate, if (d.direction == "lent") "They paid you" else "You paid them", d.id, -sign * m.amount, 0, m.id)
                    else -> Ev(m.localDate, "Linked movement (the debt amount already covers it)", d.id, 0, 0, m.id)
                }
            }
            if (d.closed) {
                val left = d.amount - repaid(d, ms)
                if (left != 0L) events += Ev(ms.lastOrNull()?.localDate, if (left > 0) "Marked settled; the rest was written off" else "Marked settled; overpayment kept", d.id, -sign * left, 0, null)
            }
        }
        var bal = 0L
        return events.sortedBy { it.date ?: "9999" }.map { e -> bal += e.change; StatementRow(e.date, e.text, e.debtId, e.change, e.interest, bal, e.tx) }
    }

    data class Share(val debt: DebtEntity, val amount: Long, val settles: Boolean)

    /** Spreads a repayment over open debts, oldest first. [rest] is what is left once everything is covered. */
    fun allocate(amount: Long, open: List<Triple<DebtEntity, Long, String>>): Pair<List<Share>, Long> {
        var rest = amount
        val parts = ArrayList<Share>()
        for ((d, left, _) in open.sortedWith(compareBy({ it.third }, { it.first.dueDate ?: "9" }))) {
            if (rest <= 0) break
            if (left <= 0) continue
            val take = minOf(rest, left)
            parts += Share(d, take, take == left)
            rest -= take
        }
        return parts to rest
    }

    /**
     * The transaction for the loan itself ([repayment] false) or a repayment.
     * Money comes in when you borrow or get paid back.
     */
    fun movement(
        d: DebtEntity,
        repayment: Boolean,
        accountId: String,
        amount: Long,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
        id: String = Ids.newV7(now),
        interest: Boolean = false,
        time: LocalTime? = null,
    ): TransactionEntity {
        val moneyIn = (d.direction == "lent") == repayment
        val at = Dates.at(date, time, zone)
        return TransactionEntity(
            id = id, type = if (moneyIn) "income" else "expense", accountId = accountId, amount = amount,
            payee = d.person, note = if (interest) "Interest" else if (repayment) "Repayment" else if (d.direction == "lent") "Lent" else "Borrowed",
            occurredAt = at, localDate = date.toString(), tzOffsetMin = Dates.tzOffsetMinutes(at, zone), debtId = d.id,
            part = if (interest) "interest" else "principal", createdAt = now,
        )
    }
}
