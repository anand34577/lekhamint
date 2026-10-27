package app.kosh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import app.kosh.core.Money
import app.kosh.ui.components.readable
import app.kosh.ui.components.DonutChart
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.HeroCard
import app.kosh.ui.components.IconTile
import app.kosh.ui.components.LocalOnHero
import app.kosh.ui.components.QuickAction
import app.kosh.ui.components.SectionCard
import app.kosh.ui.components.Slice
import app.kosh.ui.components.StackedBar
import app.kosh.ui.components.StatTile
import app.kosh.ui.components.StatusPill
import app.kosh.ui.components.greeting
import app.kosh.ui.theme.LocalMoneyColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Category
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    /** On screen the live loop syncs at once; otherwise hand it to WorkManager. */
    fun syncNow() { if (c.live.active) c.live.nudge() else c.scheduler.syncNow() }
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
    val money = LocalMoneyColors.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // A sync problem replaces the greeting, in plain words: nothing is lost, it waits on the phone.
                        val problem = s.settings.syncEnabled && s.settings.lastSyncError != null && !syncing
                        Text(
                            if (problem) "Not synced · saved on this phone" else greeting(),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(s.settings.ledgerName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
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
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        // Pull down to sync, the gesture people expect from any app that talks to a server.
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { BalanceHero(s, onClick = { nav.navigate(Routes.ACCOUNTS) }) }
            if (s.settings.removedAt > 0 && !s.settings.syncEnabled) item {
                // The server stopped accepting this phone: say so once, plainly, with the way back.
                app.kosh.ui.components.KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { nav.navigate(Routes.SYNC) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Removed from the server", style = MaterialTheme.typography.titleSmall)
                            Text("This phone no longer syncs. Your data here is safe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { nav.navigate(Routes.SYNC) }) { Text("Connect again") }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // Spending has the big + button; these are the next most common jobs.
                    QuickAction(Icons.Rounded.Add, "Income", money.income, Modifier.weight(1f)) { nav.navigate(Routes.editTx(type = "income")) }
                    QuickAction(Icons.Rounded.SwapHoriz, "Transfer", money.transfer, Modifier.weight(1f)) { nav.navigate(Routes.editTx(type = "transfer")) }
                    QuickAction(Icons.Rounded.AccountBalanceWallet, "Accounts", Color(0xFF7C3AED), Modifier.weight(1f)) { nav.navigate(Routes.ACCOUNTS) }
                    QuickAction(Icons.Rounded.Insights, "Reports", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { nav.navigate(Routes.REPORTS) }
                }
            }

            item { QuickEntryBar(onType = { nav.navigate(Routes.QUICK) }, onSpeak = { nav.navigate(Routes.QUICK_VOICE) }) }

            // Getting-started tips (dismissible).
            item {
                tipsVersion.let { }
                val tips = buildList {
                    if (s.loaded && s.lookup.accounts.size <= 1 && !vm.tipDismissed("account")) add(Triple("account", "Add your bank account or card" to "Balances then add up across everything you own.", Routes.editAccount()))
                    if (s.loaded && s.budgets.isEmpty() && !vm.tipDismissed("budget")) add(Triple("budget", "Set a monthly budget" to "See what you can still spend each day.", Routes.editBudget()))
                    if (s.loaded && !s.settings.syncEnabled && s.settings.removedAt == 0L && !vm.tipDismissed("sync")) add(Triple("sync", "Sync with your own server" to "Optional: use the web app and other phones too.", Routes.SYNC))
                }
                for ((key, text, route) in tips.take(2)) {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = { nav.navigate(route) }) {
                        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Rounded.Lightbulb, MaterialTheme.colorScheme.tertiary, size = 36.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(text.first, style = MaterialTheme.typography.titleSmall)
                                Text(text.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.dismissTip(key); tipsVersion++ }) { Icon(Icons.Rounded.Close, "Dismiss") }
                        }
                    }
                }
            }

            item { HomeTodo() }
            item { InsightsTeaser(onOpen = { nav.navigate(Routes.INSIGHTS) }) }

            if (s.upcoming.isNotEmpty()) {
                item {
                    SectionCard("Coming up", subtitle = "Bills and repeats in the next 7 days", action = "All", onAction = { nav.navigate(Routes.RECURRING) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (u in s.upcoming.take(4)) UpcomingRow(u, s.lookup, onAdd = { vm.confirm(u) }, onSkip = { vm.skip(u) })
                        }
                    }
                }
            }

            s.pace?.let { pace ->
                item {
                    val now = pace.current.lastOrNull() ?: 0L
                    val last = pace.previous.getOrNull(pace.current.size - 1) ?: 0L
                    val month = YearMonth.now()
                    // An overall monthly budget (one with no categories) becomes the limit line.
                    val overall = s.budgets.firstOrNull { it.budget.categoryIds.isEmpty() && it.window.start == month.atDay(1) && it.window.end == month.atEndOfMonth() }
                    SectionCard(
                        "Spending pace",
                        subtitle = when {
                            last <= 0L -> "Nothing was spent by this day last month"
                            now > last -> "${Money.ui(now - last, pace.currency)} more than by this day last month"
                            now < last -> "${Money.ui(last - now, pace.currency)} less than by this day last month"
                            else -> "Exactly level with last month"
                        },
                        action = "Reports", onAction = { nav.navigate(Routes.REPORTS) },
                    ) {
                        PaceChart(
                            pace.current, pace.previous, pace.currency, "Spending so far this month compared with last month",
                            daysInMonth = month.lengthOfMonth(), budget = overall?.limit,
                        )
                    }
                }
            }

            if (s.budgets.isNotEmpty()) {
                item {
                    SectionCard("Budgets", subtitle = budgetsSubtitle(s.budgets), action = "All", onAction = { nav.navigate(Routes.BUDGETS) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            for (b in s.budgets.sortedByDescending { it.fraction }.take(3)) BudgetMini(b, s.lookup) { nav.navigate(Routes.editBudget(b.budget.id)) }
                        }
                    }
                }
            }

            item { SpendingCard(s, onClick = { nav.navigate(Routes.REPORTS) }) }
            item { NetWorthCard(s, onClick = { nav.navigate(Routes.ACCOUNTS) }) }

            item { SectionHeader("Recent activity", action = "See all", onAction = { nav.navigate(Routes.TRANSACTIONS) }) }
            if (s.recent.isEmpty() && s.loaded) {
                item {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.ReceiptLong, "Nothing here yet", "Tap + to log your first expense. It takes three seconds.",
                    ) { FilledTonalButton(onClick = { nav.navigate(Routes.editTx()) }) { Text("Add an expense") } }
                }
            }
            val byDay = s.recent.take(12).groupBy { it.localDate }
            for ((day, list) in byDay) {
                item(key = "d-$day") { DayHeader(LocalDate.parse(day), dayNet(list, s.lookup), s.lookup.baseCurrency) }
                items(list, key = { it.id }) { t -> TransactionRow(t, s.lookup, onClick = { nav.navigate(Routes.editTx(t.id, t.type)) }) }
            }
        }
        }
    }
}

private fun budgetsSubtitle(list: List<BudgetStatus>): String {
    val over = list.count { it.over }
    val tight = list.count { !it.over && it.overAlert }
    return when {
        over > 0 -> "$over over the limit" + if (tight > 0) ", $tight close" else ""
        tight > 0 -> "$tight close to the limit, the rest on track"
        else -> "All on track"
    }
}

@Composable
private fun BalanceHero(s: HomeState, onClick: () -> Unit) {
    val base = s.lookup.baseCurrency
    val p = s.lookup.position
    val on = LocalOnHero.current
    HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = onClick) {
        val onHero = LocalOnHero.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total balance", style = MaterialTheme.typography.labelLarge, color = onHero.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            Text(Dates.month(YearMonth.now()), style = MaterialTheme.typography.labelMedium, color = onHero.copy(alpha = 0.75f))
        }
        MoneyText(p.available[base] ?: 0, base, style = MaterialTheme.typography.displaySmall, color = onHero, modifier = Modifier.padding(top = 2.dp))
        for ((cur, v) in p.available.filterKeys { it != base }) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MoneyText(v, cur, style = MaterialTheme.typography.titleMedium, color = onHero.copy(alpha = 0.9f))
                Text("in $cur accounts", style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.75f), modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Text("Cash, bank and wallets", style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.7f))
        Spacer(Modifier.height(14.dp))
        val income = s.monthAll?.income ?: s.month?.income ?: 0
        val spent = s.monthAll?.expense ?: s.month?.expense ?: 0
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Until the month is added up, show a placeholder rather than a misleading ₹0.
            StatTile("Money in", Modifier.weight(1f), icon = Icons.Rounded.SouthWest, onHero = true) {
                if (s.loaded) MoneyText(income, base, style = MaterialTheme.typography.titleMedium, color = onHero) else HeroPlaceholder()
            }
            StatTile("Money out", Modifier.weight(1f), icon = Icons.Rounded.NorthEast, onHero = true) {
                if (s.loaded) MoneyText(spent, base, style = MaterialTheme.typography.titleMedium, color = onHero) else HeroPlaceholder()
            }
        }
        if (s.loaded && income > 0) {
            // How much of this month's income is still unspent: one bar instead of a sentence.
            val kept = ((income - spent).toFloat() / income).coerceIn(-1f, 1f)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(on.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth((spent.toFloat() / income).coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(on.copy(alpha = 0.9f)))
            }
            Text(
                if (kept >= 0) "You've spent ${(100 - kept * 100).toInt()}% of this month's income" else "You've spent more than this month's income",
                style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp),
            )
        }
        s.monthAll?.let { all ->
            Text(
                if (all.missing.isEmpty()) "Other currencies converted with your rates." else "Leaves out ${all.missing.joinToString(", ")}: add an exchange rate to include it.",
                style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A soft bar standing in for a figure that is still being worked out. */
@Composable
private fun HeroPlaceholder() {
    Box(Modifier.padding(vertical = 4.dp).size(width = 88.dp, height = 18.dp).clip(RoundedCornerShape(6.dp)).background(LocalOnHero.current.copy(alpha = 0.22f)))
}

/** Assets against what is owed, as one bar, with the net worth figure. Hidden until there is more than cash. */
@Composable
private fun NetWorthCard(s: HomeState, onClick: () -> Unit) {
    val base = s.lookup.baseCurrency
    val p = s.lookup.position
    if (p.investments.isEmpty() && p.debt.isEmpty() && p.receivable.isEmpty() && p.payable.isEmpty()) return
    val m = LocalMoneyColors.current
    val cash = (p.available[base] ?: 0).coerceAtLeast(0)
    val invest = (p.investments[base] ?: 0).coerceAtLeast(0)
    val owedToYou = (p.receivable[base] ?: 0).coerceAtLeast(0)
    val debt = (p.debt[base] ?: 0).coerceAtLeast(0)
    val youOwe = (p.payable[base] ?: 0).coerceAtLeast(0)
    val assets = cash + invest + owedToYou
    val liabilities = debt + youOwe
    val cInvest = Color(0xFF7C3AED).readable()
    val cOwed = Color(0xFF0891B2).readable()
    SectionCard("Net worth", subtitle = "What you own minus what you owe", action = "Accounts", onAction = onClick) {
        MoneyText(p.netWorth[base] ?: 0, base, style = MaterialTheme.typography.headlineMedium)
        s.lookup.netWorthInBase(Dates.today().toString())?.let { all ->
            Text("All currencies together: " + Money.ui(all.total, base), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (all.missing.isNotEmpty()) {
                val nav = LocalNav.current
                TextButton(onClick = { nav.navigate(Routes.RATES) }, contentPadding = PaddingValues(0.dp)) { Text("Add a rate for ${all.missing.joinToString(", ")} to include it") }
            }
        }
        if (assets + liabilities > 0) {
            Spacer(Modifier.height(12.dp))
            val total = (assets + liabilities).toFloat()
            StackedBar(
                listOf(
                    cash / total to MaterialTheme.colorScheme.primary, invest / total to cInvest, owedToYou / total to cOwed,
                    debt / total to m.expense, youOwe / total to m.warning,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NetLine("Cash & bank", cash, base, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                NetLine("Investments", invest, base, cInvest, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                NetLine("Cards & loans", debt, base, m.expense, Modifier.weight(1f))
                if (owedToYou > 0 || youOwe > 0) NetLine(if (owedToYou >= youOwe) "Others owe you" else "You owe people", if (owedToYou >= youOwe) owedToYou else youOwe, base, if (owedToYou >= youOwe) cOwed else m.warning, Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NetLine(label: String, value: Long, currency: String, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(value, currency, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun SpendingCard(s: HomeState, onClick: () -> Unit) {
    val summary = s.month ?: return
    val all = summary.expenseByCategory.entries.filter { it.value > 0 }.sortedByDescending { it.value }
    if (all.isEmpty()) return
    val fallback = MaterialTheme.colorScheme.primary
    val top = all.take(4)
    val rest = all.drop(4).sumOf { it.value }
    val grey = MaterialTheme.colorScheme.outline
    val slices = top.map { (id, v) -> Slice(v, parseColor(s.lookup.categoryLook(id).second, fallback), s.lookup.categoryName(id)) } +
        (if (rest > 0) listOf(Slice(rest, grey, "Everything else")) else emptyList())
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    SectionCard("Where your money went", subtitle = Dates.month(YearMonth.now()), action = "Details", onAction = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                slices, "Spending by category: " + slices.joinToString { it.label }, size = 132.dp, thickness = 18.dp,
                selected = selected, onSelect = { selected = it },
            ) {
                val sel = selected?.let(slices::getOrNull)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (sel == null) "Spent" else "${(sel.value * 100 / summary.expense.coerceAtLeast(1))}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(sel?.value ?: summary.expense, summary.currency, style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, sl) in slices.withIndex()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selected = if (selected == i) null else i }
                            .background(if (selected == i) sl.color.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(sl.color))
                        Spacer(Modifier.width(8.dp))
                        Text(sl.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MoneyText(sl.value, summary.currency, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetMini(b: BudgetStatus, lookup: Lookup, onClick: () -> Unit) {
    val (label, color) = budgetState(b)
    val firstCat = b.budget.categoryIds.firstOrNull()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (firstCat != null) { val (icon, c) = lookup.categoryLook(firstCat); IconBadge(icon, c, size = 36.dp) }
        else IconTile(Icons.Rounded.PieChart, MaterialTheme.colorScheme.primary, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.budget.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(label, color)
            }
            BudgetBar(b.fraction, b.paceFraction, over = b.over, warn = b.overAlert || b.aheadOfPace, height = 8.dp)
            Text(budgetLine(b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A short verdict and its colour, so a budget's state is readable without doing maths. */
@Composable
fun budgetState(b: BudgetStatus): Pair<String, Color> {
    val m = LocalMoneyColors.current
    return when {
        b.over -> "Over" to MaterialTheme.colorScheme.error
        b.overAlert -> "${(b.fraction * 100).toInt()}% used" to m.warning
        b.aheadOfPace -> "Ahead of pace" to m.warning
        else -> "On track" to m.income
    }
}

@Composable
internal fun UpcomingRow(u: Upcoming, lookup: Lookup, onAdd: () -> Unit, onSkip: () -> Unit) {
    val (icon, color) = if (u.rule.type == "transfer") "transfer" to null else lookup.categoryLook(u.rule.categoryId)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, color, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(u.rule.name ?: u.rule.payee ?: lookup.categoryName(u.rule.categoryId), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val auto = if (u.rule.mode == "auto") " · automatic" else ""
            Text(
                (if (u.due) "Due " else "") + Dates.relativeDay(u.date) + auto, style = MaterialTheme.typography.bodySmall,
                color = if (u.due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (u.due && u.rule.mode == "remind") {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(if (u.rule.type == "expense") -u.rule.amount else u.rule.amount, lookup.currencyOf(u.rule.accountId), signed = u.rule.type != "transfer", style = MaterialTheme.typography.labelLarge)
                Row {
                    TextButton(onClick = onSkip, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Skip") }
                    FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Pay") }
                }
            }
        } else {
            MoneyText(if (u.rule.type == "expense") -u.rule.amount else u.rule.amount, lookup.currencyOf(u.rule.accountId), signed = u.rule.type != "transfer")
        }
    }
}

/** "₹4,200 left · ₹466/day for 9 days" or "Over by ₹500". */
fun budgetLine(b: BudgetStatus): String {
    val cur = b.budget.currency
    return when {
        b.over -> "Over by ${app.kosh.core.Money.ui(-b.remaining, cur)}"
        b.daysLeft > 0 -> "${app.kosh.core.Money.ui(b.remaining, cur)} left · ${app.kosh.core.Money.ui(b.perDayLeft, cur)}/day for ${app.kosh.core.plural(b.daysLeft, "day")}"
        else -> "${app.kosh.core.Money.ui(b.remaining, cur)} left"
    }
}

/** "Type it: 450 lunch": opens the one-line entry, starts listening straight away, or scans a receipt. */
@Composable
private fun QuickEntryBar(onType: () -> Unit, onSpeak: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = app.kosh.ui.rememberSafeScope()
    val canSpeak = remember { app.kosh.ui.speechAvailable(context) }
    var cameraUri by remember { androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null) }
    // A receipt photo goes into a new expense, which reads it and fills itself in.
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) scope.launch {
            val stored = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.repo.blobs.import(uri, "receipt.jpg") }
            app.kosh.SharedInbox.pending = app.kosh.SharedInbox.Shared(payee = null, note = null, file = stored)
            nav.navigate(Routes.editTx())
        }
    }
    fun scan() {
        val f = java.io.File(java.io.File(context.cacheDir, "camera").apply { mkdirs() }, "receipt-${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        cameraUri = uri
        runCatching { camera.launch(uri) }
    }
    androidx.compose.material3.Surface(
        onClick = onType,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .spokenAs("Quick add. Type or say one line, like 450 lunch."),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                "Type it: 450 lunch yesterday", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (c.receipts.available) IconButton(onClick = ::scan) { Icon(Icons.Rounded.DocumentScanner, "Scan a receipt", tint = MaterialTheme.colorScheme.primary) }
            if (canSpeak) IconButton(onClick = onSpeak) { Icon(Icons.Rounded.Mic, "Say it", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun Modifier.spokenAs(label: String) = this.then(
    Modifier.semantics(mergeDescendants = true) { contentDescription = label },
)

/**
 * At most one small to-do on Home, most useful first: payments found in alerts, entries without a category,
 * then an account whose balance has not been checked against the bank for a month.
 */
@Composable
private fun HomeTodo() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = app.kosh.ui.rememberSafeScope()
    val found by c.captures.items.collectAsStateWithLifecycle()
    val uncategorized by remember { c.repo.uncategorized(30) }.collectAsStateWithLifecycle(emptyList())
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var toCheck by remember { androidx.compose.runtime.mutableStateOf<app.kosh.data.db.AccountEntity?>(null) }
    var fix by remember { androidx.compose.runtime.mutableStateOf<app.kosh.data.db.AccountEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { toCheck = app.kosh.work.WeeklyCheckIn.accountToCheck(c) }
    when {
        found.any { it.state == "new" } -> FoundPaymentsBanner(onOpen = { nav.navigate(FOUND_PAYMENTS_ROUTE) })
        uncategorized.isNotEmpty() -> KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = { nav.navigate(Routes.WEEKLY) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Category, MaterialTheme.colorScheme.tertiary, size = 38.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("${app.kosh.core.plural(uncategorized.size, "entry", "entries")} without a category", style = MaterialTheme.typography.titleSmall)
                    Text("Sort them in a few taps so reports stay right", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                app.kosh.ui.components.StatusPill("Sort", MaterialTheme.colorScheme.tertiary)
            }
        }
        toCheck != null -> {
            val a = toCheck!!
            BalanceCheckCard(a, lookup.balances[a.id] ?: a.openingBalance,
                onYes = { c.settings.setBalanceChecked(a.id); toCheck = null },
                onFix = { fix = a }, modifier = Modifier.padding(vertical = 0.dp))
        }
    }
    fix?.let { a ->
        ReconcileDialog(a, lookup.balances[a.id] ?: a.openingBalance, onDismiss = { fix = null }) { actual ->
            scope.launch {
                c.repo.reconcile(a, lookup.balances[a.id] ?: a.openingBalance, actual)
                c.settings.setBalanceChecked(a.id)
                toCheck = null
            }
        }
    }
}
