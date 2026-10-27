package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import app.kosh.ui.components.LegendDot
import app.kosh.ui.components.ProgressRing
import app.kosh.ui.components.RankedBars
import app.kosh.ui.components.SectionCard
import app.kosh.ui.components.SpendCalendar
import app.kosh.ui.components.WeekdayBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kosh.AppContainer
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.data.db.ReportRow
import app.kosh.domain.CurrencySummary
import app.kosh.domain.Fx
import app.kosh.domain.Lookup
import app.kosh.domain.NatureSplit
import app.kosh.domain.Reports
import app.kosh.domain.TagSpend
import app.kosh.domain.byTag
import app.kosh.ui.LocalNav
import app.kosh.ui.components.BarPoint
import app.kosh.ui.components.DonutChart
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.IncomeExpenseBars
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalHideAmounts
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.Slice
import app.kosh.ui.components.StackedBar
import app.kosh.ui.components.parseColor
import app.kosh.ui.components.typeColor
import app.kosh.ui.koshViewModel
import app.kosh.ui.theme.LocalMoneyColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

enum class ReportSpan { MONTH, YEAR }

data class ReportQuery(val span: ReportSpan = ReportSpan.MONTH, val month: YearMonth = YearMonth.now(), val currency: String? = null) {
    companion object { const val ALL = "*" }
}

data class ReportState(
    val query: ReportQuery = ReportQuery(),
    val lookup: Lookup = Lookup(),
    val currencies: List<String> = emptyList(),
    val currency: String = "USD",
    val summary: CurrencySummary? = null,
    val trend: List<BarPoint> = emptyList(),
    val nature: NatureSplit = NatureSplit(0, 0, 0, 0),
    val payees: List<Pair<String, Long>> = emptyList(),
    val tags: List<TagSpend> = emptyList(),
    val rows: List<ReportRow> = emptyList(),
    /** True when every currency was converted into the base one with your exchange rates. */
    val converted: Boolean = false,
    /** Currencies left out of a converted view because no rate was entered. */
    val missing: List<String> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow(ReportQuery())

    val state: StateFlow<ReportState> = query.flatMapLatest { q ->
        val (from, to) = range(q)
        // Trend: 6 months up to the selected month, or the 12 months of the selected year.
        val trendMonths = if (q.span == ReportSpan.MONTH) (5 downTo 0).map { q.month.minusMonths(it.toLong()) }
        else (1..12).map { YearMonth.of(q.month.year, it) }
        val trendFrom = trendMonths.first().atDay(1)
        combine(c.repo.reportRows(minOf(from, trendFrom), maxOf(to, trendMonths.last().atEndOfMonth())), c.lookup) { raw, lookup ->
            val currencies = (listOf(lookup.baseCurrency) + Reports.summarize(raw.filter { LocalDate.parse(it.localDate) in from..to }, lookup.categoryById, lookup.baseCurrency).keys).distinct()
            // "All together": each amount is converted on its own date with the newest rate entered up to then.
            val together = q.currency == ReportQuery.ALL && currencies.size > 1
            val missing = linkedSetOf<String>()
            val all = if (!together) raw else raw.mapNotNull { r ->
                val from = r.currency ?: lookup.baseCurrency
                if (from == lookup.baseCurrency) r
                else Fx.find(lookup.rates, from, lookup.baseCurrency, r.localDate)?.let { rate -> r.copy(amount = Fx.convert(r.amount, from, lookup.baseCurrency, rate), currency = lookup.baseCurrency) }
                    ?: run { missing += from; null }
            }
            val rows = all.filter { LocalDate.parse(it.localDate) in from..to }
            val byCur = Reports.summarize(rows, lookup.categoryById, lookup.baseCurrency)
            val cur = if (together) lookup.baseCurrency else q.currency?.takeIf { it in currencies } ?: lookup.baseCurrency
            val summary = byCur[cur]
            val trend = Reports.trend(all, lookup.categoryById, cur, lookup.baseCurrency, trendMonths)
                .map { BarPoint(Dates.shortMonth(it.month), it.income, it.expense) }
            ReportState(
                q, lookup, currencies, cur, summary, trend,
                Reports.natureSplit(summary, rows, lookup.categoryById, lookup.baseCurrency),
                Reports.topPayees(rows, lookup.categoryById, cur, lookup.baseCurrency),
                Reports.byTag(rows, lookup.categoryById, cur, lookup.baseCurrency), rows, together, missing.toList(), loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportState())

    fun shift(by: Long) {
        val q = query.value
        query.value = q.copy(month = if (q.span == ReportSpan.MONTH) q.month.plusMonths(by) else q.month.plusYears(by))
    }

    companion object {
        fun range(q: ReportQuery): Pair<LocalDate, LocalDate> = when (q.span) {
            ReportSpan.MONTH -> q.month.atDay(1) to q.month.atEndOfMonth()
            ReportSpan.YEAR -> Year.of(q.month.year).atDay(1) to Year.of(q.month.year).atMonth(12).atEndOfMonth()
        }
    }
}

@Composable
fun ReportsScreen() {
    val vm = koshViewModel { ReportsViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val q = s.query
    var expanded by remember { mutableStateOf<String?>(null) }
    var slice by remember(s.summary) { mutableStateOf<Int?>(null) }
    val hidden = LocalHideAmounts.current
    val money = LocalMoneyColors.current

    Scaffold(topBar = { KoshTopBar("Reports", onBack = { nav.popBackStack() }) }) { padding ->
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(q.span == ReportSpan.MONTH, { vm.query.value = q.copy(span = ReportSpan.MONTH) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Month") }
                        SegmentedButton(q.span == ReportSpan.YEAR, { vm.query.value = q.copy(span = ReportSpan.YEAR) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Year") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous") }
                        Text(if (q.span == ReportSpan.MONTH) Dates.month(q.month) else q.month.year.toString(), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { vm.shift(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next") }
                    }
                    if (s.currencies.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(s.converted, { vm.query.value = q.copy(currency = ReportQuery.ALL) }, { Text("All in ${s.lookup.baseCurrency}") })
                            for (cur in s.currencies) FilterChip(!s.converted && s.currency == cur, { vm.query.value = q.copy(currency = cur) }, { Text(cur) })
                        }
                        if (s.converted) Text(
                            if (s.missing.isEmpty()) "Everything is converted into ${s.lookup.baseCurrency} with the rates you entered."
                            else "Left out, no exchange rate: ${s.missing.joinToString(", ")}. Add one under More > Exchange rates.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            val summary = s.summary
            if (s.loaded && (summary == null || (summary.income == 0L && summary.expense == 0L))) {
                item { EmptyState(Icons.Rounded.Insights, "Nothing to report", "No income or spending in this period.") }
            }
            if (summary != null && (summary.income != 0L || summary.expense != 0L)) {
                // 1. The headline: how much came in, went out, and what share was kept.
                item {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val rate = if (summary.income > 0) summary.net.toFloat() / summary.income else 0f
                            ProgressRing(
                                rate.coerceAtLeast(0f), if (summary.net >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                size = 92.dp, thickness = 10.dp, description = "Kept ${(rate * 100).toInt()} percent of income",
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (summary.income > 0) "${(rate * 100).toInt()}%" else "–", style = MaterialTheme.typography.titleLarge)
                                    Text("kept", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FigureRow("Income", summary.income, s.currency, money.income)
                                FigureRow("Spent", summary.expense, s.currency, money.expense)
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                FigureRow(if (summary.net >= 0) "Saved" else "Overspent", kotlin.math.abs(summary.net), s.currency, if (summary.net >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // 2. Where it went: a tappable donut over the category list.
                val spend = summary.expenseByCategory.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                if (spend.isNotEmpty()) {
                    item {
                        val fallback = MaterialTheme.colorScheme.primary
                        val slices = spend.map { (id, v) -> Slice(v, parseColor(s.lookup.categoryLook(id).second, fallback), s.lookup.categoryName(id)) }
                        val desc = slices.take(5).joinToString { "${it.label} ${pct(it.value, summary.expense)}%" }
                        SectionCard("Spending by category", subtitle = "Tap a slice or a row for details") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                DonutChart(slices, "Spending by category: $desc", selected = slice, onSelect = { slice = it; expanded = it?.let { i -> spend[i].key } }) {
                                    val sel = slice?.let(slices::getOrNull)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
                                        Text(sel?.label ?: "Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        MoneyText(sel?.value ?: summary.expense, s.currency, style = MaterialTheme.typography.titleMedium)
                                        if (sel != null && !hidden) Text("${pct(sel.value, summary.expense)}%", style = MaterialTheme.typography.labelMedium, color = sel.color)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            spend.forEachIndexed { i, (id, v) ->
                                CategoryShareRow(id, v, summary.expense, s, id != null && expanded == id) {
                                    expanded = if (expanded == id) null else id
                                    slice = if (expanded == null) null else i
                                }
                            }
                        }
                    }
                }

                // 3. When it went: the calendar (month) and the weekday pattern.
                val perDay = dailySpend(s)
                if (q.span == ReportSpan.MONTH && perDay.isNotEmpty()) {
                    item {
                        SectionCard("Spending calendar", subtitle = "Darker days cost more") {
                            SpendCalendar(q.month, perDay, s.currency)
                        }
                    }
                }
                if (perDay.size >= 7) {
                    item {
                        // The typical day (median) for each weekday, days with nothing included: one rent or
                        // EMI day would otherwise make its weekday look like the expensive one.
                        val days = List(7) { ArrayList<Long>() }
                        val (from, to) = ReportsViewModel.range(q)
                        var d = from
                        val last = minOf(to, LocalDate.now())
                        while (!d.isAfter(last)) { days[d.dayOfWeek.value - 1] += perDay[d] ?: 0L; d = d.plusDays(1) }
                        val typical = days.map { v -> if (v.isEmpty()) 0L else v.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 } }
                        val peak = typical.indexOf(typical.max())
                        SectionCard(
                            "Which days cost the most",
                            subtitle = if (typical.max() <= 0L) "Typical spending on each weekday" else
                                "Typical spending on each weekday · most on ${java.time.DayOfWeek.of(peak + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}s",
                        ) { WeekdayBars(typical, s.currency) }
                    }
                }

                // 4. Over time.
                item {
                    SectionCard(if (q.span == ReportSpan.MONTH) "Last 6 months" else "Month by month", subtitle = "Tap a month for its figures") {
                        IncomeExpenseBars(s.trend, "Income and spending per month", currency = s.currency)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LegendDot("Income", money.income)
                            LegendDot("Spending", money.expense)
                        }
                    }
                }

                // 5. Needs, wants and savings, against the 50/30/20 guideline.
                // The 50/30/20 guide is about shares of income: needs, wants, and what is saved (kept, or
                // spent in savings & debt-payment categories). Without income it falls back to shares of spending.
                if (s.nature.total > 0) {
                    item {
                        val n = s.nature
                        val cNeed = Color(0xFF0EA5E9); val cWant = Color(0xFFF59E0B); val cSave = Color(0xFF22C55E); val cOther = Color(0xFF94A3B8)
                        val byIncome = summary.income > 0
                        val base = (if (byIncome) summary.income else n.total).toFloat()
                        // What was saved = savings-category spending plus whatever of the income was not spent at all.
                        val saved = if (byIncome) (n.savings + summary.net).coerceAtLeast(0) else n.savings
                        SectionCard(
                            "Needs, wants and savings",
                            subtitle = if (byIncome) "Share of income. A common guide: 50% needs, 30% wants, 20% saved" else "Share of spending (no income this period)",
                        ) {
                            StackedBar(listOf(n.need / base to cNeed, n.want / base to cWant, saved / base to cSave, n.unassigned / base to cOther))
                            Spacer(Modifier.height(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                NatureLine("Needs", n.need, base, cNeed, s.currency, 50, higherIsBetter = false)
                                NatureLine("Wants", n.want, base, cWant, s.currency, 30, higherIsBetter = false)
                                NatureLine(if (byIncome) "Saved" else "Savings & debt", saved, base, cSave, s.currency, 20, higherIsBetter = true)
                                if (n.unassigned > 0) NatureLine("Not set", n.unassigned, base, cOther, s.currency, null)
                            }
                            if (n.unassigned > 0) Text(
                                "Set a type (need, want or savings) on categories under More > Categories to sort the rest.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }

                if (s.payees.isNotEmpty()) {
                    item {
                        SectionCard("Top payees", subtitle = "Where most of the money went") {
                            RankedBars(s.payees.map { (p, v) -> Triple(p, v, money.expense.copy(alpha = 0.8f)) }, s.currency, total = summary.expense)
                        }
                    }
                }

                if (s.tags.isNotEmpty()) {
                    item {
                        SectionCard("By tag") {
                            RankedBars(
                                s.tags.map { t -> Triple("#" + (s.lookup.tagById[t.tagId]?.name ?: "Deleted tag") + " · ${t.count}", t.spent, parseColor(s.lookup.tagById[t.tagId]?.color, MaterialTheme.colorScheme.primary)) },
                                s.currency,
                            )
                        }
                    }
                }

                val income = summary.incomeByCategory.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                if (income.isNotEmpty()) {
                    item {
                        SectionCard("Income by source") {
                            RankedBars(
                                income.map { (id, v) -> Triple(s.lookup.categoryName(id), v, parseColor(s.lookup.categoryLook(id).second, money.income)) },
                                s.currency, total = summary.income,
                                leading = { i -> val (icon, color) = s.lookup.categoryLook(income[i].key); IconBadge(icon, color, size = 32.dp) },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/** Spending per day in the report's currency, for the calendar and weekday charts. */
private fun dailySpend(s: ReportState): Map<LocalDate, Long> {
    val out = HashMap<LocalDate, Long>()
    for (r in s.rows) {
        if ((r.currency ?: s.lookup.baseCurrency) != s.currency) continue
        val v = Reports.spending(r, s.lookup.categoryById)
        if (v != 0L) out.merge(LocalDate.parse(r.localDate), v, Long::plus)
    }
    return out.filterValues { it > 0 }
}

private fun pct(v: Long, total: Long) = if (total <= 0) 0 else (v * 100.0 / total).toInt()

@Composable
private fun FigureRow(label: String, value: Long, currency: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(value, currency, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

/** One line of the 50/30/20 card: share, the guideline beside it, and whether it is above or below. */
@Composable
private fun NatureLine(label: String, value: Long, total: Float, color: Color, currency: String, guide: Int?, higherIsBetter: Boolean = false) {
    val share = (value / total * 100).toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (guide != null) {
                val diff = share - guide
                Text(
                    when {
                        kotlin.math.abs(diff) <= 5 -> "About right (guide $guide%)"
                        (diff > 0) == higherIsBetter -> "Better than the $guide% guide by ${kotlin.math.abs(diff)} points"
                        diff > 0 -> "$diff points above the $guide% guide"
                        else -> "${-diff} points below the $guide% guide"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$share%", style = MaterialTheme.typography.titleSmall, color = color)
            MoneyText(value, currency, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CategoryShareRow(id: String?, value: Long, total: Long, s: ReportState, expanded: Boolean, onClick: () -> Unit) {
    val (icon, color) = s.lookup.categoryLook(id)
    val c = parseColor(color, MaterialTheme.colorScheme.primary)
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(if (expanded) c.copy(alpha = 0.08f) else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = id != null, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, color, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.lookup.categoryName(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MoneyText(value, s.currency, style = MaterialTheme.typography.labelLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Box(Modifier.fillMaxWidth(if (total > 0) (value.toFloat() / total).coerceIn(0.02f, 1f) else 0f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
                    }
                    if (!LocalHideAmounts.current) Text("  ${pct(value, total)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (expanded && id != null) {
            // Sub-category breakdown of this group.
            val kids = Reports.summarize(
                s.rows.filter { Reports.rootOf(it.categoryId, s.lookup.categoryById) == id },
                s.lookup.categoryById, s.lookup.baseCurrency, rollUp = false,
            )[s.currency]?.expenseByCategory.orEmpty().entries.sortedByDescending { it.value }
            for ((kid, v) in kids) {
                Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(if (kid == id) "${s.lookup.categoryName(id)} (general)" else s.lookup.categoryName(kid), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    MoneyText(v, s.currency, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
