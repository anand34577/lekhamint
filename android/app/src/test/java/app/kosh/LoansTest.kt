package app.kosh

import app.kosh.data.db.DebtEntity
import app.kosh.data.db.DepositEntity
import app.kosh.data.db.LoanEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Debts
import app.kosh.domain.Deposits
import app.kosh.domain.Loans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Same cases and numbers as web/src/lib/finance.test.ts, so both apps agree to the paisa. */
class LoansTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun emiMatchesTheStandardFormula() {
        assertEquals(867823, Loans.emi(100000000, "8.5", 240))
        assertEquals(4339116, Loans.emi(500000000, "8.5", 240))
        assertEquals(100000, Loans.emi(1200000, null, 12))
        assertEquals(33334, Loans.emi(100000, null, 3))
    }

    @Test fun monthlyInterestIsExact() {
        assertEquals(708333, Loans.monthlyInterest(100000000, "8.5"))
        assertEquals(1000, Loans.monthlyInterest(120000, "10"))
        assertEquals(0, Loans.monthlyInterest(6, "10"))
        assertEquals(1, Loans.monthlyInterest(60, "10"))
        assertEquals(0, Loans.monthlyInterest(100000, null))
    }

    @Test fun scheduleRepaysExactlyThePrincipal() {
        val s = Loans.schedule(100000000, "8.5", 867823, d("2026-01-05"))
        assertEquals(240, s.rows.size)
        assertEquals(100000000, s.rows.sumOf { it.principal })
        assertEquals(108277663, s.totalInterest)
        assertEquals(Loans.Row(240, d("2045-12-05"), 861861, 6105, 861861, 867966, 0), s.rows[239])
        assertEquals(listOf(33334L, 33334L, 33332L), Loans.schedule(100000, null, 33334, d("2026-01-01")).rows.map { it.payment })
        assertEquals(listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31")), Loans.schedule(300000, null, 100000, d("2026-01-31")).rows.map { it.date })
        assertTrue(Loans.schedule(100000000, "12", 500000, d("2026-01-01")).neverRepaid)
    }

    @Test fun statusCountsOnlyRecordedPayments() {
        val loan = LoanEntity("L1", name = "Home", accountId = "LA", currency = "INR", principal = 100000000, annualRate = "8.5",
            startDate = "2025-12-05", firstDueDate = "2026-01-05", tenureMonths = 240)
        val moves = listOf(
            TransactionEntity("t1", type = "transfer", accountId = "B", toAccountId = "LA", amount = 159490, toAmount = 159490, occurredAt = 0, localDate = "2026-01-05", loanId = "L1", part = "principal"),
            TransactionEntity("t2", type = "expense", accountId = "B", amount = 708333, occurredAt = 0, localDate = "2026-01-05", loanId = "L1", part = "interest"),
        )
        val st = Loans.status(loan, moves, -(100000000L - 159490), d("2026-03-10"))
        assertEquals(867823, st.emi)
        assertEquals(99840510, st.outstanding)
        assertEquals(1, st.paidInstalments)
        assertEquals(239, st.remainingInstalments)
        assertEquals(d("2026-02-05"), st.nextDue)
        assertEquals(707204, st.nextInterest)
        assertEquals(160619, st.nextPrincipal)
        assertEquals(2, st.overdue)
        assertEquals(708333, st.interestPaid)
        assertEquals(d("2045-12-05"), st.endDate)
        assertEquals(107569330, st.projectedInterest)
        val done = Loans.status(loan.copy(principal = 100, tenureMonths = 1, paidBefore = 1), emptyList(), 0, d("2027-01-01"))
        assertNull(done.nextDue)
        assertEquals(0, done.overdue)
    }

    @Test fun paymentsSplitInterestFirst() {
        assertEquals(708333L to 159490L, Loans.splitPayment(867823, 100000000, "8.5"))
        assertEquals(500000L to 0L, Loans.splitPayment(500000, 100000000, "8.5"))
        assertEquals(2000L to 200000L, Loans.splitPayment(1000000, 200000, "12"))
    }

    @Test fun rateValidationExplains() {
        assertNull(Loans.rateError("8.5"))
        assertTrue(Loans.rateError("8.5%")!!.contains("without the % sign"))
        assertTrue(Loans.rateError("75")!!.contains("above 60%"))
    }

    @Test fun deposits() {
        assertEquals(10718590, Deposits.fdValue(10000000, "7", d("2026-01-01"), d("2027-01-01"), "quarterly"))
        assertEquals(10961432, Deposits.fdValue(10000000, "7.25", d("2026-01-15"), d("2027-04-25"), "quarterly"))
        assertEquals(10178082, Deposits.fdValue(10000000, "6.5", d("2026-01-01"), d("2026-04-11"), "simple"))
        assertEquals(5361450, Deposits.fdValue(5000000, "7", d("2026-03-31"), d("2027-03-31"), "monthly"))
        assertEquals(Deposits.Rd(6000000, 6231066, 12), Deposits.rdValue(500000, "7", d("2026-01-01"), d("2027-01-01"), "quarterly"))
        assertEquals(Deposits.Rd(3000000, 3051948, 6), Deposits.rdValue(500000, "7", d("2026-01-01"), d("2027-01-01"), "quarterly", d("2026-06-15")))
        val payout = DepositEntity("D", name = "FD", accountId = "A", currency = "INR", amount = 10000000, annualRate = "7", startDate = "2026-01-01",
            maturityDate = "2026-11-15", payout = "quarterly", taxPercent = "10")
        val e = Deposits.estimate(payout, d("2026-06-01"))
        assertEquals(listOf(175000L, 175000L, 175000L, 86301L), e.payouts.map { it.amount })
        assertEquals(listOf(10000000L, 611301L, 61130L, 318L), listOf(e.maturity, e.interest, e.tax, e.days))
        val bank = Deposits.estimate(payout.copy(payout = "cumulative", maturityDate = "2027-01-01", maturityAmount = 10718000, taxPercent = null), d("2026-01-01"))
        assertEquals(listOf(10718000L, 718000L), listOf(bank.maturity, bank.interest))
    }

    @Test fun debtsCountInterestApartAndFlipWhenOverpaid() {
        val lent = DebtEntity("d1", person = "Rohan", direction = "lent", amount = 1500000, currency = "INR")
        val borrowed = DebtEntity("d2", person = "Dad", direction = "borrowed", amount = 300000, currency = "INR")
        val moves = listOf(
            TransactionEntity("a", type = "expense", accountId = "B", amount = 1500000, occurredAt = 0, localDate = "2026-09-10", debtId = "d1"),
            TransactionEntity("b", type = "income", accountId = "B", amount = 500000, occurredAt = 0, localDate = "2026-10-10", debtId = "d1"),
            TransactionEntity("c", type = "income", accountId = "B", amount = 10000, occurredAt = 0, localDate = "2026-10-10", debtId = "d1", part = "interest"),
            TransactionEntity("e", type = "income", accountId = "B", amount = 300000, occurredAt = 0, localDate = "2026-09-15", debtId = "d2"),
            TransactionEntity("f", type = "expense", accountId = "B", amount = 400000, occurredAt = 0, localDate = "2026-10-01", debtId = "d2"),
        )
        assertEquals(1000000, Debts.outstanding(lent, moves))
        assertEquals(-100000, Debts.balance(borrowed, moves))
        // Same as server TestPositionCountsDebts: ₹10,000 from Rohan + ₹1,000 Dad owes back.
        val (toMe, iOwe) = Debts.totals(listOf(lent, borrowed), moves)
        assertEquals(mapOf("INR" to 1100000L), toMe)
        assertTrue(iOwe.isEmpty())
    }
}
