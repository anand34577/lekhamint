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
    ): TransactionEntity {
        val moneyIn = (d.direction == "lent") == repayment
        val at = date.atTime(if (date == Dates.today()) LocalTime.now(zone) else LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return TransactionEntity(
            id = id, type = if (moneyIn) "income" else "expense", accountId = accountId, amount = amount,
            payee = d.person, note = if (interest) "Interest" else if (repayment) "Repayment" else if (d.direction == "lent") "Lent" else "Borrowed",
            occurredAt = at, localDate = date.toString(), tzOffsetMin = Dates.tzOffsetMinutes(at, zone), debtId = d.id,
            part = if (interest) "interest" else "principal", createdAt = now,
        )
    }
}
