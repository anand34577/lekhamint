package app.kosh.domain

import app.kosh.data.db.DepositEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Fixed and recurring deposit estimates. A port of web/src/lib/deposits.ts;
 * LoansTest checks the same figures as web/src/lib/finance.test.ts.
 * Banks differ in day counts, rounding and tax, so the bank's maturity
 * amount, when entered, always wins.
 */
object Deposits {
    private val periodMonths = mapOf("monthly" to 1, "quarterly" to 3, "half_yearly" to 6, "yearly" to 12)

    fun days(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)

    /** Whole months from [a] to [b] (anchored on a's day) and the days left over. */
    fun monthsAndDays(a: LocalDate, b: LocalDate): Pair<Int, Long> {
        var m = 0
        while (!a.plusMonths((m + 1).toLong()).isAfter(b)) m++
        return m to days(a.plusMonths(m.toLong()), b)
    }

    private fun rate(s: String) = s.toDouble() / 100

    fun fdValue(p: Long, rate: String, start: LocalDate, end: LocalDate, compounding: String): Long {
        if (!end.isAfter(start)) return p
        val r = rate(rate)
        val step = periodMonths[compounding] ?: return (p * (1 + r * days(start, end) / 365.0)).roundToLong()
        val (months, _) = monthsAndDays(start, end)
        val periods = months / step
        val leftover = days(start.plusMonths((periods * step).toLong()), end)
        val grown = p * (1 + r * step / 12).pow(periods)
        return (grown * (1 + r * leftover / 365.0)).roundToLong()
    }

    data class Rd(val deposited: Long, val value: Long, val instalments: Int)

    fun rdValue(inst: Long, rate: String, start: LocalDate, end: LocalDate, compounding: String, asOf: LocalDate = end): Rd {
        val r = rate(rate)
        val step = periodMonths[compounding] ?: 3
        val (total, _) = monthsAndDays(start, end)
        var deposited = 0L
        var value = 0.0
        var n = 0
        for (k in 0 until maxOf(1, total)) {
            val paidOn = start.plusMonths(k.toLong())
            if (paidOn.isAfter(asOf) || !paidOn.isBefore(end)) break
            n++
            deposited += inst
            val until = if (asOf.isBefore(end)) asOf else end
            val (m, d) = monthsAndDays(paidOn, until)
            val years = m / 12.0 + d / 365.0
            value += inst * (1 + r * step / 12).pow(years * 12 / step)
        }
        return Rd(deposited, value.roundToLong(), n)
    }

    data class Payout(val date: LocalDate, val amount: Long)

    fun payouts(d: DepositEntity): List<Payout> {
        val step = periodMonths[d.payout] ?: return emptyList()
        val r = rate(d.annualRate)
        val start = LocalDate.parse(d.startDate)
        val end = LocalDate.parse(d.maturityDate)
        val out = ArrayList<Payout>()
        var i = 1
        while (out.size < 1200) {
            val date = start.plusMonths((i * step).toLong())
            if (date.isAfter(end)) {
                val prev = start.plusMonths(((i - 1) * step).toLong())
                val left = days(prev, end)
                if (left > 0) out += Payout(end, (d.amount * r * left / 365).roundToLong())
                break
            }
            out += Payout(date, (d.amount * r * step / 12).roundToLong())
            i++
        }
        return out
    }

    data class Estimate(val invested: Long, val interest: Long, val maturity: Long, val payouts: List<Payout>, val tax: Long, val valueToday: Long, val fromBank: Boolean, val days: Long)

    fun estimate(d: DepositEntity, today: LocalDate): Estimate {
        val start = LocalDate.parse(d.startDate)
        val end = LocalDate.parse(d.maturityDate)
        val asOf = if (today.isBefore(start)) start else if (today.isAfter(end)) end else today
        val invested: Long
        var maturity: Long
        var payouts = emptyList<Payout>()
        val valueToday: Long
        when {
            d.kind == "rd" -> {
                val full = rdValue(d.amount, d.annualRate, start, end, d.compounding)
                invested = full.deposited; maturity = full.value
                valueToday = rdValue(d.amount, d.annualRate, start, end, d.compounding, asOf).value
            }
            d.payout != "cumulative" -> { invested = d.amount; payouts = payouts(d); maturity = d.amount; valueToday = d.amount }
            else -> {
                invested = d.amount
                maturity = fdValue(d.amount, d.annualRate, start, end, d.compounding)
                valueToday = fdValue(d.amount, d.annualRate, start, asOf, d.compounding)
            }
        }
        d.maturityAmount?.let { maturity = it }
        val interest = maturity - invested + payouts.sumOf { it.amount }
        val tax = d.taxPercent?.toDoubleOrNull()?.let { (maxOf(0, interest) * it / 100).roundToLong() } ?: 0
        return Estimate(invested, interest, maturity, payouts, tax, valueToday, d.maturityAmount != null, days(start, end))
    }

    fun kindLabel(k: String) = when (k) { "rd" -> "Recurring deposit"; "other" -> "Other deposit"; else -> "Fixed deposit" }
}
