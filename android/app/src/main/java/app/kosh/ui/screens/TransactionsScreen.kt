package app.kosh.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
        if (f.query.isBlank()) c.repo.transactionsBetween(f.month.atDay(1), f.month.atEndOfMonth()) else c.repo.search(f.query.trim())
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

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            f.query, { q -> vm.update { it.copy(query = q) } },
                            placeholder = { Text("Search payee or note") }, singleLine = true,
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Total("Income", s.income, s.lookup.baseCurrency, typeColor("income"))
                        Total("Spent", -s.expense, s.lookup.baseCurrency, typeColor("expense"))
                        Total("Net", s.income - s.expense, s.lookup.baseCurrency, MaterialTheme.colorScheme.onSurface)
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
                items(list, key = { it.id }) { t -> TransactionRow(t, s.lookup, onClick = { nav.navigate(Routes.editTx(t.id, t.type)) }) }
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

@Composable
private fun Total(label: String, amount: Long, currency: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount, currency, signed = label != "Net" || amount != 0L, color = color, style = MaterialTheme.typography.titleSmall)
    }
}
