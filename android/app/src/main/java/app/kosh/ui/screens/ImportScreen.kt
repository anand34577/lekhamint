package app.kosh.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kosh.AppContainer
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.core.plural
import app.kosh.data.StatementReader
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.StatementImport
import app.kosh.domain.StatementImport.DateFormat
import app.kosh.domain.StatementImport.Role
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.components.HeroCard
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.LocalOnHero
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.PickerSheet
import app.kosh.ui.components.SectionCard
import app.kosh.ui.components.StatusPill
import app.kosh.ui.koshViewModel
import app.kosh.ui.theme.LocalMoneyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId

/** One statement line as it will be saved, with what the person can still change. */
data class ImportLine(
    val row: StatementImport.Row,
    val include: Boolean,
    val duplicateOf: String?,
    val categoryId: String?,
    val payee: String,
    val tagIds: List<String>,
    val id: String,
    /** Already imported earlier (same file or an overlapping one): never saved twice. */
    val alreadyImported: Boolean = false,
)

class ImportViewModel(private val c: AppContainer, private val app: Application) : ViewModel() {
    enum class Stage { PICK, PASSWORD, MAP, PREVIEW, DONE }

    var stage by mutableStateOf(Stage.PICK); private set
    var busy by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var fileName by mutableStateOf(""); private set
    var content by mutableStateOf<StatementReader.Content?>(null); private set
    private var bytes: ByteArray? = null
    var passwordError by mutableStateOf<String?>(null); private set

    // Column mapping (tables only).
    var headerRow by mutableStateOf(0); private set
    val roles = mutableStateListOf<Role>()
    var dateFormat by mutableStateOf(DateFormat.DMY)
    var decimalComma by mutableStateOf(false)
    var positiveIsExpense by mutableStateOf(false)
    var accountId by mutableStateOf<String?>(null)

    val lines = mutableStateListOf<ImportLine>()
    var imported by mutableStateOf(0); private set
    var skipped by mutableStateOf(0); private set

    val account: AccountEntity? get() = accountId?.let { c.lookup.value.accountById[it] }
    private val currency get() = account?.currency ?: c.settings.current.baseCurrency

    fun load(uri: Uri) = viewModelScope.launch {
        error = null; busy = "Reading the file…"
        try {
            val (name, data) = withContext(Dispatchers.IO) { StatementReader.bytes(app, uri) }
            fileName = name; bytes = data
            open(null)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't read that file"
        } finally { busy = null }
    }

    fun loadBytes(name: String, data: ByteArray) = viewModelScope.launch {
        error = null; fileName = name; bytes = data
        busy = "Reading the file…"
        try { open(null) } finally { busy = null }
    }

    fun unlock(password: String) = viewModelScope.launch {
        busy = "Opening the PDF…"; passwordError = null
        try { open(password) } finally { busy = null }
    }

    private suspend fun open(password: String?) {
        val data = bytes ?: return
        val result = withContext(Dispatchers.Default) {
            runCatching { StatementReader.read(app, fileName, data, c.settings.current.baseCurrency, password) }
        }
        result.onSuccess { ct ->
            content = ct
            guessAccount(ct)
            when (ct) {
                is StatementReader.Content.Table -> {
                    if (ct.table.size < 2) { error = "No rows found in this file."; stage = Stage.PICK; return }
                    headerRow = StatementImport.findHeader(ct.table)
                    roles.clear(); roles += StatementImport.guessRoles(ct.table[headerRow])
                    val di = roles.indexOf(Role.DATE)
                    dateFormat = StatementImport.detectDateFormat(
                        if (di >= 0) ct.table.drop(headerRow + 1).take(200).map { it.getOrNull(di).orEmpty() } else emptyList(),
                        java.util.Locale.getDefault().country == "US",
                    )
                    positiveIsExpense = account?.type == "credit_card" && Role.AMOUNT in roles && Role.DEBIT !in roles
                    stage = Stage.MAP
                }
                is StatementReader.Content.Rows -> {
                    if (ct.parsed.rows.isEmpty()) { error = "No transactions found in this file."; stage = Stage.PICK; return }
                    stage = Stage.MAP
                }
            }
        }.onFailure { e ->
            when (e) {
                is StatementReader.NeedsPassword -> stage = Stage.PASSWORD
                is StatementReader.WrongPassword -> { stage = Stage.PASSWORD; passwordError = "That password didn't work. Try again." }
                else -> { error = e.message ?: "Couldn't read that file"; stage = Stage.PICK }
            }
        }
    }

    /** "XXXX4521" or an OFX account number in the file, matched to an account's name or note; else the last used. */
    private suspend fun guessAccount(ct: StatementReader.Content) {
        val l = c.lookup.value
        val sample = when (ct) {
            is StatementReader.Content.Table -> ct.table.take(12).joinToString(" ") { it.joinToString(" ") }
            is StatementReader.Content.Rows -> (ct.parsed.account.orEmpty() + " " + ct.text.take(3000))
        } + " " + fileName
        val digits = Regex("""(?:[Xx*]{2,}|a/?c\s*(?:no\.?)?\s*:?\s*\d*?)(\d{4})\b""", RegexOption.IGNORE_CASE).findAll(sample).map { it.groupValues[1] }.toList() +
            Regex("""\b\d{8,}(\d{4})\b""").findAll(sample).map { it.groupValues[1] }.toList()
        val byDigits = digits.firstNotNullOfOrNull { d -> l.activeAccounts.firstOrNull { it.name.contains(d) || it.note?.contains(d) == true } }
        val card = if (sample.contains("credit card", ignoreCase = true)) l.activeAccounts.filter { it.type == "credit_card" }.singleOrNull() else null
        accountId = (byDigits ?: card ?: c.autoFill.pickAccount(l, null, null))?.id
    }

    fun setRole(i: Int, role: Role) {
        // A role other than note/ignore belongs to one column only.
        if (role != Role.NOTE && role != Role.IGNORE) for (k in roles.indices) if (roles[k] == role) roles[k] = Role.IGNORE
        roles[i] = role
    }

    fun moveHeader(by: Int) {
        val t = (content as? StatementReader.Content.Table)?.table ?: return
        headerRow = (headerRow + by).coerceIn(-1, t.size - 2)
        roles.clear(); roles += if (headerRow >= 0) StatementImport.guessRoles(t[headerRow]) else List(t.maxOf { it.size }) { Role.IGNORE }
    }

    fun parsed(): StatementImport.Parsed? = when (val ct = content) {
        is StatementReader.Content.Rows -> ct.parsed
        is StatementReader.Content.Table -> if (roles.isEmpty()) null else
            StatementImport.toRows(ct.table, StatementImport.Options(roles.toList(), headerRow, dateFormat, currency, decimalComma, positiveIsExpense))
        null -> null
    }

    fun preview() = viewModelScope.launch {
        val acc = account ?: run { error = "Choose the account this statement is for"; return@launch }
        val p = parsed() ?: return@launch
        if (p.rows.isEmpty()) { error = "No rows could be read. Check the date and amount columns."; return@launch }
        busy = "Checking for duplicates…"; error = null
        try {
            val rows = p.rows
            val from = rows.minOf { it.date }.minusDays(2).toString()
            val to = rows.maxOf { it.date }.plusDays(2).toString()
            val existing = c.repo.db.transactions().forAccountBetween(acc.id, from, to)
                .map { StatementImport.Existing(it.id, it.type, it.amount, java.time.LocalDate.parse(it.localDate), it.accountId) }
            val dup = StatementImport.findDuplicates(rows, acc.id, existing)
            val fp = StatementImport.fingerprints(rows)
            val l = c.lookup.value
            val out = ArrayList<ImportLine>(rows.size)
            for ((i, r) in rows.withIndex()) {
                val id = StatementImport.importId(acc.id, fp[i])
                val again = c.repo.findTransaction(id) != null
                val clean = StatementImport.payeeFrom(r.description)
                val g = c.autoFill.guess(r.type, clean, note = r.description, amount = r.amount)
                val fromFile = r.category.takeIf { it.isNotBlank() }?.let { matchCategory(it, l, r.type) }
                out += ImportLine(
                    r, include = !again && !dup.containsKey(i), duplicateOf = dup[i],
                    categoryId = fromFile ?: g.categoryId, payee = (g.payee ?: clean).take(200), tagIds = g.tagIds, id = id, alreadyImported = again,
                )
            }
            lines.clear(); lines += out
            stage = Stage.PREVIEW
        } finally { busy = null }
    }

    fun toggle(i: Int) { if (i in lines.indices && !lines[i].alreadyImported) lines[i] = lines[i].copy(include = !lines[i].include) }
    fun setCategory(i: Int, id: String?) { if (i in lines.indices) lines[i] = lines[i].copy(categoryId = id) }

    fun import() = viewModelScope.launch {
        val acc = account ?: return@launch
        val chosen = lines.filter { it.include && !it.alreadyImported }
        if (chosen.isEmpty()) return@launch
        busy = "Importing…"
        try {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            var saved = 0
            for (batch in chosen.chunked(200)) {
                val txs = batch.filter { c.repo.findTransaction(it.id) == null }.map { x ->
                    val at = x.row.date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
                    val note = listOf(x.row.description.takeIf { !it.equals(x.payee, ignoreCase = true) }, x.row.note.ifBlank { null })
                        .filterNotNull().joinToString(" · ").take(2000).ifEmpty { null }
                    TransactionEntity(
                        id = x.id, type = x.row.type, accountId = acc.id, amount = x.row.amount, categoryId = x.categoryId,
                        payee = x.payee.ifBlank { null }, note = note, occurredAt = at, localDate = x.row.date.toString(),
                        tzOffsetMin = Dates.tzOffsetMinutes(at, zone), tagIds = x.tagIds, createdAt = now,
                    )
                }
                c.repo.saveTransactions(txs)
                saved += txs.size
                busy = "Importing… $saved of ${chosen.size}"
            }
            imported = saved
            skipped = lines.size - saved
            stage = Stage.DONE
        } catch (e: Exception) {
            error = "Couldn't import: ${e.message ?: e.javaClass.simpleName}"
        } finally { busy = null }
    }

    fun reset() {
        stage = Stage.PICK; content = null; bytes = null; lines.clear(); error = null; passwordError = null; roles.clear()
    }

    /** A category by name or "Parent · Child" / "Parent: Child" / "Parent > Child" path, preferring the row's kind. */
    private fun matchCategory(name: String, l: app.kosh.domain.Lookup, kind: String): String? {
        val n = name.trim().lowercase()
        val wanted = n.replace(Regex("""\s*(:|>|/|·)\s*"""), " · ")
        val live = l.categories.filter { !it.deleted && !it.archived }
        fun pick(pred: (app.kosh.data.db.CategoryEntity) -> Boolean) = live.firstOrNull { it.kind == kind && pred(it) } ?: live.firstOrNull(pred)
        return (pick { l.categoryPath(it.id).lowercase() == wanted } ?: pick { it.name.lowercase() == n })?.id
    }
}

@Composable
fun ImportScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm = koshViewModel { ImportViewModel(it, context.applicationContext as Application) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::load) }
    // A statement shared from another app or opened from a file manager.
    androidx.compose.runtime.LaunchedEffect(Unit) { app.kosh.ImportInbox.take()?.let { (name, data) -> vm.loadBytes(name, data) } }
    fun pick() = picker.launch(arrayOf("*/*"))

    Scaffold(
        topBar = {
            KoshTopBar("Import a statement", onBack = { nav.popBackStack() })
        },
        bottomBar = {
            when (vm.stage) {
                ImportViewModel.Stage.MAP -> BottomAction("Preview", enabled = vm.busy == null && vm.account != null) { vm.preview() }
                ImportViewModel.Stage.PREVIEW -> {
                    val n = vm.lines.count { it.include && !it.alreadyImported }
                    BottomAction(if (n == 0) "Nothing to import" else "Import ${plural(n, "entry", "entries")}", enabled = n > 0 && vm.busy == null) { vm.import() }
                }
                else -> {}
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (vm.stage) {
                ImportViewModel.Stage.PICK -> PickStage(vm.error, onPick = ::pick)
                ImportViewModel.Stage.PASSWORD -> PasswordStage(vm.fileName, vm.passwordError, onUnlock = vm::unlock, onCancel = vm::reset)
                ImportViewModel.Stage.MAP -> MapStage(vm)
                ImportViewModel.Stage.PREVIEW -> PreviewStage(vm)
                ImportViewModel.Stage.DONE -> DoneStage(vm.imported, vm.skipped, onSee = { nav.popBackStack(); nav.navigate(Routes.TRANSACTIONS) }, onAnother = { vm.reset(); pick() })
            }
            vm.busy?.let { msg ->
                Surface(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(msg, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp)) { Text(label) }
    }
}

@Composable
private fun PickStage(error: String?, onPick: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val on = LocalOnHero.current
                Icon(Icons.Rounded.UploadFile, null, tint = on, modifier = Modifier.size(36.dp))
                Text("Bring in your bank's statement", style = MaterialTheme.typography.titleLarge, color = on, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Months of history in one go, with categories filled in from your rules and past entries. Anything already here is spotted and left out.",
                    style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.88f), modifier = Modifier.padding(top = 6.dp),
                )
                Button(onClick = onPick, modifier = Modifier.padding(top = 14.dp),
                    colors = app.kosh.ui.components.heroButtonColors()) {
                    Text("Choose a file")
                }
            }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
        item {
            SectionCard("Works with") {
                for ((t, d) in listOf(
                    "PDF statements" to "Including password-locked ones (often your date of birth or PAN)",
                    "Excel (.xls, .xlsx) and CSV" to "What most bank websites and apps offer as “download”",
                    "OFX, QFX and QIF" to "From other money apps and some banks",
                )) {
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(t, style = MaterialTheme.typography.titleSmall)
                        Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text(
                "Where to find it: in your bank's app or website, open the account statement, pick the dates and download. " +
                    "The file is read on this phone and never uploaded; only the entries you keep are saved.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PasswordStage(name: String, error: String?, onUnlock: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KoshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Text("This PDF is locked", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                }
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Banks usually lock statements with something like your date of birth (DDMMYYYY), part of your name and birth date, or your customer ID. " +
                        "The email the statement came with says which.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = error != null,
                    supportingText = error?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onUnlock(pw) }, enabled = pw.isNotEmpty()) { Text("Open") }
                }
                Text("The password is used once to open the file and is not saved.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapStage(vm: ImportViewModel) {
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var pickAccount by remember { mutableStateOf(false) }
    val ct = vm.content
    val parsed = vm.parsed()
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionCard(vm.fileName, subtitle = when (ct) {
                is StatementReader.Content.Rows -> "${ct.kind.name} · ${plural(ct.parsed.rows.size, "transaction")} found"
                is StatementReader.Content.Table -> "${ct.kind.name} · ${plural(ct.table.size, "row")}"
                null -> ""
            }) {
                Text("Which account is this statement for?", style = MaterialTheme.typography.labelLarge)
                AssistChip({ pickAccount = true }, { Text(vm.account?.name ?: "Choose an account") }, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (ct is StatementReader.Content.Table) {
            item {
                SectionCard("Columns", subtitle = "Check what each column holds. Most are guessed for you.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (vm.headerRow >= 0) "Headings on row ${vm.headerRow + 1}" else "No heading row", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.moveHeader(-1) }, enabled = vm.headerRow > -1) { Text("Up") }
                        TextButton(onClick = { vm.moveHeader(1) }) { Text("Down") }
                    }
                    val sample = ct.table.drop(vm.headerRow + 1).take(3)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in vm.roles.indices) {
                            Column(Modifier.width(150.dp)) {
                                Text(ct.table.getOrNull(vm.headerRow)?.getOrNull(i)?.ifBlank { null } ?: "Column ${i + 1}",
                                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                RolePicker(vm.roles[i]) { vm.setRole(i, it) }
                                for (r in sample) Text(r.getOrNull(i).orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Dates are written", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(vm.dateFormat == DateFormat.DMY, { vm.dateFormat = DateFormat.DMY }, { Text("Day first (24/10)") })
                        FilterChip(vm.dateFormat == DateFormat.MDY, { vm.dateFormat = DateFormat.MDY }, { Text("Month first (10/24)") })
                        FilterChip(vm.dateFormat == DateFormat.YMD, { vm.dateFormat = DateFormat.YMD }, { Text("Year first") })
                    }
                    ToggleRow("Comma is the decimal point (1.234,56)", vm.decimalComma) { vm.decimalComma = it }
                    if (Role.AMOUNT in vm.roles) ToggleRow("Plain amounts are spending (card statements)", vm.positiveIsExpense) { vm.positiveIsExpense = it }
                }
            }
        }
        if (parsed != null) {
            item {
                val out = parsed.rows.filter { it.type == "expense" }.sumOf { it.amount }
                val inn = parsed.rows.filter { it.type == "income" }.sumOf { it.amount }
                val cur = vm.account?.currency ?: lookup.baseCurrency
                SectionCard("What was read") {
                    Text("${plural(parsed.rows.size, "transaction")} · ${Money.ui(out, cur)} out · ${Money.ui(inn, cur)} in", style = MaterialTheme.typography.bodyMedium)
                    if (parsed.rows.isNotEmpty()) {
                        Text("${Dates.medium(parsed.rows.minOf { it.date })} to ${Dates.medium(parsed.rows.maxOf { it.date })}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (parsed.errors.isNotEmpty()) {
                        Text("${plural(parsed.errors.size, "line")} couldn't be read and will be left out:", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                        for (e in parsed.errors.take(4)) Text("Line ${e.line}: ${e.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        vm.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
    }
    if (pickAccount) {
        PickerSheet("Account", lookup.activeAccounts, onDismiss = { pickAccount = false }, onPick = { vm.accountId = it.id },
            searchText = { it.name }, selected = { it.id == vm.accountId }) { AccountListItem(it, lookup) }
    }
}

@Composable
private fun RolePicker(role: Role, onPick: (Role) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(role != Role.IGNORE, { open = true }, { Text(role.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        DropdownMenu(open, { open = false }) {
            for (r in Role.entries) DropdownMenuItem({ Text(r.label) }, { onPick(r); open = false })
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Composable
private fun PreviewStage(vm: ImportViewModel) {
    val c = LocalContainer.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var categoryFor by remember { mutableStateOf<Int?>(null) }
    val cur = vm.account?.currency ?: lookup.baseCurrency
    val chosen = vm.lines.filter { it.include && !it.alreadyImported }
    val money = LocalMoneyColors.current
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionCard("Ready to import into ${vm.account?.name ?: ""}") {
                Text("${plural(chosen.size, "entry", "entries")} · ${Money.ui(chosen.filter { it.row.type == "expense" }.sumOf { it.row.amount }, cur)} out · " +
                    "${Money.ui(chosen.filter { it.row.type == "income" }.sumOf { it.row.amount }, cur)} in", style = MaterialTheme.typography.bodyMedium)
                val dups = vm.lines.count { it.duplicateOf != null }
                val again = vm.lines.count { it.alreadyImported }
                if (dups > 0) Text("${plural(dups, "entry", "entries")} look already recorded and are unticked. Tick any that are new.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (again > 0) Text("${plural(again, "entry", "entries")} were imported before and are skipped.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val uncategorised = chosen.count { it.categoryId == null }
                if (uncategorised > 0) Text("$uncategorised without a category: tap one to pick, or sort them later in the weekly check-in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(vm.lines, key = { _, x -> x.id }) { i, x ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.toggle(i) }.padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(x.include && !x.alreadyImported, { vm.toggle(i) }, enabled = !x.alreadyImported)
                Column(Modifier.weight(1f)) {
                    Text(x.payee.ifBlank { x.row.description }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Dates.short(x.row.date) + " · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            x.categoryId?.let { lookup.categoryPath(it) } ?: if (x.row.type == "income") "Income · no category" else "No category",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { categoryFor = i },
                        )
                    }
                    when {
                        x.alreadyImported -> StatusPill("Imported before", MaterialTheme.colorScheme.onSurfaceVariant)
                        x.duplicateOf != null -> StatusPill("Looks already recorded", MaterialTheme.colorScheme.tertiary)
                    }
                }
                MoneyText(if (x.row.type == "income") x.row.amount else -x.row.amount, cur, signed = x.row.type == "income",
                    style = MaterialTheme.typography.titleSmall, color = if (x.row.type == "income") money.income else MaterialTheme.colorScheme.onSurface)
            }
        }
        vm.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
    }
    categoryFor?.let { i ->
        val x = vm.lines.getOrNull(i)
        if (x != null) CategorySheet(lookup, x.row.type, x.categoryId, onDismiss = { categoryFor = null }) { vm.setCategory(i, it) }
    }
}

@Composable
private fun DoneStage(imported: Int, skipped: Int, onSee: () -> Unit, onAnother: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(56.dp), tint = LocalMoneyColors.current.income)
        Text("Imported ${plural(imported, "entry", "entries")}", style = MaterialTheme.typography.titleLarge)
        if (skipped > 0) Text("${plural(skipped, "line")} left out (duplicates or unticked)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Importing the same file again won't add anything twice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSee, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("See them") }
        OutlinedButton(onClick = onAnother, modifier = Modifier.fillMaxWidth()) { Text("Import another file") }
    }
}
