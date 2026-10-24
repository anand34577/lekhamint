package app.kosh.domain

import app.kosh.data.db.LoanEntity
import app.kosh.data.db.TransactionEntity
import java.math.BigInteger
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Loan and EMI arithmetic (docs/02, "Loans and deposits"). A line-for-line port
 * of web/src/lib/loans.ts; LoansTest asserts the same numbers as
 * web/src/lib/finance.test.ts.
 *
 * Interest is charged monthly on the reducing balance, rounded half-up with
 * exact integer arithmetic on the rate's decimal digits. The EMI uses the
 * standard formula, rounded half-up; the last instalment absorbs rounding.
 */
object Loans {
    /** "8.65" -> (865, 100); null or zero = interest-free. */
    fun rateParts(rate: String?): Pair<BigInteger, BigInteger>? {
        val m = Regex("""^(\d+)(?:\.(\d+))?$""").matchEntire(rate?.trim() ?: return null) ?: return null
        val frac = m.groupValues[2]
        val num = BigInteger(m.groupValues[1] + frac)
        if (num.signum() == 0) return null
        return num to BigInteger.TEN.pow(frac.length)
    }

    /** An error message for a typed annual rate, or null if it is fine. */
    fun rateError(rate: String, allowZero: Boolean = false): String? {
        val t = rate.trim()
        if (t.isEmpty()) return if (allowZero) null else "Enter the interest rate"
        if (!Regex("""^\d+(\.\d{1,4})?$""").matches(t)) return "Use a number like 8.5 (up to 4 decimal places), without the % sign"
        val v = t.toDouble()
        if (v == 0.0 && !allowZero) return "The rate must be more than 0"
        if (v > 60) return "That rate is above 60% a year; check the number"
        return null
    }

    /** One month's interest on [balance] at [rate] % a year, rounded half-up. Exact. */
    fun monthlyInterest(balance: Long, rate: String?): Long {
        val (num, den) = rateParts(rate) ?: return 0
        if (balance <= 0) return 0
        val d = den.multiply(BigInteger.valueOf(1200))
        return BigInteger.valueOf(balance).multiply(num).multiply(BigInteger.valueOf(2)).add(d).divide(d.multiply(BigInteger.valueOf(2))).toLong()
    }

    fun emi(principal: Long, rate: String?, months: Int): Long {
        if (principal <= 0 || months <= 0) return 0
        val p = rateParts(rate) ?: return (principal + months - 1) / months
        val r = p.first.toDouble() / p.second.toDouble() / 1200
        val f = (1 + r).pow(months)
        return (principal * r * f / (f - 1)).roundToLong()
    }

    data class Row(val n: Int, val date: LocalDate, val opening: Long, val interest: Long, val principal: Long, val payment: Long, val closing: Long)
    data class Schedule(val rows: List<Row>, val totalInterest: Long, val totalPayment: Long, val neverRepaid: Boolean, val truncated: Boolean)

    fun schedule(balance: Long, rate: String?, payment: Long, anchor: LocalDate, firstN: Int = 1, maxRows: Int = 600): Schedule {
        val rows = ArrayList<Row>()
        var b = balance
        var ti = 0L
        var tp = 0L
        if (balance > 0 && payment <= monthlyInterest(balance, rate) || payment <= 0) return Schedule(rows, 0, 0, balance > 0, false)
        var n = firstN
        while (b > 0 && rows.size < maxRows) {
            val interest = monthlyInterest(b, rate)
            var principal = minOf(payment - interest, b)
            if (b - principal > 0 && b - principal < maxOf(1L, (payment / 100.0).roundToLong())) principal = b
            val pay = principal + interest
            rows += Row(n, anchor.plusMonths((n - 1).toLong()), b, interest, principal, pay, b - principal)
            b -= principal; ti += interest; tp += pay; n++
        }
        return Schedule(rows, ti, tp, false, b > 0)
    }

    data class Status(
        val emi: Long, val outstanding: Long, val overpaid: Long, val paidInstalments: Int, val remainingInstalments: Int,
        val nextDue: LocalDate?, val nextInterest: Long, val nextPrincipal: Long, val overdue: Int,
        val principalPaid: Long, val interestPaid: Long, val feesPaid: Long, val prepaid: Long,
        val endDate: LocalDate?, val projectedInterest: Long, val progress: Double, val projection: Schedule,
    )

    /** Status from the terms, the money actually recorded and the loan account's balance (negative while owed). */
    fun status(l: LoanEntity, moves: List<TransactionEntity>, accountBalance: Long, today: LocalDate): Status {
        val live = moves.filter { !it.deleted && it.loanId == l.id }
        val outstanding = maxOf(0, -accountBalance)
        val overpaid = maxOf(0, accountBalance)
        val intoLoan = { m: TransactionEntity -> m.type == "transfer" && m.toAccountId == l.accountId }
        fun sum(f: (TransactionEntity) -> Boolean) = live.filter(f).sumOf { if (it.type == "transfer") it.toAmount ?: it.amount else it.amount }
        val principalPaid = sum { intoLoan(it) && it.part == "principal" }
        val prepaid = sum { intoLoan(it) && it.part == "prepayment" }
        val interestPaid = sum { it.type == "expense" && it.part == "interest" }
        val feesPaid = sum { it.type == "expense" && it.part == "fee" }
        val emiDates = live.filter { (intoLoan(it) || it.type == "expense") && (it.part == "principal" || it.part == "interest") }.map { it.localDate }.toSet()
        val paid = l.paidBefore + emiDates.size
        val pay = l.emiAmount ?: emi(l.principal, l.annualRate, l.tenureMonths)
        val done = l.closed || outstanding == 0L
        val anchor = LocalDate.parse(l.firstDueDate)
        val projection = if (done) schedule(0, l.annualRate, pay, anchor) else schedule(outstanding, l.annualRate, pay, anchor, paid + 1)
        val first = projection.rows.firstOrNull()
        val overdue = if (done) 0 else projection.rows.takeWhile { it.date.isBefore(today) }.size
        return Status(
            emi = pay, outstanding = outstanding, overpaid = overpaid, paidInstalments = paid,
            remainingInstalments = if (done) 0 else projection.rows.size, nextDue = if (done) null else first?.date,
            nextInterest = first?.interest ?: 0, nextPrincipal = first?.principal ?: 0, overdue = overdue,
            principalPaid = principalPaid, interestPaid = interestPaid, feesPaid = feesPaid, prepaid = prepaid,
            endDate = if (done) null else projection.rows.lastOrNull()?.date, projectedInterest = projection.totalInterest,
            progress = if (l.principal > 0) (1 - outstanding.toDouble() / l.principal).coerceIn(0.0, 1.0) else 0.0, projection = projection,
        )
    }

    /** Interest first, then principal, never more principal than is owed. */
    fun splitPayment(amount: Long, outstanding: Long, rate: String?): Pair<Long, Long> {
        val interest = minOf(amount, monthlyInterest(outstanding, rate))
        return interest to minOf(outstanding, amount - interest)
    }

    val KINDS = listOf(
        "home" to "Home loan", "vehicle" to "Vehicle loan", "personal" to "Personal loan", "education" to "Education loan",
        "gold" to "Gold loan", "business" to "Business loan", "consumer" to "Consumer / no-cost EMI", "other" to "Other loan",
    )
    fun kindLabel(k: String) = KINDS.firstOrNull { it.first == k }?.second ?: "Loan"
}
