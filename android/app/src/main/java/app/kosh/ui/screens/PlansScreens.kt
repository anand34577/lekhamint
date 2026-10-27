package app.kosh.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.KoshJson
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.domain.Goal
import app.kosh.domain.Goals
import app.kosh.domain.Lookup
import app.kosh.domain.Reports
import app.kosh.domain.TaxSections
import app.kosh.domain.Valuation
import app.kosh.domain.num
import app.kosh.domain.str
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.DateField
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.FormSheet
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.PickerSheet
import app.kosh.ui.components.SectionHeader
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate

// --- Goals -----------------------------------------------------------------------------

@Composable
fun GoalsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val goals by c.repo.goals.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<Goal?>(null) }
    var adding by remember { mutableStateOf(false) }
    val worth = { id: String -> lookup.accountById[id]?.let(lookup::worth) }
    val cur = { id: String -> lookup.accountById[id]?.currency }
    val (active, archived) = goals.partition { !it.archived }
    Scaffold(
        topBar = {
            KoshTopBar("Goals", onBack = { nav.popBackStack() }) {
                androidx.compose.material3.FilledTonalButton(onClick = { adding = true }, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Rounded.Add, null); Text("New", Modifier.padding(start = 4.dp))
                }
            }
        },
    ) { padding ->
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (active.isEmpty()) item {
                EmptyState(Icons.Rounded.Savings, "Save for what matters",
                    "Set a target, like an emergency fund or a trip. Link the accounts where you keep the money and see how much to put aside each month.") {
                    androidx.compose.material3.FilledTonalButton(onClick = { adding = true }) { Text("Set a goal") }
                }
            }
            // All goals in the main currency together: one ring for the whole picture.
            val inBase = active.filter { it.currency == lookup.baseCurrency }.map { it to Goals.progress(it, worth, cur) }
            if (inBase.size > 1) item {
                val saved = inBase.sumOf { minOf(it.second.saved, it.second.target) }
                val target = inBase.sumOf { it.second.target }
                val monthly = inBase.sumOf { it.second.perMonth ?: 0 }
                app.kosh.ui.components.HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val on = app.kosh.ui.components.LocalOnHero.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        app.kosh.ui.components.ProgressRing(
                            if (target > 0) saved.toFloat() / target else 0f, on, size = 84.dp, thickness = 10.dp, track = on.copy(alpha = 0.2f),
                        ) { Text("${if (target > 0) (saved * 100 / target) else 0}%", style = MaterialTheme.typography.titleMedium, color = on) }
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text("Saved across ${app.kosh.core.plural(inBase.size, "goal")}", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.85f))
                            MoneyText(saved, lookup.baseCurrency, style = MaterialTheme.typography.headlineSmall, color = on)
                            Text("of ${Money.ui(target, lookup.baseCurrency)}", style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.8f))
                            if (monthly > 0) Text("Put aside ${Money.ui(wholeUp(monthly, lookup.baseCurrency), lookup.baseCurrency)} a month to stay on plan", style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            items(active.sortedBy { it.targetDate ?: "9999" }, key = { it.id }) { g ->
                GoalCard(g, Goals.progress(g, worth, cur), explain = g.id == active.sortedBy { it.targetDate ?: "9999" }.first().id) { editing = g }
            }
            if (archived.isNotEmpty()) {
                item { SectionHeader("Finished and archived") }
                items(archived, key = { it.id }) { g ->
                    ListItem(headlineContent = { Text(g.name) }, supportingContent = { Text(g.achievedDate?.let { "Reached ${Dates.medium(LocalDate.parse(it))}" } ?: "Archived") },
                        trailingContent = { MoneyText(g.targetAmount, g.currency) }, modifier = Modifier.clickable { editing = g },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
        }
    }
    if (adding) GoalSheet(null, lookup) { adding = false }
    editing?.let { g -> GoalSheet(g, lookup) { editing = null } }
}

/** Rounds up to whole currency units: "put aside ₹12,374", not "₹12,373.99". */
private fun wholeUp(minor: Long, currency: String): Long {
    var f = 1L
    repeat(app.kosh.core.Currencies.digits(currency)) { f *= 10 }
    return (minor + f - 1) / f * f
}

/** One goal: a ring for how far along it is, a bar that marks where the plan says you should be today, and what to do next. */
@Composable
private fun GoalCard(g: Goal, p: Goals.Progress, explain: Boolean, onClick: () -> Unit) {
    val m = app.kosh.ui.theme.LocalMoneyColors.current
    val tint = when (p.status) {
        Goals.Status.DONE, Goals.Status.AHEAD -> m.income
        Goals.Status.BEHIND, Goals.Status.OVERDUE -> m.warning
        else -> MaterialTheme.colorScheme.primary
    }
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.kosh.ui.components.ProgressRing(p.fraction, tint, size = 64.dp, thickness = 8.dp) {
                    Text("${(p.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MoneyText(p.saved, g.currency, style = MaterialTheme.typography.titleLarge)
                        Text("of ${Money.ui(p.target, g.currency)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    app.kosh.ui.components.StatusPill(p.status.label, tint, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            // Saved so far, with a tick where the plan says you should be by today.
            val track = MaterialTheme.colorScheme.surfaceContainerHighest
            val marker = MaterialTheme.colorScheme.onSurface
            val planned = p.expected?.takeIf { p.target > 0 && p.status != Goals.Status.DONE }?.let { (it.toFloat() / p.target).coerceIn(0f, 1f) }
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val h = 10.dp.toPx()
                val y = (size.height - h) / 2
                drawRoundRect(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                if (p.fraction > 0f) drawRoundRect(tint, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(size.width * p.fraction, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                if (planned != null) {
                    val x = size.width * planned
                    drawLine(marker, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 3f)
                }
            }
            if (planned != null && explain) {
                Text("The mark shows where the plan says you'd be today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    p.status == Goals.Status.DONE -> "Goal reached. Well done!"
                    p.perMonth != null -> "Put aside ${Money.ui(wholeUp(p.perMonth, g.currency), g.currency)} a month for ${maxOf(1, Math.round(p.monthsLeft ?: 1.0))} more months" + (g.targetDate?.let { " (by ${Dates.medium(LocalDate.parse(it))})" } ?: "")
                    p.status == Goals.Status.OVERDUE -> "${Money.ui(p.left, g.currency)} still to go and the date has passed."
                    else -> "${Money.ui(p.left, g.currency)} still to go. Add a date to see the monthly amount."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun GoalSheet(existing: Goal?, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val snackbar = LocalSnackbar.current
    val currency = existing?.currency ?: lookup.baseCurrency
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var target by remember { mutableStateOf(existing?.targetAmount?.let { Money.toPlain(it, currency) }.orEmpty()) }
    var saved by remember { mutableStateOf(existing?.savedAmount?.takeIf { it > 0 }?.let { Money.toPlain(it, currency) }.orEmpty()) }
    var hasDate by remember { mutableStateOf(existing?.targetDate != null || existing == null) }
    var date by remember { mutableStateOf(existing?.targetDate?.let(LocalDate::parse) ?: Dates.today().plusMonths(12)) }
    var linked by remember { mutableStateOf(existing?.accountIds.orEmpty().toSet()) }
    var archived by remember { mutableStateOf(existing?.archived ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    val id = remember { existing?.id ?: Ids.newV7() }
    val pool = lookup.activeAccounts.filter { !Lookup.isLiability(it.type) && it.currency == currency }
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New goal" else "Edit goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("What are you saving for?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField(target, { target = it }, currency, "Target amount")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("By a date", Modifier.weight(1f)); Switch(hasDate, { hasDate = it })
                }
                if (hasDate) DateField("Target date", date, { date = it }, Modifier.fillMaxWidth())
                if (pool.isNotEmpty()) {
                    Text("Where is the money kept? Their whole balance counts.", style = MaterialTheme.typography.labelLarge)
                    for (a in pool) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { linked = if (a.id in linked) linked - a.id else linked + a.id }) {
                        Checkbox(a.id in linked, { linked = if (it) linked + a.id else linked - a.id })
                        Text(a.name, Modifier.weight(1f)); MoneyText(lookup.worth(a), a.currency, style = MaterialTheme.typography.bodySmall)
                    }
                }
                AmountField(saved, { saved = it }, currency, "Already set aside elsewhere (optional)", supporting = "Cash at home, or money in an account you do not track here")
                if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) { Text("Archived", Modifier.weight(1f)); Switch(archived, { archived = it }) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (existing != null) TextButton(onClick = {
                    scope.launch {
                        c.repo.deleteRaw(Goal.ENTITY, existing.id); onDismiss()
                        val r = snackbar.showSnackbar("Goal deleted", actionLabel = "Undo", withDismissAction = true)
                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) c.repo.restoreRaw(Goal.ENTITY, existing.id)
                    }
                }) { Text("Delete goal", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val t = Money.parse(target, currency)
                val s = if (saved.isBlank()) 0 else Money.parse(saved, currency)
                error = when {
                    name.isBlank() -> "Give the goal a name"
                    t == null || t <= 0 -> "Enter the amount you want to save"
                    s == null || s < 0 -> "“Already set aside” should be a number"
                    else -> null
                }
                if (error != null) return@Button
                val g = Goal(id, name.trim(), t!!, currency, if (hasDate) date.toString() else null, existing?.startDate ?: Dates.today().toString(),
                    linked.toList(), s!!, existing?.color, archived, existing?.achievedDate, existing?.note, existing?.createdAt ?: System.currentTimeMillis())
                scope.launch { c.repo.saveRaw(Goal.ENTITY, id, g.toJson()); onDismiss() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Investment values -----------------------------------------------------------------

/** Account types whose worth can differ from the money put in (funds, shares, gold, property). */
fun isValued(type: String) = type == "investment" || type == "savings" || type == "other"

/** Value now, what was put in, and the gain, for an account that has a recorded value. */
@Composable
fun ValueSummary(a: AccountEntity, lookup: Lookup, onUpdate: () -> Unit) {
    val value = lookup.values[a.id]
    val put = lookup.balances[a.id] ?: a.openingBalance
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (value != null) {
            val gain = value - put
            Text("Worth now ${Money.ui(value, a.currency)} · you put in ${Money.ui(put, a.currency)}", style = MaterialTheme.typography.bodyMedium)
            Text((if (gain >= 0) "Gain " else "Loss ") + Money.ui(kotlin.math.abs(gain), a.currency) + if (put > 0) " (%.1f%%)".format(gain * 100.0 / put) else "",
                color = if (gain >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
        }
        AssistChip(onClick = onUpdate, label = { Text(if (value == null) "Record what it is worth" else "Update value") })
    }
}

@Composable
fun ValuationSheet(a: AccountEntity, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val all by c.repo.valuations.collectAsStateWithLifecycle(emptyList())
    val history = all.filter { it.accountId == a.id }.sortedByDescending { it.date }
    var value by remember { mutableStateOf(Money.toPlain(lookup.worth(a), a.currency)) }
    var date by remember { mutableStateOf(Dates.today()) }
    var note by remember { mutableStateOf("") }
    val id = remember { Ids.newV7() }
    val put = lookup.balances[a.id] ?: a.openingBalance
    val parsed = Money.parse(value, a.currency)
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("What is ${a.name} worth?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Copy the value from your fund statement, broker or bank app. Money you put in or take out later is added on top, so update this now and then.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountField(value, { value = it }, a.currency, "Worth on that day")
                DateField("Date", date, { date = it }, Modifier.fillMaxWidth(), max = Dates.today())
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note (optional)") }, placeholder = { Text("NAV × units") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (parsed != null && put > 0) Text(
                    (if (parsed >= put) "A gain of " else "A loss of ") + Money.ui(kotlin.math.abs(parsed - put), a.currency) + " (%.1f%%) on what you put in.".format((parsed - put) * 100.0 / put),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (history.isNotEmpty()) Text("Recorded values", style = MaterialTheme.typography.labelLarge)
                for (v in history) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        MoneyText(v.value, a.currency)
                        Text(Dates.medium(LocalDate.parse(v.date)) + (v.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { c.repo.deleteRaw(Valuation.ENTITY, v.id) } }) { Icon(Icons.Rounded.DeleteOutline, "Delete this value") }
                }
            }
        },
        confirmButton = {
            Button(enabled = parsed != null && parsed >= 0, onClick = {
                scope.launch {
                    c.repo.saveRaw(Valuation.ENTITY, id, mapOf("account_id" to str(a.id), "date" to str(date.toString()), "value" to num(parsed),
                        "note" to str(note.trim().ifEmpty { null }), "created_at" to num(System.currentTimeMillis())))
                    onDismiss()
                }
            }) { Text("Save value") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Year & tax ------------------------------------------------------------------------

/** The income-tax section a category counts for, kept in its extras (server field tax_section). */
fun taxSectionOf(cat: CategoryEntity?): String? =
    cat?.extras?.let { runCatching { ((KoshJson.parseToJsonElement(it) as JsonObject)["tax_section"] as? JsonPrimitive)?.contentOrNull }.getOrNull() }

@Composable
fun YearTaxScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = app.kosh.ui.rememberSafeScope()
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val prefs = remember { c.settings }
    var startMonth by remember { mutableIntStateOf(prefs.fyStartMonth ?: if (lookup.baseCurrency == "INR") 4 else 1) }
    var offset by remember { mutableIntStateOf(0) }
    val (from, to) = TaxSections.yearOf(Dates.today(), startMonth, offset)
    val rows by remember(from, to) { c.repo.reportRows(from, to) }.collectAsStateWithLifecycle(emptyList())
    var marking by remember { mutableStateOf<CategoryEntity?>(null) }
    var pickMonth by remember { mutableStateOf(false) }
    val sum = Reports.summarize(rows, lookup.categoryById, lookup.baseCurrency, rollUp = false)[lookup.baseCurrency]
    // Spending per tax section: a sub-category without its own tag uses its parent's.
    val bySection = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
    for ((catId, amount) in sum?.expenseByCategory.orEmpty()) {
        val cat = catId?.let { lookup.categoryById[it] }
        val sec = taxSectionOf(cat) ?: taxSectionOf(cat?.parentId?.let { lookup.categoryById[it] }) ?: continue
        if (amount > 0) bySection.getOrPut(sec) { mutableListOf() } += lookup.categoryPath(catId) to amount
    }
    Scaffold(topBar = { KoshTopBar("Year & tax", onBack = { nav.popBackStack() }) }) { padding ->
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { offset-- }) { Icon(Icons.Rounded.ChevronLeft, "Previous year") }
                    Text(TaxSections.label(from), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { offset++ }, enabled = offset < 0) { Icon(Icons.Rounded.ChevronRight, "Next year") }
                }
                TextButton(onClick = { pickMonth = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Year starts in ${java.time.Month.of(startMonth).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}")
                }
            }
            item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val inc = sum?.income ?: 0; val exp = sum?.expense ?: 0
                        Row { Text("Income", Modifier.weight(1f)); MoneyText(inc, lookup.baseCurrency) }
                        Row { Text("Spending", Modifier.weight(1f)); MoneyText(exp, lookup.baseCurrency) }
                        Row { Text(if (inc >= exp) "Saved" else "Spent more than earned", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); MoneyText(inc - exp, lookup.baseCurrency, style = MaterialTheme.typography.titleSmall) }
                        if (inc > 0 && inc > exp) LinearProgressIndicator(progress = { ((inc - exp).toFloat() / inc).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        if (inc > 0 && inc > exp) Text("${((inc - exp) * 100 / inc)}% of income kept", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { SectionHeader("Tax summary") }
            item {
                Text("Spending in categories marked for tax. A summary of your records to gather proofs and fill your return, not tax advice.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (bySection.isEmpty()) item {
                EmptyState(Icons.Rounded.Percent, "No categories marked yet", "Tap a category below to choose what it counts for, for example 80C for PPF or 80D for health insurance.")
            }
            for ((code, lines) in bySection) item(key = "sec-$code") {
                val s = TaxSections.byCode(code)
                val total = lines.sumOf { it.second }
                KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row { Text(s?.label ?: code, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); MoneyText(total, lookup.baseCurrency, style = MaterialTheme.typography.titleSmall) }
                        val limit = s?.limit
                        if (limit != null && lookup.baseCurrency == "INR") {
                            LinearProgressIndicator(progress = { (total.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                            Text(if (total >= limit) "Limit of ${Money.ui(limit, "INR")} reached" else "${Money.ui(limit - total, "INR")} of room left under ${Money.ui(limit, "INR")}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        for ((n, v) in lines) Row { Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); MoneyText(v, lookup.baseCurrency, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { SectionHeader("Mark categories for tax") }
            items(lookup.roots("expense").flatMap { listOf(it) + lookup.children(it.id) }, key = { "cat-" + it.id }) { cat ->
                val sec = taxSectionOf(cat)
                ListItem(
                    headlineContent = { Text(lookup.categoryPath(cat.id)) },
                    trailingContent = { Text(sec ?: "—", color = if (sec != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clickable { marking = cat },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        }
    }
    marking?.let { cat ->
        PickerSheet(
            title = "${cat.name} counts for",
            items = listOf<TaxSections.Section?>(null) + TaxSections.all,
            row = { Text(it?.label ?: "Nothing (not for tax)", Modifier.padding(16.dp)) },
            onDismiss = { marking = null },
            onPick = { s -> scope.launch { c.repo.setTaxSection(cat, s?.code) }; marking = null },
        )
    }
    if (pickMonth) PickerSheet(
        title = "Financial year starts in",
        items = (1..12).toList(),
        row = { Text(java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), Modifier.padding(16.dp)) },
        onDismiss = { pickMonth = false },
        onPick = { m -> startMonth = m; prefs.fyStartMonth = m; offset = 0; pickMonth = false },
    )
}
