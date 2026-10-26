package app.kosh.ui.screens

import app.kosh.ui.rememberSafeScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Sell
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kosh.AppContainer
import app.kosh.data.db.ReportRow
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Lookup
import app.kosh.domain.Reports
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.components.DayHeader
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.MonthSwitcher
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.PickerSheet
import app.kosh.ui.components.TransactionRow
import app.kosh.ui.components.dayNet
import app.kosh.ui.components.typeColor
import app.kosh.ui.koshViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

data class TxFilter(
    val month: YearMonth = YearMonth.now(),
    val query: String = "",
    val type: String? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val tagId: String? = null,
    /** How many search hits to load; raised by "Show more" so long histories stay reachable. */
    val searchLimit: Int = 200,
)

data class TxListState(
    val filter: TxFilter = TxFilter(),
    val items: List<TransactionEntity> = emptyList(),
    val lookup: Lookup = Lookup(),
    val income: Long = 0,
    val expense: Long = 0,
    val loaded: Boolean = false,
)

/** Same exclusions as reports: only real income and spending count in totals. */
fun TransactionEntity.toReportRow(lookup: Lookup): ReportRow? {
    if (type == "transfer" || status == "planned" || isAdjustment || excludedFromReports || debtId != null) return null
    return ReportRow(id, type, amount, categoryId, accountId, lookup.accountById[accountId]?.currency, localDate, payee, tagIds)
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TransactionsViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(TxFilter())

    private val source = combine(filter.debounce(150), c.lookup) { f, l -> f to l }.flatMapLatest { (f, _) ->
        if (f.query.isBlank()) c.repo.transactionsBetween(f.month.atDay(1), f.month.atEndOfMonth()) else c.repo.search(f.query.trim(), f.searchLimit)
    }

    val state: StateFlow<TxListState> = combine(source, filter, c.lookup) { list, f, lookup ->
        val cats = f.categoryId?.let { id -> setOf(id) + lookup.children(id).map { it.id } }
        val shown = list.filter { t ->
            (f.type == null || t.type == f.type) &&
                (f.accountId == null || t.accountId == f.accountId || t.toAccountId == f.accountId) &&
                (cats == null || t.categoryId in cats || lookup.splitCategories[t.id].orEmpty().any { it in cats }) &&
                (f.tagId == null || f.tagId in t.tagIds)
        }
        val summary = Reports.summarize(shown.mapNotNull { it.toReportRow(lookup) }, lookup.categoryById, lookup.baseCurrency)[lookup.baseCurrency]
        TxListState(f, shown, lookup, summary?.income ?: 0, summary?.expense ?: 0, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TxListState())

    fun update(block: (TxFilter) -> TxFilter) { filter.value = block(filter.value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen() {
    val vm = koshViewModel { TransactionsViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var searching by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    val f = s.filter
    val c = app.kosh.ui.components.LocalContainer.current
    val snackbar = app.kosh.ui.LocalSnackbar.current
    val scope = rememberSafeScope()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkCategory by remember { mutableStateOf(false) }
    // Only rows still on screen can stay selected (a filter or month change drops the rest).
    val chosen = s.items.filter { it.id in selected }
    // Money lent, borrowed or tied to a loan or deposit is changed from its own screen, never in bulk.
    val bulkable = chosen.filter { it.debtId == null && it.loanId == null && it.depositId == null }
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selected.isNotEmpty()) TopAppBar(
                title = { Text("${chosen.size} selected") },
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") } },
                actions = {
                    val kinds = bulkable.filter { it.type != "transfer" && !s.lookup.isSplit(it.id) }.map { it.type }.toSet()
                    IconButton(onClick = { bulkCategory = true }, enabled = kinds.size == 1) { Icon(Icons.Rounded.Sell, "Change category") }
                    IconButton(onClick = {
                        val ids = bulkable.map { it.id }
                        val skipped = chosen.size - ids.size
                        selected = emptySet()
                        scope.launch {
                            c.repo.deleteTransactions(ids)
                            val msg = "${ids.size} moved to Trash" + if (skipped > 0) ". $skipped linked to a loan, deposit or debt stayed." else ""
                            if (snackbar.showSnackbar(msg, actionLabel = "Undo", withDismissAction = true) == androidx.compose.material3.SnackbarResult.ActionPerformed) c.repo.restoreTransactions(ids)
                        }
                    }, enabled = bulkable.isNotEmpty()) { Icon(Icons.Rounded.Delete, "Delete selected") }
                },
            ) else TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            f.query, { q -> vm.update { it.copy(query = q) } },
                            placeholder = { Text("Payee, note, category or amount") }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Activity")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) vm.update { it.copy(query = "") }
                        searching = !searching
                    }) { Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Close search" else "Search") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (f.query.isBlank()) {
                item { MonthSwitcher(f.month, { m -> vm.update { it.copy(month = m) } }) }
                item {
                    app.kosh.ui.components.KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                app.kosh.ui.components.StatTile("Income", Modifier.weight(1f), tint = typeColor("income")) {
                                    MoneyText(s.income, s.lookup.baseCurrency, color = typeColor("income"), style = MaterialTheme.typography.titleSmall)
                                }
                                app.kosh.ui.components.StatTile("Spent", Modifier.weight(1f), tint = typeColor("expense")) {
                                    MoneyText(s.expense, s.lookup.baseCurrency, color = typeColor("expense"), style = MaterialTheme.typography.titleSmall)
                                }
                                app.kosh.ui.components.StatTile("Net", Modifier.weight(1f), tint = MaterialTheme.colorScheme.primary) {
                                    MoneyText(s.income - s.expense, s.lookup.baseCurrency, signed = true, style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            if (s.expense > 0 && f.type != "income" && f.type != "transfer") {
                                Spacer(Modifier.height(12.dp))
                                DailyBars(f.month, s.items, s.lookup)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((t, label) in listOf("expense" to "Expenses", "income" to "Income", "transfer" to "Transfers")) {
                        FilterChip(f.type == t, { vm.update { it.copy(type = if (it.type == t) null else t) } }, { Text(label) })
                    }
                    if (f.accountId != null) {
                        InputChip(true, { vm.update { it.copy(accountId = null) } }, { Text(s.lookup.accountName(f.accountId)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Clear account filter") })
                    } else {
                        FilterChip(false, { pick = "account" }, { Text("Account") })
                    }
                    if (f.categoryId != null) {
                        InputChip(true, { vm.update { it.copy(categoryId = null) } }, { Text(s.lookup.categoryName(f.categoryId)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Clear category filter") })
                    } else {
                        FilterChip(false, { pick = "category" }, { Text("Category") })
                    }
                    if (f.tagId != null) {
                        InputChip(true, { vm.update { it.copy(tagId = null) } }, { Text("#" + (s.lookup.tagById[f.tagId]?.name ?: "tag")) }, trailingIcon = { Icon(Icons.Rounded.Close, "Clear tag filter") })
                    } else if (s.lookup.activeTags.isNotEmpty()) {
                        FilterChip(false, { pick = "tag" }, { Text("Tag") })
                    }
                }
            }
            if (s.loaded && s.items.isEmpty()) {
                item {
                    if (f.query.isNotBlank()) {
                        EmptyState(Icons.Rounded.SearchOff, "No matches", "Nothing found for \"${f.query}\".")
                    } else {
                        EmptyState(Icons.AutoMirrored.Rounded.ReceiptLong, "No transactions", "Nothing recorded in this period. Tap + to add one.")
                    }
                }
            }
            val byDay = s.items.groupBy { it.localDate }
            for ((day, list) in byDay) {
                item(key = "d-$day") { DayHeader(LocalDate.parse(day), dayNet(list, s.lookup), s.lookup.baseCurrency) }
                items(list, key = { it.id }) { t ->
                    app.kosh.ui.components.SwipeToDeleteRow(
                        enabled = selected.isEmpty() && t.debtId == null && t.loanId == null && t.depositId == null,
                        onDelete = {
                            scope.launch {
                                c.repo.deleteTransactions(listOf(t.id))
                                if (snackbar.showSnackbar("Moved to Trash", actionLabel = "Undo", withDismissAction = true) == androidx.compose.material3.SnackbarResult.ActionPerformed) c.repo.restoreTransactions(listOf(t.id))
                            }
                        },
                    ) {
                        TransactionRow(
                            t, s.lookup, selected = t.id in selected,
                            onClick = { if (selected.isNotEmpty()) selected = if (t.id in selected) selected - t.id else selected + t.id else nav.navigate(Routes.editTx(t.id, t.type)) },
                            onLongClick = { selected = if (t.id in selected) selected - t.id else selected + t.id },
                        )
                    }
                }
            }
            if (f.query.isNotBlank() && s.items.size >= f.searchLimit) item {
                androidx.compose.material3.TextButton(onClick = { vm.update { it.copy(searchLimit = it.searchLimit + 300) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Show more results") }
            }
        }
    }

    if (bulkCategory) {
        val kind = bulkable.firstOrNull { it.type != "transfer" }?.type ?: "expense"
        CategorySheet(s.lookup, kind, null, onDismiss = { bulkCategory = false }) { id ->
            val ids = bulkable.filter { it.type != "transfer" && !s.lookup.isSplit(it.id) }.map { it.id }
            selected = emptySet()
            scope.launch {
                val before = c.repo.recategorize(ids, id)
                val msg = "${ids.size} moved to ${s.lookup.categoryName(id)}"
                if (snackbar.showSnackbar(msg, actionLabel = "Undo", withDismissAction = true) == androidx.compose.material3.SnackbarResult.ActionPerformed) c.repo.setCategories(before)
            }
        }
    }
    when (pick) {
        "account" -> PickerSheet("Filter by account", s.lookup.accounts, { pick = null }, { a -> vm.update { it.copy(accountId = a.id) } }, searchText = { it.name }) { a -> AccountListItem(a, s.lookup) }
        "category" -> {
            val kind = if (f.type == "income") "income" else "expense"
            CategorySheet(s.lookup, kind, f.categoryId, onDismiss = { pick = null }) { id -> vm.update { it.copy(categoryId = id) } }
        }
        "tag" -> PickerSheet("Filter by tag", s.lookup.tags, { pick = null }, { t -> vm.update { it.copy(tagId = t.id) } }, searchText = { it.name }) { t ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text("#" + t.name) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

/** One small bar per day of [month]: the shape of the month's spending at a glance. Today is outlined. */
@Composable
private fun DailyBars(month: java.time.YearMonth, items: List<app.kosh.data.db.TransactionEntity>, lookup: app.kosh.domain.Lookup) {
    val perDay = LongArray(month.lengthOfMonth() + 1)
    for (t in items) {
        val r = t.toReportRow(lookup) ?: continue
        if ((r.currency ?: lookup.baseCurrency) != lookup.baseCurrency) continue
        val d = LocalDate.parse(r.localDate)
        if (java.time.YearMonth.from(d) == month) perDay[d.dayOfMonth] += app.kosh.domain.Reports.spending(r, lookup.categoryById).coerceAtLeast(0)
    }
    val max = perDay.max().coerceAtLeast(1)
    val today = LocalDate.now()
    val color = typeColor("expense")
    val muted = MaterialTheme.colorScheme.surfaceContainerHighest
    val peakDay = perDay.indices.maxBy { perDay[it] }
    Text(
        "Daily spending · busiest day ${peakDay} ${month.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} (${app.kosh.core.Money.compact(perDay[peakDay], lookup.baseCurrency)})",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val n = month.lengthOfMonth()
        val slot = size.width / n
        val w = (slot * 0.62f).coerceAtLeast(2f)
        for (d in 1..n) {
            val h = if (perDay[d] > 0) (size.height * perDay[d] / max).coerceAtLeast(3f) else 3f
            val x = slot * (d - 1) + (slot - w) / 2
            val isToday = month.atDay(d) == today
            drawRoundRect(
                if (perDay[d] > 0) (if (d == peakDay) color else color.copy(alpha = 0.55f)) else muted,
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
            )
            if (isToday) drawCircle(color, 3.5f, androidx.compose.ui.geometry.Offset(x + w / 2, size.height + 0f))
        }
    }
}
