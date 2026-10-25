package app.kosh.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.backup.ReportExport
import app.kosh.core.Dates
import app.kosh.domain.ReportBuilders
import app.kosh.domain.ReportDoc
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.AssumptionNote
import app.kosh.ui.components.DateField
import app.kosh.ui.components.ErrorText
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.PickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** Statements as PDF or Excel, from the same report model as the web app. */
@Composable
fun StatementsScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val loans = rememberLoanEntries()
    val debts by c.repo.debts.collectAsStateWithLifecycle(emptyList())
    var kind by remember { mutableStateOf("summary") }
    val month = YearMonth.now()
    var from by remember { mutableStateOf(month.atDay(1)) }
    var to by remember { mutableStateOf(month.atEndOfMonth()) }
    var accountId by remember { mutableStateOf(lookup.activeAccounts.firstOrNull()?.id) }
    var loanId by remember { mutableStateOf(loans.firstOrNull()?.loan?.id) }
    val people = debts.filter { !it.deleted }.map { it.person.trim() to it.currency }.distinctBy { it.first.lowercase() + it.second }
    var person by remember { mutableStateOf(people.firstOrNull()) }
    var picker by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun build(): ReportDoc = withContext(Dispatchers.Default) {
        when (kind) {
            "account" -> ReportBuilders.accountStatement(lookup, accountId ?: error("Choose an account"), from, to, c.db.transactions().everything().filter { it.localDate <= to.toString() })
            "loan" -> loans.firstOrNull { it.loan.id == loanId }?.let { ReportBuilders.loan(lookup, it.status, it.loan) } ?: error("Choose a loan")
            "person" -> person?.let { (n, cur) -> ReportBuilders.person(lookup, n, cur, c.repo.debts.first(), c.repo.debtMoves.first()) } ?: error("Choose a person")
            else -> ReportBuilders.incomeExpense(lookup, from, to, c.repo.reportRows(from, to).first())
        }
    }
    fun export(pdf: Boolean, uri: android.net.Uri?) {
        uri ?: return
        scope.launch {
            error = null
            runCatching {
                val doc = build()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { if (pdf) ReportExport.writePdf(doc, c.settings.current.ledgerName, it) else ReportExport.writeXlsx(doc, c.settings.current.ledgerName, it) }
                }
            }.onSuccess { snackbar.showSnackbar(if (pdf) "PDF saved" else "Excel file saved") }
                .onFailure { error = it.message ?: "Could not create the file" }
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { export(true, it) }
    val saveXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { export(false, it) }
    val fileName = { ext: String -> "${when (kind) { "account" -> "account-statement"; "loan" -> "loan-statement"; "person" -> "statement-${person?.first ?: "person"}"; else -> "income-spending" }}-$from.$ext".replace(' ', '-') }
    val needsPeriod = kind == "summary" || kind == "account"
    val invalid = when {
        needsPeriod && to.isBefore(from) -> "The end date is before the start date"
        kind == "account" && accountId == null -> "Add an account first"
        kind == "loan" && loanId == null -> "You have no loans yet"
        kind == "person" && person == null -> "You have not recorded money lent or borrowed yet"
        else -> null
    }

    Scaffold(topBar = { KoshTopBar("Statements & export", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Report", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(kind == "summary", { kind = "summary" }, label = { Text("Income & spending") })
                FilterChip(kind == "account", { kind = "account" }, label = { Text("Account") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(kind == "loan", { kind = "loan" }, label = { Text("Loan") })
                FilterChip(kind == "person", { kind = "person" }, label = { Text("Person") })
            }
            if (needsPeriod) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(from == month.atDay(1) && to == month.atEndOfMonth(), { from = month.atDay(1); to = month.atEndOfMonth() }, label = { Text("This month") })
                    FilterChip(from == month.minusMonths(1).atDay(1), { from = month.minusMonths(1).atDay(1); to = month.minusMonths(1).atEndOfMonth() }, label = { Text("Last month") })
                    FilterChip(from == YearMonth.of(month.year, 1).atDay(1) && to == month.atEndOfMonth(), { from = YearMonth.of(month.year, 1).atDay(1); to = month.atEndOfMonth() }, label = { Text("This year") })
                }
                DateField("From", from, { from = it })
                DateField("To", to, { to = it })
            }
            if (kind == "account") OutlinedButton(onClick = { picker = "account" }, modifier = Modifier.fillMaxWidth()) { Text("Account: ${lookup.accountName(accountId)}") }
            if (kind == "loan") OutlinedButton(onClick = { picker = "loan" }, modifier = Modifier.fillMaxWidth()) { Text("Loan: ${loans.firstOrNull { it.loan.id == loanId }?.loan?.name ?: "—"}") }
            if (kind == "person") OutlinedButton(onClick = { picker = "person" }, modifier = Modifier.fillMaxWidth()) { Text("Person: ${person?.let { "${it.first} (${it.second})" } ?: "—"}") }
            ErrorText(invalid ?: error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = invalid == null, onClick = { savePdf.launch(fileName("pdf")) }) { Icon(Icons.Rounded.PictureAsPdf, null); Text("  PDF") }
                OutlinedButton(enabled = invalid == null, onClick = { saveXlsx.launch(fileName("xlsx")) }) { Icon(Icons.Rounded.TableChart, null); Text("  Excel") }
            }
            AssumptionNote("PDFs are A4 with the period, currency, generation time and page numbers on every report. Excel files contain real numbers and dates, one sheet per table. Full backups and CSV are under Backup & restore.")
            Text("Generated ${Dates.medium(Dates.today())} in ${lookup.baseCurrency} unless the account uses another currency.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (picker) {
        "account" -> PickerSheet(title = "Account", items = lookup.activeAccounts, onDismiss = { picker = null }, onPick = { accountId = it.id }, selected = { it.id == accountId }) { a ->
            ListItem(headlineContent = { Text(a.name) }, supportingContent = { Text(a.currency) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
        "loan" -> PickerSheet(title = "Loan", items = loans, onDismiss = { picker = null }, onPick = { loanId = it.loan.id }, selected = { it.loan.id == loanId }) { e ->
            ListItem(headlineContent = { Text(e.loan.name) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
        "person" -> PickerSheet(title = "Person", items = people, onDismiss = { picker = null }, onPick = { person = it }, selected = { it == person }) { p ->
            ListItem(headlineContent = { Text(p.first) }, supportingContent = { Text(p.second) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
    }
}
