package app.kosh.ui.screens

import app.kosh.ui.components.FormSheet
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.kosh.data.db.DebtEntity
import app.kosh.domain.Debts
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.DateField
import app.kosh.ui.components.WhenFields
import app.kosh.ui.components.isFuture
import app.kosh.ui.components.nowMinute
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.typeColor
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Debts are listed per person; the person screen shows a running statement. */
fun personRoute(key: String) = "person/" + Uri.encode(key)
const val PERSON_ROUTE = "person/{key}"

private val Debts.Person.owesYou get() = net > 0

@Composable
fun DebtsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val debts by c.repo.debts.collectAsStateWithLifecycle(emptyList())
    val moves by c.repo.debtMoves.collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }
    var showSettled by remember { mutableStateOf(false) }
    val today = Dates.today().toString()
    val people = remember(debts, moves) { Debts.people(debts, moves.groupBy { it.debtId.orEmpty() }, today) }
    val open = people.filter { it.open > 0 }
    val settled = people.filter { it.open == 0 }
    val owed = open.filter { it.net > 0 }.groupBy { it.currency }.mapValues { (_, l) -> l.sumOf { it.net } }
    val owe = open.filter { it.net < 0 }.groupBy { it.currency }.mapValues { (_, l) -> -l.sumOf { it.net } }

    Scaffold(
        topBar = { KoshTopBar("Lent & borrowed", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add") }) },
    ) { padding ->
        app.kosh.ui.components.SyncRefreshBox(Modifier.padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (people.isEmpty()) item {
                EmptyState(Icons.Rounded.Handshake, "Nothing owed", "Keep track of money you lent to friends or borrowed, even from long ago. Record repayments and settle up whenever.")
            }
            if (open.isNotEmpty()) item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Owed to you", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (owed.isEmpty()) Text("—")
                            for ((cur, v) in owed) MoneyText(v, cur, color = typeColor("income"), style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("You owe", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (owe.isEmpty()) Text("—")
                            for ((cur, v) in owe) MoneyText(v, cur, color = typeColor("expense"), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            val overdue = open.sumOf { it.overdue }
            if (overdue > 0) item {
                Text(
                    "$overdue ${if (overdue == 1) "debt is" else "debts are"} past the due date.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
            }
            items(open, key = { it.key }) { p -> PersonRow(p, today) { nav.navigate(personRoute(p.key)) } }
            if (settled.isNotEmpty()) {
                item {
                    TextButton(onClick = { showSettled = !showSettled }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(if (showSettled) "Hide settled" else "Show settled (${settled.size})")
                    }
                }
                if (showSettled) items(settled, key = { it.key }) { p -> PersonRow(p, today) { nav.navigate(personRoute(p.key)) } }
            }
            if (people.isNotEmpty()) item {
                Text(
                    "Money you lend stays yours: it counts in net worth as owed to you, never as spending. Borrowed money is owed, never income. Interest you record is real income or cost.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
    if (creating) DebtDialog(null, onDismiss = { creating = false }, onRepay = {})
}

@Composable
private fun PersonRow(p: Debts.Person, today: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(p.name) },
        supportingContent = {
            val parts = buildList {
                add(
                    when {
                        p.open == 0 -> "All settled"
                        p.owesYou -> "Owes you"
                        else -> "You owe"
                    },
                )
                if (p.open > 1) add("${p.open} debts")
                if (p.overdue > 0) add("overdue") else p.nextDue?.let { add("due ${Dates.relativeDay(LocalDate.parse(it))}") }
            }
            Text(parts.joinToString(" · "), color = if (p.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = { Initial(p.name, if (p.open == 0) null else if (p.owesYou) typeColor("income") else typeColor("expense")) },
        trailingContent = {
            if (p.open > 0) MoneyText(kotlin.math.abs(p.net), p.currency, color = if (p.owesYou) typeColor("income") else typeColor("expense"), style = MaterialTheme.typography.titleMedium)
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Initial(name: String, tint: Color?) {
    val base = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = base.copy(alpha = 0.14f), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = base, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun PersonScreen(key: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val debts by c.repo.debts.collectAsStateWithLifecycle(emptyList())
    val moves by c.repo.debtMoves.collectAsStateWithLifecycle(emptyList())
    val today = Dates.today().toString()
    val byDebt = remember(moves) { moves.groupBy { it.debtId.orEmpty() } }
    val person = remember(debts, moves, key) { Debts.people(debts, byDebt, today).firstOrNull { it.key == key } }
    var edit by remember { mutableStateOf<DebtEntity?>(null) }
    var more by remember { mutableStateOf<String?>(null) } // direction to add
    var repay by remember { mutableStateOf<String?>(null) } // direction being repaid

    Scaffold(topBar = { KoshTopBar(person?.name ?: "Person", onBack = { nav.popBackStack() }) }) { padding ->
        if (person == null) {
            EmptyState(Icons.Rounded.Handshake, "Nothing here", "This person has no debts left.", Modifier.padding(padding))
            return@Scaffold
        }
        val statement = remember(person, byDebt) { Debts.statement(person.debts, byDebt) }
        val lentOpen = person.debts.any { it.direction == "lent" && !it.closed && Debts.outstanding(it, byDebt[it.id].orEmpty()) > 0 }
        val borrowedOpen = person.debts.any { it.direction == "borrowed" && !it.closed && Debts.outstanding(it, byDebt[it.id].orEmpty()) > 0 }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                KoshCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                person.open == 0 -> "All settled"
                                person.owesYou -> "${person.name} owes you"
                                else -> "You owe ${person.name}"
                            },
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MoneyText(
                            kotlin.math.abs(person.net), person.currency, style = MaterialTheme.typography.displaySmall,
                            color = if (person.open == 0) Color.Unspecified else if (person.owesYou) typeColor("income") else typeColor("expense"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (lentOpen) FilledTonalButton(onClick = { repay = "lent" }) { Text("They paid me") }
                            if (borrowedOpen) FilledTonalButton(onClick = { repay = "borrowed" }) { Text("I paid them") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { more = "lent" }) { Text("I lent more") }
                            OutlinedButton(onClick = { more = "borrowed" }) { Text("I borrowed more") }
                        }
                    }
                }
            }
            item { SectionHeader("Statement") }
            items(statement.reversed()) { r ->
                ListItem(
                    overlineContent = r.date?.let { { Text(Dates.medium(LocalDate.parse(it))) } },
                    headlineContent = { Text(r.text.substringBefore(" · ")) },
                    supportingContent = {
                        val note = r.text.substringAfter(" · ", "")
                        Text(listOf(note, "Balance ${Money.ui(kotlin.math.abs(r.balance), person.currency)} ${if (r.balance > 0) "owed to you" else if (r.balance < 0) "you owe" else ""}").filter { it.isNotBlank() }.joinToString(" · ").trim())
                    },
                    trailingContent = {
                        when {
                            // Shown as money in or out of your pocket: lending is money out, being paid back is money in.
                            r.change != 0L && r.text.startsWith("Marked settled") -> MoneyText(-r.change, person.currency, signed = true, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.change != 0L -> MoneyText(-r.change, person.currency, signed = true, color = if (r.change < 0) typeColor("income") else typeColor("expense"))
                            r.interest != 0L -> Text("${Money.ui(r.interest, person.currency)} interest", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    modifier = Modifier.clickable {
                        if (r.transactionId != null) nav.navigate(Routes.editTx(r.transactionId))
                        else edit = person.debts.firstOrNull { it.id == r.debtId }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { SectionHeader("Debts") }
            items(person.debts.sortedBy { it.closed }, key = { it.id }) { d ->
                val left = Debts.balance(d, byDebt[d.id].orEmpty())
                ListItem(
                    headlineContent = { Text((if (d.direction == "lent") "Lent " else "Borrowed ") + Money.ui(d.amount, d.currency)) },
                    supportingContent = {
                        Text(
                            when {
                                d.closed -> "Settled"
                                left < 0 -> "Overpaid by ${Money.ui(-left, d.currency)}"
                                left == d.amount -> "Nothing repaid yet"
                                else -> "${Money.ui(left, d.currency)} left"
                            } + (d.dueDate?.takeIf { !d.closed }?.let { " · due ${Dates.relativeDay(LocalDate.parse(it))}" } ?: "") + (d.note?.let { " · $it" } ?: ""),
                        )
                    },
                    modifier = Modifier.clickable { edit = d },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        if (edit != null) DebtDialog(edit, onDismiss = { edit = null }, onRepay = { edit = null; repay = it.direction })
        more?.let { dir -> DebtDialog(null, onDismiss = { more = null }, onRepay = {}, presetPerson = person.name, presetDirection = dir, presetCurrency = person.currency) }
        repay?.let { dir ->
            val open = person.debts.filter { it.direction == dir && !it.closed }
                .map { Triple(it, Debts.outstanding(it, byDebt[it.id].orEmpty()), Debts.start(it, byDebt[it.id].orEmpty())) }.filter { it.second > 0 }
            PersonRepayDialog(person, dir, open, onDismiss = { repay = null })
        }
    }
}

/** One repayment from or to a person, spread over their open debts oldest first. */
@Composable
private fun PersonRepayDialog(person: Debts.Person, direction: String, open: List<Triple<DebtEntity, Long, String>>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val currency = person.currency
    val lent = direction == "lent"
    val total = open.sumOf { it.second }
    var amount by remember { mutableStateOf(Money.toPlain(total, currency)) }
    var interest by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.today()) }
    var time by remember { mutableStateOf(nowMinute()) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // One ID per share, fixed while the dialog is open, so a double tap cannot record it twice.
    val ids = remember { List(open.size + 1) { Ids.newV7() } }
    val minor = Money.parse(amount, currency)
    val extra = if (interest.isBlank()) 0L else Money.parse(interest, currency)
    val plan = if (minor != null && minor > 0 && minor <= total) Debts.allocate(minor, open).first else emptyList()
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text(if (lent) "${person.name} paid you back" else "You paid ${person.name} back") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Outstanding: ${Money.ui(total, currency)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountField(
                    amount, { amount = it }, currency, "Amount repaid",
                    supporting = when {
                        amount.isNotBlank() && minor == null -> "Enter a number like 5000"
                        minor != null && minor > total -> "Only ${Money.ui(total, currency)} is owed. Put anything extra in Interest."
                        plan.size > 1 -> "Covers ${plan.size} debts, oldest first"
                        minor != null && minor in 1 until total -> "Part payment: ${Money.ui(total - minor, currency)} will still be owed"
                        else -> null
                    },
                )
                AmountField(interest, { interest = it }, currency, "Interest or extra (optional)", supporting = if (extra == null) "Enter an amount or leave empty" else "Counts as ${if (lent) "income" else "a cost"}, not as repayment")
                AccountChooser(currency, accountId, if (lent) "Into" else "From") { accountId = it }
                WhenFields("Paid on", date, { date = it }, time, { time = it }, max = Dates.today())
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && plan.isNotEmpty() && extra != null && extra >= 0 && accountId != null && !isFuture(date, time), onClick = {
                saving = true
                val acc = accountId!!
                val txs = plan.mapIndexed { i, share -> Debts.movement(share.debt, repayment = true, accountId = acc, amount = share.amount, date = date, id = ids[i], time = time) } +
                    listOfNotNull(extra?.takeIf { it > 0 }?.let { Debts.movement(plan.first().debt, repayment = true, accountId = acc, amount = it, date = date, id = ids.last(), interest = true, time = time) })
                val closed = plan.filter { it.settles }.map { it.debt.copy(closed = true) }
                scope.launch {
                    c.repo.saveAll(debts = closed, transactions = txs)
                    onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
