package app.kosh.domain

import kotlin.math.floor
import kotlin.math.pow

/** Calculator maths shared with web/src/lib/tools.ts. Money is in minor units; parts always add up. */
object Tools {
    /** Splits [total] by [weights] (largest remainder); ties go to earlier entries. */
    fun allocate(total: Long, weights: List<Double>): List<Long> {
        val sum = weights.sumOf { maxOf(0.0, it) }
        if (sum <= 0 || weights.isEmpty()) return weights.map { 0L }
        val exact = weights.map { total * maxOf(0.0, it) / sum }
        val parts = exact.map { floor(it).toLong() }.toMutableList()
        var left = total - parts.sum()
        val order = exact.indices.sortedWith(compareByDescending<Int> { exact[it] - floor(exact[it]) }.thenBy { it })
        var k = 0
        while (left > 0) { parts[order[k % order.size]]++; left--; k++ }
        return parts
    }

    data class Transfer(val from: Int, val to: Int, val amount: Long)

    /** Fewest transfers (largest debtor pays largest creditor) to bring every net to zero. */
    fun settle(net: List<Long>): List<Transfer> {
        val debt = net.withIndex().filter { it.value < 0 }.map { it.index to -it.value }.sortedByDescending { it.second }.map { it.first to it.second }.toMutableList()
        val cred = net.withIndex().filter { it.value > 0 }.map { it.index to it.value }.sortedByDescending { it.second }.toMutableList()
        val out = ArrayList<Transfer>()
        var d = 0
        var c = 0
        while (d < debt.size && c < cred.size) {
            val amt = minOf(debt[d].second, cred[c].second)
            if (amt > 0) out += Transfer(debt[d].first, cred[c].first, amt)
            debt[d] = debt[d].first to debt[d].second - amt
            cred[c] = cred[c].first to cred[c].second - amt
            if (debt[d].second == 0L) d++
            if (cred[c].second == 0L) c++
        }
        return out
    }

    /** Largest principal whose EMI is at most [payment]. */
    fun maxPrincipal(payment: Long, rate: String?, months: Int): Long {
        if (payment <= 0 || months <= 0) return 0
        val r = rate?.toDoubleOrNull()?.div(1200) ?: 0.0
        if (r == 0.0) return payment * months
        var p = floor(payment * (1 - (1 + r).pow(-months)) / r).toLong()
        while (p > 0 && Loans.emi(p, rate, months) > payment) p--
        return p
    }
}
