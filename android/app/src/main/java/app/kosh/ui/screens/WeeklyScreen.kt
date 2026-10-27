package app.kosh.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.core.plural
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.RuleEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.Routes
import app.kosh.ui.components.HeroCard
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.IconTile
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.LocalOnHero
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.SectionCard
import app.kosh.ui.components.StatusPill
import app.kosh.ui.rememberSafeScope
import app.kosh.ui.theme.LocalMoneyColors
import app.kosh.work.WeeklyCheckIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * The weekly check-in: the week at a glance, then one card at a time for what needs a decision.
 * Everything here can also be done elsewhere; this just puts the few loose ends in one place.
 */
@Composable
fun WeeklyScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberSafeScope()
    val snackbar = LocalSnackbar.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val uncategorized by remember { c.repo.uncategorized(30) }.collectAsStateWithLifecycle(emptyList())
    val found by c.captures.items.collectAsStateWithLifecycle()
    var summary by remember { mutableStateOf<WeeklyCheckIn.Summary?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { summary = WeeklyCheckIn.summary(c) }
    // Entries skipped in this visit stay out of the queue until the screen is opened again.
    val skipped = remember { mutableStateOf(setOf<String>()) }
    val queue = uncategorized.filter { it.id !in skipped.value }
    val waiting = found.count { it.state == "new" }
    var reconcile by remember { mutableStateOf<AccountEntity?>(null) }
    var checkedNow by remember { mutableStateOf(setOf<String>()) }

    Scaffold(topBar = { KoshTopBar("Weekly check-in", onBack = { nav.popBackStack() }) }) { padding ->
        val s = summary
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { if (s != null) WeekHero(s) }

            if (queue.isNotEmpty()) {
                item {
                    CategoryQueue(queue.first(), position = uncategorized.size - queue.size + 1, total = uncategorized.size,
                        onPicked = { tx, categoryId, remember ->
                            scope.launch {
                                val before = c.repo.recategorize(listOf(tx.id), categoryId)
                                if (remember && !tx.payee.isNullOrBlank()) {
                                    c.repo.saveCategorizationRule(
                                        RuleEntity(id = Ids.newV7(), name = tx.payee, pattern = tx.payee!!.trim(), matchType = "equals",
                                            txType = tx.type, setCategoryId = categoryId, createdAt = System.currentTimeMillis(),
                                            sortOrder = (c.rules.value.maxOfOrNull { it.sortOrder } ?: -1) + 1),
                                    )
                                }
                                val r = snackbar.showSnackbar("Filed under ${lookup.categoryPath(categoryId)}", "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) c.repo.setCategories(before)
                            }
                        },
                        onSkip = { skipped.value = skipped.value + it.id },
                        onOpen = { nav.navigate(Routes.editTx(id = it.id)) },
                    )
                }
            }

            if (waiting > 0) {
                item {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { nav.navigate(FOUND_PAYMENTS_ROUTE) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Rounded.MarkEmailRead, MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("${plural(waiting, "payment")} found in alerts", style = MaterialTheme.typography.titleSmall)
                                Text("Save or ignore each with one tap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill("Review", MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            val check = s?.toCheck?.takeIf { it.id !in checkedNow }
            if (check != null) {
                item {
                    BalanceCheckCard(check, lookup.balances[check.id] ?: check.openingBalance,
                        onYes = { c.settings.setBalanceChecked(check.id); checkedNow = checkedNow + check.id },
                        onFix = { reconcile = check })
                }
            }

            if (s != null && s.budgets.isNotEmpty()) {
                item {
                    SectionCard("Budgets to watch", action = "All budgets", onAction = { nav.navigate(Routes.BUDGETS) }) {
                        for (b in s.budgets) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.budget.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (b.over) "Over by ${Money.ui(-b.remaining, b.budget.currency)}"
                                        else "${Money.ui(b.remaining, b.budget.currency)} left for ${plural(b.daysLeft, "day")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (b.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                StatusPill("${(b.fraction * 100).toInt()}%", if (b.over) MaterialTheme.colorScheme.error else LocalMoneyColors.current.expense)
                            }
                        }
                    }
                }
            }

            if (s != null && s.bills.isNotEmpty()) {
                item {
                    SectionCard("Coming up in 7 days", action = "Bills", onAction = { nav.navigate(Routes.RECURRING) }) {
                        for (u in s.bills.take(6)) UpcomingRow(u, lookup,
                            onAdd = { scope.launch { c.recurring.confirm(u.rule, u.date); refresh++ } },
                            onSkip = { scope.launch { c.recurring.skip(u.rule, u.date); refresh++ } })
                    }
                }
            }

            if (s != null) {
                item {
                    val allDone = queue.isEmpty() && waiting == 0 && check == null
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (allDone) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(44.dp), tint = LocalMoneyColors.current.income)
                            Text("All caught up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                            Text("See you next Sunday.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { c.settings.setReviewed(); nav.popBackStack() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(if (allDone) "Done" else "Finish for now")
                        }
                        if (!settings.weeklyCheckIn) {
                            TextButton(onClick = { c.settings.setWeeklyCheckIn(true) }) { Text("Remind me every Sunday") }
                        }
                    }
                }
            }
        }
        }
    }
    reconcile?.let { a ->
        ReconcileDialog(a, lookup.balances[a.id] ?: a.openingBalance, onDismiss = { reconcile = null }) { actual ->
            scope.launch {
                c.repo.reconcile(a, lookup.balances[a.id] ?: a.openingBalance, actual)
                c.settings.setBalanceChecked(a.id)
                checkedNow = checkedNow + a.id
                snackbar.showSnackbar("${a.name} now matches your bank. The difference is left out of reports.")
            }
        }
    }
}

@Composable
private fun WeekHero(s: WeeklyCheckIn.Summary) {
    val lookup by LocalContainer.current.lookup.collectAsStateWithLifecycle()
    HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val on = LocalOnHero.current
        Text("${app.kosh.core.Dates.short(s.from)} – ${app.kosh.core.Dates.short(s.to)}", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
        Text("Spent this week", style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp))
        MoneyText(s.spent, s.currency, style = MaterialTheme.typography.displaySmall, color = on)
        if (s.lastWeek > 0) {
            val diff = s.spent - s.lastWeek
            val pct = kotlin.math.abs(diff * 100 / s.lastWeek)
            Text(
                when {
                    diff > 0 -> "$pct% more than last week (${Money.ui(s.lastWeek, s.currency)})"
                    diff < 0 -> "$pct% less than last week (${Money.ui(s.lastWeek, s.currency)})"
                    else -> "The same as last week"
                },
                style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.85f),
            )
        }
        if (s.top.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            for ((cat, amount) in s.top) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(lookup.categoryPath(cat), style = MaterialTheme.typography.bodyMedium, color = on, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MoneyText(amount, s.currency, style = MaterialTheme.typography.labelLarge, color = on)
                }
            }
        }
    }
}

/** One uncategorised entry with the likeliest categories as chips: one tap files it and the next one shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryQueue(
    tx: TransactionEntity,
    position: Int,
    total: Int,
    onPicked: (TransactionEntity, String, Boolean) -> Unit,
    onSkip: (TransactionEntity) -> Unit,
    onOpen: (TransactionEntity) -> Unit,
) {
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    var always by remember(tx.id) { mutableStateOf(false) }
    var suggestions by remember(tx.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tx.id) {
        val guess = c.autoFill.guess(tx.type, tx.payee, tx.note, tx.amount).categoryId
        val used = c.repo.topCategories(tx.type, 6)
        suggestions = (listOfNotNull(guess) + used + lookup.roots(tx.type).map { it.id }).distinct()
            .filter { lookup.categoryById[it]?.archived == false }.take(6)
    }
    SectionCard("Needs a category", subtitle = "$position of $total · from the last 30 days") {
        AnimatedContent(tx, label = "next") { t ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.payee ?: t.note?.take(60) ?: if (t.type == "income") "Money received" else "Payment", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Dates.relativeDay(java.time.LocalDate.parse(t.localDate))} · ${lookup.accountName(t.accountId)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoneyText(if (t.type == "expense") -t.amount else t.amount, lookup.currencyOf(t.accountId), signed = t.type == "income",
                        style = MaterialTheme.typography.titleMedium, color = if (t.type == "income") LocalMoneyColors.current.income else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (id in suggestions) {
                        val cat = lookup.categoryById[id] ?: continue
                        FilterChip(false, { onPicked(t, id, always) }, { Text(lookup.categoryPath(id), maxLines = 1) },
                            leadingIcon = { IconBadge(cat.icon, cat.color, size = 20.dp) })
                    }
                    FilterChip(false, { sheet = true }, { Text("More") }, leadingIcon = { Icon(Icons.Rounded.MoreHoriz, null, Modifier.size(18.dp)) })
                }
                if (!t.payee.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(always, { always = it })
                        Text("Always file “${t.payee}” this way", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onOpen(t) }) { Text("Open") }
                    OutlinedButton(onClick = { onSkip(t) }) { Text("Skip") }
                }
            }
        }
    }
    if (sheet) CategorySheet(lookup, tx.type, null, onDismiss = { sheet = false }) { id -> if (id != null) onPicked(tx, id, always) }
}

/** "Does Cash show ₹2,340?": one tap to confirm, or fix it to the bank's figure. */
@Composable
fun BalanceCheckCard(a: AccountEntity, balance: Long, onYes: () -> Unit, onFix: () -> Unit, modifier: Modifier = Modifier) {
    KoshCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.AccountBalance, MaterialTheme.colorScheme.tertiary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Does this match your bank?", style = MaterialTheme.typography.titleSmall)
                    Text("${a.name} shows", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MoneyText(balance, a.currency, style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onFix) { Text("No, fix it") }
                Button(onClick = onYes) { Text("Yes, it matches") }
            }
        }
    }
}
