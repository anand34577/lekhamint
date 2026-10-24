package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.core.Recurrence
import app.kosh.data.db.RecurringRuleEntity
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.PickerSheet
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun RecurringScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val rules by c.repo.recurringRules.collectAsStateWithLifecycle(emptyList())
    val lookup by c.lookup.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { KoshTopBar("Recurring & bills", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.editRule()) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Recurring") }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (rules.isEmpty()) {
                item { EmptyState(Icons.Rounded.Repeat, "No recurring items", "Add rent, subscriptions, salary or EMIs. LekhaMint reminds you or records them on the due date.") }
            }
            val monthly = rules.filter { !it.paused && it.type == "expense" }.groupBy { lookup.currencyOf(it.accountId) }
                .mapValues { (_, l) -> l.sumOf { r -> monthlyEquivalent(r) } }
            if (monthly.isNotEmpty()) {
                item {
                    Column(Modifier.padding(20.dp)) {
                        Text("About this much goes out every month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for ((cur, v) in monthly) MoneyText(v, cur, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            items(rules, key = { it.id }) { r ->
                val next = c.recurring.nextDate(r)
                val (icon, color) = if (r.type == "transfer") "transfer" to null else lookup.categoryLook(r.categoryId)
                ListItem(
                    headlineContent = { Text(r.name ?: r.payee ?: lookup.categoryName(r.categoryId)) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(Recurrence.describe(r.frequency, r.every))
                                append(" · ")
                                append(if (r.paused) "Paused" else next?.let { "next ${Dates.relativeDay(it)}" } ?: "Ended")
                                append(if (r.mode == "auto") " · auto" else " · remind")
                            },
                        )
                    },
                    leadingContent = { IconBadge(icon, color) },
                    trailingContent = { MoneyText(if (r.type == "income") r.amount else -r.amount, lookup.currencyOf(r.accountId), signed = r.type != "transfer") },
                    modifier = Modifier.clickable { nav.navigate(Routes.editRule(r.id)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

private fun monthlyEquivalent(r: RecurringRuleEntity): Long {
    val every = r.every.coerceAtLeast(1)
    return when (r.frequency) {
        "daily" -> r.amount * 30 / every
        "weekly" -> r.amount * 52 / 12 / every
        "yearly" -> r.amount / 12 / every
        else -> r.amount / every
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditRuleScreen(id: String?) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var existing by remember { mutableStateOf<RecurringRuleEntity?>(null) }
    var type by remember { mutableStateOf("expense") }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf(lookup.activeAccounts.firstOrNull()?.id) }
    var toAccountId by remember { mutableStateOf<String?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var frequency by remember { mutableStateOf("monthly") }
    var every by remember { mutableStateOf("1") }
    var start by remember { mutableStateOf(Dates.today()) }
    var endMode by remember { mutableStateOf("never") }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var count by remember { mutableStateOf("12") }
    var mode by remember { mutableStateOf("remind") }
    var paused by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        val r = id?.let { c.repo.findRule(it) } ?: return@LaunchedEffect
        existing = r; type = r.type; name = r.name ?: r.payee.orEmpty()
        amount = Money.toPlain(r.amount, lookup.currencyOf(r.accountId)); accountId = r.accountId; toAccountId = r.toAccountId
        categoryId = r.categoryId; frequency = r.frequency; every = r.every.toString(); start = LocalDate.parse(r.startDate)
        endDate = Dates.parse(r.endDate); endMode = when { r.endDate != null -> "date"; r.maxOccurrences != null -> "count"; else -> "never" }
        count = (r.maxOccurrences ?: 12).toString(); mode = r.mode; paused = r.paused
    }
    val currency = lookup.currencyOf(accountId)

    fun save() {
        val minor = Money.parse(amount, currency)
        val ev = every.toIntOrNull()
        when {
            minor == null || minor <= 0 -> { error = "Enter the amount"; return }
            accountId == null -> { error = "Choose an account"; return }
            type == "transfer" && (toAccountId == null || toAccountId == accountId) -> { error = "Choose a different destination account"; return }
            ev == null || ev !in 1..366 -> { error = "Repeat interval must be 1–366"; return }
            endMode == "date" && (endDate == null || endDate!!.isBefore(start)) -> { error = "End date must be after the start"; return }
            endMode == "count" && (count.toIntOrNull() ?: 0) < 1 -> { error = "Enter how many times"; return }
        }
        val now = System.currentTimeMillis()
        val base = existing ?: RecurringRuleEntity(id = Ids.newV7(now), type = type, accountId = accountId!!, amount = minor!!, frequency = frequency, startDate = start.toString(), createdAt = now)
        val scheduleChanged = existing == null || existing!!.startDate != start.toString() || existing!!.frequency != frequency || existing!!.every != ev
        val rule = base.copy(
            name = name.trim().ifEmpty { null }, payee = name.trim().ifEmpty { null }, type = type, accountId = accountId!!,
            toAccountId = if (type == "transfer") toAccountId else null,
            amount = minor!!, toAmount = if (type == "transfer") (base.toAmount.takeIf { lookup.currencyOf(toAccountId) != currency } ?: minor) else null,
            categoryId = if (type == "transfer") null else categoryId, frequency = frequency, every = ev!!,
            startDate = start.toString(), endDate = if (endMode == "date") endDate.toString() else null,
            maxOccurrences = if (endMode == "count") count.toInt() else null, mode = mode, paused = paused,
            // A changed schedule restarts from the first occurrence on or after today.
            nextDate = if (scheduleChanged) {
                Recurrence.occurrence(start, frequency, ev, Recurrence.firstIndexOnOrAfter(start, frequency, ev, maxOf(start, Dates.today()))).toString()
            } else {
                base.nextDate
            },
        )
        scope.launch {
            c.repo.saveRule(rule)
            if (mode == "auto") c.recurring.runAuto()
            nav.popBackStack()
        }
    }

    Scaffold(topBar = { KoshTopBar(if (id == null) "New recurring" else "Edit recurring", onBack = { nav.popBackStack() }, closeIcon = true) { TextButton(onClick = ::save) { Text("Save") } } }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("expense" to "Expense", "income" to "Income", "transfer" to "Transfer").forEachIndexed { i, (t, l) ->
                    SegmentedButton(type == t, { type = t; categoryId = null }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("Rent, Netflix, Salary…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField(amount, { amount = it; error = null }, currency, "Amount")
            PickRow("Account", lookup.accountName(accountId)) { pick = "from" }
            if (type == "transfer") PickRow("To account", toAccountId?.let { lookup.accountName(it) } ?: "Choose") { pick = "to" }
            if (type != "transfer") PickRow("Category", lookup.categoryPath(categoryId)) { pick = "category" }
            Text("Repeats", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((f, l) in listOf("daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly", "yearly" to "Yearly")) FilterChip(frequency == f, { frequency = f }, { Text(l) })
            }
            OutlinedTextField(
                every, { every = it.filter(Char::isDigit).take(3) }, label = { Text("Every") },
                suffix = { Text(Recurrence.describe(frequency, every.toIntOrNull() ?: 1).removePrefix("Every ").removePrefix("${every} ")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            PickRow("Starts", Dates.medium(start)) { pick = "start" }
            if (frequency == "monthly" && start.dayOfMonth > 28) {
                Text("In shorter months it falls on the last day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Ends", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(endMode == "never", { endMode = "never" }, { Text("Never") })
                FilterChip(endMode == "date", { endMode = "date"; if (endDate == null) endDate = start.plusYears(1) }, { Text("On a date") })
                FilterChip(endMode == "count", { endMode = "count" }, { Text("After N times") })
            }
            if (endMode == "date") PickRow("End date", endDate?.let(Dates::medium) ?: "Choose") { pick = "end" }
            if (endMode == "count") {
                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(4) }, label = { Text("Number of times") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            SwitchRow("Add automatically", if (mode == "auto") "Recorded on each due date" else "You get a reminder and confirm each one", mode == "auto") { mode = if (it) "auto" else "remind" }
            if (existing != null) SwitchRow("Paused", null, paused) { paused = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (existing != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (pick) {
        "from", "to" -> PickerSheet(
            if (pick == "to") "To account" else "Account", lookup.activeAccounts, { pick = null },
            { a -> if (pick == "to") toAccountId = a.id else accountId = a.id }, searchText = { it.name },
        ) { a -> AccountListItem(a, lookup) }
        "category" -> CategorySheet(lookup, if (type == "income") "income" else "expense", categoryId, { pick = null }) { categoryId = it }
        "start", "end" -> {
            val initial = if (pick == "start") start else endDate ?: start
            val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { pick = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            if (pick == "start") start = d else endDate = d
                        }
                        pick = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { pick = null }) { Text("Cancel") } },
            ) { DatePicker(state) }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete recurring item?", "Transactions already recorded stay.", "Delete", destructive = true, onConfirm = {
            scope.launch { existing?.let { c.repo.deleteRule(it.id) }; nav.popBackStack() }
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
fun PickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
