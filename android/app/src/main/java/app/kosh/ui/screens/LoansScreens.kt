package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.kosh.data.db.LoanEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Loans
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.Routes
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
import app.kosh.ui.components.PickerSheet
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.typeColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Loans & EMIs on Android (docs/02, "Loans and deposits"), the same model as
 * the web: what is owed lives in a loan account; an EMI is a principal
 * transfer into it plus an interest expense, linked by loan_id.
 */

/** A transaction linked to a loan, deposit or debt, dated [date] (noon unless today). */
fun linkedTx(
    id: String, type: String, accountId: String, amount: Long, date: LocalDate, part: String?, note: String?, payee: String?,
    toAccountId: String? = null, categoryId: String? = null, loanId: String? = null, depositId: String? = null,
): TransactionEntity {
    val zone = ZoneId.systemDefault()
    val at = date.atTime(if (date == Dates.today()) LocalTime.now(zone) else LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    return TransactionEntity(
        id = id, type = type, accountId = accountId, amount = amount, toAccountId = toAccountId, toAmount = if (type == "transfer") amount else null,
        categoryId = if (type == "transfer") null else categoryId, payee = payee, note = note, occurredAt = at, localDate = date.toString(),
        tzOffsetMin = Dates.tzOffsetMinutes(at, zone), loanId = loanId, depositId = depositId, part = part, createdAt = now,
    )
}

/** A category by any of [names] (case-insensitive). */
fun Lookup.findCategory(kind: String, vararg names: String): String? =
    names.firstNotNullOfOrNull { n -> categories.firstOrNull { !it.archived && it.kind == kind && it.name.equals(n, ignoreCase = true) }?.id }

fun Lookup.loanInterestCategory(l: LoanEntity): String? =
    l.interestCategoryId?.takeIf { categoryById.containsKey(it) } ?: if (l.kind == "home") findCategory("expense", "Mortgage interest", "Interest & fees") else findCategory("expense", "Interest & fees", "Loan interest")

/** Bank, cash and wallet accounts in [currency] (not loan, deposit or card accounts). */
fun Lookup.payAccounts(currency: String): List<AccountEntity> =
    activeAccounts.filter { it.currency == currency && !Lookup.isLiability(it.type) && !Lookup.isInvestment(it.type) }

@Composable
fun AccountPicker(label: String, choices: List<AccountEntity>, selected: String?, lookup: Lookup, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (choices.isEmpty()) {
        Text("No matching account. Add one under Accounts first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(lookup.accountName(selected) + (selected?.let { lookup.balances[it] }?.let { " · ${Money.format(it, lookup.currencyOf(selected))}" } ?: ""))
        }
    }
    if (open) PickerSheet(title = label, items = choices, onDismiss = { open = false }, onPick = { onPick(it.id) }, selected = { it.id == selected }) { a ->
        ListItem(headlineContent = { Text(a.name) }, supportingContent = { lookup.balances[a.id]?.let { Text("Balance ${Money.format(it, a.currency)}") } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

data class LoanEntry(val loan: LoanEntity, val moves: List<TransactionEntity>, val status: Loans.Status)

@Composable
fun rememberLoanEntries(): List<LoanEntry> {
    val c = LocalContainer.current
    val loans by c.repo.loans.collectAsStateWithLifecycle(emptyList())
    val moves by c.repo.loanMoves.collectAsStateWithLifecycle(emptyList())
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val today = Dates.today()
    return remember(loans, moves, lookup.balances) {
        loans.map { l ->
            val ms = moves.filter { it.loanId == l.id }
            LoanEntry(l, ms, Loans.status(l, ms, lookup.balances[l.accountId] ?: lookup.accountById[l.accountId]?.openingBalance ?: 0, today))
        }
    }
}

@Composable
fun LoansScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val entries = rememberLoanEntries()
    var adding by remember { mutableStateOf(false) }
    var showClosed by remember { mutableStateOf(false) }
    val active = entries.filter { !it.loan.closed }
    Scaffold(
        topBar = { KoshTopBar("Loans & EMIs", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add loan") }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (entries.isEmpty()) {
                item { EmptyState(Icons.Rounded.AccountBalance, "No loans yet", "Add a home, car, personal or any other loan, new or already running. LekhaMint keeps the schedule, splits each EMI into principal and interest, and reminds you before it is due.") }
            } else {
                item {
                    val owed = active.groupBy { it.loan.currency }.mapValues { (_, l) -> l.sumOf { it.status.outstanding } }
                    val emis = active.groupBy { it.loan.currency }.mapValues { (_, l) -> l.sumOf { it.status.emi } }
                    KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Still owed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for ((cur, v) in owed) MoneyText(v, cur, style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(4.dp))
                            for ((cur, v) in emis) Text("EMIs ${Money.format(v, cur)} a month across ${active.size} ${if (active.size == 1) "loan" else "loans"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(entries.filter { showClosed || !it.loan.closed }, key = { it.loan.id }) { e -> LoanCard(e) { nav.navigate("loan/${e.loan.id}") } }
            if (entries.any { it.loan.closed }) item {
                TextButton(onClick = { showClosed = !showClosed }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showClosed) "Hide closed loans" else "Show closed loans") }
            }
            if (entries.isNotEmpty()) item { AssumptionNote("Schedules assume interest is charged monthly on the reducing balance. Lenders may round or count days differently; enter the EMI and each payment's interest as your statement shows.") }
        }
    }
    if (adding) LoanEditor(null) { adding = false }
}

@Composable
private fun LoanCard(e: LoanEntry, onClick: () -> Unit) {
    val s = e.status
    val l = e.loan
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(Loans.kindLabel(l.kind), l.lender, l.annualRate?.let { "$it% ${l.rateType}" } ?: "Interest-free").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(s.outstanding, l.currency, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (l.closed) "closed" else "owed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!l.closed) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { s.progress.toFloat() }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(
                        "EMI ${Money.format(s.emi, l.currency)}",
                        s.nextDue?.let { (if (s.overdue > 0) "Overdue since " else "Next ") + Dates.relativeDay(it) },
                        "Paid ${s.paidInstalments} of ${s.paidInstalments + s.remainingInstalments}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LoanDetailScreen(id: String) {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val e = rememberLoanEntries().firstOrNull { it.loan.id == id }
    var tab by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<String?>(null) }
    if (e == null) {
        Scaffold(topBar = { KoshTopBar("Loan", onBack = { nav.popBackStack() }) }) { p -> EmptyState(Icons.Rounded.AccountBalance, "Loan not found", "It may have been deleted on another device.", Modifier.padding(p)) }
        return
    }
    val l = e.loan
    val s = e.status
    val cur = l.currency
    Scaffold(topBar = {
        KoshTopBar(l.name, onBack = { nav.popBackStack() }) {
            IconButton(onClick = { action = "edit" }) { Icon(Icons.Rounded.Edit, "Edit loan") }
            IconButton(onClick = { action = "delete" }) { Icon(Icons.Rounded.Delete, "Delete loan") }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Outstanding", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(s.outstanding, cur, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        FactRow("EMI") { MoneyText(s.emi, cur) }
                        s.nextDue?.let { d -> FactRow(if (s.overdue > 0) "Overdue since" else "Next due") { Text(Dates.relativeDay(d), color = if (s.overdue > 0) MaterialTheme.colorScheme.error else Color.Unspecified) } }
                        if (s.nextDue != null) FactRow("Next split") { Text("${Money.format(s.nextInterest, cur)} interest + ${Money.format(s.nextPrincipal, cur)} principal", style = MaterialTheme.typography.bodySmall) }
                        FactRow("Instalments") { Text("${s.paidInstalments} paid · ${s.remainingInstalments} left") }
                        FactRow("Interest paid") { MoneyText(s.interestPaid, cur) }
                        FactRow("Interest still to pay") { MoneyText(s.projectedInterest, cur) }
                        s.endDate?.let { FactRow("Last EMI") { Text(Dates.medium(it)) } }
                        if (s.overpaid > 0) Text("More was paid than owed (${Money.format(s.overpaid, cur)}). Check for a payment recorded twice.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (s.projection.neverRepaid) Text("The EMI does not cover the monthly interest, so this loan would never be repaid. Check the EMI and rate.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (!l.closed) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.outstanding > 0) OutlinedButton(onClick = { action = "pay" }) { Text("Pay EMI") }
                            if (s.outstanding > 0) OutlinedButton(onClick = { action = "prepay" }) { Text("Prepay") }
                            OutlinedButton(onClick = { action = "close" }) { Text(if (s.outstanding > 0) "Foreclose" else "Close") }
                        }
                    }
                }
            }
            item {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Schedule") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Payments") })
                }
            }
            if (tab == 0) {
                items(s.projection.rows.take(120), key = { "r${it.n}" }) { r ->
                    ListItem(
                        headlineContent = { Text("#${r.n} · ${Dates.medium(r.date)}", color = if (r.date.isBefore(Dates.today())) MaterialTheme.colorScheme.error else Color.Unspecified) },
                        supportingContent = { Text("${Money.format(r.principal, cur)} principal · ${Money.format(r.interest, cur)} interest · ${Money.format(r.closing, cur)} left") },
                        trailingContent = { MoneyText(r.payment, cur) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (s.projection.rows.size > 120) item { AssumptionNote("Showing the next 120 of ${s.projection.rows.size} instalments.") }
                item { AssumptionNote("Projected from what is owed today at ${l.annualRate ?: "0"}% a year, monthly on the reducing balance. An estimate, not a lender statement.") }
            } else {
                val groups = e.moves.filter { it.part != null && (it.type != "transfer" || it.toAccountId == l.accountId) }.groupBy { it.localDate }.toSortedMap(reverseOrder())
                if (groups.isEmpty()) item { AssumptionNote(if (l.paidBefore > 0) "No payments recorded yet (${l.paidBefore} were paid before tracking started)." else "No payments recorded yet.") }
                for ((date, list) in groups) item(key = "p$date") {
                    val principal = list.filter { it.part == "principal" || it.part == "prepayment" }.sumOf { it.toAmount ?: it.amount }
                    val interest = list.filter { it.part == "interest" }.sumOf { it.amount }
                    val fee = list.filter { it.part == "fee" }.sumOf { it.amount }
                    ListItem(
                        headlineContent = { Text(Dates.medium(LocalDate.parse(date))) },
                        supportingContent = { Text(listOfNotNull("${Money.format(principal, cur)} principal", "${Money.format(interest, cur)} interest", fee.takeIf { it > 0 }?.let { "${Money.format(it, cur)} fees" }).joinToString(" · ")) },
                        trailingContent = { MoneyText(-(principal + interest + fee), cur, signed = true, color = typeColor("expense")) },
                        modifier = Modifier.clickable { list.firstOrNull()?.let { nav.navigate(Routes.editTx(it.id, it.type)) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
    when (action) {
        "pay" -> PayEmiDialog(e, lookup) { action = null }
        "prepay" -> PrepayDialog(e, lookup) { action = null }
        "close" -> CloseLoanDialog(e, lookup) { action = null }
        "edit" -> LoanEditor(l) { action = null }
        "delete" -> {
            val scope = rememberCoroutineScope()
            AlertDialog(
                onDismissRequest = { action = null },
                title = { Text("Delete ${l.name}?") },
                text = { Text("The loan's schedule and reminders are removed. Its loan account and every payment stay as ordinary transactions, so balances do not change. To keep the history, close the loan instead.") },
                confirmButton = { TextButton(onClick = { scope.launch { c.repo.deleteLoan(l.id); action = null; nav.popBackStack() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun PayEmiDialog(e: LoanEntry, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val l = e.loan
    val s = e.status
    val next = s.projection.rows.firstOrNull()
    val today = Dates.today()
    val ids = remember { Triple(Ids.newV7(), Ids.newV7(), Ids.newV7()) }
    var amount by remember { mutableStateOf(Money.toPlain(next?.payment ?: s.emi, l.currency)) }
    var interestText by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(if (next != null && !next.date.isAfter(today)) next.date else today) }
    val choices = lookup.payAccounts(l.currency)
    var accountId by remember { mutableStateOf(l.payFromAccountId?.takeIf { id -> choices.any { it.id == id } } ?: choices.firstOrNull()?.id) }
    var saving by remember { mutableStateOf(false) }
    val minor = Money.parse(amount, l.currency)
    val auto = minor?.let { Loans.splitPayment(it, s.outstanding, l.annualRate) }
    val interest = if (interestText.isBlank()) auto?.first else Money.parse(interestText, l.currency)
    val principal = if (minor != null && interest != null) minor - interest else null
    val feeMinor = if (fee.isBlank()) 0L else Money.parse(fee, l.currency)
    val error = when {
        minor == null || minor <= 0 -> "Enter the amount paid"
        interest == null || interest < 0 -> "Enter the interest part as an amount, or leave it empty"
        interest > minor -> "The interest cannot be more than the payment"
        principal != null && principal > s.outstanding -> "That repays more than the ${Money.format(s.outstanding, l.currency)} owed. Use Foreclose to close the loan."
        feeMinor == null || feeMinor < 0 -> "Enter the charges as an amount, or leave them empty"
        accountId == null -> "Choose the account it was paid from"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay EMI · ${l.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                next?.let { Text("Instalment ${it.n}, due ${Dates.relativeDay(it.date)}. Owed now: ${Money.format(s.outstanding, l.currency)}", style = MaterialTheme.typography.bodySmall) }
                AmountField(amount, { amount = it }, l.currency, "Amount paid")
                AmountField(interestText, { interestText = it }, l.currency, "Interest part (optional)",
                    supporting = "Calculated ${Money.format(auto?.first ?: 0, l.currency)}; type your statement's figure if different")
                AmountField(fee, { fee = it }, l.currency, "Late fee or charges (optional)")
                DateField("Paid on", date, { date = it }, max = today)
                AccountPicker("Paid from", choices, accountId, lookup) { accountId = it }
                if (principal != null && interest != null && error == null) {
                    Text("Split: ${Money.format(principal, l.currency)} principal + ${Money.format(interest, l.currency)} interest" +
                        if (principal == s.outstanding) ". This pays off the loan." else "", style = MaterialTheme.typography.bodySmall)
                }
                ErrorText(if (amount.isNotBlank()) error else null)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && !saving, onClick = {
                saving = true
                val base = { id: String, type: String, amt: Long, part: String, note: String, to: String?, cat: String? ->
                    linkedTx(id, type, accountId!!, amt, date, part, note, l.lender ?: l.name, toAccountId = to, categoryId = cat, loanId = l.id)
                }
                val txs = listOfNotNull(
                    principal!!.takeIf { it > 0 }?.let { base(ids.first, "transfer", it, "principal", "${l.name} EMI · principal", l.accountId, null) },
                    interest!!.takeIf { it > 0 }?.let { base(ids.second, "expense", it, "interest", "${l.name} EMI · interest", null, lookup.loanInterestCategory(l)) },
                    feeMinor!!.takeIf { it > 0 }?.let { base(ids.third, "expense", it, "fee", "${l.name} · charges", null, lookup.findCategory("expense", "Bank fees", "Interest & fees")) },
                )
                val closes = principal == s.outstanding
                scope.launch {
                    c.repo.saveAll(loans = if (closes) listOf(l.copy(closed = true, closedDate = date.toString())) else emptyList(), transactions = txs)
                    snackbar.showSnackbar(if (closes) "${l.name} is fully repaid" else "EMI recorded")
                    onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PrepayDialog(e: LoanEntry, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val l = e.loan
    val s = e.status
    val id = remember { Ids.newV7() }
    var amount by remember { mutableStateOf("") }
    var keepEmi by remember { mutableStateOf(true) }
    val choices = lookup.payAccounts(l.currency)
    var accountId by remember { mutableStateOf(l.payFromAccountId ?: choices.firstOrNull()?.id) }
    var date by remember { mutableStateOf(Dates.today()) }
    var saving by remember { mutableStateOf(false) }
    val minor = Money.parse(amount, l.currency)
    val left = minor?.let { s.outstanding - it }
    val anchor = LocalDate.parse(l.firstDueDate)
    val before = s.projection
    val keepEmiSched = left?.takeIf { it > 0 }?.let { Loans.schedule(it, l.annualRate, s.emi, anchor, s.paidInstalments + 1) }
    val newEmi = left?.takeIf { it > 0 }?.let { Loans.emi(it, l.annualRate, maxOf(1, s.remainingInstalments)) }
    val keepTenureSched = if (left != null && left > 0 && newEmi != null) Loans.schedule(left, l.annualRate, newEmi, anchor, s.paidInstalments + 1) else null
    val error = when {
        minor == null || minor <= 0 -> "Enter the amount you are prepaying"
        minor >= s.outstanding -> "That is all of the ${Money.format(s.outstanding, l.currency)} owed. Use Foreclose instead."
        accountId == null -> "Choose the account"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prepay · ${l.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AmountField(amount, { amount = it }, l.currency, "Prepayment")
                DateField("On", date, { date = it }, max = Dates.today())
                AccountPicker("From", choices, accountId, lookup) { accountId = it }
                if (keepEmiSched != null && keepTenureSched != null && newEmi != null) {
                    FilterChip(keepEmi, { keepEmi = true }, label = { Text("Keep EMI: ${keepEmiSched.rows.size} left (saves ${Money.format(before.totalInterest - keepEmiSched.totalInterest, l.currency)})") })
                    FilterChip(!keepEmi, { keepEmi = false }, label = { Text("Lower EMI to ${Money.format(newEmi, l.currency)} (saves ${Money.format(before.totalInterest - keepTenureSched.totalInterest, l.currency)})") })
                    Text("Tell your lender which option you chose.", style = MaterialTheme.typography.bodySmall)
                }
                ErrorText(if (amount.isNotBlank()) error else null)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && !saving, onClick = {
                saving = true
                val tx = linkedTx(id, "transfer", accountId!!, minor!!, date, "prepayment", "${l.name} · prepayment", l.lender ?: l.name, toAccountId = l.accountId, loanId = l.id)
                scope.launch {
                    c.repo.saveAll(loans = if (!keepEmi && newEmi != null) listOf(l.copy(emiAmount = newEmi)) else emptyList(), transactions = listOf(tx))
                    onDismiss()
                }
            }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloseLoanDialog(e: LoanEntry, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val l = e.loan
    val owed = e.status.outstanding
    val ids = remember { Triple(Ids.newV7(), Ids.newV7(), Ids.newV7()) }
    var interest by remember { mutableStateOf(Money.toPlain(Loans.monthlyInterest(owed, l.annualRate), l.currency)) }
    var charge by remember { mutableStateOf("") }
    val choices = lookup.payAccounts(l.currency)
    var accountId by remember { mutableStateOf(l.payFromAccountId ?: choices.firstOrNull()?.id) }
    var date by remember { mutableStateOf(Dates.today()) }
    var saving by remember { mutableStateOf(false) }
    val i = if (interest.isBlank()) 0L else Money.parse(interest, l.currency)
    val ch = if (charge.isBlank()) 0L else Money.parse(charge, l.currency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (owed > 0) "Foreclose ${l.name}" else "Close ${l.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (owed > 0) {
                    Text("Pays the remaining ${Money.format(owed, l.currency)} of principal and closes the loan. Use the figures from your lender's foreclosure letter.")
                    AmountField(interest, { interest = it }, l.currency, "Interest to the closing date")
                    AmountField(charge, { charge = it }, l.currency, "Foreclosure charge")
                    DateField("On", date, { date = it }, max = Dates.today())
                    AccountPicker("From", choices, accountId, lookup) { accountId = it }
                } else Text("Nothing is owed, so this moves the loan to the closed list. Its history stays.")
                ErrorText(if (i == null || ch == null) "Interest and charges must be amounts" else null)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && i != null && ch != null && (owed == 0L || accountId != null), onClick = {
                saving = true
                val txs = if (owed == 0L) emptyList() else listOfNotNull(
                    linkedTx(ids.first, "transfer", accountId!!, owed, date, "prepayment", "${l.name} · foreclosure", l.lender ?: l.name, toAccountId = l.accountId, loanId = l.id),
                    i!!.takeIf { it > 0 }?.let { linkedTx(ids.second, "expense", accountId!!, it, date, "interest", "${l.name} · interest to closure", l.lender ?: l.name, categoryId = lookup.loanInterestCategory(l), loanId = l.id) },
                    ch!!.takeIf { it > 0 }?.let { linkedTx(ids.third, "expense", accountId!!, it, date, "fee", "${l.name} · foreclosure charge", l.lender ?: l.name, categoryId = lookup.findCategory("expense", "Bank fees"), loanId = l.id) },
                )
                scope.launch { c.repo.saveAll(loans = listOf(l.copy(closed = true, closedDate = date.toString())), transactions = txs); onDismiss() }
            }) { Text(if (owed > 0) "Pay off and close" else "Close") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add a new or already-running loan, or edit one's terms. */
@Composable
fun LoanEditor(existing: LoanEntity?, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val today = Dates.today()
    val ids = remember { listOf(existing?.id ?: Ids.newV7(), existing?.accountId ?: Ids.newV7(), Ids.newV7(), Ids.newV7()) }
    val cur = existing?.currency ?: lookup.baseCurrency
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var lender by remember { mutableStateOf(existing?.lender.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: "home") }
    var principal by remember { mutableStateOf(existing?.let { Money.toPlain(it.principal, cur) }.orEmpty()) }
    var rate by remember { mutableStateOf(existing?.annualRate.orEmpty()) }
    var free by remember { mutableStateOf(existing != null && existing.annualRate == null) }
    var months by remember { mutableStateOf(existing?.tenureMonths?.toString().orEmpty()) }
    var start by remember { mutableStateOf(existing?.startDate?.let(LocalDate::parse) ?: today) }
    var firstDue by remember { mutableStateOf(existing?.firstDueDate?.let(LocalDate::parse) ?: today.plusMonths(1)) }
    var customEmi by remember { mutableStateOf(existing?.emiAmount?.let { Money.toPlain(it, cur) }.orEmpty()) }
    var running by remember { mutableStateOf(false) }
    var paidBefore by remember { mutableStateOf("") }
    var owedNow by remember { mutableStateOf("") }
    var intoAccount by remember { mutableStateOf<String?>(lookup.payAccounts(cur).firstOrNull()?.id) }
    var direct by remember { mutableStateOf(false) }
    var payFrom by remember { mutableStateOf(existing?.payFromAccountId ?: lookup.payAccounts(cur).firstOrNull()?.id) }
    var kindPicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val p = Money.parse(principal, cur)
    val n = months.toIntOrNull()
    val r = if (free) null else rate.trim()
    val calc = if (p != null && n != null && n in 1..600 && (free || Loans.rateError(rate) == null)) Loans.emi(p, r, n) else null
    val custom = if (customEmi.isBlank()) null else Money.parse(customEmi, cur)
    val emi = custom ?: calc
    val dueSoFar = if (n != null) (0 until n).count { !firstDue.plusMonths(it.toLong()).isAfter(today) } else 0
    val paidN = if (running) paidBefore.toIntOrNull() ?: dueSoFar else 0
    val projected = if (p != null && emi != null) Loans.schedule(p, r, emi, firstDue) else null
    val projectedLeft = projected?.rows?.getOrNull(paidN - 1)?.closing ?: p ?: 0
    val owed = if (running) (if (owedNow.isBlank()) projectedLeft else Money.parse(owedNow, cur)) else p
    val error = when {
        name.isBlank() -> "Give the loan a name, like Home loan"
        p == null || p <= 0 -> "Enter the amount borrowed"
        !free && Loans.rateError(rate) != null -> Loans.rateError(rate)
        n == null || n !in 1..600 -> "Tenure must be 1 to 600 months"
        firstDue.isBefore(start) -> "The first EMI cannot be before the loan starts"
        customEmi.isNotBlank() && (custom == null || custom <= 0) -> "Enter the EMI from your loan papers, or leave it empty"
        emi != null && r != null && emi <= Loans.monthlyInterest(p, r) -> "That EMI does not cover the first month's interest; check the EMI and rate"
        running && (paidN < 0 || paidN > n) -> "EMIs already paid must be 0 to $n"
        running && (owed == null || owed < 0 || owed > p) -> "What you still owe must be between 0 and the amount borrowed"
        existing == null && !running && !direct && intoAccount == null -> "Choose the account the money came into"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a loan" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lender, { lender = it }, label = { Text("Lender") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AssistChip(onClick = { kindPicker = true }, label = { Text(Loans.kindLabel(kind)) })
                AmountField(principal, { principal = it }, cur, "Amount borrowed")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { free = !free }) { Checkbox(free, { free = it }); Text("Interest-free") }
                if (!free) OutlinedTextField(rate, { rate = it }, label = { Text("Interest rate (% a year)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (rate.isNotBlank()) Loans.rateError(rate) ?: "" else "For example 8.5") }, isError = rate.isNotBlank() && Loans.rateError(rate) != null)
                OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text("Tenure (months)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { n?.let { Text("${it / 12} years ${it % 12} months") } })
                DateField("Loan started on", start, { start = it; if (existing == null) firstDue = it.plusMonths(1) })
                DateField("First EMI date", firstDue, { firstDue = it })
                AmountField(customEmi, { customEmi = it }, cur, "EMI as per lender (optional)", supporting = calc?.let { "Calculated: ${Money.format(it, cur)}" })
                if (existing == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { running = !running }) { Checkbox(running, { running = it }); Text("Already running; I've paid some EMIs") }
                    if (running) {
                        OutlinedTextField(paidBefore, { paidBefore = it.filter(Char::isDigit) }, label = { Text("EMIs already paid") }, placeholder = { Text("$dueSoFar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        AmountField(owedNow, { owedNow = it }, cur, "Still owed today", supporting = "About ${Money.format(projectedLeft, cur)} by our schedule; use your statement's figure")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { direct = !direct }) { Checkbox(direct, { direct = it }); Text("Paid directly to the seller (not into my account)") }
                        if (!direct) AccountPicker("Money came into", lookup.payAccounts(cur), intoAccount, lookup) { intoAccount = it }
                    }
                }
                AccountPicker("EMIs are paid from", lookup.payAccounts(cur), payFrom, lookup) { payFrom = it }
                if (projected != null && existing == null && error == null) Text("EMI ${Money.format(emi!!, cur)} · total interest ${Money.format(projected.totalInterest, cur)} · last EMI ${projected.rows.lastOrNull()?.date?.let { Dates.medium(it) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                ErrorText(if (name.isNotBlank() || principal.isNotBlank()) error else null)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && !saving, onClick = {
                saving = true
                val now = System.currentTimeMillis()
                val loan = (existing ?: LoanEntity(id = ids[0], name = name, accountId = ids[1], currency = cur, principal = p!!, startDate = start.toString(), firstDueDate = firstDue.toString(), tenureMonths = n!!, createdAt = now))
                    .copy(name = name.trim(), lender = lender.trim().ifEmpty { null }, kind = kind, principal = p!!, annualRate = r, rateType = existing?.rateType ?: "fixed", startDate = start.toString(),
                        firstDueDate = firstDue.toString(), tenureMonths = n!!, emiAmount = custom, payFromAccountId = payFrom,
                        interestCategoryId = existing?.interestCategoryId ?: lookup.loanInterestCategory(LoanEntity(id = "", name = "", accountId = "", currency = cur, principal = 0, startDate = "", firstDueDate = "", tenureMonths = 1, kind = kind)),
                        paidBefore = if (existing == null) paidN else existing.paidBefore)
                val account = if (existing == null) AccountEntity(id = ids[1], name = name.trim(), type = "loan", currency = cur,
                    openingBalance = if (running) -owed!! else if (direct) -p else 0, color = "#C2410C", icon = "bank", note = lender.trim().ifEmpty { null }, createdAt = now)
                else lookup.accountById[existing.accountId]?.copy(name = name.trim())
                val disbursal = if (existing == null && !running && !direct) linkedTx(ids[2], "transfer", ids[1], p, start, null, "${name.trim()} · loan disbursed", lender.trim().ifEmpty { name.trim() }, toAccountId = intoAccount, loanId = ids[0]) else null
                scope.launch {
                    c.repo.saveAll(accounts = listOfNotNull(account), loans = listOf(loan), transactions = listOfNotNull(disbursal))
                    snackbar.showSnackbar(if (existing == null) "${loan.name} added · EMI ${Money.format(emi ?: 0, cur)}" else "Loan updated")
                    onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (kindPicker) PickerSheet(title = "Type of loan", items = Loans.KINDS, onDismiss = { kindPicker = false }, onPick = { kind = it.first }, selected = { it.first == kind }) { k ->
        ListItem(headlineContent = { Text(k.second) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}
