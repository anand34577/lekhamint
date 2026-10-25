package app.kosh.domain

import app.kosh.core.Currencies
import app.kosh.data.KoshJson
import app.kosh.data.db.RawRecordEntity
import app.kosh.data.str
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Manual exchange rates (nothing is fetched from the internet), used only to
 * total money held in several currencies. Mirrors the server's report.Convert:
 * the newest rate on or before the date, the inverse of a reverse rate when that
 * one is newer, and a missing rate is reported, never treated as 1:1.
 */
object Fx {
    const val ENTITY = "exchange_rates"

    /** 1 [base] = [rate] [quote], valid from [date]. */
    data class Rate(val id: String, val base: String, val quote: String, val rate: BigDecimal, val date: String)

    fun parse(r: RawRecordEntity): Rate? {
        if (r.deleted) return null
        val d = runCatching { KoshJson.parseToJsonElement(r.data) as JsonObject }.getOrNull() ?: return null
        val rate = d.str("rate")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        return Rate(r.id, d.str("base") ?: return null, d.str("quote") ?: return null, rate, d.str("effective_date") ?: return null)
    }

    /** Multiplier turning [from] into [to] on [asOf] (ISO date), or null when no rate is known. */
    fun find(rates: List<Rate>, from: String, to: String, asOf: String): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        val best = rates.filter { it.date <= asOf && ((it.base == from && it.quote == to) || (it.base == to && it.quote == from)) }
            .maxWithOrNull(compareBy<Rate> { it.date }.thenBy { if (it.base == from) 1 else 0 }) ?: return null
        return if (best.base == from) best.rate else BigDecimal.ONE.divide(best.rate, MathContext(20))
    }

    /** Minor units of [from] as minor units of [to], rounded half away from zero. */
    fun convert(minor: Long, from: String, to: String, rate: BigDecimal): Long {
        val shift = Currencies.digits(to) - Currencies.digits(from)
        val v = BigDecimal(minor).multiply(rate).movePointRight(shift).setScale(0, RoundingMode.HALF_UP)
        return v.max(BigDecimal(Long.MIN_VALUE)).min(BigDecimal(Long.MAX_VALUE)).toLong()
    }

    /** [total] is everything in [target]; [missing] lists currencies that had no rate and were left out. */
    data class Total(val total: Long, val missing: List<String>)

    fun total(amounts: Map<String, Long>, target: String, rates: List<Rate>, asOf: String): Total {
        var sum = 0L
        val missing = ArrayList<String>()
        for ((cur, v) in amounts) {
            val r = find(rates, cur, target, asOf)
            if (r == null) missing += cur else sum += if (cur == target) v else convert(v, cur, target, r)
        }
        return Total(sum, missing.sorted())
    }
}
