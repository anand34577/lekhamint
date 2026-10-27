package app.kosh.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.InputChip
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.input.KeyboardType
import app.kosh.data.db.SplitEntity
import app.kosh.domain.RuleInput
import app.kosh.domain.RuleOutcome
import app.kosh.domain.Rules
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kosh.AppContainer
import app.kosh.core.Calculator
import app.kosh.core.Currencies
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.core.Recurrence
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.CategoryEntity
import app.kosh.data.db.RecurringRuleEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.AmountPad
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.PickerSheet
import app.kosh.ui.components.typeColor
import app.kosh.ui.koshViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class EditTxViewModel(
    private val c: AppContainer,
    private val id: String?,
    initialType: String,
    private val copy: Boolean,
    private val presetAccount: String?,
    private val presetTo: String? = null,
    private val presetAmount: String? = null,
) : ViewModel() {
    val isEdit = id != null && !copy
    var type by mutableStateOf(if (initialType in listOf("expense", "income", "transfer")) initialType else "expense")
        private set
    var expr by mutableStateOf("")
    var toExpr by mutableStateOf("")
    var accountId by mutableStateOf<String?>(null)
    var toAccountId by mutableStateOf<String?>(null)
    var categoryId by mutableStateOf<String?>(null)
        private set
    private var categoryChosen = false
    var date by mutableStateOf(Dates.today())
    /** Time of day; null means "now" for a new entry, or the saved time when editing. */
    var time by mutableStateOf<java.time.LocalTime?>(null)
    var payee by mutableStateOf("")
        private set
    var note by mutableStateOf("")
    var status by mutableStateOf("cleared")
    var excluded by mutableStateOf(false)
    var repeat by mutableStateOf<String?>(null)
    var repeatMode by mutableStateOf("remind")
    var error by mutableStateOf<String?>(null)
    /** Things worth a second look (overdraft, card limit, likely duplicate); saving again confirms. */
    var warning by mutableStateOf<String?>(null)
    private var warnedFor: String? = null
    private var saving = false
    /** The ID a new entry will get, fixed until it is saved, so a double tap cannot record it twice. */
    private var newId = Ids.newV7()
    var suggested by mutableStateOf<List<String>>(emptyList())
        private set
    var payeeSuggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    /** Set when this transaction belongs to a debt, loan or deposit: its type is fixed so the books stay consistent. */
    var linkedTo by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set
    var tagIds by mutableStateOf<List<String>>(emptyList())
    /** Receipts and bills on this transaction; written when it is saved. */
    val files = mutableStateListOf<FileItem>()

    /** Split lines; [splitMode] replaces the single category with several. */
    var splitMode by mutableStateOf(false)
        private set
    val splitLines = mutableStateListOf<SplitLine>()
    private var hadSplits = false

    /** The rules that matched the current payee, shown so suggestions are never a mystery. */
    var ruleOutcome by mutableStateOf<RuleOutcome?>(null)
        private set
    private var original: TransactionEntity? = null
    private var payeeJob: Job? = null
    /** Set when the form was opened for a found payment, so it leaves the "found" list once saved. */
    private var captureId: String? = null

    /** What a receipt photo says (read on the phone), while it is being read and once it is. */
    var receipt by mutableStateOf<app.kosh.ocr.ReceiptGuess?>(null)
        private set
    var readingReceipt by mutableStateOf(false)
        private set
    /** True once the receipt's amount, date and shop are in the form; the note then offers Undo. */
    var receiptApplied by mutableStateOf(false)
        private set
    private var beforeReceipt: Triple<String, String, LocalDate>? = null

    fun addFile(f: FileItem) {
        files += f
        readReceipt(f)
    }

    private fun readReceipt(f: FileItem) {
        if (isEdit || !f.isImage || !c.receipts.available || type == "transfer") return
        viewModelScope.launch {
            readingReceipt = true
            val lines = try { c.receipts.lines(c.repo.blobs.file(f.sha)) } catch (e: Exception) { emptyList() } finally { readingReceipt = false }
            // The reader itself could not start: say nothing rather than blame the photo.
            if (!c.receipts.available) return@launch
            val g = app.kosh.ocr.ReceiptParser.parse(lines, Dates.today(), java.util.Locale.getDefault().country == "US")
            receipt = g
            // An empty form is filled straight away; otherwise the values are offered.
            if (!g.isEmpty && amountMinor() == null) applyReceipt()
        }
    }

    fun applyReceipt() {
        val g = receipt ?: return
        beforeReceipt = Triple(expr, payee, date)
        g.total?.let { t ->
            val v = t.setScale(Currencies.digits(currency), java.math.RoundingMode.HALF_UP).stripTrailingZeros()
            expr = (if (v.scale() < 0) v.setScale(0) else v).toPlainString()
        }
        g.date?.let { date = it; time = null }
        if (g.merchant != null && payee.isBlank()) changePayee(g.merchant)
        receiptApplied = true
        error = null
    }

    fun undoReceipt() {
        beforeReceipt?.let { (e, p, d) -> expr = e; changePayee(p); date = d }
        receiptApplied = false
    }

    fun dismissReceipt() { receipt = null }

    val lookup get() = c.lookup.value

    init {
        viewModelScope.launch {
            val t = id?.let { c.repo.findTransaction(it) }
            if (t != null) {
                original = if (copy) null else t
                if (!copy) linkedTo = when {
                    t.debtId != null -> "a lent or borrowed record"
                    t.loanId != null -> "a loan"
                    t.depositId != null -> "a deposit"
                    else -> null
                }
                type = t.type
                expr = Money.toPlain(t.amount, lookup.currencyOf(t.accountId))
                t.toAmount?.let { toExpr = Money.toPlain(it, lookup.currencyOf(t.toAccountId)) }
                accountId = t.accountId
                toAccountId = t.toAccountId
                categoryId = t.categoryId
                categoryChosen = true
                date = if (copy) Dates.today() else LocalDate.parse(t.localDate)
                time = if (copy) null else Instant.ofEpochMilli(t.occurredAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
                payee = t.payee.orEmpty()
                note = t.note.orEmpty()
                status = t.status
                excluded = t.excludedFromReports
                tagIds = t.tagIds
                if (!copy) files += loadFiles(c, t.id)
                val splits = c.repo.splitsFor(t.id)
                if (splits.isNotEmpty()) {
                    hadSplits = !copy
                    splitMode = true
                    val cur = lookup.currencyOf(t.accountId)
                    splitLines += splits.map { s ->
                        SplitLine(if (copy) Ids.newV7() else s.id, s.categoryId, Money.toPlain(s.amount, cur), s.note.orEmpty())
                    }
                }
            } else {
                val active = lookup.activeAccounts
                val last = c.repo.lastUsedAccountId()?.takeIf { l -> active.any { it.id == l } }
                // Paying a card or loan: pay from the account used last, else the first one that holds money.
                val payer = if (presetTo != null) {
                    val ok = { a: AccountEntity -> !Lookup.isLiability(a.type) && a.id != presetTo }
                    active.firstOrNull { it.id == presetAccount && ok(it) } ?: active.firstOrNull { it.id == last && ok(it) } ?: active.firstOrNull(ok)
                } else null
                accountId = payer?.id ?: presetAccount ?: last ?: active.firstOrNull()?.id
                if (type == "transfer") toAccountId = presetTo?.takeIf { to -> active.any { it.id == to } } ?: active.firstOrNull { it.id != accountId }?.id
                presetAmount?.let { expr = it }
                // Something shared into the app (a bank SMS, a receipt photo): fill the form from it.
                app.kosh.SharedInbox.take()?.let { sh ->
                    captureId = sh.captureId
                    // "A/c XX1234": pick the account whose name or note carries those digits.
                    val byDigits = sh.accountHint?.let { hint -> active.firstOrNull { a -> a.name.contains(hint) || a.note?.contains(hint) == true } }
                    // A card alert with no matching digits: the only credit card is a safe guess.
                    val byKind = if (byDigits == null && sh.note?.contains("credit card", ignoreCase = true) == true) active.filter { it.type == "credit_card" }.singleOrNull() else null
                    (byDigits ?: byKind)?.let { accountId = it.id }
                    sh.at?.let { at ->
                        val z = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                        date = z.toLocalDate(); time = z.toLocalTime().withSecond(0).withNano(0)
                    }
                    sh.accountId?.takeIf { id -> active.any { it.id == id } }?.let { accountId = it }
                    sh.date?.let { date = it }
                    sh.payee?.let { changePayee(it) }
                    sh.note?.let { note = it }
                    sh.file?.let { addFile(fileFromStore(it)) }
                    sh.categoryId?.takeIf { lookup.categoryById.containsKey(it) }?.let { pickCategory(it) }
                    if (sh.tagIds.isNotEmpty()) tagIds = (tagIds + sh.tagIds).distinct()
                }
                if (presetTo != null && note.isBlank()) note = if (lookup.accountById[presetTo]?.type == "credit_card") "Credit card bill" else "Loan payment"
            }
            refreshSuggestions()
            baseline = snapshot()
            loaded = true
        }
    }

    private var baseline: List<Any?>? = null
    private fun snapshot(): List<Any?> = listOf(
        type, expr, toExpr, accountId, toAccountId, categoryId, date, time, payee, note, status, excluded, repeat, tagIds,
        files.toList(), splitMode, splitLines.toList(),
    )

    /** Something entered that closing would lose: any content in a new entry, any change to an existing one. */
    val hasUnsavedInput: Boolean
        get() = when {
            done || !loaded -> false
            isEdit -> baseline != snapshot()
            else -> expr.isNotBlank() || payee.isNotBlank() || note.isNotBlank() || files.any { !it.removed }
        }

    val currency: String get() = lookup.currencyOf(accountId)
    val toCurrency: String get() = lookup.currencyOf(toAccountId)
    val crossCurrency: Boolean get() = type == "transfer" && toAccountId != null && currency != toCurrency

    fun amountMinor(): Long? = Calculator.evaluate(expr)?.let { Money.fromDecimal(it, currency) }

    fun changeType(t: String) {
        if (t == type) return
        type = t
        if (t == "transfer") splitMode = false
        if (t == "transfer" && toAccountId == null) toAccountId = lookup.activeAccounts.firstOrNull { it.id != accountId }?.id
        // A category of the other kind no longer fits.
        val kind = lookup.categoryById[categoryId]?.kind
        if (t == "transfer" || (kind != null && kind != t)) {
            categoryId = null
            categoryChosen = false
        }
        viewModelScope.launch { refreshSuggestions() }
    }

    fun press(key: Char) {
        expr = Calculator.press(expr, key, Currencies.digits(currency))
        error = null
    }

    fun clearAmount() { expr = "" }

    fun pickCategory(id: String?) {
        categoryId = id
        categoryChosen = id != null
        error = null
    }

    fun changePayee(p: String) {
        payee = p
        payeeJob?.cancel()
        payeeJob = viewModelScope.launch {
            delay(250)
            payeeSuggestions = c.repo.payeeSuggestions(p).filter { !it.equals(p.trim(), ignoreCase = true) }.take(4)
            if (type == "transfer") return@launch
            // Your rules come first, then history: the same payee usually means the same category.
            val outcome = runRules()
            if (!categoryChosen && !splitMode && p.isNotBlank()) {
                val fromRule = outcome?.categoryId?.takeIf { fitsType(it) }
                categoryId = fromRule ?: c.repo.lastWithPayee(p, type)?.categoryId?.takeIf { fitsType(it) } ?: categoryId
            }
            outcome?.tagIds?.let { add -> tagIds = (tagIds + add.filter { lookup.tagById.containsKey(it) }).distinct() }
        }
    }

    private fun fitsType(categoryId: String): Boolean {
        val kind = lookup.categoryById[categoryId]?.kind ?: return false
        return kind == type || (type == "income" && kind == "expense") // income in an expense category is a refund
    }

    private fun runRules(): RuleOutcome? {
        if (type == "transfer" || payee.isBlank() && note.isBlank()) {
            ruleOutcome = null
            return null
        }
        val out = Rules.apply(c.rules.value, RuleInput(payee, note, type, accountId, amountMinor() ?: 0))
        ruleOutcome = out.takeIf { !it.isEmpty }
        return ruleOutcome
    }

    fun toggleSplit() {
        if (splitMode) {
            splitMode = false
            return
        }
        splitMode = true
        if (splitLines.isEmpty()) {
            val total = amountMinor()
            splitLines += SplitLine(Ids.newV7(), categoryId, total?.let { Money.toPlain(it, currency) }.orEmpty())
            splitLines += SplitLine(Ids.newV7(), null, "")
        }
        categoryId = null
        categoryChosen = false
    }

    fun addSplitLine() {
        val left = splitRemaining()
        splitLines += SplitLine(Ids.newV7(), null, left?.takeIf { it > 0 }?.let { Money.toPlain(it, currency) }.orEmpty())
    }

    fun updateSplit(i: Int, line: SplitLine) { if (i in splitLines.indices) splitLines[i] = line; error = null }
    fun removeSplit(i: Int) { if (i in splitLines.indices) splitLines.removeAt(i) }

    fun splitAmount(line: SplitLine): Long? = Calculator.evaluate(line.expr)?.let { Money.fromDecimal(it, currency) }

    /** Total minus the lines entered so far, or null while the total is empty. */
    fun splitRemaining(): Long? {
        val total = amountMinor() ?: return null
        return total - splitLines.sumOf { splitAmount(it) ?: 0 }
    }

    fun choosePayee(p: String) {
        changePayee(p)
        payeeSuggestions = emptyList()
    }

    private suspend fun refreshSuggestions() {
        if (type == "transfer") {
            suggested = emptyList()
            return
        }
        val used = c.repo.topCategories(type, 8).filter { lookup.categoryById[it]?.let { c -> !c.archived } == true }
        val roots = lookup.roots(type).map { it.id }
        suggested = (used + roots).distinct().take(8)
    }

    fun save(keepOpen: Boolean, onSaved: () -> Unit = {}) {
        if (saving) return
        val amount = amountMinor()
        val acc = accountId
        when {
            amount == null || amount <= 0 -> { error = "Enter an amount"; return }
            acc == null -> { error = "Choose an account"; return }
            type == "transfer" && toAccountId == null -> { error = "Choose where the money went"; return }
            type == "transfer" && toAccountId == acc -> { error = "Pick two different accounts"; return }
        }
        val split = splitMode && type != "transfer"
        if (split) {
            when {
                splitLines.size < 2 -> { error = "A split needs at least two lines"; return }
                splitLines.any { it.categoryId == null } -> { error = "Choose a category for every line"; return }
                splitLines.any { (splitAmount(it) ?: 0) <= 0 } -> { error = "Enter an amount for every line"; return }
                splitRemaining() != 0L -> {
                    val left = splitRemaining() ?: 0
                    error = if (left > 0) "${Money.ui(left, currency)} still to assign" else "Lines add up to ${Money.ui(-left, currency)} more than the total"
                    return
                }
            }
        }
        // Rules apply to new entries: fill a missing category and tidy the payee name.
        val outcome = if (!isEdit) runRules() else null
        if (!split && type != "transfer" && categoryId == null && !categoryChosen) {
            outcome?.categoryId?.takeIf { fitsType(it) }?.let { categoryId = it }
        }
        val finalPayee = outcome?.payee ?: payee.trim()
        val toAmount = if (type == "transfer") {
            if (crossCurrency) Money.parse(toExpr, toCurrency) ?: run { error = "Enter the amount received in $toCurrency"; return } else amount
        } else {
            null
        }
        val now = System.currentTimeMillis()
        val occurredAt = time?.let { date.atTime(it).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            ?: original?.takeIf { LocalDate.parse(it.localDate) == date }?.occurredAt ?: Dates.instantFor(date)
        val ruleId = if (!isEdit && repeat != null) Ids.newV7(now) else null
        val txId = when {
            original != null -> original!!.id
            ruleId != null -> Ids.recurringOccurrence(ruleId, date)
            else -> newId
        }
        val base = original ?: TransactionEntity(id = txId, type = type, accountId = acc!!, amount = amount!!, occurredAt = occurredAt, localDate = date.toString(), createdAt = now)
        val tx = base.copy(
            type = type, accountId = acc!!, amount = amount!!,
            toAccountId = if (type == "transfer") toAccountId else null, toAmount = toAmount,
            categoryId = if (type == "transfer" || split) null else categoryId,
            payee = finalPayee.ifEmpty { null }, note = note.trim().ifEmpty { null },
            occurredAt = occurredAt, localDate = date.toString(), tzOffsetMin = Dates.tzOffsetMinutes(occurredAt),
            status = status, excludedFromReports = excluded, recurringRuleId = ruleId ?: base.recurringRuleId,
            tagIds = tagIds.filter { lookup.tagById.containsKey(it) },
        )
        val lines = when {
            split -> splitLines.map { l ->
                SplitEntity(id = l.id, transactionId = txId, categoryId = l.categoryId!!, amount = splitAmount(l)!!, note = l.note.trim().ifEmpty { null })
            }
            hadSplits -> emptyList() // the split was turned off: remove its lines
            else -> null
        }
        saving = true
        viewModelScope.launch {
            // Warn once about overdrawing, passing a card limit or a likely duplicate; the same entry saved again goes through.
            val key = listOf(tx.type, tx.accountId, tx.toAccountId, tx.amount, tx.localDate, tx.status).joinToString("|")
            if (warnedFor != key) {
                val lines2 = ArrayList<String>()
                val account = lookup.accountById[tx.accountId]
                if (account != null && tx.type != "income" && tx.status != "planned") {
                    val bal = lookup.balances[account.id] ?: account.openingBalance
                    val back = original?.takeIf { it.accountId == account.id && it.status != "planned" && it.type != "income" }?.amount ?: 0
                    val after = bal + back - tx.amount
                    if (Lookup.isLiability(account.type)) {
                        account.creditLimit?.let { lim -> if (-after > lim) lines2 += "This takes ${account.name} past its ${Money.ui(lim, account.currency)} limit." }
                    } else if (after < 0 && bal + back >= 0) lines2 += "${account.name} has ${Money.ui(bal + back, account.currency)}; this takes it below zero."
                }
                if (original == null && tx.type != "transfer") c.repo.likelyDuplicate(tx)?.let { lines2 += "The same amount is already recorded on this account for that day${it.payee?.let { p -> " ($p)" } ?: ""}. Tap Save again if this is a second payment." }
                if (lines2.isNotEmpty()) { warning = lines2.joinToString("\n"); warnedFor = key; saving = false; return@launch }
            }
            warning = null
            try {
                if (lines == null) c.repo.saveTransaction(tx) else c.repo.saveTransactionWithSplits(tx, lines)
                saveFiles(c, txId, files.toList())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave Save stuck: say what went wrong and let the person try again.
                error = "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
                saving = false
                return@launch
            }
            if (ruleId != null) {
                val freq = repeat!!
                c.repo.saveRule(
                    RecurringRuleEntity(
                        id = ruleId, name = tx.payee, type = tx.type, accountId = tx.accountId, toAccountId = tx.toAccountId,
                        amount = tx.amount, toAmount = tx.toAmount, categoryId = tx.categoryId, payee = tx.payee, note = tx.note,
                        tagIds = tx.tagIds, frequency = freq, every = 1, startDate = date.toString(), mode = repeatMode,
                        nextDate = Recurrence.nextAfter(date, freq, 1, date)?.toString(), createdAt = now,
                    ),
                )
            }
            captureId?.let { c.captures.mark(it, "added") }
            captureId = null
            newId = Ids.newV7()
            warnedFor = null
            saving = false
            onSaved()
            if (keepOpen) {
                expr = ""; payee = ""; note = ""; repeat = null; categoryChosen = false
                splitMode = false; splitLines.clear(); hadSplits = false; ruleOutcome = null; files.clear()
            } else {
                done = true
            }
        }
    }

    fun delete(onDeleted: (TransactionEntity) -> Unit) {
        val t = original ?: return
        viewModelScope.launch {
            c.repo.deleteTransaction(t.id)
            onDeleted(t)
            done = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EditTransactionScreen(id: String?, initialType: String, copy: Boolean, presetAccount: String?, presetTo: String? = null, presetAmount: String? = null) {
    val vm = koshViewModel(key = "tx-$id-$copy-$initialType-$presetTo") { EditTxViewModel(it, id, initialType, copy, presetAccount, presetTo, presetAmount) }
    val c = LocalContainer.current
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var textFocused by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var pickAccount by remember { mutableStateOf<String?>(null) } // "from" | "to"
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var splitPick by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(vm.done) { if (vm.done) nav.popBackStack() }
    // Closing with ✕ or Back after typing something asks first, so an entry is never lost by a slip.
    var askDiscard by remember { mutableStateOf(false) }
    val close = { if (vm.hasUnsavedInput) askDiscard = true else nav.popBackStack() }
    androidx.activity.compose.BackHandler(enabled = vm.hasUnsavedInput) { askDiscard = true }
    if (askDiscard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(if (vm.isEdit) "Discard your changes?" else "Discard this entry?") },
            text = { Text(if (vm.isEdit) "The changes you made here will not be saved." else "What you entered will not be saved.") },
            confirmButton = { TextButton(onClick = { askDiscard = false; nav.popBackStack() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep editing") } },
        )
    }

    val title = when {
        vm.isEdit -> "Edit"
        vm.type == "income" -> "New income"
        vm.type == "transfer" -> "New transfer"
        else -> "New expense"
    }
    Scaffold(
        topBar = {
            KoshTopBar(title, onBack = { close() }, closeIcon = true) {
                if (vm.isEdit) {
                    IconButton(onClick = { nav.navigate(app.kosh.ui.Routes.editTx(id, vm.type, copy = true)) }) { Icon(Icons.Rounded.ContentCopy, "Duplicate") }
                    IconButton(onClick = {
                        vm.delete { t ->
                            c.appScope.launch(Dispatchers.Main) {
                                val r = snackbar.showSnackbar("Moved to Trash", actionLabel = "Undo", withDismissAction = true)
                                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) c.repo.restoreTransaction(t.id)
                            }
                        }
                    }) { Icon(Icons.Rounded.Delete, "Delete") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                TypeTabs(vm.type, enabled = { t -> vm.linkedTo == null || vm.type == t }, onPick = vm::changeType)

                vm.linkedTo?.let { what ->
                    Text(
                        "Part of $what. You can fix the amount, date or account here, but not the type; use the ${what.substringAfterLast(' ')} screen to change what it means.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                AmountDisplay(vm, onTap = { focus.clearFocus() })
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                vm.warning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                ReceiptNote(vm)

                // Accounts
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    AccountChip(lookup.accountById[vm.accountId], if (vm.type == "transfer") "From" else "Account", Modifier.weight(1f)) { pickAccount = "from" }
                    if (vm.type == "transfer") {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                        AccountChip(lookup.accountById[vm.toAccountId], "To", Modifier.weight(1f)) { pickAccount = "to" }
                    }
                }
                if (vm.crossCurrency) {
                    app.kosh.ui.components.AmountField(
                        vm.toExpr, { vm.toExpr = it }, vm.toCurrency, "Amount received in ${vm.toCurrency}",
                        supporting = "Different currencies: enter what arrived.",
                    )
                }

                // Categories
                if (vm.type != "transfer" && vm.splitMode) {
                    // Split amounts use the system keyboard, so the calculator pad hides while one is focused.
                    SplitEditor(vm, lookup, onPickCategory = { splitPick = it }, onTextFocus = { textFocused = it })
                } else if (vm.type != "transfer") {
                    Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Category", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        vm.categoryId?.let { Text(lookup.categoryPath(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                    }
                    val shown = vm.suggested.take(8).let { s -> if (vm.categoryId != null && vm.categoryId !in s) listOf(vm.categoryId!!) + s else s }
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    ) {
                        items(shown.size) { i ->
                            val cid = shown[i]
                            val cat = lookup.categoryById[cid] ?: return@items
                            val (icon, color) = lookup.categoryLook(cid)
                            CategoryTile(cat.name, selected = vm.categoryId == cid, onClick = { vm.pickCategory(if (vm.categoryId == cid) null else cid) }) {
                                IconBadge(icon, color, size = 44.dp)
                            }
                        }
                        item {
                            CategoryTile("More", selected = false, onClick = { showCategories = true }) {
                                app.kosh.ui.components.IconTile(Icons.Rounded.MoreHoriz, MaterialTheme.colorScheme.onSurfaceVariant, size = 44.dp)
                            }
                        }
                        item {
                            CategoryTile("Split", selected = false, onClick = vm::toggleSplit) {
                                app.kosh.ui.components.IconTile(Icons.AutoMirrored.Rounded.CallSplit, MaterialTheme.colorScheme.onSurfaceVariant, size = 44.dp)
                            }
                        }
                    }
                    if (vm.type == "income" && lookup.categoryById[vm.categoryId]?.kind == "expense") {
                        Text("Income in an expense category counts as a refund and reduces that spending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Date
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                    val today = Dates.today()
                    FilterChip(vm.date == today, { vm.date = today }, { Text("Today") })
                    FilterChip(vm.date == today.minusDays(1), { vm.date = today.minusDays(1) }, { Text("Yesterday") })
                    FilterChip(
                        vm.date != today && vm.date != today.minusDays(1), { showDate = true },
                        { Text(if (vm.date != today && vm.date != today.minusDays(1)) Dates.relativeDay(vm.date) else "Pick date") },
                        leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        vm.time != null, { showTime = true },
                        { Text(vm.time?.let { app.kosh.core.Dates.time(it, android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)) } ?: "Time") },
                    )
                }

                // Payee & note
                if (vm.type != "transfer") {
                    OutlinedTextField(
                        vm.payee, vm::changePayee,
                        label = { Text(if (vm.type == "income") "From (payer)" else "Paid to (payee)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).onFocusChanged { textFocused = it.isFocused },
                    )
                    if (vm.payeeSuggestions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (p in vm.payeeSuggestions) AssistChip(onClick = { vm.choosePayee(p) }, label = { Text(p) })
                        }
                    }
                    vm.ruleOutcome?.let { o ->
                        val what = buildList {
                            o.categoryId?.let { add(lookup.categoryPath(it)) }
                            if (!vm.isEdit) o.payee?.let { add("saved as “$it”") }
                            lookup.tagNames(o.tagIds).forEach { add("#$it") }
                        }.joinToString(", ")
                        val rule = o.matched.first()
                        Text(
                            "Rule “${rule.name ?: rule.pattern}”: $what",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp).clickable { nav.navigate(app.kosh.ui.Routes.editCatRule(rule.id)) },
                        )
                    }
                }
                OutlinedTextField(
                    vm.note, { vm.note = it }, label = { Text("Note") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).onFocusChanged { textFocused = it.isFocused },
                    maxLines = 3,
                )

                ReceiptsField(vm.files, onAdd = { vm.addFile(it) }, onRemove = { f ->
                    val i = vm.files.indexOfFirst { it.id == f.id }
                    if (i >= 0) { if (f.existing) vm.files[i] = f.copy(removed = true) else vm.files.removeAt(i) }
                })

                // Tags
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    for (tid in vm.tagIds) {
                        val tag = lookup.tagById[tid] ?: continue
                        InputChip(
                            selected = true, onClick = { vm.tagIds = vm.tagIds - tid }, label = { Text("#" + tag.name) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove tag ${tag.name}", Modifier.size(16.dp)) },
                        )
                    }
                    AssistChip(onClick = { showTags = true }, label = { Text(if (vm.tagIds.isEmpty()) "Add tag" else "Tags") }, leadingIcon = { Icon(Icons.Rounded.Sell, null, Modifier.size(18.dp)) })
                }

                // More options
                Row(
                    Modifier.fillMaxWidth().clickable { more = !more }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("More options", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                AnimatedVisibility(more) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Status", style = MaterialTheme.typography.labelMedium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("cleared" to "Cleared", "pending" to "Pending", "planned" to "Planned").forEachIndexed { i, (s, l) ->
                                SegmentedButton(vm.status == s, { vm.status = s }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                            }
                        }
                        if (vm.status == "planned") Text("Planned items don't change balances or reports until you mark them cleared.", style = MaterialTheme.typography.bodySmall)
                        if (vm.type != "transfer") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Exclude from reports")
                                    Text("For example, an expense someone will pay back", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(vm.excluded, { vm.excluded = it })
                            }
                        }
                        if (!vm.isEdit && !vm.splitMode) {
                            Text("Repeat", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(null to "Never", "weekly" to "Weekly", "monthly" to "Monthly", "yearly" to "Yearly", "daily" to "Daily").forEach { (f, l) ->
                                    FilterChip(vm.repeat == f, { vm.repeat = f }, { Text(l) })
                                }
                            }
                            if (vm.repeat != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Add automatically")
                                        Text(if (vm.repeatMode == "auto") "Recorded on each due date" else "You'll be reminded to confirm each one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(vm.repeatMode == "auto", { vm.repeatMode = if (it) "auto" else "remind" })
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (!textFocused) {
                AmountPad(onKey = { focus.clearFocus(); vm.press(it) }, onClear = vm::clearAmount, modifier = Modifier.padding(top = 4.dp))
            }
            SaveButton(
                label = if (vm.warning != null) "Save anyway" else if (vm.isEdit) "Save changes" else "Save",
                onClick = { vm.save(keepOpen = false) },
                onLongClick = if (vm.isEdit) null else ({
                    vm.save(keepOpen = true) { c.appScope.launch(Dispatchers.Main) { snackbar.showSnackbar("Saved. Add the next one.") } }
                }),
                ready = vm.amountMinor()?.let { it > 0 } == true,
            )
        }
    }

    if (showCategories) CategorySheet(lookup, if (vm.type == "income") "income" else "expense", vm.categoryId, onDismiss = { showCategories = false }) { vm.pickCategory(it) }
    if (showTags) TagPickerSheet(lookup, vm.tagIds, onDismiss = { showTags = false }) { vm.tagIds = it }
    splitPick?.let { i ->
        val line = vm.splitLines.getOrNull(i)
        CategorySheet(lookup, if (vm.type == "income") "income" else "expense", line?.categoryId, onDismiss = { splitPick = null }) { id ->
            if (line != null) vm.updateSplit(i, line.copy(categoryId = id))
        }
    }
    pickAccount?.let { which ->
        PickerSheet(
            title = if (which == "to") "To account" else "Account",
            items = lookup.activeAccounts,
            onDismiss = { pickAccount = null },
            onPick = { a -> if (which == "to") vm.toAccountId = a.id else vm.accountId = a.id },
            searchText = { it.name },
            selected = { it.id == (if (which == "to") vm.toAccountId else vm.accountId) },
        ) { a -> AccountListItem(a, lookup) }
    }
    if (showTime) {
        val now = java.time.LocalTime.now()
        val state = androidx.compose.material3.rememberTimePickerState(vm.time?.hour ?: now.hour, vm.time?.minute ?: now.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current))
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Time of day") },
            text = { androidx.compose.material3.TimePicker(state) },
            confirmButton = { TextButton(onClick = { vm.time = java.time.LocalTime.of(state.hour, state.minute); showTime = false }) { Text("OK") } },
            dismissButton = { Row { if (vm.time != null) TextButton(onClick = { vm.time = null; showTime = false }) { Text("Use now") }; TextButton(onClick = { showTime = false }) { Text("Cancel") } } },
        )
    }
    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun AmountDisplay(vm: EditTxViewModel, onTap: () -> Unit) {
    val info = Currencies.get(vm.currency)
    val color = typeColor(vm.type)
    val shown = vm.expr.ifEmpty { "0" }
    val result = if (Calculator.hasOperator(vm.expr)) vm.amountMinor() else null
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = "Amount ${vm.expr.ifEmpty { "zero" }} ${info.name}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.symbol.trim(), fontSize = 30.sp, color = color.copy(alpha = 0.8f), fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 6.dp))
            Text(
                shown, style = MaterialTheme.typography.displayLarge.copy(fontSize = if (shown.length > 9) 44.sp else 56.sp),
                color = if (vm.expr.isEmpty()) color.copy(alpha = 0.35f) else color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (result == null) {
            Text(
                when (vm.type) { "income" -> "Money in"; "transfer" -> "Moved between your accounts"; else -> "Money out" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result != null) {
            Text("= ${Money.ui(result, vm.currency)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountChip(a: AccountEntity?, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(a?.icon ?: "wallet", a?.color, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(a?.name ?: "Choose", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun AccountListItem(a: AccountEntity, lookup: Lookup) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(a.name) },
        supportingContent = { Text(accountTypeLabel(a.type)) },
        leadingContent = { IconBadge(a.icon, a.color) },
        trailingContent = { app.kosh.ui.components.MoneyText(lookup.balances[a.id] ?: a.openingBalance, a.currency) },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SaveButton(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)?, ready: Boolean = true) {
    Surface(
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp).height(52.dp)
            .semantics { contentDescription = if (onLongClick != null) "$label. Long press to save and add another." else label }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Two-level category chooser: tap a group or one of its sub-categories. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategorySheet(lookup: Lookup, kind: String, selected: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search categories") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val roots: List<CategoryEntity> = lookup.roots(kind)
            for (root in roots) {
                val kids = lookup.children(root.id)
                val q = query.trim()
                val rootMatch = q.isEmpty() || root.name.contains(q, true)
                val kidsShown = if (q.isEmpty() || rootMatch) kids else kids.filter { it.name.contains(q, true) }
                if (!rootMatch && kidsShown.isEmpty()) continue
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(root.id); onDismiss() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(root.icon, root.color, size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(root.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (selected == root.id) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
                if (kidsShown.isNotEmpty()) {
                    FlowRow(Modifier.padding(start = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in kidsShown) {
                            FilterChip(selected == k.id, { onPick(k.id); onDismiss() }, { Text(k.name) })
                        }
                    }
                }
            }
            if (kind == "income") {
                Text("Refund? Pick from your expense categories:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (root in lookup.roots("expense")) {
                        if (query.isNotBlank() && !root.name.contains(query.trim(), true)) continue
                        FilterChip(selected == root.id, { onPick(root.id); onDismiss() }, { Text(root.name) }, leadingIcon = { IconBadge(root.icon, root.color, size = 20.dp) })
                    }
                }
            }
            Box(Modifier.height(8.dp))
        }
    }
}

fun accountTypeLabel(type: String) = when (type) {
    "cash" -> "Cash"
    "bank" -> "Bank account"
    "wallet" -> "Wallet / UPI"
    "credit_card" -> "Credit card"
    "savings" -> "Savings"
    "investment" -> "Investment"
    "deposit" -> "Fixed / recurring deposit"
    "loan" -> "Loan"
    else -> "Other"
}

/** One editable split line: [expr] may be arithmetic, like the main amount. */
data class SplitLine(val id: String, val categoryId: String?, val expr: String, val note: String = "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitEditor(vm: EditTxViewModel, lookup: Lookup, onPickCategory: (Int) -> Unit, onTextFocus: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Split across categories", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::toggleSplit) { Text("Don't split") }
    }
    vm.splitLines.forEachIndexed { i, line ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            val (icon, color) = lookup.categoryLook(line.categoryId)
            Surface(
                onClick = { onPickCategory(i) }, shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1.2f),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon, color, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (line.categoryId == null) "Category" else lookup.categoryName(line.categoryId),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        color = if (line.categoryId == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            OutlinedTextField(
                line.expr, { vm.updateSplit(i, line.copy(expr = it)) }, singleLine = true,
                placeholder = { Text("0") }, prefix = { Text(Currencies.get(vm.currency).symbol.trim()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).onFocusChanged { onTextFocus(it.isFocused) },
            )
            IconButton(onClick = { vm.removeSplit(i) }, enabled = vm.splitLines.size > 2) { Icon(Icons.Rounded.Close, "Remove line") }
        }
    }
    val left = vm.splitRemaining()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = vm::addSplitLine) { Text("+ Add line") }
        Spacer(Modifier.weight(1f))
        when {
            left == null -> Text("Enter the total first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            left == 0L -> Text("All assigned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            left > 0 -> Text("${Money.ui(left, vm.currency)} left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> Text("${Money.ui(-left, vm.currency)} over", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Expense / Income / Transfer as one pill switch, each in its own colour so the choice is obvious. */
@Composable
private fun TypeTabs(type: String, enabled: (String) -> Boolean, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((t, label) in listOf("expense" to "Expense", "income" to "Income", "transfer" to "Transfer")) {
            val sel = type == t
            val color = typeColor(t)
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (sel) color else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(enabled = enabled(t), role = androidx.compose.ui.semantics.Role.Tab) { onPick(t) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    color = when {
                        sel -> app.kosh.ui.components.contentOn(color)
                        !enabled(t) -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** A category as an icon with its name underneath; selected tiles get a tinted background. */
@Composable
private fun CategoryTile(name: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp)
            .semantics { contentDescription = name + if (selected) ", selected" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(4.dp))
        Text(
            name, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Read from the receipt: ₹1,234 · DMart · 12 Oct" with Use or Undo, or a note that it couldn't be read. */
@Composable
private fun ReceiptNote(vm: EditTxViewModel) {
    if (vm.readingReceipt) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Reading the receipt…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
        return
    }
    val g = vm.receipt ?: return
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DocumentScanner, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                if (g.isEmpty) {
                    Text("Couldn't read this receipt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Try a closer, flatter photo in good light, or enter it yourself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    Text(if (vm.receiptApplied) "Filled in from the receipt" else "The receipt says", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        listOfNotNull(g.total?.let { Money.ui(Money.fromDecimal(it, vm.currency) ?: 0, vm.currency) }, g.merchant, g.date?.let { Dates.medium(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                g.isEmpty -> IconButton(onClick = vm::dismissReceipt) { Icon(Icons.Rounded.Close, "Dismiss") }
                vm.receiptApplied -> TextButton(onClick = vm::undoReceipt) { Text("Undo") }
                else -> TextButton(onClick = vm::applyReceipt) { Text("Use") }
            }
        }
    }
}
