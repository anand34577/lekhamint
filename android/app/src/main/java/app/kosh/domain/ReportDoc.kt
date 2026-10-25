package app.kosh.domain

import app.kosh.core.Money
import app.kosh.data.db.DebtEntity
import app.kosh.data.db.TransactionEntity
import java.time.LocalDate

/**
 * One report model for every PDF and spreadsheet the phone exports (mirrors
 * web/src/lib/reports.ts), so a PDF and its spreadsheet always agree.
 */
data class ReportDoc(
    val title: String,
    val subtitle: String?,
    val period: Pair<LocalDate, LocalDate>?,
    val currency: String,
    val stats: List<Stat>,
    val sections: List<Section>,
    val notes: List<String>,
) {
    data class Stat(val label: String, val value: Long, val count: Boolean = false)
    enum class Kind { TEXT, MONEY, DATE, NUMBER }
    data class Column(val label: String, val kind: Kind, val weight: Float = 1f)
    /** Cells: String, Long (money or number), LocalDate, or null. */
    data class Section(val title: String, val columns: List<Column>, val rows: List<List<Any?>>, val total: List<Any?>? = null, val empty: String = "Nothing to show.")
}

object ReportBuilders {
    private fun Lookup.label(t: TransactionEntity): String = when {
        t.type == "transfer" -> if (t.loanId != null) "Loan repayment" else if (t.depositId != null) "Deposit" else "Transfer"
        t.debtId != null && t.part != "interest" && t.part != "fee" -> "Lent & borrowed"
        t.isAdjustment -> "Balance adjustment"
        else -> categoryPath(t.categoryId)
    }

    private fun Lookup.describe(t: TransactionEntity): String =
        if (t.type == "transfer") "${accountName(t.accountId)} → ${accountName(t.toAccountId)}${t.note?.let { " · $it" } ?: ""}"
        else listOfNotNull(t.payee, t.note).joinToString(" · ").ifEmpty { categoryName(t.categoryId) }

    /** Opening balance, every movement with a running balance, closing balance. [all] = every live transaction of the account up to [to]. */
    fun accountStatement(lookup: Lookup, accountId: String, from: LocalDate, to: LocalDate, all: List<TransactionEntity>): ReportDoc {
        val acc = lookup.accountById[accountId] ?: error("That account no longer exists")
        fun delta(t: TransactionEntity) = when {
            t.type == "transfer" && t.toAccountId == accountId && t.accountId != accountId -> t.toAmount ?: t.amount
            t.type == "income" -> t.amount
            else -> -t.amount
        }
        val counted = all.filter { !it.deleted && it.status != "planned" && (it.accountId == accountId || it.toAccountId == accountId) }
        val opening = acc.openingBalance + counted.filter { it.localDate < from.toString() }.sumOf(::delta)
        val inRange = all.filter { !it.deleted && (it.accountId == accountId || it.toAccountId == accountId) && it.localDate >= from.toString() && it.localDate <= to.toString() }
            .sortedWith(compareBy({ it.localDate }, { it.occurredAt }))
        var bal = opening
        var inSum = 0L
        var outSum = 0L
        val rows = inRange.map { t ->
            val d = delta(t)
            val planned = t.status == "planned"
            if (!planned) { bal += d; if (d > 0) inSum += d else outSum -= d }
            listOf(LocalDate.parse(t.localDate), lookup.describe(t) + if (planned) " (planned, not counted)" else "", lookup.label(t), d.takeIf { it > 0 }, (-d).takeIf { it > 0 }, if (planned) null else bal)
        }
        return ReportDoc(
            title = "Account statement · ${acc.name}", subtitle = acc.note, period = from to to, currency = acc.currency,
            stats = listOf(ReportDoc.Stat("Opening balance", opening), ReportDoc.Stat("Money in", inSum), ReportDoc.Stat("Money out", outSum), ReportDoc.Stat("Closing balance", bal)),
            sections = listOf(ReportDoc.Section("Transactions", listOf(
                ReportDoc.Column("Date", ReportDoc.Kind.DATE, 0.9f), ReportDoc.Column("Description", ReportDoc.Kind.TEXT, 2.4f), ReportDoc.Column("Category", ReportDoc.Kind.TEXT, 1.4f),
                ReportDoc.Column("In", ReportDoc.Kind.MONEY), ReportDoc.Column("Out", ReportDoc.Kind.MONEY), ReportDoc.Column("Balance", ReportDoc.Kind.MONEY),
            ), rows, total = listOf(null, "Totals", null, inSum, outSum, bal), empty = "No transactions in this period.")),
            notes = listOf(
                "Opening + in − out = closing: ${Money.format(opening, acc.currency)} + ${Money.format(inSum, acc.currency)} − ${Money.format(outSum, acc.currency)} = ${Money.format(bal, acc.currency)}.",
                "Planned transactions are listed but do not change the balance.",
            ),
        )
    }

    /** Income and spending by category for a period, from report rows (same exclusions as the server). */
    fun incomeExpense(lookup: Lookup, from: LocalDate, to: LocalDate, rows: List<app.kosh.data.db.ReportRow>): ReportDoc {
        val base = lookup.baseCurrency
        val s = Reports.summarize(rows, lookup.categoryById, base)[base] ?: CurrencySummary(base, 0, 0, emptyMap(), emptyMap())
        val exp = s.expenseByCategory.filterValues { it != 0L }.toList().sortedByDescending { it.second }
        val inc = s.incomeByCategory.filterValues { it != 0L }.toList().sortedByDescending { it.second }
        val pct = { v: Long, t: Long -> if (t == 0L) "0%" else "%.1f%%".format(v * 100.0 / t) }
        return ReportDoc(
            title = "Income & spending", subtitle = null, period = from to to, currency = base,
            stats = listOf(ReportDoc.Stat("Income", s.income), ReportDoc.Stat("Spending", s.expense), ReportDoc.Stat(if (s.income >= s.expense) "Saved" else "Overspent", kotlin.math.abs(s.income - s.expense))),
            sections = listOf(
                ReportDoc.Section("Spending by category", listOf(ReportDoc.Column("Category", ReportDoc.Kind.TEXT, 2.5f), ReportDoc.Column("Amount", ReportDoc.Kind.MONEY), ReportDoc.Column("% of spending", ReportDoc.Kind.TEXT, 0.8f)),
                    exp.map { (id, v) -> listOf(lookup.categoryName(id), v, pct(v, s.expense)) }, total = listOf("Total", s.expense, "100%"), empty = "No spending in this period."),
                ReportDoc.Section("Income by source", listOf(ReportDoc.Column("Source", ReportDoc.Kind.TEXT, 2.5f), ReportDoc.Column("Amount", ReportDoc.Kind.MONEY), ReportDoc.Column("% of income", ReportDoc.Kind.TEXT, 0.8f)),
                    inc.map { (id, v) -> listOf(lookup.categoryName(id), v, pct(v, s.income)) }, total = listOf("Total", s.income, "100%"), empty = "No income in this period."),
            ),
            notes = listOf(
                "Amounts in $base; other currencies are not converted here.",
                "Transfers between your accounts, loan principal, money lent or borrowed and deposit movements are not income or spending. Interest and fees are.",
            ),
        )
    }

    fun loan(lookup: Lookup, e: Loans.Status, l: app.kosh.data.db.LoanEntity): ReportDoc = ReportDoc(
        title = "Loan statement · ${l.name}", subtitle = listOfNotNull(Loans.kindLabel(l.kind), l.lender, l.annualRate?.let { "$it% a year" } ?: "interest-free").joinToString(" · "),
        period = null, currency = l.currency,
        stats = listOf(ReportDoc.Stat("Borrowed", l.principal), ReportDoc.Stat("Outstanding", e.outstanding), ReportDoc.Stat("EMI", e.emi), ReportDoc.Stat("Interest still to pay", e.projectedInterest)),
        sections = listOf(ReportDoc.Section("Remaining schedule (projected)", listOf(
            ReportDoc.Column("#", ReportDoc.Kind.NUMBER, 0.4f), ReportDoc.Column("Due", ReportDoc.Kind.DATE), ReportDoc.Column("Payment", ReportDoc.Kind.MONEY),
            ReportDoc.Column("Principal", ReportDoc.Kind.MONEY), ReportDoc.Column("Interest", ReportDoc.Kind.MONEY), ReportDoc.Column("Balance after", ReportDoc.Kind.MONEY),
        ), e.projection.rows.map { listOf(it.n.toLong(), it.date, it.payment, it.principal, it.interest, it.closing) },
            total = listOf(null, "Total", e.projection.totalPayment, e.outstanding, e.projection.totalInterest, 0L), empty = "Nothing left to pay.")),
        notes = listOf("${e.paidInstalments} instalments paid, ${e.remainingInstalments} left. Interest paid so far ${Money.format(e.interestPaid, l.currency)}.",
            "Projected monthly on the reducing balance at the current rate; an estimate, not a lender statement."),
    )

    fun person(lookup: Lookup, name: String, currency: String, debts: List<DebtEntity>, moves: List<TransactionEntity>): ReportDoc {
        val mine = debts.filter { !it.deleted && it.person.trim().equals(name.trim(), ignoreCase = true) && it.currency == currency }
        data class Ev(val date: String?, val text: String, val change: Long, val interest: Long)
        val evs = ArrayList<Ev>()
        for (d in mine) {
            val ms = moves.filter { it.debtId == d.id && !it.deleted }.sortedBy { it.localDate }
            val sign = if (d.direction == "lent") 1 else -1
            val first = ms.firstOrNull { !Debts.isRepayment(d, it) && it.part != "interest" && it.part != "fee" }
            evs += Ev(first?.localDate ?: ms.firstOrNull()?.localDate, (if (d.direction == "lent") "You lent" else "You borrowed") + (d.note?.let { " · $it" } ?: ""), sign * d.amount, 0)
            for (m in ms) when {
                m === first -> {}
                m.part == "interest" || m.part == "fee" -> evs += Ev(m.localDate, if (d.direction == "lent") "Interest received" else "Interest paid", 0, m.amount)
                Debts.isRepayment(d, m) -> evs += Ev(m.localDate, if (d.direction == "lent") "They paid you" else "You paid them", -sign * m.amount, 0)
            }
            if (d.closed) { val left = Debts.balance(d, ms); if (left != 0L) evs += Ev(ms.lastOrNull()?.localDate, "Marked settled", -sign * left, 0) }
        }
        var bal = 0L
        val rows = evs.sortedBy { it.date ?: "9999" }.map { e -> bal += e.change; listOf(e.date?.let(LocalDate::parse), e.text, e.change.takeIf { it != 0L }, e.interest.takeIf { it != 0L }, bal) }
        return ReportDoc(
            title = "Statement with $name", subtitle = null, period = null, currency = currency,
            stats = listOf(ReportDoc.Stat(if (bal > 0) "$name owes you" else if (bal < 0) "You owe $name" else "Settled", kotlin.math.abs(bal))),
            sections = listOf(ReportDoc.Section("History", listOf(ReportDoc.Column("Date", ReportDoc.Kind.DATE), ReportDoc.Column("What", ReportDoc.Kind.TEXT, 2.4f),
                ReportDoc.Column("Amount", ReportDoc.Kind.MONEY), ReportDoc.Column("Interest", ReportDoc.Kind.MONEY), ReportDoc.Column("Balance", ReportDoc.Kind.MONEY)), rows)),
            notes = listOf("Balance: positive means they owe you, negative means you owe them. Interest does not change the balance."),
        )
    }
}
