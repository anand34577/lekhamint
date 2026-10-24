package app.kosh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kosh.core.Dates
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Lookup
import java.time.LocalDate

/** Signed amount of a transaction as seen from [accountId] (or the source account). */
fun signedAmount(t: TransactionEntity, accountId: String? = null): Long = when (t.type) {
    "income" -> t.amount
    "expense" -> -t.amount
    else -> if (accountId != null && t.toAccountId == accountId) (t.toAmount ?: t.amount) else -t.amount
}

@Composable
fun TransactionRow(t: TransactionEntity, lookup: Lookup, onClick: () -> Unit, forAccount: String? = null) {
    val transfer = t.type == "transfer"
    val split = !transfer && t.categoryId == null && lookup.isSplit(t.id)
    val loan = t.debtId != null
    val (icon, color) = when {
        transfer -> "transfer" to "#2563EB"
        loan -> "family" to "#0891B2"
        split -> "split" to "#7C3AED"
        else -> lookup.categoryLook(t.categoryId)
    }
    val categoryLabel = if (loan) {
        "Loan"
    } else if (split) {
        "Split · " + lookup.splitCategories[t.id].orEmpty().distinct().joinToString(", ") { lookup.categoryName(it) }
    } else {
        lookup.categoryName(t.categoryId)
    }
    val title = when {
        transfer -> "${lookup.accountName(t.accountId)} → ${lookup.accountName(t.toAccountId)}"
        !t.payee.isNullOrBlank() -> t.payee
        else -> categoryLabel
    }
    val subtitle = buildList {
        if (!transfer && !t.payee.isNullOrBlank()) add(categoryLabel)
        lookup.tagNames(t.tagIds).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" ") { n -> "#$n" }) }
        if (!transfer && forAccount == null) add(lookup.accountName(t.accountId))
        if (!t.note.isNullOrBlank()) add(t.note)
    }.joinToString(" · ")
    val currency = if (transfer && forAccount != null && t.toAccountId == forAccount) lookup.currencyOf(t.toAccountId) else lookup.currencyOf(t.accountId)
    val amount = signedAmount(t, forAccount)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, color)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (t.recurringRuleId != null) Icon(Icons.Rounded.Repeat, "Recurring", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.status != "cleared") Icon(Icons.Rounded.Schedule, t.status, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(
                amount, currency, signed = true, style = MaterialTheme.typography.bodyLarge,
                color = if (transfer && forAccount == null) MaterialTheme.colorScheme.onSurface else typeColor(if (amount >= 0) "income" else "expense"),
            )
            if (t.originalAmount != null && t.originalCurrency != null) {
                MoneyText(t.originalAmount, t.originalCurrency, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Today" / "Mon, 21 Oct" header with the day's net in [currency]. */
@Composable
fun DayHeader(date: LocalDate, net: Long?, currency: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Dates.relativeDay(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (net != null && net != 0L) {
            MoneyText(net, currency, signed = true, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Net of income and expenses for a day in the base currency (transfers, adjustments and loans excluded). */
fun dayNet(list: List<TransactionEntity>, lookup: Lookup): Long? {
    val inBase = list.filter { it.type != "transfer" && !it.isAdjustment && it.debtId == null && lookup.currencyOf(it.accountId) == lookup.baseCurrency }
    if (inBase.isEmpty()) return null
    return inBase.sumOf { signedAmount(it) }
}
