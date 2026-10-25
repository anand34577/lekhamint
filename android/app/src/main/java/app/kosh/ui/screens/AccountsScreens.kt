package app.kosh.ui.screens

import app.kosh.ui.components.FormSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Currencies
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.db.AccountEntity
import app.kosh.domain.CardBilling
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.Routes
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.CurrencyPickerSheet
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshIcons
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.TransactionRow
import app.kosh.ui.components.parseColor
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { KoshTopBar("Accounts", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.editAccount()) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Account") })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Net worth", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (lookup.netWorth.isEmpty()) MoneyText(0, lookup.baseCurrency, style = MaterialTheme.typography.headlineMedium)
                        for ((cur, v) in lookup.netWorth.entries.sortedByDescending { it.key == lookup.baseCurrency }) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MoneyText(v, cur, style = if (cur == lookup.baseCurrency) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
                                if (lookup.netWorth.size > 1) Text(cur, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                        lookup.netWorthInBase(app.kosh.core.Dates.today().toString())?.let { all ->
                            Text(
                                "All together: " + Money.format(all.total, lookup.baseCurrency) +
                                    if (all.missing.isNotEmpty()) " (without ${all.missing.joinToString(", ")}: add an exchange rate)" else "",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Balances are calculated from your transactions, so they are always exact.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val (active, archived) = lookup.accounts.partition { !it.archived }
            if (active.isEmpty()) item { EmptyState(Icons.Rounded.AccountBalanceWallet, "No accounts", "Add cash, bank accounts, cards and wallets.") }
            items(active, key = { it.id }) { a -> AccountRow(a, lookup.worth(a)) { nav.navigate(Routes.account(a.id)) } }
            if (archived.isNotEmpty()) {
                item { SectionHeader("Archived") }
                items(archived, key = { it.id }) { a -> AccountRow(a, lookup.balances[a.id] ?: a.openingBalance) { nav.navigate(Routes.account(a.id)) } }
            }
        }
    }
}

@Composable
private fun AccountRow(a: AccountEntity, balance: Long, onClick: () -> Unit) {
    val due = CardBilling.due(a, balance, app.kosh.core.Dates.today())
    ListItem(
        headlineContent = { Text(a.name) },
        supportingContent = {
            val soon = due != null && due.daysLeft <= 3
            Text(
                accountTypeLabel(a.type) + (if (!a.includeInNetWorth) " · not in net worth" else "") +
                    (due?.let { " · due " + if (it.daysLeft == 0L) "today" else if (it.daysLeft == 1L) "tomorrow" else "in ${it.daysLeft} days" } ?: ""),
                color = if (soon) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        leadingContent = { IconBadge(a.icon ?: KoshIcons.forAccountType(a.type), a.color) },
        trailingContent = { MoneyText(balance, a.currency, style = MaterialTheme.typography.titleMedium, color = if (balance < 0) MaterialTheme.colorScheme.error else Color.Unspecified) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun AccountDetailScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    // History grows without bound, so it loads in pages: "Show older" raises the limit.
    var limit by remember(id) { mutableIntStateOf(100) }
    val txs by remember(id, limit) { c.repo.accountTransactions(id, limit) }.collectAsStateWithLifecycle(emptyList())
    val account = lookup.accountById[id]
    var reconcile by remember { mutableStateOf(false) }
    var valuing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    Scaffold(
        topBar = {
            KoshTopBar(account?.name ?: "Account", onBack = { nav.popBackStack() }) {
                IconButton(onClick = { nav.navigate(Routes.editAccount(id)) }) { Icon(Icons.Rounded.Edit, "Edit account") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.editTx(account = id)) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add") })
        },
    ) { padding ->
        if (account == null) {
            EmptyState(Icons.Rounded.AccountBalanceWallet, "Account not found", "It may have been deleted.", Modifier.padding(padding))
            return@Scaffold
        }
        val balance = lookup.balances[id] ?: account.openingBalance
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(accountTypeLabel(account.type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(balance, account.currency, style = MaterialTheme.typography.displaySmall)
                        val limit = account.creditLimit
                        if (account.type == "credit_card" && limit != null && limit > 0) {
                            val used = (-balance).coerceAtLeast(0)
                            val credit = balance.coerceAtLeast(0) // paid more than owed: the card holds your money
                            LinearProgressIndicator(progress = { (used.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                            Text(
                                if (credit > 0) "In credit by ${Money.format(credit, account.currency)} · ${Money.format(limit + credit, account.currency)} available of ${Money.format(limit, account.currency)} limit"
                                else "${Money.format(used, account.currency)} used of ${Money.format(limit, account.currency)} · ${Money.format((limit - used).coerceAtLeast(0), account.currency)} available",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if ((account.type == "credit_card" || account.type == "loan") && balance > 0) {
                            Text("Overpaid: ${Money.format(balance, account.currency)} in your favour. It reduces what you owe on your next spends, or ask for a refund.", style = MaterialTheme.typography.bodySmall)
                        }
                        CardBilling.due(account, balance, app.kosh.core.Dates.today())?.let { due ->
                            Text(
                                "Payment due " + (when (due.daysLeft) { 0L -> "today"; 1L -> "tomorrow"; else -> "in ${due.daysLeft} days" }) + " (${app.kosh.core.Dates.medium(due.date)})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (due.daysLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (Lookup.isLiability(account.type) && balance < 0) {
                            Button(onClick = { nav.navigate(Routes.editTx(type = "transfer", to = id, amount = Money.toPlain(-balance, account.currency))) }) {
                                Text(if (account.type == "credit_card") "Pay card bill · ${Money.format(-balance, account.currency)}" else "Make a payment · ${Money.format(-balance, account.currency)}")
                            }
                        }
                        if (isValued(account.type)) ValueSummary(account, lookup, onUpdate = { valuing = true })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { reconcile = true }) { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Reconcile") }
                            OutlinedButton(onClick = { nav.navigate(Routes.editTx(type = "transfer", account = id)) }) { Text("Transfer") }
                        }
                    }
                }
            }
            item { SectionHeader("Transactions") }
            if (txs.isEmpty()) item { Text("No transactions yet.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(txs, key = { it.id }) { t -> TransactionRow(t, lookup, onClick = { nav.navigate(Routes.editTx(t.id, t.type)) }, forAccount = id) }
            if (txs.size >= limit) item {
                TextButton(onClick = { limit += 200 }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Show older transactions") }
            }
        }
        if (valuing) ValuationSheet(account, lookup, onDismiss = { valuing = false })
        if (reconcile) {
            ReconcileDialog(account, balance, onDismiss = { reconcile = false }) { actual ->
                scope.launch {
                    if (c.repo.reconcile(account, balance, actual)) snackbar.showSnackbar("Balance adjusted. The adjustment is left out of reports.")
                }
            }
        }
    }
}

@Composable
private fun ReconcileDialog(a: AccountEntity, current: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf(Money.toPlain(current, a.currency)) }
    val parsed = Money.parse(text.removePrefix("-"), a.currency)?.let { if (text.startsWith("-")) -it else it }
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("Reconcile ${a.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the balance your bank or wallet shows now. LekhaMint records the difference as an adjustment.")
                AmountField(text, { text = it }, a.currency, "Actual balance", allowNegative = true)
                if (parsed != null && parsed != current) {
                    Text("Adjustment: ${Money.format(parsed - current, a.currency, signed = true)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm); onDismiss() }, enabled = parsed != null) { Text("Adjust") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val accountTypes = listOf("cash", "bank", "wallet", "credit_card", "savings", "investment", "deposit", "loan", "other")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditAccountScreen(id: String?) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val existing by remember(id) { if (id != null) c.repo.account(id) else flowOf(null) }.collectAsStateWithLifecycle(null)
    var loaded by remember { mutableStateOf(id == null) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("bank") }
    var currency by remember { mutableStateOf(c.settings.current.baseCurrency) }
    var opening by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    var statementDay by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("") }
    var color by remember { mutableStateOf<String?>(null) }
    var icon by remember { mutableStateOf<String?>(null) }
    var inNetWorth by remember { mutableStateOf(true) }
    var archived by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var txCount by remember { mutableIntStateOf(0) }
    var uses by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickCurrency by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(existing) {
        val a = existing ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        name = a.name; type = a.type; currency = a.currency
        val liability = a.type == "credit_card" || a.type == "loan"
        opening = if (a.openingBalance == 0L) "" else Money.toPlain(if (liability) -a.openingBalance else a.openingBalance, a.currency)
        limit = a.creditLimit?.let { Money.toPlain(it, a.currency) }.orEmpty()
        statementDay = a.statementDay?.toString().orEmpty(); dueDay = a.dueDay?.toString().orEmpty()
        color = a.color; icon = a.icon; inNetWorth = a.includeInNetWorth; archived = a.archived; note = a.note.orEmpty()
        txCount = c.repo.transactionCountForAccount(a.id)
        uses = c.repo.accountUses(a.id)
        loaded = true
    }
    val liability = type == "credit_card" || type == "loan"

    fun save() {
        if (name.isBlank()) { error = "Give the account a name"; return }
        val raw = if (opening.isBlank()) 0L else Money.parse(opening.removePrefix("-"), currency)?.let { if (opening.startsWith("-")) -it else it }
            ?: run { error = "Check the opening balance"; return }
        val openingMinor = if (liability) -raw else raw
        val limitMinor = if (type == "credit_card" && limit.isNotBlank()) Money.parse(limit, currency) ?: run { error = "Check the credit limit"; return } else null
        fun day(v: String): Int? = v.trim().toIntOrNull()?.takeIf { it in 1..31 }
        val isCard = type == "credit_card"
        if (isCard && statementDay.isNotBlank() && day(statementDay) == null || isCard && dueDay.isNotBlank() && day(dueDay) == null) {
            error = "Statement and due days should be a day of the month, from 1 to 31"; return
        }
        val now = System.currentTimeMillis()
        val base = existing ?: AccountEntity(id = Ids.newV7(now), name = name, type = type, currency = currency, createdAt = now)
        scope.launch {
            c.repo.saveAccount(
                base.copy(
                    name = name.trim(), type = type, currency = currency, openingBalance = openingMinor, creditLimit = limitMinor,
                    statementDay = if (isCard) day(statementDay) else null, dueDay = if (isCard) day(dueDay) else null,
                    color = color, icon = icon ?: KoshIcons.forAccountType(type), includeInNetWorth = inNetWorth, archived = archived,
                    note = note.trim().ifEmpty { null },
                ),
            )
            nav.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            KoshTopBar(if (id == null) "New account" else "Edit account", onBack = { nav.popBackStack() }, closeIcon = true) {
                TextButton(onClick = ::save) { Text("Save") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                name, { name = it; error = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                placeholder = { Text("e.g. HDFC Savings, Amex, Paytm") },
            )
            Text("Type", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in accountTypes) {
                    FilterChip(type == t, { type = t },{ Text(accountTypeLabel(t)) }, leadingIcon = { Icon(KoshIcons.of(KoshIcons.forAccountType(t)), null, Modifier.size(18.dp)) })
                }
            }
            OutlinedTextField(
                currency, {}, readOnly = true, label = { Text("Currency") }, modifier = Modifier.fillMaxWidth().clickable(enabled = txCount == 0) { pickCurrency = true },
                enabled = txCount == 0,
                supportingText = { Text(if (txCount > 0) "Can't change once the account has transactions" else Currencies.get(currency).name) },
                trailingIcon = { if (txCount == 0) TextButton(onClick = { pickCurrency = true }) { Text("Change") } },
            )
            AmountField(
                opening, { opening = it; error = null }, currency,
                if (liability) "Amount owed when you start tracking" else "Opening balance",
                allowNegative = !liability,
                supporting = if (liability) "What you owe on this ${accountTypeLabel(type).lowercase()} today" else "The balance before your first transaction in LekhaMint",
            )
            if (type == "credit_card") {
                AmountField(limit, { limit = it }, currency, "Credit limit (optional)", supporting = "Shows how much of the limit you have used")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        statementDay, { statementDay = it.filter(Char::isDigit).take(2); error = null }, label = { Text("Statement day") }, singleLine = true,
                        supportingText = { Text("Day of month, optional") }, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        dueDay, { dueDay = it.filter(Char::isDigit).take(2); error = null }, label = { Text("Payment due day") }, singleLine = true,
                        supportingText = { Text("You get a reminder") }, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            ColorRow(color) { color = it }
            Text("Icon", style = MaterialTheme.typography.labelLarge)
            IconRow(icon ?: KoshIcons.forAccountType(type), listOf("cash", "bank", "wallet", "card", "savings", "invest", "percent", "briefcase", "home", "car", "gift", "star")) { icon = it }
            SwitchRow("Include in net worth", "Turn off for accounts you only track", inNetWorth) { inNetWorth = it }
            SwitchRow("Archived", "Hidden from pickers; history stays", archived) { archived = it }
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (id != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickCurrency) CurrencyPickerSheet(onDismiss = { pickCurrency = false }, selected = currency) { currency = it }
    if (confirmDelete && id != null) {
        if (txCount > 0 || uses.isNotEmpty()) {
            val why = buildList {
                if (txCount > 0) add("$txCount transactions")
                addAll(uses)
            }.joinToString(", ")
            ConfirmDialog(
                "Archive instead?", "This account has $why, so it can't be deleted without breaking them. Archiving hides it from pickers and keeps your history. An archived account is left out of net worth, so move its money first if it still holds some.",
                "Archive", onConfirm = { archived = true; save() }, onDismiss = { confirmDelete = false },
            )
        } else {
            ConfirmDialog(
                "Delete account?", "It moves to Trash and can be restored.", "Delete", destructive = true,
                onConfirm = { scope.launch { c.repo.deleteAccount(id); nav.popBackStack(Routes.ACCOUNTS, inclusive = false) } },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorRow(selected: String?, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (hex in KoshIcons.palette) {
            val col = parseColor(hex, Color.Gray)
            Box(
                Modifier.size(36.dp).background(col, CircleShape).clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected.equals(hex, ignoreCase = true)) Icon(Icons.Rounded.Check, "Selected", tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconRow(selected: String?, keys: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in keys) {
            val sel = selected == k
            Box(
                Modifier.size(44.dp).background(
                    if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape,
                ).clickable { onPick(k) },
                contentAlignment = Alignment.Center,
            ) { Icon(KoshIcons.of(k), k, tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
