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
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.FilledTonalButton
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
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.SectionCard
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.StackedBar
import app.kosh.ui.components.StatTile
import app.kosh.ui.components.StatusPill
import app.kosh.ui.theme.LocalMoneyColors
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
    val m = LocalMoneyColors.current
    val base = lookup.baseCurrency
    val today = Dates.today()
    val live = rules.filter { !it.deleted }
    val active = live.filter { !it.paused && c.recurring.nextDate(it) != null }
    val inBase = active.filter { lookup.currencyOf(it.accountId) == base }
    val billsMonthly = inBase.filter { it.type == "expense" }.sumOf { monthlyEquivalent(it) }
    val incomeMonthly = inBase.filter { it.type == "income" }.sumOf { monthlyEquivalent(it) }
    // The next 30 days, for the timeline.
    val due = active.flatMap { r -> c.recurring.dueDates(r, today.plusDays(30)).filter { !it.isBefore(today) }.map { r to it } }.sortedBy { it.second }
    Scaffold(
        topBar = {
            KoshTopBar("Recurring & bills", onBack = { nav.popBackStack() }) {
                FilledTonalButton(onClick = { nav.navigate(Routes.editRule()) }, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Rounded.Add, null); Text("New", Modifier.padding(start = 4.dp))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (live.isEmpty()) {
                item {
                    EmptyState(Icons.Rounded.Repeat, "No recurring items", "Add rent, subscriptions, salary or EMIs. LekhaMint reminds you or records them on the due date.") {
                        FilledTonalButton(onClick = { nav.navigate(Routes.editRule()) }) { Text("Add a bill or salary") }
                    }
                }
                return@LazyColumn
            }
            item {
                SectionCard("Every month", subtitle = "Regular money in and out, worked out per month") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Comes in", Modifier.weight(1f), icon = Icons.Rounded.SouthWest, tint = m.income) { MoneyText(incomeMonthly, base, style = MaterialTheme.typography.titleSmall) }
                        StatTile("Bills", Modifier.weight(1f), icon = Icons.Rounded.NorthEast, tint = m.expense) { MoneyText(billsMonthly, base, style = MaterialTheme.typography.titleSmall) }
                        if (incomeMonthly > 0) StatTile("Left", Modifier.weight(1f)) { MoneyText(incomeMonthly - billsMonthly, base, style = MaterialTheme.typography.titleSmall) }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (incomeMonthly > 0) {
                        val f = (billsMonthly.toFloat() / incomeMonthly).coerceIn(0f, 1f)
                        StackedBar(listOf(f to m.expense, (1f - f) to m.income.copy(alpha = 0.45f)))
                        Text(
                            if (billsMonthly <= incomeMonthly) "Bills take ${(f * 100).toInt()}% of your regular income."
                            else "Bills are more than your regular income.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        Text(
                            "Add your salary as a recurring income to see what's left after bills.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (due.isNotEmpty()) {
                item {
                    SectionCard("Next 30 days", subtitle = "${due.size} ${if (due.size == 1) "payment" else "payments"} due") {
                        DueTimeline(due.map { (r, d) -> d to if (r.type == "income") m.income else if (r.type == "transfer") m.transfer else m.expense }, today)
                        Spacer(Modifier.height(10.dp))
                        for ((r, d) in due.take(5)) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(Dates.relativeDay(d, today), if (d == today) m.warning else MaterialTheme.colorScheme.primary)
                                Text(r.name ?: r.payee ?: lookup.categoryName(r.categoryId), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                MoneyText(if (r.type == "expense") -r.amount else r.amount, lookup.currencyOf(r.accountId), style = MaterialTheme.typography.labelLarge, signed = r.type != "transfer")
                            }
                        }
                    }
                }
            }
            val groups = listOf(
                "Bills & subscriptions" to active.filter { it.type == "expense" },
                "Income" to active.filter { it.type == "income" },
                "Transfers & savings" to active.filter { it.type == "transfer" },
                "Paused or ended" to live.filter { it !in active },
            )
            for ((title, list) in groups) {
                if (list.isEmpty()) continue
                item(key = "h-$title") { SectionHeader("$title (${list.size})") }
                item(key = "g-$title") {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            for (r in list.sortedBy { c.recurring.nextDate(it) ?: LocalDate.MAX }) RuleRow(r, lookup, c.recurring.nextDate(r)) { nav.navigate(Routes.editRule(r.id)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(r: RecurringRuleEntity, lookup: app.kosh.domain.Lookup, next: LocalDate?, onClick: () -> Unit) {
    val (icon, color) = if (r.type == "transfer") "transfer" to null else lookup.categoryLook(r.categoryId)
    val cur = lookup.currencyOf(r.accountId)
    ListItem(
        headlineContent = { Text(r.name ?: r.payee ?: lookup.categoryName(r.categoryId), maxLines = 1) },
        supportingContent = {
            Text(
                buildString {
                    append(Recurrence.describe(r.frequency, r.every))
                    append(" · ")
                    append(if (r.paused) "Paused" else next?.let { "next ${Dates.relativeDay(it)}" } ?: "Ended")
                    append(if (r.mode == "auto") " · adds itself" else " · reminds you")
                },
            )
        },
        leadingContent = { IconBadge(icon, color) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(if (r.type == "expense") -r.amount else r.amount, cur, signed = r.type != "transfer", style = MaterialTheme.typography.titleSmall)
                if (r.frequency != "monthly" || r.every != 1) {
                    MoneyText(monthlyEquivalent(r), cur, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("a month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The next 30 days as a line, with a dot on each due date (stacked when several fall on one day). */
@Composable
private fun DueTimeline(points: List<Pair<LocalDate, Color>>, today: LocalDate) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val y = size.height - 8.dp.toPx()
            val r = 6.dp.toPx()
            drawLine(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val perDay = HashMap<Long, Int>()
            for ((d, col) in points) {
                val i = java.time.temporal.ChronoUnit.DAYS.between(today, d)
                val stack = (perDay.merge(i, 1, Int::plus) ?: 1) - 1
                if (stack > 2) continue
                val x = (size.width * i / 30f).coerceIn(r, size.width - r)
                drawCircle(col, r, androidx.compose.ui.geometry.Offset(x, y - stack * 2.6f * r))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Today", style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.weight(1f))
            Text(Dates.short(today.plusDays(15)), style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(Dates.short(today.plusDays(30)), style = MaterialTheme.typography.labelSmall, color = label, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
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
    val scope = app.kosh.ui.rememberSafeScope()
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
