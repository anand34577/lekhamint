package app.kosh.domain

import app.kosh.data.KoshJson
import app.kosh.data.db.RawRecordEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Goals, valuations and attachments (server schema v8). This app version keeps
 * them as raw records (they sync and back up like everything else) and reads
 * them through these small models. Field names match server/internal/entity.
 */

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonObject.b(k: String): Boolean = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
private fun JsonObject.ids(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
private fun dataOf(r: RawRecordEntity): JsonObject = runCatching { KoshJson.parseToJsonElement(r.data) as JsonObject }.getOrDefault(JsonObject(emptyMap()))

fun str(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull
fun num(v: Long?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

data class Goal(
    val id: String, val name: String, val targetAmount: Long, val currency: String, val targetDate: String?, val startDate: String?,
    val accountIds: List<String>, val savedAmount: Long, val color: String?, val archived: Boolean, val achievedDate: String?, val note: String?, val createdAt: Long,
) {
    fun toJson(): Map<String, JsonElement> = mapOf(
        "name" to str(name), "target_amount" to num(targetAmount), "currency" to str(currency), "target_date" to str(targetDate),
        "start_date" to str(startDate), "account_ids" to JsonArray(accountIds.map(::JsonPrimitive)), "saved_amount" to num(savedAmount),
        "color" to str(color), "archived" to JsonPrimitive(archived), "achieved_date" to str(achievedDate), "note" to str(note), "created_at" to num(createdAt),
    )

    companion object {
        const val ENTITY = "goals"
        fun parse(r: RawRecordEntity): Goal? {
            if (r.deleted || r.entity != ENTITY) return null
            val d = dataOf(r)
            return Goal(
                r.id, d.s("name") ?: return null, d.l("target_amount") ?: return null, d.s("currency") ?: return null, d.s("target_date"), d.s("start_date"),
                d.ids("account_ids"), d.l("saved_amount") ?: 0, d.s("color"), d.b("archived"), d.s("achieved_date"), d.s("note"), d.l("created_at") ?: 0,
            )
        }
    }
}

data class Valuation(val id: String, val accountId: String, val date: String, val value: Long, val note: String?, val createdAt: Long) {
    companion object {
        const val ENTITY = "valuations"
        fun parse(r: RawRecordEntity): Valuation? {
            if (r.deleted || r.entity != ENTITY) return null
            val d = dataOf(r)
            return Valuation(r.id, d.s("account_id") ?: return null, d.s("date") ?: return null, d.l("value") ?: return null, d.s("note"), d.l("created_at") ?: 0)
        }
    }
}

data class Attachment(val id: String, val transactionId: String, val name: String, val mime: String, val size: Long, val sha256: String, val createdAt: Long) {
    val isImage get() = mime.startsWith("image/") && mime != "image/heic" && mime != "image/heif"

    fun toJson(): Map<String, JsonElement> = mapOf(
        "transaction_id" to str(transactionId), "name" to str(name), "mime" to str(mime), "size" to num(size), "sha256" to str(sha256), "created_at" to num(createdAt),
    )

    companion object {
        const val ENTITY = "attachments"
        fun parse(r: RawRecordEntity): Attachment? {
            if (r.deleted || r.entity != ENTITY) return null
            val d = dataOf(r)
            return Attachment(r.id, d.s("transaction_id") ?: return null, d.s("name") ?: "file", d.s("mime") ?: "application/octet-stream",
                d.l("size") ?: 0, d.s("sha256") ?: return null, d.l("created_at") ?: 0)
        }
    }
}

/** Same rules as web/src/lib/goals.ts. */
object Goals {
    enum class Status(val label: String) { DONE("Reached"), AHEAD("Ahead of plan"), ON_TRACK("On track"), BEHIND("Behind plan"), OVERDUE("Past the date"), NO_DATE("No date set") }

    data class Progress(val saved: Long, val target: Long, val fraction: Float, val left: Long, val monthsLeft: Double?, val perMonth: Long?, val expected: Long?, val status: Status)

    /** [worth] gives an account's current worth in its own currency, and [currencyOf] its currency. */
    fun progress(g: Goal, worth: (String) -> Long?, currencyOf: (String) -> String?, today: LocalDate = LocalDate.now()): Progress {
        var saved = max(0, g.savedAmount)
        for (id in g.accountIds) if (currencyOf(id) == g.currency) saved += max(0, worth(id) ?: 0)
        val target = g.targetAmount
        val left = max(0, target - saved)
        val fraction = if (target > 0) min(1f, saved.toFloat() / target) else 0f
        if (left == 0L) return Progress(saved, target, 1f, 0, null, null, null, Status.DONE)
        val due = g.targetDate?.let(LocalDate::parse) ?: return Progress(saved, target, fraction, left, null, null, null, Status.NO_DATE)
        val daysLeft = ChronoUnit.DAYS.between(today, due)
        if (daysLeft < 0) return Progress(saved, target, fraction, left, 0.0, null, target, Status.OVERDUE)
        val months = daysLeft / 30.44
        val perMonth = ceil(left / max(1.0, months)).toLong()
        val start = g.startDate?.let(LocalDate::parse) ?: today
        val total = ChronoUnit.DAYS.between(start, due)
        val elapsed = min(max(0, ChronoUnit.DAYS.between(start, today)), max(0, total))
        val expected = if (total > 0) target * elapsed / total else target
        val slack = target * 0.05
        val status = when {
            saved >= expected + slack -> Status.AHEAD
            saved >= expected - slack -> Status.ON_TRACK
            else -> Status.BEHIND
        }
        return Progress(saved, target, fraction, left, months, perMonth, expected, status)
    }
}

/** Income-tax sections a category can count for (web/src/lib/tax.ts). */
object TaxSections {
    data class Section(val code: String, val label: String, val limit: Long?)
    val all = listOf(
        Section("80C", "80C · Investments and payments", 150_000_00), Section("80CCD(1B)", "80CCD(1B) · NPS (extra)", 50_000_00),
        Section("80D", "80D · Health insurance", 25_000_00), Section("80D-parents", "80D · Parents' health insurance", 25_000_00),
        Section("80E", "80E · Education loan interest", null), Section("80G", "80G · Donations", null),
        Section("80TTA", "80TTA · Savings interest", 10_000_00), Section("24(b)", "24(b) · Home loan interest", 200_000_00),
        Section("HRA", "HRA · Rent paid", null), Section("medical", "Medical expenses", null),
        Section("business", "Business or work expense", null), Section("other", "Other tax-relevant", null),
    )
    fun byCode(code: String?) = all.firstOrNull { it.code == code }

    /** The financial year containing [d], starting in [startMonth] (April in India). */
    fun yearOf(d: LocalDate, startMonth: Int, offset: Int = 0): Pair<LocalDate, LocalDate> {
        val firstYear = (if (d.monthValue >= startMonth) d.year else d.year - 1) + offset
        val from = LocalDate.of(firstYear, startMonth, 1)
        return from to from.plusYears(1).minusDays(1)
    }

    fun label(from: LocalDate): String = if (from.monthValue == 1) "${from.year}" else "FY ${from.year}–${(from.year + 1) % 100}"
}
