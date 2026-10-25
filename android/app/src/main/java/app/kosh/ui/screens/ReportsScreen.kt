package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
    val hidden = LocalHideAmounts.current

    Scaffold(topBar = { KoshTopBar("Reports", onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (summary != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("Income", summary.income, s.currency, typeColor("income"), Modifier.weight(1f))
                        Stat("Spent", summary.expense, s.currency, typeColor("expense"), Modifier.weight(1f))
                        Stat("Saved", summary.net, s.currency, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    }
                    if (summary.income > 0) {
                        val rate = (summary.net * 100.0 / summary.income).toInt()
                        Text(
                            "You kept $rate% of your income.", Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val spend = summary.expenseByCategory.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                if (spend.isNotEmpty()) {
                    item { SectionHeader("Spending by category") }
                    item {
                        val fallback = MaterialTheme.colorScheme.primary
                        val slices = spend.map { (id, v) -> Slice(v, parseColor(s.lookup.categoryLook(id).second, fallback), s.lookup.categoryName(id)) }
                        val desc = slices.take(5).joinToString { "${it.label} ${pct(it.value, summary.expense)}%" }
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            DonutChart(slices, "Spending by category: $desc") {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    MoneyText(summary.expense, s.currency, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                    for ((id, v) in spend) {
                        item(key = "cat-$id") {
                            CategoryShareRow(id, v, summary.expense, s, expanded == id) { expanded = if (expanded == id) null else id }
                        }
                    }
                }

                item { SectionHeader(if (q.span == ReportSpan.MONTH) "Last 6 months" else "Month by month") }
                item {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            IncomeExpenseBars(s.trend, "Income and spending per month", currency = s.currency)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Legend("Income", LocalMoneyColors.current.income)
                                Legend("Spending", LocalMoneyColors.current.expense)
                            }
                        }
                    }
                }

                if (s.nature.total > 0) {
                    item { SectionHeader("Needs, wants and savings") }
                    item {
                        val n = s.nature
                        val t = n.total.toFloat()
                        val cNeed = Color(0xFF0EA5E9); val cWant = Color(0xFFF59E0B); val cSave = Color(0xFF22C55E); val cOther = Color(0xFF94A3B8)
                        KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                StackedBar(listOf(n.need / t to cNeed, n.want / t to cWant, n.savings / t to cSave, n.unassigned / t to cOther))
                                NatureLine("Needs", n.need, t, cNeed, s.currency, "guideline 50%")
                                NatureLine("Wants", n.want, t, cWant, s.currency, "guideline 30%")
                                NatureLine("Savings & debt", n.savings, t, cSave, s.currency, "guideline 20%")
                                if (n.unassigned > 0) NatureLine("Not set", n.unassigned, t, cOther, s.currency, null)
                            }
                        }
                    }
                }

                if (s.payees.isNotEmpty()) {
                    item { SectionHeader("Top payees") }
                    for ((p, v) in s.payees) {
                        item(key = "payee-$p") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text(p, Modifier.weight(1f))
                                MoneyText(v, s.currency)
                            }
                        }
                    }
                }

                if (s.tags.isNotEmpty()) {
                    item { SectionHeader("By tag") }
                    for (t in s.tags) {
                        item(key = "tag-${t.tagId}") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge("tag", s.lookup.tagById[t.tagId]?.color, size = 28.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text("#" + (s.lookup.tagById[t.tagId]?.name ?: "Deleted tag"))
                                    Text(
                                        if (t.count == 1) "1 transaction" else "${t.count} transactions",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MoneyText(t.spent, s.currency)
                            }
                        }
                    }
                }

                val income = summary.incomeByCategory.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                if (income.isNotEmpty()) {
                    item { SectionHeader("Income by source") }
                    for ((id, v) in income) {
                        item(key = "inc-$id") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val (icon, color) = s.lookup.categoryLook(id)
                                IconBadge(icon, color, size = 28.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(s.lookup.categoryName(id), Modifier.weight(1f))
                                if (!hidden) Text("${pct(v, summary.income)}%  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MoneyText(v, s.currency)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pct(v: Long, total: Long) = if (total <= 0) 0 else (v * 100.0 / total).toInt()

@Composable
private fun Stat(label: String, value: Long, currency: String, color: Color, modifier: Modifier) {
    KoshCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(value, currency, style = MaterialTheme.typography.titleSmall, color = color)
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).padding(0.dp)) { androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) } }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NatureLine(label: String, value: Long, total: Float, color: Color, currency: String, hint: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp)) { androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) } }
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f))
        Text("${(value / total * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        if (hint != null) Text("  $hint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        MoneyText(value, currency, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CategoryShareRow(id: String?, value: Long, total: Long, s: ReportState, expanded: Boolean, onClick: () -> Unit) {
    val (icon, color) = s.lookup.categoryLook(id)
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = id != null, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, color, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.lookup.categoryName(id))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (total > 0) value.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp),
                    color = parseColor(color, MaterialTheme.colorScheme.primary), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {},
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(value, s.currency)
                Text("${pct(value, total)}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded && id != null) {
            // Sub-category breakdown of this group.
            val kids = Reports.summarize(
                s.rows.filter { Reports.rootOf(it.categoryId, s.lookup.categoryById) == id },
                s.lookup.categoryById, s.lookup.baseCurrency, rollUp = false,
            )[s.currency]?.expenseByCategory.orEmpty().entries.sortedByDescending { it.value }
            for ((kid, v) in kids) {
                Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 20.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(if (kid == id) "${s.lookup.categoryName(id)} (general)" else s.lookup.categoryName(kid), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    MoneyText(v, s.currency, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

