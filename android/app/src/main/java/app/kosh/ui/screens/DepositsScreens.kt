package app.kosh.ui.screens

import app.kosh.ui.components.FormSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.DepositEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Deposits
import app.kosh.domain.Loans
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.AssumptionNote
import app.kosh.ui.components.DateField
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.ErrorText
import app.kosh.ui.components.FactRow
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Fixed and recurring deposits on Android; the same model and maths as the web (docs/02). */

data class DepositEntry(val dep: DepositEntity, val moves: List<TransactionEntity>, val balance: Long, val est: Deposits.Estimate, val rdPaid: Int)

@Composable
fun rememberDepositEntries(): List<DepositEntry> {
    val c = LocalContainer.current
    val deps by c.repo.deposits.collectAsStateWithLifecycle(emptyList())
    val moves by c.repo.depositMoves.collectAsStateWithLifecycle(emptyList())
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val today = Dates.today()
    return remember(deps, moves, lookup.balances, lookup.accounts) {
        deps.map { d ->
            val ms = moves.filter { it.depositId == d.id }
            val opening = lookup.accountById[d.accountId]?.openingBalance ?: 0
            val rdPaid = if (d.kind != "rd") 0 else ms.count { it.type == "transfer" && it.toAccountId == d.accountId && it.part == "principal" } + (maxOf(0, opening) / maxOf(1, d.amount)).toInt()
            DepositEntry(d, ms, lookup.balances[d.accountId] ?: opening, Deposits.estimate(d, today), rdPaid)
        }
    }
}

@Composable
fun DepositsScreen() {
    val nav = LocalNav.current
    val entries = rememberDepositEntries()
    var adding by remember { mutableStateOf(false) }
    var showClosed by remember { mutableStateOf(false) }
    val today = Dates.today()
    val active = entries.filter { it.dep.status == "active" }
    Scaffold(
        topBar = { KoshTopBar("Deposits", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add deposit") }) },
    ) { padding ->
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (entries.isEmpty()) item { EmptyState(Icons.Rounded.Savings, "No deposits yet", "Add fixed or recurring deposits to see their value, maturity dates and interest. Estimates use the terms you enter; the bank's figure wins when you add it.") }
            else item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("In active deposits", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for ((cur, v) in active.groupBy { it.dep.currency }.mapValues { (_, l) -> l.sumOf { it.balance } }) MoneyText(v, cur, style = MaterialTheme.typography.headlineMedium)
                        for ((cur, v) in active.groupBy { it.dep.currency }.mapValues { (_, l) -> l.sumOf { it.est.maturity } }) Text("About ${Money.ui(v, cur)} at maturity (estimate)", style = MaterialTheme.typography.bodySmall)
                        active.minByOrNull { it.dep.maturityDate }?.let { Text("Next: ${it.dep.name}, ${Dates.relativeDay(LocalDate.parse(it.dep.maturityDate))}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            items(entries.filter { showClosed || it.dep.status == "active" }.sortedBy { it.dep.maturityDate }, key = { it.dep.id }) { e ->
                val d = e.dep
                val start = LocalDate.parse(d.startDate)
                val end = LocalDate.parse(d.maturityDate)
                val total = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1)
                val done = ChronoUnit.DAYS.between(start, today).coerceIn(0, total)
                KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { nav.navigate("deposit/${d.id}") }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(Deposits.kindLabel(d.kind), d.institution, "${d.annualRate}%", if (d.status == "active") "matures ${Dates.medium(end)}" else d.status).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = if (d.status == "active" && !end.isAfter(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                MoneyText(e.balance, d.currency, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("→ ${Money.ui(e.est.maturity, d.currency)}${if (e.est.fromBank) "" else " est."}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (d.status == "active") { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {}) }
                    }
                }
            }
            if (entries.any { it.dep.status != "active" }) item { TextButton(onClick = { showClosed = !showClosed }, Modifier.padding(horizontal = 8.dp)) { Text(if (showClosed) "Hide matured and closed" else "Show matured and closed") } }
            if (entries.isNotEmpty()) item { AssumptionNote("Estimates compound every full period from the start date and pay simple interest for leftover days. Banks differ in day counts, rounding and tax deducted; enter the bank's maturity amount for an exact figure.") }
        }
        }
    }
    if (adding) DepositEditor { adding = false }
}

@Composable
fun DepositDetailScreen(id: String) {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val e = rememberDepositEntries().firstOrNull { it.dep.id == id }
    var action by remember { mutableStateOf<String?>(null) }
    val scope = app.kosh.ui.rememberSafeScope()
    if (e == null) {
        Scaffold(topBar = { KoshTopBar("Deposit", onBack = { nav.popBackStack() }) }) { p -> EmptyState(Icons.Rounded.Savings, "Deposit not found", "It may have been deleted on another device.", Modifier.padding(p)) }
        return
    }
    val d = e.dep
    val cur = d.currency
    val today = Dates.today()
    val end = LocalDate.parse(d.maturityDate)
    val rdTotal = if (d.kind == "rd") (e.est.invested / maxOf(1, d.amount)).toInt() else 0
    Scaffold(topBar = {
        KoshTopBar(d.name, onBack = { nav.popBackStack() }) { IconButton(onClick = { action = "delete" }) { Icon(Icons.Rounded.Delete, "Delete deposit") } }
    }) { padding ->
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("In the deposit now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(e.balance, cur, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        FactRow(if (d.status == "active") "Expected at maturity" else "Maturity value") { Text("${Money.ui(e.est.maturity, cur)}${if (e.est.fromBank) "" else " (estimate)"}") }
                        FactRow("Matures") { Text("${Dates.medium(end)} · ${Dates.relativeDay(end).let { if (end.isAfter(today)) "in " + Deposits.termLabel(today, end) else it }}") }
                        FactRow("Term") { Text(Deposits.termLabel(LocalDate.parse(d.startDate), end)) }
                        FactRow("Interest over the term") { MoneyText(e.est.interest, cur) }
                        if (e.est.tax > 0) FactRow("Estimated tax at ${d.taxPercent}%") { MoneyText(e.est.tax, cur) }
                        if (d.kind == "rd") FactRow("Instalments") { Text("${e.rdPaid} of $rdTotal paid") }
                        FactRow("Rate") { Text("${d.annualRate}% a year, ${d.compounding.replace('_', '-')}") }
                        if (d.payout != "cumulative") FactRow("Interest paid") { Text(d.payout.replace('_', '-') + " to " + lookup.accountName(d.payoutAccountId)) }
                        if (d.status == "active") Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (d.kind == "rd" && e.rdPaid < rdTotal) OutlinedButton(onClick = { action = "instalment" }) { Text("Instalment") }
                            OutlinedButton(onClick = { action = "interest" }) { Text("Interest") }
                            OutlinedButton(onClick = { action = "mature" }) { Text(if (!end.isAfter(today)) "Matured" else "Close") }
                        }
                    }
                }
            }
            if (e.est.payouts.isNotEmpty()) {
                item { Text("Interest payouts", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(e.est.payouts, key = { "p${it.date}" }) { p ->
                    val got = e.moves.any { it.part == "interest" && it.localDate.take(7) == p.date.toString().take(7) }
                    ListItem(headlineContent = { Text(Dates.medium(p.date)) }, supportingContent = { Text(if (got) "Received" else if (p.date.isBefore(today)) "Not recorded yet" else "Expected") },
                        trailingContent = { MoneyText(p.amount, cur) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
            item { Text("History", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            if (e.moves.isEmpty()) item { AssumptionNote("No money movements recorded for this deposit.") }
            items(e.moves.reversed(), key = { it.id }) { m ->
                val signed = when { m.type == "transfer" -> if (m.toAccountId == d.accountId) m.amount else -m.amount; m.type == "income" -> m.amount; else -> -m.amount }
                ListItem(headlineContent = { Text(m.note ?: m.part.orEmpty()) }, supportingContent = { Text(Dates.medium(LocalDate.parse(m.localDate))) },
                    trailingContent = { MoneyText(signed, cur, signed = true) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
        }
    }
    when (action) {
        "mature" -> MatureDialog(e, lookup, early = end.isAfter(today)) { action = null }
        "interest" -> InterestDialog(e, lookup) { action = null }
        "instalment" -> InstalmentDialog(e, lookup) { action = null }
        "delete" -> AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Delete ${d.name}?") },
            text = { Text("The deposit's details are removed. Its account and linked transactions stay, so balances do not change. If it matured or was closed, record that instead to keep the history.") },
            confirmButton = { TextButton(onClick = { scope.launch { c.repo.deleteDeposit(d.id); action = null; nav.popBackStack() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
    }
}

private fun Lookup.bankAccounts(cur: String): List<AccountEntity> = activeAccounts.filter { it.currency == cur && !Lookup.isLiability(it.type) && !Lookup.isInvestment(it.type) }

@Composable
private fun MatureDialog(e: DepositEntry, lookup: Lookup, early: Boolean, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val d = e.dep
    val ids = remember { Triple(Ids.newV7(), Ids.newV7(), Ids.newV7()) }
    var received by remember { mutableStateOf(if (early) "" else Money.toPlain(e.est.maturity, d.currency)) }
    var tax by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(if (early) Dates.today() else minOf(Dates.today(), LocalDate.parse(d.maturityDate))) }
    var into by remember { mutableStateOf(d.payoutAccountId ?: lookup.bankAccounts(d.currency).firstOrNull()?.id) }
    var saving by remember { mutableStateOf(false) }
    val got = Money.parse(received, d.currency)
    val t = if (tax.isBlank()) 0L else Money.parse(tax, d.currency)
    val diff = if (got != null && t != null) got + t - e.balance else null
    val error = when {
        got == null || got <= 0 -> "Enter the amount that arrived, as on the bank statement"
        t == null || t < 0 -> "Enter the tax deducted as an amount, or leave it empty"
        into == null -> "Choose the account the money went to"
        !early && diff != null && diff < 0 && -diff > e.balance / 20 -> "That is much less than what is in the deposit. If it closed early with a penalty, use early closure; otherwise check the amount."
        else -> null
    }
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text(if (early) "Close ${d.name} early" else "${d.name} matured") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("In the deposit: ${Money.ui(e.balance, d.currency)}. " + if (early) "Banks usually pay a lower rate when closed early; enter what you received." else "Expected about ${Money.ui(e.est.maturity, d.currency)}.", style = MaterialTheme.typography.bodySmall)
                AmountField(received, { received = it }, d.currency, "Amount received")
                AmountField(tax, { tax = it }, d.currency, "Tax deducted (optional)")
                DateField("On", date, { date = it }, max = Dates.today())
                AccountPicker("Into account", lookup.bankAccounts(d.currency), into, lookup) { into = it }
                if (diff != null && diff != 0L && error == null) Text(if (diff > 0) "Records ${Money.ui(diff, d.currency)} as interest income." else "Records ${Money.ui(-diff, d.currency)} as a penalty.", style = MaterialTheme.typography.bodySmall)
                ErrorText(if (received.isNotBlank()) error else null)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && !saving, onClick = {
                saving = true
                val payee = d.institution ?: d.name
                val txs = listOfNotNull(
                    diff?.takeIf { it > 0 }?.let { linkedTx(ids.first, "income", d.accountId, it, date, "interest", "${d.name} · interest", payee, categoryId = lookup.findCategory("income", "Interest"), depositId = d.id) },
                    diff?.takeIf { it < 0 }?.let { linkedTx(ids.first, "expense", d.accountId, -it, date, "fee", "${d.name} · early closure penalty", payee, categoryId = lookup.findCategory("expense", "Bank fees"), depositId = d.id) },
                    t?.takeIf { it > 0 }?.let { linkedTx(ids.second, "expense", d.accountId, it, date, "fee", "${d.name} · tax deducted", payee, categoryId = lookup.findCategory("expense", "Taxes"), depositId = d.id) },
                    linkedTx(ids.third, "transfer", d.accountId, got!!, date, "principal", "${d.name} · ${if (early) "closed early" else "matured"}", payee, toAccountId = into, depositId = d.id),
                )
                scope.launch { c.repo.saveAll(deposits = listOf(d.copy(status = if (early) "closed" else "matured", closedDate = date.toString())), transactions = txs); onDismiss() }
            }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InterestDialog(e: DepositEntry, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val d = e.dep
    val id = remember { Ids.newV7() }
    val next = e.est.payouts.firstOrNull { p -> e.moves.none { it.part == "interest" && it.localDate.take(7) == p.date.toString().take(7) } }
    var amount by remember { mutableStateOf(next?.let { Money.toPlain(it.amount, d.currency) }.orEmpty()) }
    var date by remember { mutableStateOf(next?.date?.takeIf { !it.isAfter(Dates.today()) } ?: Dates.today()) }
    val payout = d.payout != "cumulative"
    var into by remember { mutableStateOf(if (payout) d.payoutAccountId ?: lookup.bankAccounts(d.currency).firstOrNull()?.id else d.accountId) }
    val minor = Money.parse(amount, d.currency)
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("Interest · ${d.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (payout) "Interest paid out to your account. It counts as income." else "Interest added to the deposit. It counts as income and grows the deposit.", style = MaterialTheme.typography.bodySmall)
                AmountField(amount, { amount = it }, d.currency, "Interest")
                DateField("On", date, { date = it }, max = Dates.today())
                if (payout) AccountPicker("Paid into", lookup.bankAccounts(d.currency), into, lookup) { into = it }
            }
        },
        confirmButton = {
            TextButton(enabled = minor != null && minor > 0 && into != null, onClick = {
                scope.launch {
                    c.repo.saveAll(transactions = listOf(linkedTx(id, "income", into!!, minor!!, date, "interest", "${d.name} · interest", d.institution ?: d.name, categoryId = lookup.findCategory("income", "Interest"), depositId = d.id)))
                    onDismiss()
                }
            }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InstalmentDialog(e: DepositEntry, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val d = e.dep
    val id = remember { Ids.newV7() }
    val due = LocalDate.parse(d.startDate).plusMonths(e.rdPaid.toLong())
    var date by remember { mutableStateOf(minOf(due, Dates.today())) }
    var from by remember { mutableStateOf(lookup.bankAccounts(d.currency).firstOrNull()?.id) }
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("RD instalment ${e.rdPaid + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Due ${Dates.relativeDay(due)}. Moves ${Money.ui(d.amount, d.currency)} into the deposit; it is saving, not spending.", style = MaterialTheme.typography.bodySmall)
                DateField("On", date, { date = it }, max = Dates.today())
                AccountPicker("From", lookup.bankAccounts(d.currency), from, lookup) { from = it }
            }
        },
        confirmButton = {
            TextButton(enabled = from != null, onClick = {
                scope.launch {
                    c.repo.saveAll(transactions = listOf(linkedTx(id, "transfer", from!!, d.amount, date, "principal", "${d.name} · instalment ${e.rdPaid + 1}", d.institution ?: d.name, toAccountId = d.accountId, depositId = d.id)))
                    onDismiss()
                }
            }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DepositEditor(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val scope = app.kosh.ui.rememberSafeScope()
    val snackbar = LocalSnackbar.current
    val ids = remember { Triple(Ids.newV7(), Ids.newV7(), Ids.newV7()) }
    val cur = lookup.baseCurrency
    val today = Dates.today()
    var kind by remember { mutableStateOf("fd") }
    var name by remember { mutableStateOf("") }
    var institution by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    // Term as years + months + days: banks sell 7-day, 46-day and "1 year 4 months 15 days" deposits.
    var years by remember { mutableStateOf("1") }
    var months by remember { mutableStateOf("0") }
    var days by remember { mutableStateOf("0") }
    var start by remember { mutableStateOf(today) }
    var compounding by remember { mutableStateOf("quarterly") }
    var payout by remember { mutableStateOf("cumulative") }
    var bankMaturity by remember { mutableStateOf("") }
    var fromBank by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf(lookup.bankAccounts(cur).firstOrNull()?.id) }
    var saving by remember { mutableStateOf(false) }
    val minor = Money.parse(amount, cur)
    val ty = years.ifBlank { "0" }.toIntOrNull()
    val tm = months.ifBlank { "0" }.toIntOrNull()
    val td = if (kind == "rd") 0 else days.ifBlank { "0" }.toIntOrNull()
    val n = if (ty != null && tm != null) ty * 12 + tm else null
    val end = if (n != null && td != null) start.plusMonths(n.toLong()).plusDays(td.toLong()) else null
    val bank = if (bankMaturity.isBlank()) null else Money.parse(bankMaturity, cur)
    val preview = if (minor != null && minor > 0 && Loans.rateError(rate) == null && end != null && end.isAfter(start))
        Deposits.estimate(DepositEntity("", name = "", accountId = "", currency = cur, amount = minor, annualRate = rate.trim(), startDate = start.toString(), maturityDate = end.toString(), kind = kind,
            compounding = compounding, payout = if (kind == "rd") "cumulative" else payout, maturityAmount = bank), today) else null
    val error = when {
        name.isBlank() -> "Give the deposit a name"
        minor == null || minor <= 0 -> if (kind == "rd") "Enter the monthly instalment" else "Enter the amount deposited"
        Loans.rateError(rate) != null -> Loans.rateError(rate)
        end == null || !end.isAfter(start) -> if (kind == "rd") "Enter the term in years and months" else "Enter the term: years, months or days"
        n!! > 600 || td!! > 3650 -> "Term must be 50 years or less"
        kind == "rd" && n < 1 -> "A recurring deposit runs for at least one month"
        bankMaturity.isNotBlank() && (bank == null || bank <= 0) -> "Enter the bank's maturity amount, or leave it empty"
        fromBank && from == null -> "Choose the account it was paid from"
        else -> null
    }
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("Add a deposit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "fd", { kind = "fd" }, label = { Text("Fixed") })
                    FilterChip(kind == "rd", { kind = "rd" }, label = { Text("Recurring") })
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(institution, { institution = it }, label = { Text("Bank or institution") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField(amount, { amount = it }, cur, if (kind == "rd") "Monthly instalment" else "Amount deposited")
                OutlinedTextField(rate, { rate = it }, label = { Text("Interest rate (% a year)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = rate.isNotBlank() && Loans.rateError(rate) != null, supportingText = { Text(if (rate.isNotBlank()) Loans.rateError(rate) ?: "" else "As on the receipt") })
                DateField("Start date", start, { start = it })
                Text("Term", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = if (kind == "rd") listOf("6 months" to Triple(0, 6, 0), "1 year" to Triple(1, 0, 0), "2 years" to Triple(2, 0, 0), "3 years" to Triple(3, 0, 0), "5 years" to Triple(5, 0, 0))
                    else listOf("7 days" to Triple(0, 0, 7), "30 days" to Triple(0, 0, 30), "91 days" to Triple(0, 0, 91), "6 months" to Triple(0, 6, 0),
                        "1 year" to Triple(1, 0, 0), "2 years" to Triple(2, 0, 0), "5 years" to Triple(5, 0, 0))
                    for ((label, t) in presets) {
                        val on = ty == t.first && tm == t.second && (kind == "rd" || td == t.third)
                        FilterChip(on, { years = t.first.toString(); months = t.second.toString(); days = t.third.toString() }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val digits = { v: String -> v.filter(Char::isDigit).take(4) }
                    OutlinedTextField(years, { years = digits(it) }, label = { Text("Years") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    OutlinedTextField(months, { months = digits(it) }, label = { Text("Months") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    if (kind != "rd") OutlinedTextField(days, { days = digits(it) }, label = { Text("Days") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
                if (end != null && end.isAfter(start)) {
                    Text("Matures on ${Dates.medium(end)} · ${Deposits.days(start, end)} days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Compounding", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, l) in listOf("quarterly" to "Quarterly", "monthly" to "Monthly", "yearly" to "Yearly", "simple" to "Simple")) FilterChip(compounding == k, { compounding = k }, label = { Text(l) })
                }
                if (kind == "fd") {
                    Text("Interest", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((k, l) in listOf("cumulative" to "At maturity", "monthly" to "Monthly", "quarterly" to "Quarterly")) FilterChip(payout == k, { payout = k }, label = { Text(l) })
                    }
                }
                AmountField(bankMaturity, { bankMaturity = it }, cur, "Bank's maturity amount (optional)")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { fromBank = !fromBank }) {
                    Checkbox(fromBank, { fromBank = it }); Text(if (kind == "rd") "Instalments come from my account" else "Paid from one of my accounts")
                }
                if (fromBank) AccountPicker("From", lookup.bankAccounts(cur), from, lookup) { from = it }
                else Text(if (kind == "rd") "Already running: the instalments due so far are counted as paid." else "Opened before: no account balance goes down.", style = MaterialTheme.typography.bodySmall)
                preview?.let { Text("Invested ${Money.ui(it.invested, cur)} · interest ${Money.ui(it.interest, cur)} · ${if (payout == "cumulative" || kind == "rd") "at maturity" else "principal back"} ${Money.ui(it.maturity, cur)}${if (it.fromBank) "" else " (estimate)"}", style = MaterialTheme.typography.bodySmall) }
                ErrorText(if (name.isNotBlank() || amount.isNotBlank()) error else null)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && !saving, onClick = {
                saving = true
                val now = System.currentTimeMillis()
                val payee = institution.trim().ifEmpty { name.trim() }
                val rdSoFar = if (kind == "rd" && !fromBank) (0 until n!!).count { !start.plusMonths(it.toLong()).isAfter(today) } else 0
                val account = AccountEntity(id = ids.second, name = name.trim(), type = "deposit", currency = cur,
                    openingBalance = if (kind == "rd") rdSoFar * minor!! else if (fromBank) 0 else minor!!, color = "#0F766E", icon = "savings", note = institution.trim().ifEmpty { null }, createdAt = now)
                val dep = DepositEntity(id = ids.first, name = name.trim(), institution = institution.trim().ifEmpty { null }, kind = kind, accountId = ids.second, currency = cur,
                    amount = minor!!, annualRate = rate.trim(), startDate = start.toString(), maturityDate = end!!.toString(), compounding = compounding,
                    payout = if (kind == "rd") "cumulative" else payout, payoutAccountId = if (payout != "cumulative") from else null, maturityAmount = bank, createdAt = now)
                val funding = if (fromBank && !start.isAfter(today)) linkedTx(ids.third, "transfer", from!!, minor, start, "principal", if (kind == "rd") "${dep.name} · instalment 1" else "${dep.name} · deposit opened", payee, toAccountId = ids.second, depositId = ids.first) else null
                scope.launch {
                    c.repo.saveAll(accounts = listOf(account), deposits = listOf(dep), transactions = listOfNotNull(funding))
                    snackbar.showSnackbar("${dep.name} added")
                    onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
