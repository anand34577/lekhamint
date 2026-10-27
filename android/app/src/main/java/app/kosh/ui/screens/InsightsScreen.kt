package app.kosh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kosh.AppContainer
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.db.LoanEntity
import app.kosh.data.db.RecurringRuleEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.CashForecast
import app.kosh.domain.Forecast
import app.kosh.domain.FlowEvent
import app.kosh.domain.Insight
import app.kosh.domain.Insights
import app.kosh.domain.Loans
import app.kosh.domain.Lookup
import app.kosh.domain.RecurringEngine
import app.kosh.domain.Reports
import app.kosh.domain.SafeBasis
import app.kosh.domain.SafeToSpend
import app.kosh.domain.Subscription
import app.kosh.domain.Subscriptions
import app.kosh.domain.Tone
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.Routes
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.ForecastChart
import app.kosh.ui.components.HeroCard
import app.kosh.ui.components.IconTile
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalHideAmounts
import app.kosh.ui.components.LocalOnHero
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.RankedBars
import app.kosh.ui.components.SectionCard
import app.kosh.ui.components.StatTile
import app.kosh.ui.components.StatusPill
import app.kosh.ui.components.chartPalette
import app.kosh.ui.koshViewModel
import app.kosh.ui.theme.LocalMoneyColors
import app.kosh.ui.todayFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class InsightsState(
    val loaded: Boolean = false,
    val currency: String = "USD",
    /** Cash, bank and wallet accounts the forecast follows. */
    val cashAccounts: Int = 0,
    val cash: Long = 0,
    val safe: SafeToSpend? = null,
    val forecast: Forecast? = null,
    val horizon: Int = 60,
    val events: List<FlowEvent> = emptyList(),
    val everydayPerDay: Long = 0,
    val historyDays: Int = 0,
    val insights: List<Insight> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val lookup: Lookup = Lookup(),
)

/**
 * Known money movements from [today] to [until] in the main currency that touch the given cash accounts:
 * income and bills from Bills & recurring, and loan EMIs. Bills overdue by more than a week are left out
 * (they were most likely paid without being marked).
 */
internal fun knownEvents(
    engine: RecurringEngine, rules: List<RecurringRuleEntity>, loans: List<LoanEntity>, loanMoves: List<TransactionEntity>,
    lookup: Lookup, cashIds: Set<String>, today: LocalDate, until: LocalDate,
): List<FlowEvent> {
    val base = lookup.baseCurrency
    val out = ArrayList<FlowEvent>()
    val oldest = today.minusDays(7)
    for (r in rules) {
        if (r.deleted || r.paused || lookup.currencyOf(r.accountId) != base) continue
        val title = r.name ?: r.payee ?: "Bill"
        val amount = when (r.type) {
            "income" -> if (r.accountId in cashIds) r.amount else null
            "expense" -> if (r.accountId in cashIds) -r.amount else null
            // Moving money out to a card, loan or investment uses cash; the reverse brings it back.
            "transfer" -> when {
                r.accountId in cashIds && r.toAccountId !in cashIds -> -r.amount
                r.accountId !in cashIds && r.toAccountId in cashIds -> r.toAmount ?: r.amount
                else -> null
            }
            else -> null
        } ?: continue
        for (d in engine.dueDates(r, until)) if (!d.isBefore(oldest)) out += FlowEvent(d, amount, title)
    }
    for (l in loans) {
        if (l.deleted || l.closed || l.currency != base) continue
        if (l.payFromAccountId != null && l.payFromAccountId !in cashIds) continue
        val st = Loans.status(l, loanMoves.filter { it.loanId == l.id }, lookup.balances[l.accountId] ?: 0, today)
        for (row in st.projection.rows) {
            if (row.date.isAfter(until)) break
            if (!row.date.isBefore(oldest)) out += FlowEvent(row.date, -row.payment, "${l.name} EMI")
        }
    }
    return out.sortedBy { it.date }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(private val c: AppContainer) : ViewModel() {
    val horizon = MutableStateFlow(60)
    private val hiddenVersion = MutableStateFlow(0)

    val state: StateFlow<InsightsState> = todayFlow().flatMapLatest { today ->
        val month = YearMonth.from(today)
        val prev = month.minusMonths(1)
        combine(
            combine(c.repo.transactionsBetween(today.minusDays(400), today), c.repo.reportRows(prev.atDay(1), today), ::Pair),
            c.lookup,
            combine(c.repo.recurringRules, c.repo.loans, c.repo.loanMoves, app.kosh.ui.budgetStatuses(c)) { r, l, mv, b -> Triple(r, l, mv) to b },
            horizon,
            hiddenVersion,
        ) { (txs, rows), lookup, (plans, budgets), h, _ ->
            val (rules, loans, moves) = plans
            val base = lookup.baseCurrency
            val cash = lookup.accounts.filter {
                !it.deleted && !it.archived && it.includeInNetWorth && it.currency == base && !Lookup.isLiability(it.type) && !Lookup.isInvestment(it.type)
            }
            val cashIds = cash.map { it.id }.toSet()
            val start = cash.sumOf { lookup.worth(it) }

            // Everyday spending: up to 90 days in the main currency that no bill, EMI or deposit already covers.
            val firstSeen = txs.minOfOrNull { it.localDate }?.let(LocalDate::parse) ?: today
            val historyDays = ChronoUnit.DAYS.between(firstSeen, today).toInt().coerceIn(14, 90)
            val from = today.minusDays(historyDays - 1L)
            val totals = HashMap<LocalDate, Long>()
            for (t in txs) {
                if (!CashForecast.isEveryday(t) || lookup.currencyOf(t.accountId) != base) continue
                val d = LocalDate.parse(t.localDate)
                if (d.isBefore(from)) continue
                totals[d] = (totals[d] ?: 0L) + t.amount
            }
            val spend = CashForecast.dailySpend(totals, from, historyDays)
            val events = knownEvents(c.recurring, rules, loans, moves, lookup, cashIds, today, today.plusDays(maxOf(h, 40).toLong()))
            val forecast = CashForecast.forecast(start, events, spend, today, h)

            // Insights: this month so far against the same days of last month.
            val day = today.dayOfMonth
            val prevEnd = prev.atDay(minOf(day, prev.lengthOfMonth()))
            val nowRows = rows.filter { LocalDate.parse(it.localDate) >= month.atDay(1) }
            val prevRows = rows.filter { LocalDate.parse(it.localDate).let { d -> d < month.atDay(1) && d <= prevEnd } }
            val now = Reports.summarize(nowRows, lookup.categoryById, base)[base]
            val before = Reports.summarize(prevRows, lookup.categoryById, base)[base]
            val insights = Insights.of(
                now?.expenseByCategory ?: emptyMap(), before?.expenseByCategory ?: emptyMap(),
                now?.income ?: 0, now?.expense ?: 0, before?.expense ?: 0, day, lookup::categoryName,
            )

            // Safe to spend: an overall budget for this month first, then this month's income, then cash.
            val overall = budgets.firstOrNull {
                it.budget.categoryIds.isEmpty() && it.budget.accountIds.isEmpty() && it.budget.currency == base && it.window.contains(today) &&
                    it.window.end == month.atEndOfMonth()
            }
            val incomeLeft = now?.takeIf { it.income > 0 }?.let { it.income - it.expense }
            val safe = CashForecast.safe(overall?.remaining, incomeLeft, start, events, spend, today)

            val ruled = rules.filter { !it.deleted && it.payee != null }.map { Subscriptions.payeeKey(it.payee!!) }.toSet()
            val subs = Subscriptions.detect(
                txs.filter { lookup.currencyOf(it.accountId) == base && it.loanId == null && it.depositId == null && it.debtId == null && !it.isAdjustment },
                today, ruled,
            ).filter { !c.settings.flag("sub.${it.key}") }

            InsightsState(
                loaded = true, currency = base, cashAccounts = cash.size, cash = start, safe = safe, forecast = forecast, horizon = h,
                events = events.filter { !it.date.isAfter(today.plusDays(h.toLong())) }, everydayPerDay = spend.mean.toLong(), historyDays = historyDays,
                insights = insights, subscriptions = subs, lookup = lookup,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InsightsState())

    fun hide(s: Subscription) {
        c.settings.setFlag("sub.${s.key}")
        hiddenVersion.value++
    }

    /** Adds the repeat payment to Bills & recurring with a reminder before it is due. */
    fun track(s: Subscription, lookup: Lookup, done: () -> Unit) = viewModelScope.launch {
        val account = lookup.accountById[s.accountId]?.takeIf { !it.deleted && !it.archived } ?: lookup.activeAccounts.firstOrNull() ?: return@launch
        c.repo.saveRule(
            RecurringRuleEntity(
                id = Ids.newV7(), name = s.payee, type = "expense", accountId = account.id, amount = s.amount, categoryId = s.categoryId,
                payee = s.payee, frequency = s.cadence.ruleFrequency, every = s.cadence.ruleEvery, startDate = s.nextDate.toString(),
                nextDate = s.nextDate.toString(), mode = "remind", createdAt = System.currentTimeMillis(),
            ),
        )
        done()
    }
}

@Composable
fun InsightsScreen() {
    val vm = koshViewModel { InsightsViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = app.kosh.ui.rememberSafeScope()

    Scaffold(topBar = { KoshTopBar("Insights", onBack = { nav.popBackStack() }) }) { padding ->
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "What's coming and what stands out, worked out on this phone from your own records.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (!s.loaded) return@LazyColumn
            if (s.cashAccounts == 0) {
                item {
                    EmptyState(
                        Icons.Rounded.AccountBalanceWallet, "No cash or bank accounts",
                        "Safe to spend and the forecast follow cash, bank and wallet accounts in ${s.currency}. Add one to see where your money is heading.",
                    ) { FilledTonalButton(onClick = { nav.navigate(Routes.editAccount()) }) { Text("Add an account") } }
                }
            } else {
                s.safe?.let { item { SafeToSpendCard(it, s.currency) } }
            }
            if (s.insights.isNotEmpty()) {
                item {
                    SectionCard("What stands out", subtitle = "This month so far, against the same days last month") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (i in s.insights) InsightRow(i)
                        }
                    }
                }
            }
            if (s.cashAccounts > 0) s.forecast?.let { f -> item { ForecastCard(s, f, onHorizon = { vm.horizon.value = it }, onRecurring = { nav.navigate(Routes.RECURRING) }) } }
            item {
                SubscriptionsCard(
                    s,
                    onTrack = { sub -> vm.track(sub, s.lookup) { scope.launch { snackbar.showSnackbar("${sub.payee} is now in Bills & recurring. You'll be reminded before it's due.") } } },
                    onHide = vm::hide,
                )
            }
        }
        }
    }
}

/** The headline: what can be spent each day until payday (or month end) without missing a known bill. */
@Composable
private fun SafeToSpendCard(safe: SafeToSpend, cur: String) {
    val m = LocalMoneyColors.current
    HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val on = LocalOnHero.current
        Text("Safe to spend", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.85f))
        Row(verticalAlignment = Alignment.Bottom) {
            MoneyText(whole(safe.perDay, cur), cur, style = MaterialTheme.typography.displaySmall, color = on)
            Text(" a day", style = MaterialTheme.typography.titleMedium, color = on.copy(alpha = 0.85f), modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            "for ${safe.days} ${if (safe.days == 1) "day" else "days"} until ${if (safe.toPayday) "the day before payday" else "month end"} (${Dates.short(safe.until)}), ${safe.basis.label}",
            style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(14.dp))
        // The sum, one part at a time: what you have, what's already promised, what's left.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(safe.basis.startLabel, Modifier.weight(1f), icon = Icons.Rounded.AccountBalanceWallet, onHero = true) {
                MoneyText(safe.start, cur, style = MaterialTheme.typography.titleSmall, color = on)
            }
            StatTile("Bills due", Modifier.weight(1f), icon = Icons.Rounded.NorthEast, onHero = true) {
                MoneyText(safe.billsOut, cur, style = MaterialTheme.typography.titleSmall, color = on)
            }
            if (safe.incomeIn > 0) {
                StatTile("Coming in", Modifier.weight(1f), icon = Icons.Rounded.SouthWest, onHero = true) {
                    MoneyText(safe.incomeIn, cur, style = MaterialTheme.typography.titleSmall, color = on, signed = true)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Free to spend", style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            MoneyText(safe.free, cur, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = on)
        }
        if (safe.usualPerDay > 0 && safe.free > 0) {
            // Usual pace against the safe pace, as two bars.
            val top = maxOf(safe.usualPerDay, safe.perDay).toFloat()
            Spacer(Modifier.height(12.dp))
            PaceBar("Safe pace", safe.perDay / top, on.copy(alpha = 0.95f), on)
            Spacer(Modifier.height(6.dp))
            PaceBar("Your usual", safe.usualPerDay / top, if (safe.usualPerDay > safe.perDay) m.warning else on.copy(alpha = 0.55f), on)
            Text(
                if (safe.usualPerDay > safe.perDay) "You usually spend ${Money.ui(whole(safe.usualPerDay, cur), cur)} a day. Slow down a little to stay clear of bills."
                else "You usually spend ${Money.ui(whole(safe.usualPerDay, cur), cur)} a day, within the safe pace.",
                style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp),
            )
        } else if (safe.free <= 0) {
            Text(
                when (safe.basis) {
                    app.kosh.domain.SafeBasis.BUDGET -> "This month's budget is used up once the bills still due are paid."
                    app.kosh.domain.SafeBasis.INCOME -> "This month's income is used up once the bills still due are paid."
                    app.kosh.domain.SafeBasis.CASH -> "Bills due before ${Dates.short(safe.until)} are more than the money in cash and bank accounts. Move money in or put off a payment."
                },
                style = MaterialTheme.typography.bodySmall, color = on, modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (safe.cappedByCash) {
            Text(
                "Limited by the money in your cash and bank accounts.",
                style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Rounds down to whole currency units, for per-day figures. */
private fun whole(minor: Long, currency: String): Long {
    var f = 1L
    repeat(app.kosh.core.Currencies.digits(currency)) { f *= 10 }
    return minor / f * f
}

@Composable
private fun PaceBar(label: String, fraction: Float, color: Color, on: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = on.copy(alpha = 0.85f), modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(on.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

@Composable
private fun InsightRow(i: Insight) {
    val m = LocalMoneyColors.current
    val (icon, tint) = when (i.tone) {
        Tone.WARN -> (if (i.title.contains("up")) Icons.Rounded.TrendingUp else Icons.Rounded.WarningAmber) to m.expense
        Tone.GOOD -> (if (i.title.contains("down")) Icons.Rounded.TrendingDown else Icons.Rounded.ThumbUp) to m.income
        Tone.INFO -> Icons.Rounded.Lightbulb to MaterialTheme.colorScheme.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint, size = 38.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(i.title, style = MaterialTheme.typography.titleSmall)
            Text(i.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ForecastCard(s: InsightsState, f: Forecast, onHorizon: (Int) -> Unit, onRecurring: () -> Unit) {
    val cur = s.currency
    val end = f.points.last()
    SectionCard("Cash forecast", subtitle = "Where your cash and bank balance is heading") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(30, 60, 90).forEachIndexed { i, d ->
                SegmentedButton(s.horizon == d, { onHorizon(d) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text("$d days") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Now", Modifier.weight(1f)) { MoneyText(s.cash, cur, style = MaterialTheme.typography.titleSmall) }
            StatTile("In ${s.horizon} days", Modifier.weight(1f)) { MoneyText(end.expected, cur, style = MaterialTheme.typography.titleSmall) }
            StatTile("Lowest", Modifier.weight(1f), tint = if (f.lowest.expected < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                MoneyText(f.lowest.expected, cur, style = MaterialTheme.typography.titleSmall, color = if (f.lowest.expected < 0) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        }
        f.shortBy?.let { d ->
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Cash could run short around ${Dates.short(d)} if spending stays at its usual level.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ForecastChart(
            f.points.map { it.date }, f.points.map { it.expected }, f.points.map { it.low }, f.points.map { it.high }, cur,
            "Cash and bank balance over the next ${s.horizon} days, likely ${Money.ui(end.expected, cur)}",
        )
        Spacer(Modifier.height(14.dp))
        Text("How it's worked out", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Step(1, "Start from today's cash, bank and wallets", "${s.cashAccounts} ${if (s.cashAccounts == 1) "account" else "accounts"}", s.cash, cur)
        Step(2, "Add income, take off bills and EMIs on their dates", "${s.events.size} known ${if (s.events.size == 1) "item" else "items"}", f.knownIn - f.knownOut, cur, signed = true)
        Step(3, "Take off everyday spending at your usual pace", "About ${Money.ui(whole(s.everydayPerDay, cur), cur)} a day, from the last ${s.historyDays} days", -f.everyday, cur, signed = true)
        Step(4, "Show a range, not one number", "The shaded band is where 8 in 10 months would land. It widens the further ahead you look.", null, cur)
        if (s.events.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Known items", style = MaterialTheme.typography.titleSmall)
            for (e in s.events.take(6)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.short(e.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                    Text(e.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    MoneyText(e.amount, cur, style = MaterialTheme.typography.labelLarge, signed = true, color = if (e.amount > 0) LocalMoneyColors.current.income else Color.Unspecified)
                }
            }
            if (s.events.size > 6) TextButton(onClick = onRecurring) { Text("See all bills and repeats") }
        } else {
            Text(
                "Add your salary and regular bills in Bills & recurring for a sharper forecast.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onRecurring) { Text("Open Bills & recurring") }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, body: String, value: Long?, cur: String, signed: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) MoneyText(value, cur, style = MaterialTheme.typography.labelLarge, signed = signed, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionsCard(s: InsightsState, onTrack: (Subscription) -> Unit, onHide: (Subscription) -> Unit) {
    val cur = s.currency
    val m = LocalMoneyColors.current
    val palette = chartPalette()
    val active = s.subscriptions.filter { !it.stale }
    val yearly = active.sumOf { it.yearly }
    SectionCard("Subscriptions and repeat payments", subtitle = "Found in your past spending") {
        if (s.subscriptions.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Autorenew, MaterialTheme.colorScheme.primary, size = 38.dp)
                Text(
                    "Nothing found yet. Payments to the same payee at a steady interval and a similar amount show up here, so you can spot price rises and cancel what you don't use.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp),
                )
            }
            return@SectionCard
        }
        if (active.isNotEmpty()) {
            Text("These cost you about", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                MoneyText(yearly, cur, style = MaterialTheme.typography.headlineSmall)
                Text(" a year", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(
                "${Money.ui(yearly / 12, cur)} a month across ${active.size} ${if (active.size == 1) "payment" else "payments"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (active.size > 1) RankedBars(active.take(6).mapIndexed { i, x -> Triple(x.payee, x.yearly, palette[i % palette.size]) }, cur, total = yearly)
            Spacer(Modifier.height(14.dp))
        }
        for (x in s.subscriptions) {
            KoshCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(x.payee, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                        MoneyText(x.amount, cur, style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(x.cadence.label, MaterialTheme.colorScheme.primary, icon = Icons.Rounded.Autorenew)
                        x.priceChange?.let { (from, to) ->
                            if (!LocalHideAmounts.current) StatusPill(
                                "was ${Money.ui(from, cur)}", if (to > from) m.expense else m.income,
                                icon = if (to > from) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown,
                            )
                        }
                        if (x.stale) StatusPill("Stopped?", MaterialTheme.colorScheme.onSurfaceVariant)
                        else StatusPill("Next ~${Dates.short(x.nextDate)}", m.transfer, icon = Icons.Rounded.CalendarMonth)
                    }
                    Text(
                        "${x.count} payments · last ${Dates.short(x.lastDate)}" +
                            (x.categoryId?.let { " · ${s.lookup.categoryName(it)}" } ?: "") +
                            (if (!LocalHideAmounts.current) " · ${Money.ui(x.yearly, cur)} a year" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onHide(x) }) { Text("Not a repeat") }
                        if (!x.stale) TextButton(onClick = { onTrack(x) }) { Text("Remind me") }
                    }
                }
            }
        }
        Text(
            "Found from the last 13 months: at least 3 payments to the same payee at a steady interval. Ones already in Bills & recurring are left out.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A small card for Home: safe to spend per day and the top thing that stands out. Opens Insights. */
@Composable
fun InsightsTeaser(onOpen: () -> Unit) {
    val vm = koshViewModel(key = "insights-teaser") { InsightsViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val safe = s.safe
    if (!s.loaded || s.cashAccounts == 0 || safe == null) return
    val m = LocalMoneyColors.current
    val top = s.insights.firstOrNull()
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onOpen) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Safe to spend", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (safe.free > 0) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            MoneyText(whole(safe.perDay, s.currency), s.currency, style = MaterialTheme.typography.headlineSmall)
                            Text(" a day", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Text(
                            "until ${Dates.short(safe.until)}, ${safe.basis.label}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Nothing spare: say by how much and why, instead of a bare "0 a day".
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (safe.free < 0) {
                                MoneyText(-safe.free, s.currency, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                                Text(" short", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                            } else {
                                Text("Nothing spare", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        val until = Dates.short(safe.until)
                        Text(
                            when {
                                safe.cappedByCash -> "Bills due by $until need more than your cash and bank accounts hold"
                                safe.billsOut > 0 && safe.start > 0 -> "Bills due by $until (${Money.ui(safe.billsOut, s.currency)}) need more than the ${Money.ui(safe.start, s.currency)} ${if (safe.basis == SafeBasis.BUDGET) "left in your budget" else "left this month"}"
                                safe.basis == SafeBasis.BUDGET -> "Your monthly budget is used up until $until"
                                else -> "This month's spending has passed its income"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val usual = safe.usualPerDay
                val ok = safe.free > 0 && (usual == 0L || usual <= safe.perDay)
                StatusPill(if (ok) "On track" else if (safe.free <= 0) "Over" else "Go easy", if (ok) m.income else if (safe.free <= 0) MaterialTheme.colorScheme.error else m.warning)
            }
            if (safe.free <= 0) {
                // Short: offer the next step for the cause, not only the bad news.
                val nav = app.kosh.ui.LocalNav.current
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (safe.billsOut > 0) androidx.compose.material3.AssistChip(onClick = { nav.navigate(app.kosh.ui.Routes.RECURRING) }, label = { Text("Bills due") })
                    if (safe.cappedByCash) androidx.compose.material3.AssistChip(onClick = { nav.navigate(app.kosh.ui.Routes.editTx(type = "transfer")) }, label = { Text("Move money in") })
                    else if (safe.basis == SafeBasis.BUDGET) androidx.compose.material3.AssistChip(onClick = { nav.navigate(app.kosh.ui.Routes.BUDGETS) { popUpTo(app.kosh.ui.Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true } }, label = { Text("Review budget") })
                }
            }
            if (top != null) {
                Spacer(Modifier.height(12.dp))
                InsightRow(top)
            }
            s.subscriptions.firstOrNull { it.priceChange != null && it.priceChange.second > it.priceChange.first && !it.stale }?.let { x ->
                Spacer(Modifier.height(10.dp))
                InsightRow(Insight(Tone.WARN, "${x.payee} costs more now", "Was ${Money.ui(x.priceChange!!.first, s.currency)}, now ${Money.ui(x.priceChange.second, s.currency)}."))
            }
        }
    }
}
