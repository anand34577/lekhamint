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
 * spending or income. Repayments are linked the same way. Mirrors
 * web/src/pages/Debts.tsx.
 */
object Debts {
    /** A repayment brings money in for a loan you gave, and sends it out for one you took. */
    fun isRepayment(d: DebtEntity, t: TransactionEntity): Boolean =
        t.debtId == d.id && !t.deleted && t.type == (if (d.direction == "lent") "income" else "expense")

    /** What is still owed: the amount minus repayments (never below zero). */
    fun outstanding(d: DebtEntity, moves: List<TransactionEntity>): Long =
        (d.amount - moves.filter { isRepayment(d, it) }.sumOf { it.amount }).coerceAtLeast(0)

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
    ): TransactionEntity {
        val moneyIn = (d.direction == "lent") == repayment
        val at = date.atTime(if (date == Dates.today()) LocalTime.now(zone) else LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return TransactionEntity(
            id = Ids.newV7(now), type = if (moneyIn) "income" else "expense", accountId = accountId, amount = amount,
            payee = d.person, note = if (repayment) "Repayment" else if (d.direction == "lent") "Lent" else "Borrowed",
            occurredAt = at, localDate = date.toString(), tzOffsetMin = Dates.tzOffsetMinutes(at, zone), debtId = d.id, createdAt = now,
        )
    }
}
