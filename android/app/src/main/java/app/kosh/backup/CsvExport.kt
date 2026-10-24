package app.kosh.backup

import app.kosh.core.Money
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Lookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Spreadsheet-friendly export of transactions (RFC 4180 CSV, UTF-8 with BOM for Excel). */
object CsvExport {
    private val header = listOf(
        "date", "type", "amount", "currency", "account", "to_account", "to_amount", "category", "parent_category",
        "payee", "note", "status", "excluded_from_reports", "is_adjustment", "id",
    )

    suspend fun write(rows: List<TransactionEntity>, lookup: Lookup, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿")
        w.write(header.joinToString(",")); w.write("\r\n")
        var n = 0
        for (t in rows.sortedWith(compareBy({ it.localDate }, { it.occurredAt }))) {
            if (t.deleted) continue
            val cur = lookup.currencyOf(t.accountId)
            val cat = t.categoryId?.let { lookup.categoryById[it] }
            val parent = cat?.parentId?.let { lookup.categoryById[it] }
            val signed = when (t.type) { "expense", "transfer" -> -t.amount; else -> t.amount }
            val fields = listOf(
                t.localDate, t.type, Money.toPlain(signed, cur), cur, lookup.accountName(t.accountId),
                t.toAccountId?.let { lookup.accountName(it) }.orEmpty(),
                t.toAmount?.let { Money.toPlain(it, lookup.currencyOf(t.toAccountId)) }.orEmpty(),
                cat?.name.orEmpty(), parent?.name.orEmpty(), t.payee.orEmpty(), t.note.orEmpty(), t.status,
                t.excludedFromReports.toString(), t.isAdjustment.toString(), t.id,
            )
            w.write(fields.joinToString(",") { escape(it) }); w.write("\r\n")
            n++
        }
        w.flush()
        n
    }

    fun escape(s: String): String {
        // Guard against spreadsheet formula injection, then quote as needed.
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r" && s.toBigDecimalOrNull() == null) "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
