package app.kosh.data

import app.kosh.data.db.ReportRow
import app.kosh.data.db.SplitEntity

/**
 * Replaces each transaction whose live splits add up to its amount with one
 * row per split, as the server does (server/internal/report/lines.go). Other
 * rows pass through unchanged, so a half-finished split never loses money.
 */
fun expandSplits(rows: List<ReportRow>, splits: List<SplitEntity>): List<ReportRow> {
    if (splits.isEmpty()) return rows
    val byTx = splits.filter { !it.deleted }.groupBy { it.transactionId }
    return rows.flatMap { r ->
        val parts = byTx[r.id]
        if (parts.isNullOrEmpty() || parts.sumOf { it.amount } != r.amount) listOf(r)
        else parts.sortedBy { it.sortOrder }.map { s -> r.copy(categoryId = s.categoryId, amount = s.amount) }
    }
}
