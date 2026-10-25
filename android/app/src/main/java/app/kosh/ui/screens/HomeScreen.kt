package app.kosh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kosh.AppContainer
import app.kosh.core.Dates
import app.kosh.data.AppSettings
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.BudgetStatus
import app.kosh.domain.CurrencySummary
import app.kosh.domain.Lookup
import app.kosh.domain.Reports
import app.kosh.domain.Upcoming
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.budgetStatuses
import app.kosh.ui.components.BudgetBar
import app.kosh.ui.components.DayHeader
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.PaceChart
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.TransactionRow
import app.kosh.ui.components.ago
import app.kosh.ui.components.dayNet
import app.kosh.ui.components.parseColor
import app.kosh.ui.components.typeColor
import app.kosh.ui.koshViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class HomeState(
    val lookup: Lookup = Lookup(),
    val month: CurrencySummary? = null,
    /** This month's income and spending across every currency, in the main currency; null when only one is used. */
    val monthAll: MonthAll? = null,
    val pace: Pace? = null,
    val recent: List<TransactionEntity> = emptyList(),
    val budgets: List<BudgetStatus> = emptyList(),
    val upcoming: List<Upcoming> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val pending: Int = 0,
    val loaded: Boolean = false,
)

/** Cumulative spending by day: this month so far, and the whole month before it. */
data class Pace(val current: List<Long>, val previous: List<Long>, val currency: String)

data class MonthAll(val income: Long, val expense: Long, val missing: List<String>)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    /** The month on screen follows the calendar, so an app left open over a month end rolls over. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val monthSummary = app.kosh.ui.todayFlow().map { YearMonth.from(it) }.distinctUntilChanged().flatMapLatest { month ->
        combine(c.repo.reportRows(month.atDay(1), month.atEndOfMonth()), c.lookup) { rows, lookup ->
            val all = Reports.summarize(rows, lookup.categoryById, lookup.baseCurrency)
            val asOf = month.atEndOfMonth().coerceAtMost(Dates.today()).toString()
            val multi = all.keys.any { it != lookup.baseCurrency }
            val income = if (multi) app.kosh.domain.Fx.total(all.mapValues { it.value.income }, lookup.baseCurrency, lookup.rates, asOf) else null
            val expense = if (multi) app.kosh.domain.Fx.total(all.mapValues { it.value.expense }, lookup.baseCurrency, lookup.rates, asOf) else null
            all[lookup.baseCurrency] to if (income != null && expense != null) MonthAll(income.total, expense.total, income.missing) else null
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val pace = app.kosh.ui.todayFlow().distinctUntilChanged().flatMapLatest { today ->
        val month = YearMonth.from(today)
        val prev = month.minusMonths(1)
        combine(c.repo.reportRows(prev.atDay(1), month.atEndOfMonth()), c.lookup) { rows, lookup ->
            val cur = lookup.baseCurrency
            val now = Reports.dailyCumulative(rows, lookup.categoryById, cur, cur, month, today.dayOfMonth)
            val before = Reports.dailyCumulative(rows, lookup.categoryById, cur, cur, prev, prev.lengthOfMonth())
            if ((now.lastOrNull() ?: 0L) <= 0L && (before.lastOrNull() ?: 0L) <= 0L) null else Pace(now, before, cur)
        }
    }

    val state: StateFlow<HomeState> = combine(
        combine(c.lookup, monthSummary, c.repo.recentTransactions(25), pace) { l, m, r, p -> arrayOf<Any?>(l, m, r, p) },
        budgetStatuses(c),
        c.repo.recurringRules,
        c.settings.state,
        c.repo.pendingCount,
    ) { parts, budgets, rules, settings, pending ->
        @Suppress("UNCHECKED_CAST")
        val lookup = parts[0] as Lookup
        @Suppress("UNCHECKED_CAST")
        val monthly = parts[1] as Pair<CurrencySummary?, MonthAll?>
        @Suppress("UNCHECKED_CAST")
        val recent = parts[2] as List<TransactionEntity>
        HomeState(lookup, monthly.first, monthly.second, parts[3] as Pace?, recent, budgets, c.recurring.upcoming(rules, days = 7), settings, pending, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState(lookup = c.lookup.value, settings = c.settings.current))

    fun confirm(u: Upcoming) = viewModelScope.launch { c.recurring.confirm(u.rule, u.date) }
    fun skip(u: Upcoming) = viewModelScope.launch { c.recurring.skip(u.rule, u.date) }
    fun toggleHide() = c.settings.setHideAmounts(!c.settings.current.hideAmounts)
    fun dismissTip(key: String) = c.settings.setFlag("tip.$key")
    fun tipDismissed(key: String) = c.settings.flag("tip.$key")
    fun syncNow() = c.scheduler.syncNow()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val vm = koshViewModel { HomeViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val c = LocalContainer.current
    val syncing by c.sync.running.collectAsStateWithLifecycle()
    var tipsVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.settings.ledgerName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Dates.month(YearMonth.now()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (s.settings.syncEnabled) {
                        IconButton(onClick = { vm.syncNow() }) {
                            val icon = when {
                                syncing -> Icons.Rounded.CloudSync
                                s.settings.lastSyncError != null -> Icons.Rounded.CloudOff
                                else -> Icons.Rounded.CloudDone
                            }
                            val desc = when {
                                syncing -> "Syncing"
                                s.settings.lastSyncError != null -> "Sync problem: ${s.settings.lastSyncError}"
                                else -> "Synced ${ago(s.settings.lastSyncAt)}. Tap to sync now."
                            }
                            Icon(icon, desc, tint = if (s.settings.lastSyncError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = vm::toggleHide) {
                        Icon(
                            if (s.settings.hideAmounts) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (s.settings.hideAmounts) "Show amounts" else "Hide amounts",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { BalanceCard(s, onClick = { nav.navigate(Routes.ACCOUNTS) }) }

            // Getting-started tips (dismissible).
            item {
                tipsVersion.let { }
                val tips = buildList {
                    if (s.loaded && s.lookup.accounts.size <= 1 && !vm.tipDismissed("account")) add(Triple("account", "Add your bank account or card", Routes.editAccount()))
                    if (s.loaded && s.budgets.isEmpty() && !vm.tipDismissed("budget")) add(Triple("budget", "Set a monthly budget", Routes.editBudget()))
                    if (s.loaded && !s.settings.syncEnabled && !vm.tipDismissed("sync")) add(Triple("sync", "Sync with your own server (optional)", Routes.SYNC))
                }
                for ((key, text, route) in tips.take(2)) {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = { nav.navigate(route) }) {
                        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { vm.dismissTip(key); tipsVersion++ }) { Icon(Icons.Rounded.Close, "Dismiss") }
                        }
                    }
                }
            }

            if (s.upcoming.isNotEmpty()) {
                item { SectionHeader("Upcoming bills", action = "All", onAction = { nav.navigate(Routes.RECURRING) }) }
                items(s.upcoming.take(4), key = { "up-${it.rule.id}-${it.date}" }) { u -> UpcomingRow(u, s.lookup, onAdd = { vm.confirm(u) }, onSkip = { vm.skip(u) }) }
            }

            if (s.budgets.isNotEmpty()) {
                item { SectionHeader("Budgets", action = "All", onAction = { nav.navigate(Routes.BUDGETS) }) }
                items(s.budgets.take(3), key = { "b-${it.budget.id}" }) { b -> BudgetMini(b) { nav.navigate(Routes.editBudget(b.budget.id)) } }
            }

            s.pace?.let { pace ->
                item { SectionHeader("Spending pace", action = "Reports", onAction = { nav.navigate(Routes.REPORTS) }) }
                item {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            val now = pace.current.lastOrNull() ?: 0L
                            val last = pace.previous.getOrNull(pace.current.size - 1) ?: 0L
                            PaceChart(pace.current, pace.previous, pace.currency, "Spending so far this month compared with last month")
                            Text(
                                when {
                                    last <= 0L -> "Nothing was spent by this day last month."
                                    now > last -> "${app.kosh.core.Money.format(now - last, pace.currency)} more than by this day last month."
                                    now < last -> "${app.kosh.core.Money.format(last - now, pace.currency)} less than by this day last month."
                                    else -> "Exactly the same as by this day last month."
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            item { SpendingCard(s, onClick = { nav.navigate(Routes.REPORTS) }) }

            item { SectionHeader("Recent activity", action = "See all", onAction = { nav.navigate(Routes.TRANSACTIONS) }) }
            if (s.recent.isEmpty() && s.loaded) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                        Text("Tap + to log your first expense. It takes three seconds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val byDay = s.recent.groupBy { it.localDate }
            for ((day, list) in byDay) {
                item(key = "d-$day") { DayHeader(LocalDate.parse(day), dayNet(list, s.lookup), s.lookup.baseCurrency) }
                items(list, key = { it.id }) { t -> TransactionRow(t, s.lookup, onClick = { nav.navigate(Routes.editTx(t.id, t.type)) }) }
            }
        }
    }
}

@Composable
private fun BalanceCard(s: HomeState, onClick: () -> Unit) {
    val base = s.lookup.baseCurrency
    val p = s.lookup.position
    KoshCard(Modifier.fillMaxWidth().padding(16.dp), onClick = onClick) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AccountBalance, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Available to spend", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Accounts", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoneyText(p.available[base] ?: 0, base, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 4.dp))
            for ((cur, v) in p.available.filterKeys { it != base }) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MoneyText(v, cur, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("in $cur accounts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            Text("Cash, bank and wallet accounts. Credit limits are not counted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.investments.isNotEmpty() || p.debt.isNotEmpty() || p.receivable.isNotEmpty() || p.payable.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Net worth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(p.netWorth[base] ?: 0, base, style = MaterialTheme.typography.headlineSmall)
                val all = s.lookup.netWorthInBase(Dates.today().toString())
                if (all != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "All currencies together: " + app.kosh.core.Money.format(all.total, base),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (all.missing.isNotEmpty()) {
                        val nav = LocalNav.current
                        TextButton(onClick = { nav.navigate(Routes.RATES) }, contentPadding = PaddingValues(0.dp)) {
                            Text("Add a rate for ${all.missing.joinToString(", ")} to include it")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Investments", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(p.investments[base] ?: 0, base, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Owed on cards & loans", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(p.debt[base] ?: 0, base, style = MaterialTheme.typography.titleMedium, color = if ((p.debt[base] ?: 0) > 0) typeColor("expense") else androidx.compose.ui.graphics.Color.Unspecified)
                    }
                }
                if ((p.receivable[base] ?: 0) != 0L || (p.payable[base] ?: 0) != 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            p.receivable[base]?.let { "Others owe you ${app.kosh.core.Money.format(it, base)}" },
                            p.payable[base]?.let { "you owe people ${app.kosh.core.Money.format(it, base)}" },
                        ).joinToString(" · ") + " (included in net worth)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("Income this month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(s.monthAll?.income ?: s.month?.income ?: 0, base, signed = true, color = typeColor("income"), style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    Text("Spent this month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(-(s.monthAll?.expense ?: s.month?.expense ?: 0), base, signed = true, color = typeColor("expense"), style = MaterialTheme.typography.titleMedium)
                }
            }
            s.monthAll?.let { all ->
                Text(
                    if (all.missing.isEmpty()) "Includes other currencies, converted with your rates." else "Leaves out ${all.missing.joinToString(", ")}: add an exchange rate to include it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SpendingCard(s: HomeState, onClick: () -> Unit) {
    val summary = s.month ?: return
    val top = summary.expenseByCategory.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(5)
    if (top.isEmpty()) return
    SectionHeader("Where your money went", action = "Reports", onAction = onClick)
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val max = top.first().value.toFloat()
            for ((catId, amount) in top) {
                val (icon, color) = s.lookup.categoryLook(catId)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(icon, color, size = 32.dp)
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(s.lookup.categoryName(catId), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            MoneyText(amount, summary.currency, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { amount / max },
                            modifier = Modifier.fillMaxWidth(),
                            color = parseColor(color, MaterialTheme.colorScheme.primary),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetMini(b: BudgetStatus, onClick: () -> Unit) {
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.budget.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(budgetLine(b), style = MaterialTheme.typography.bodySmall, color = if (b.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BudgetBar(b.fraction, b.paceFraction, over = b.over, warn = b.overAlert)
        }
    }
}

@Composable
private fun UpcomingRow(u: Upcoming, lookup: Lookup, onAdd: () -> Unit, onSkip: () -> Unit) {
    val (icon, color) = if (u.rule.type == "transfer") "transfer" to null else lookup.categoryLook(u.rule.categoryId)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, color, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(u.rule.name ?: u.rule.payee ?: lookup.categoryName(u.rule.categoryId), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val auto = if (u.rule.mode == "auto") " · added automatically" else ""
            Text(
                Dates.relativeDay(u.date) + auto, style = MaterialTheme.typography.bodySmall,
                color = if (u.due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MoneyText(if (u.rule.type == "income") u.rule.amount else -u.rule.amount, lookup.currencyOf(u.rule.accountId), signed = u.rule.type != "transfer")
        if (u.due && u.rule.mode == "remind") {
            FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Add") }
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

/** "₹4,200 left · ₹466/day for 9 days" or "Over by ₹500". */
fun budgetLine(b: BudgetStatus): String {
    val cur = b.budget.currency
    return when {
        b.over -> "Over by ${app.kosh.core.Money.format(-b.remaining, cur)}"
        b.daysLeft > 0 -> "${app.kosh.core.Money.format(b.remaining, cur)} left · ${app.kosh.core.Money.format(b.perDayLeft, cur)}/day for ${b.daysLeft} days"
        else -> "${app.kosh.core.Money.format(b.remaining, cur)} left"
    }
}
