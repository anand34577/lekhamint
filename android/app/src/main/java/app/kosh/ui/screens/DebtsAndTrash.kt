package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.db.DebtEntity
import app.kosh.domain.Debts
import app.kosh.ui.LocalNav
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.PickerSheet
import app.kosh.ui.components.TransactionRow
import app.kosh.ui.components.typeColor
import kotlinx.coroutines.launch

@Composable
fun DebtsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val debts by c.repo.debts.collectAsStateWithLifecycle(emptyList())
    val moves by c.repo.debtMoves.collectAsStateWithLifecycle(emptyList())
    var edit by remember { mutableStateOf<DebtEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var repaying by remember { mutableStateOf<DebtEntity?>(null) }
    var showSettled by remember { mutableStateOf(false) }
    val movesByDebt = moves.groupBy { it.debtId }
    val left = { d: DebtEntity -> Debts.outstanding(d, movesByDebt[d.id].orEmpty()) }
    Scaffold(
        topBar = { KoshTopBar("Lent & borrowed", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add") }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (debts.isEmpty()) item { EmptyState(Icons.Rounded.Handshake, "Nothing owed", "Keep track of money you lent to friends or borrowed, record repayments, and settle up later.") }
            val open = debts.filter { !it.closed }
            val owedToYou = open.filter { it.direction == "lent" }.groupBy { it.currency }.mapValues { (_, l) -> l.sumOf(left) }.filterValues { it > 0 }
            val youOwe = open.filter { it.direction == "borrowed" }.groupBy { it.currency }.mapValues { (_, l) -> l.sumOf(left) }.filterValues { it > 0 }
            if (open.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column {
                            Text("Owed to you", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (owedToYou.isEmpty()) Text("—")
                            for ((cur, v) in owedToYou) MoneyText(v, cur, color = typeColor("income"), style = MaterialTheme.typography.titleMedium)
                        }
                        Column {
                            Text("You owe", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (youOwe.isEmpty()) Text("—")
                            for ((cur, v) in youOwe) MoneyText(v, cur, color = typeColor("expense"), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            val shown = debts.filter { showSettled || !it.closed }.sortedWith(compareBy({ it.closed }, { it.dueDate ?: "9" }))
            items(shown, key = { it.id }) { d ->
                val remaining = left(d)
                val bal = Debts.balance(d, movesByDebt[d.id].orEmpty())
                ListItem(
                    headlineContent = { Text(d.person, textDecoration = if (d.closed) TextDecoration.LineThrough else null) },
                    supportingContent = {
                        Text(
                            (if (d.direction == "lent") "Owes you" else "You owe") +
                                (if (!d.closed && bal < 0) " · overpaid by ${Money.format(-bal, d.currency)}" else if (!d.closed && remaining != d.amount) " · ${Money.format(remaining, d.currency)} of ${Money.format(d.amount, d.currency)} left" else "") +
                                (d.dueDate?.let { " · due ${Dates.relativeDay(java.time.LocalDate.parse(it))}" }.takeIf { !d.closed } ?: "") +
                                (if (d.closed) " · settled" else "") + (d.note?.let { " · $it" } ?: ""),
                        )
                    },
                    leadingContent = { IconBadge("family", if (d.direction == "lent") "#2E7D32" else "#C2410C") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!d.closed && remaining > 0) {
                                TextButton(onClick = { repaying = d }) { Text(if (d.direction == "lent") "Got paid" else "Paid") }
                            }
                            MoneyText(if (d.closed) d.amount else remaining, d.currency, color = if (d.closed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                        }
                    },
                    modifier = Modifier.clickable { edit = d },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (debts.any { it.closed }) {
                item {
                    TextButton(onClick = { showSettled = !showSettled }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(if (showSettled) "Hide settled" else "Show settled (${debts.count { it.closed }})")
                    }
                }
            }
            if (debts.isNotEmpty()) {
                item {
                    Text(
                        "Money you lend stays yours: it counts in net worth as owed to you, never as spending. Borrowed money is owed, never income. Interest you record is real income or cost.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (creating || edit != null) DebtDialog(edit, onDismiss = { creating = false; edit = null }, onRepay = { edit = null; repaying = it })
    repaying?.let { d -> RepayDialog(d, Debts.outstanding(d, movesByDebt[d.id].orEmpty()), onDismiss = { repaying = null }) }
}

/** Accounts in [currency] to take the money from or put it into. */
@Composable
private fun AccountChooser(currency: String, selected: String?, label: String, onPick: (String) -> Unit) {
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val choices = lookup.activeAccounts.filter { it.currency == currency }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(choices.map { it.id }) { if (selected == null || choices.none { it.id == selected }) choices.firstOrNull()?.let { onPick(it.id) } }
    if (choices.isEmpty()) {
        Text("No $currency account, so only the loan is recorded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${lookup.accountName(selected)}") }
    if (open) {
        PickerSheet(
            title = label, items = choices, onDismiss = { open = false }, onPick = { onPick(it.id) },
            selected = { it.id == selected },
        ) { a -> ListItem(headlineContent = { Text(a.name) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
    }
}

@Composable
private fun DebtDialog(existing: DebtEntity?, onDismiss: () -> Unit, onRepay: (DebtEntity) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf(existing?.person.orEmpty()) }
    var direction by remember { mutableStateOf(existing?.direction ?: "lent") }
    val currency = existing?.currency ?: c.settings.current.baseCurrency
    var amount by remember { mutableStateOf(existing?.let { Money.toPlain(it.amount, it.currency) }.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var linkAccount by remember { mutableStateOf(existing == null) }
    var accountId by remember { mutableStateOf<String?>(null) }
    // IDs fixed for the life of the dialog: saving twice writes the same rows, never a second debt.
    val debtId = remember { existing?.id ?: Ids.newV7() }
    val moveId = remember { Ids.newV7() }
    var saving by remember { mutableStateOf(false) }
    var confirmSettle by remember { mutableStateOf(false) }
    val moves by c.repo.debtMoves.collectAsStateWithLifecycle(emptyList())
    val paidBack = existing?.let { e -> Debts.repaid(e, moves.filter { it.debtId == e.id }) } ?: 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Lent or borrowed" else existing.person) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(direction == "lent", { direction = "lent" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("I lent") }
                    SegmentedButton(direction == "borrowed", { direction = "borrowed" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("I borrowed") }
                }
                OutlinedTextField(person, { person = it }, label = { Text("Person") }, singleLine = true)
                val parsed = Money.parse(amount, currency)
                AmountField(amount, { amount = it }, currency, "Amount",
                    supporting = when {
                        amount.isNotBlank() && parsed == null -> "Enter a number like 5000 or 5,000.50"
                        existing != null && parsed != null && parsed < paidBack -> "${Money.format(paidBack, currency)} is already repaid, so the amount cannot be less"
                        else -> null
                    })
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true)
                if (existing == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { linkAccount = !linkAccount }) {
                        Checkbox(linkAccount, { linkAccount = it })
                        Text(if (direction == "lent") "The money left one of my accounts" else "The money came into one of my accounts")
                    }
                    if (linkAccount) AccountChooser(currency, accountId, if (direction == "lent") "From" else "Into") { accountId = it }
                } else if (!existing.closed) {
                    TextButton(onClick = { onRepay(existing) }) { Text(if (existing.direction == "lent") "Record a repayment" else "Record a payment") }
                }
            }
        },
        confirmButton = {
            val minor = Money.parse(amount, currency)
            TextButton(enabled = !saving && person.isNotBlank() && minor != null && minor > 0 && minor >= paidBack, onClick = {
                saving = true
                val now = System.currentTimeMillis()
                val base = existing ?: DebtEntity(id = debtId, person = person, direction = direction, amount = minor!!, currency = currency, createdAt = now)
                val debt = base.copy(person = person.trim(), direction = direction, amount = minor!!, note = note.trim().ifEmpty { null },
                    closed = base.closed || (existing != null && minor == paidBack))
                val move = accountId?.takeIf { existing == null && linkAccount }?.let { Debts.movement(debt, repayment = false, accountId = it, amount = minor, date = Dates.today(), id = moveId) }
                scope.launch {
                    c.repo.saveDebt(debt, move)
                    onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = {
                        if (!existing.closed && existing.amount - paidBack > 0) confirmSettle = true
                        else scope.launch { c.repo.saveDebt(existing.copy(closed = !existing.closed)); onDismiss() }
                    }) {
                        Text(if (existing.closed) "Reopen" else "Settled")
                    }
                    TextButton(onClick = { scope.launch { c.repo.deleteDebt(existing.id); onDismiss() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
    if (confirmSettle && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmSettle = false },
            title = { Text("Mark as settled?") },
            text = { Text("${Money.format(existing.amount - paidBack, existing.currency)} is still outstanding. Marking it settled writes that off: it stops counting ${if (existing.direction == "lent") "as owed to you" else "as what you owe"}, and no money moves. If money was paid, record the repayment instead.") },
            confirmButton = { TextButton(onClick = { scope.launch { c.repo.saveDebt(existing.copy(closed = true)); confirmSettle = false; onDismiss() } }) { Text("Mark settled") } },
            dismissButton = { TextButton(onClick = { confirmSettle = false }) { Text("Cancel") } },
        )
    }
}

/** Records a (part) repayment, optionally with interest; a full one also marks the debt settled. */
@Composable
private fun RepayDialog(debt: DebtEntity, outstanding: Long, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf(Money.toPlain(outstanding, debt.currency)) }
    var interest by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<String?>(null) }
    val moveId = remember { Ids.newV7() }
    val interestId = remember { Ids.newV7() }
    var saving by remember { mutableStateOf(false) }
    val minor = Money.parse(amount, debt.currency)
    val extra = if (interest.isBlank()) 0L else Money.parse(interest, debt.currency)
    val lent = debt.direction == "lent"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lent) "${debt.person} paid you back" else "You paid ${debt.person} back") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Outstanding: ${Money.format(outstanding, debt.currency)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountField(amount, { amount = it }, debt.currency, "Amount repaid",
                    supporting = when {
                        amount.isNotBlank() && minor == null -> "Enter a number like 5000"
                        minor != null && minor > outstanding -> "Only ${Money.format(outstanding, debt.currency)} is owed. Put anything extra in Interest."
                        minor != null && minor in 1 until outstanding -> "Part repayment: ${Money.format(outstanding - minor, debt.currency)} will still be owed"
                        else -> null
                    })
                AmountField(interest, { interest = it }, debt.currency, "Interest or extra (optional)",
                    supporting = if (extra == null) "Enter an amount or leave empty" else "Counts as ${if (lent) "income" else "a cost"}, not as repayment")
                AccountChooser(debt.currency, accountId, if (lent) "Into" else "From") { accountId = it }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && minor != null && minor > 0 && minor <= outstanding && extra != null && extra >= 0 && accountId != null, onClick = {
                saving = true
                val move = Debts.movement(debt, repayment = true, accountId = accountId!!, amount = minor!!, date = Dates.today(), id = moveId)
                val moves = listOfNotNull(move, extra?.takeIf { it > 0 }?.let { Debts.movement(debt, repayment = true, accountId = accountId!!, amount = it, date = Dates.today(), id = interestId, interest = true) })
                scope.launch {
                    c.repo.saveAll(debts = listOf(if (minor == outstanding) debt.copy(closed = true) else debt), transactions = moves)
                    onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TrashScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val txs by c.repo.deletedTransactions.collectAsStateWithLifecycle(emptyList())
    val accounts by c.repo.deletedAccounts.collectAsStateWithLifecycle(emptyList())
    val categories by c.repo.deletedCategories.collectAsStateWithLifecycle(emptyList())
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(topBar = { KoshTopBar("Trash", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Transactions") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Accounts") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Categories") })
            }
            Text(
                "Deleted items stay here so the deletion can sync. Restoring is a normal edit and syncs everywhere.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                when (tab) {
                    0 -> {
                        if (txs.isEmpty()) item { EmptyState(Icons.Rounded.DeleteOutline, "Trash is empty", "Deleted transactions appear here.") }
                        items(txs, key = { it.id }) { t ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { TransactionRow(t, lookup, onClick = {}) }
                                TextButton(onClick = { scope.launch { c.repo.restoreTransaction(t.id) } }) { Text("Restore") }
                            }
                        }
                    }
                    1 -> {
                        if (accounts.isEmpty()) item { EmptyState(Icons.Rounded.DeleteOutline, "Nothing here", "Deleted accounts appear here.") }
                        items(accounts, key = { it.id }) { a ->
                            ListItem(
                                headlineContent = { Text(a.name) }, leadingContent = { IconBadge(a.icon, a.color) },
                                trailingContent = { TextButton(onClick = { scope.launch { c.repo.restoreAccount(a.id) } }) { Text("Restore") } },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                    else -> {
                        if (categories.isEmpty()) item { EmptyState(Icons.Rounded.DeleteOutline, "Nothing here", "Deleted categories appear here.") }
                        items(categories, key = { it.id }) { cat ->
                            ListItem(
                                headlineContent = { Text(cat.name) }, supportingContent = { Text(cat.kind) }, leadingContent = { IconBadge(cat.icon, cat.color) },
                                trailingContent = { TextButton(onClick = { scope.launch { c.repo.restoreCategory(cat.id) } }) { Text("Restore") } },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
    }
}

