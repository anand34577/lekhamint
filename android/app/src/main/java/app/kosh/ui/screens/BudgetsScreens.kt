package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import app.kosh.ui.components.StatusPill
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.db.BudgetEntity
import app.kosh.domain.BudgetStatus
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.budgetStatuses
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.BudgetBar
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.CurrencyPickerSheet
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.LocalHideAmounts
import app.kosh.ui.components.MoneyText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val statuses by remember { budgetStatuses(c) }.collectAsStateWithLifecycle(null)
    val lookup by c.lookup.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Budgets") },
                actions = {
                    androidx.compose.material3.FilledTonalButton(onClick = { nav.navigate(Routes.editBudget()) }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New budget")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val list = statuses
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (list != null && list.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.PieChart, "No budgets yet",
                        "A monthly budget shows how much you can still spend, per day, before the month ends.",
                    ) { Button(onClick = { nav.navigate(Routes.editBudget()) }) { Text("Create a monthly budget") } }
                }
            }
            if (!list.isNullOrEmpty()) item { BudgetOverview(list, lookup.baseCurrency) }
            items(list.orEmpty().sortedWith(compareByDescending<BudgetStatus> { it.over }.thenByDescending { it.fraction }), key = { it.budget.id }) { b ->
                BudgetCard(b, lookup) { nav.navigate(Routes.editBudget(b.budget.id)) }
            }
            if (!list.isNullOrEmpty()) item {
                Text(
                    "The thin line on each bar marks where spending would be if spread evenly over the period. Left of it: you're on pace.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** All budgets in the main currency at a glance: one ring for how much of the total is used, and the counts by state. */
@Composable
private fun BudgetOverview(list: List<BudgetStatus>, base: String) {
    val inBase = list.filter { it.budget.currency == base }
    // A budget with no categories already covers everything; adding the others to it would count spending twice.
    val overall = inBase.firstOrNull { it.budget.categoryIds.isEmpty() }
    val limit = overall?.limit ?: inBase.sumOf { it.limit }
    val spent = overall?.spent ?: inBase.sumOf { it.spent }
    val m = app.kosh.ui.theme.LocalMoneyColors.current
    val over = list.count { it.over }
    val close = list.count { !it.over && (it.overAlert || it.aheadOfPace) }
    val fine = list.size - over - close
    val fraction = if (limit > 0) spent.toFloat() / limit else 0f
    val ringColor = when {
        fraction > 1f -> MaterialTheme.colorScheme.error
        fraction >= 0.8f -> m.warning
        else -> MaterialTheme.colorScheme.primary
    }
    app.kosh.ui.components.KoshCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            app.kosh.ui.components.ProgressRing(
                fraction, ringColor, size = 96.dp, thickness = 11.dp,
                description = "${(fraction * 100).toInt()} percent of your budgets used",
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
                    Text("used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (overall != null) overall.budget.name else "All budgets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(limit - spent, base, style = MaterialTheme.typography.headlineSmall, color = if (spent > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Row {
                    Text(if (spent > limit) "over · spent " else "left · spent ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(spent, base, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" of ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(limit, base, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (fine > 0) StatusPill("$fine on track", m.income)
                    if (close > 0) StatusPill("$close watch", m.warning)
                    if (over > 0) StatusPill("$over over", MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BudgetCard(b: BudgetStatus, lookup: Lookup, onClick: () -> Unit) {
    val hidden = LocalHideAmounts.current
    val cur = b.budget.currency
    val (label, color) = budgetState(b)
    KoshCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val first = b.budget.categoryIds.firstOrNull()
                if (first != null) { val (icon, col) = lookup.categoryLook(first); IconBadge(icon, col, size = 42.dp) }
                else app.kosh.ui.components.IconTile(Icons.Rounded.PieChart, MaterialTheme.colorScheme.primary, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.budget.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "${Dates.short(b.window.start)} – ${Dates.short(b.window.end)}" + if (b.daysLeft > 0) " · ${app.kosh.core.plural(b.daysLeft, "day")} left" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(label, color)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                MoneyText(b.spent, cur, style = MaterialTheme.typography.titleLarge)
                Text("  of ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                MoneyText(b.limit, cur, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
            BudgetBar(b.fraction, b.paceFraction, over = b.over, warn = b.overAlert || b.aheadOfPace)
            Text(
                if (hidden) "${(b.fraction * 100).toInt()}% used" else budgetLine(b),
                style = MaterialTheme.typography.bodyMedium,
                color = if (b.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (b.budget.categoryIds.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (id in b.budget.categoryIds.take(6)) {
                        val (icon, col) = lookup.categoryLook(id)
                        IconBadge(icon, col, size = 24.dp)
                    }
                    if (b.budget.categoryIds.size > 6) Text("+${b.budget.categoryIds.size - 6}", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (b.budget.rollover && b.limit != b.budget.amount) {
                Text(
                    if (hidden) "Includes rollover" else "Includes ${Money.ui(b.limit - b.budget.amount, cur, signed = true)} rolled over",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val periods = listOf("weekly" to "Weekly", "monthly" to "Monthly", "quarterly" to "Quarterly", "yearly" to "Yearly", "custom" to "Custom")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditBudgetScreen(id: String?) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = app.kosh.ui.rememberSafeScope()
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var existing by remember { mutableStateOf<BudgetEntity?>(null) }
    var name by remember { mutableStateOf("Monthly spending") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(c.settings.current.baseCurrency) }
    var period by remember { mutableStateOf("monthly") }
    var start by remember { mutableStateOf(YearMonth.now().atDay(1)) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    var cats by remember { mutableStateOf(setOf<String>()) }
    var rollover by remember { mutableStateOf(false) }
    var alert by remember { mutableFloatStateOf(80f) }
    var pickCats by remember { mutableStateOf(false) }
    var pickCurrency by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        val b = id?.let { c.repo.findBudget(it) } ?: return@LaunchedEffect
        existing = b
        name = b.name; amount = Money.toPlain(b.amount, b.currency); currency = b.currency; period = b.period
        start = LocalDate.parse(b.startDate); end = Dates.parse(b.endDate); cats = b.categoryIds.toSet()
        rollover = b.rollover; alert = b.alertPercent.toFloat()
    }

    fun save() {
        val minor = Money.parse(amount, currency)
        when {
            name.isBlank() -> { error = "Give the budget a name"; return }
            minor == null || minor <= 0 -> { error = "Enter the budget amount"; return }
            period == "custom" && (end == null || end!!.isBefore(start)) -> { error = "Pick an end date after the start"; return }
        }
        val now = System.currentTimeMillis()
        val base = existing ?: BudgetEntity(id = Ids.newV7(now), name = name, amount = minor!!, currency = currency, period = period, startDate = start.toString(), createdAt = now)
        scope.launch {
            c.repo.saveBudget(
                base.copy(
                    name = name.trim(), amount = minor!!, currency = currency, period = period, startDate = start.toString(),
                    endDate = if (period == "custom") end?.toString() else null, categoryIds = cats.toList(),
                    rollover = rollover && period != "custom", alertPercent = alert.toInt(),
                ),
            )
            nav.popBackStack()
        }
    }

    Scaffold(
        topBar = { KoshTopBar(if (id == null) "New budget" else "Edit budget", onBack = { nav.popBackStack() }, closeIcon = true) { TextButton(onClick = ::save) { Text("Save") } } },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField(amount, { amount = it; error = null }, currency, "Amount per period")
            TextButton(onClick = { pickCurrency = true }) { Text("Currency: $currency") }
            Text("Period", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((p, l) in periods) FilterChip(period == p, {
                    period = p
                    start = when (p) {
                        "monthly", "quarterly" -> YearMonth.now().atDay(1)
                        "yearly" -> LocalDate.now().withDayOfYear(1)
                        "weekly" -> Dates.weekStart(LocalDate.now())
                        else -> LocalDate.now()
                    }
                    if (p == "custom" && end == null) end = start.plusMonths(1).minusDays(1)
                }, { Text(l) })
            }
            Text(
                when (period) {
                    "custom" -> "From ${Dates.medium(start)} to ${end?.let(Dates::medium) ?: "?"}"
                    "weekly" -> "Starts ${Dates.medium(start)} and repeats every week"
                    "quarterly" -> "Starts ${Dates.medium(start)} and repeats every 3 months"
                    "yearly" -> "Starts ${Dates.medium(start)} and repeats every year"
                    else -> "Starts ${Dates.medium(start)} and repeats every month"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (period == "custom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { end = (end ?: start).plusMonths(1) }) { Text("End +1 month") }
                    TextButton(onClick = { end = (end ?: start).minusMonths(1).coerceAtLeast(start) }) { Text("End −1 month") }
                }
            }
            Text("Categories", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.fillMaxWidth().clickable { pickCats = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (cats.isEmpty()) "All spending" else cats.joinToString { lookup.categoryName(it) },
                    Modifier.weight(1f), maxLines = 2,
                )
                TextButton(onClick = { pickCats = true }) { Text("Choose") }
            }
            if (period != "custom") SwitchRow("Roll over", "Unspent money carries into the next period; overspending reduces it", rollover) { rollover = it }
            Text("Alert at ${alert.toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(alert, { alert = it }, valueRange = 50f..100f, steps = 9)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (existing != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete budget", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickCurrency) CurrencyPickerSheet({ pickCurrency = false }, currency) { currency = it }
    if (pickCats) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { pickCats = false }, sheetState = state) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Text("Count spending in", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                Text("Choosing a group includes its sub-categories.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                for (root in lookup.roots("expense")) {
                    CheckRow(root.name, root.id in cats, bold = true) { on -> cats = if (on) cats + root.id else cats - root.id }
                    if (root.id !in cats) {
                        for (k in lookup.children(root.id)) {
                            CheckRow(k.name, k.id in cats, indent = true) { on -> cats = if (on) cats + k.id else cats - k.id }
                        }
                    }
                }
                TextButton(onClick = { pickCats = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete budget?", "Your transactions are not affected.", "Delete", destructive = true, onConfirm = {
            scope.launch { existing?.let { c.repo.deleteBudget(it.id) }; nav.popBackStack() }
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, bold: Boolean = false, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = if (indent) 48.dp else 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange)
        Text(label, style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
    }
}
