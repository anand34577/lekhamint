package app.kosh.ui.screens

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.SharedInbox
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.data.AutoFill
import app.kosh.data.db.AccountEntity
import app.kosh.domain.QuickEntry
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.Routes
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.PickerSheet
import app.kosh.ui.rememberSafeScope
import app.kosh.ui.theme.LocalMoneyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Type or say one line ("450 swiggy dinner yesterday @hdfc") and save. The preview shows exactly what will be
 * saved, and every guess can be changed with a tap before saving. Works offline; voice uses the phone's own
 * speech recogniser when it has one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickScreen(startVoice: Boolean = false) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberSafeScope()
    val snackbar = LocalSnackbar.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val draft = remember(field.text) { QuickEntry.parse(field.text, Dates.today()) }
    // What the person changed by hand wins over the guesses.
    var typeOverride by remember { mutableStateOf<String?>(null) }
    var categoryOverride by remember { mutableStateOf<String?>(null) }
    var accountOverride by remember { mutableStateOf<String?>(null) }
    var guess by remember { mutableStateOf<AutoFill.Guess?>(null) }
    var pickCategory by remember { mutableStateOf(false) }
    var pickAccount by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val type = typeOverride ?: draft.type
    val account: AccountEntity? = accountOverride?.let { lookup.accountById[it] } ?: guess?.account
    val currency = account?.currency ?: lookup.baseCurrency
    val amount = draft.amount?.let { Money.fromDecimal(it, currency) }?.takeIf { it > 0 }
    val categoryId = categoryOverride ?: guess?.categoryId

    LaunchedEffect(field.text, type) {
        delay(150)
        guess = c.autoFill.guess(type, payee = null, amount = amount ?: 0, accountQuery = draft.account, text = draft.text)
    }

    val focus = remember { FocusRequester() }
    val canSpeak = remember { app.kosh.ui.speechAvailable(context) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) field = TextFieldValue(heard, TextRange(heard.length))
    }
    fun listen() {
        runCatching {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say it like “450 dinner at Swiggy yesterday”"),
            )
        }
    }
    LaunchedEffect(Unit) {
        if (startVoice && canSpeak) listen() else runCatching { focus.requestFocus() }
    }

    fun save() {
        val acc = account ?: return
        val minor = amount ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                val tags = (guess?.tagIds.orEmpty() + c.autoFill.tagsFor(draft.tags)).distinct()
                val payee = guess?.payee
                val tx = c.autoFill.build(type, acc, minor, categoryId, payee, null, tags, date = draft.date)
                c.repo.saveTransaction(tx)
                nav.popBackStack()
                // This screen is gone once it pops; the confirmation (and its Undo) lives on in the app scope.
                val message = "Added ${AutoFill.summary(lookup, minor, acc.currency, categoryId, payee)}"
                c.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    val r = snackbar.showSnackbar(message, "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) c.repo.deleteTransaction(tx.id)
                }
            } finally {
                saving = false
            }
        }
    }

    fun moreDetails() {
        SharedInbox.pending = SharedInbox.Shared(
            payee = guess?.payee, note = null, file = null,
            accountId = account?.id, categoryId = categoryId, tagIds = guess?.tagIds.orEmpty(), date = draft.date,
        )
        nav.popBackStack()
        nav.navigate(Routes.editTx(type = type, amount = draft.amount?.toPlainString()))
    }

    Scaffold(topBar = { KoshTopBar("Quick add", onBack = { nav.popBackStack() }, closeIcon = true) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                field, { field = it; categoryOverride = null },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("450 swiggy dinner yesterday") },
                label = { Text("Type it the way you'd say it") },
                textStyle = MaterialTheme.typography.titleMedium,
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (amount != null) save() }),
                trailingIcon = if (canSpeak) { { IconButton(onClick = ::listen) { Icon(Icons.Rounded.Mic, "Speak") } } } else null,
            )

            if (field.text.isBlank()) {
                Examples { field = TextFieldValue(it, TextRange(it.length)) }
            } else {
                KoshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(guess?.payee ?: if (type == "income") "Money received" else "Expense", style = MaterialTheme.typography.titleMedium)
                                Text(Dates.relativeDay(draft.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (amount != null) {
                                MoneyText(if (type == "income") amount else -amount, currency, signed = type == "income",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = if (type == "income") LocalMoneyColors.current.income else MaterialTheme.colorScheme.onSurface)
                            } else {
                                Text("Add an amount", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(type == "expense", { typeOverride = "expense"; categoryOverride = null }, { Text("Expense") })
                            FilterChip(type == "income", { typeOverride = "income"; categoryOverride = null }, { Text("Income") })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AssistChip({ pickCategory = true }, { Text(categoryId?.let { lookup.categoryPath(it) } ?: "Pick a category") },
                                leadingIcon = { Icon(Icons.Rounded.Category, null, Modifier.size(18.dp)) })
                            AssistChip({ pickAccount = true }, { Text(account?.name ?: "Pick an account") },
                                leadingIcon = { Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.size(18.dp)) })
                            if (draft.datedByText) AssistChip({}, { Text(Dates.medium(draft.date)) }, leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(18.dp)) })
                            for (t in draft.tags) AssistChip({}, { Text(t) }, leadingIcon = { Icon(Icons.Rounded.Tag, null, Modifier.size(18.dp)) })
                        }
                        guess?.categoryReason?.takeIf { categoryOverride == null && categoryId != null }?.let {
                            Text("Category from $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = ::moreDetails, modifier = Modifier.weight(1f).height(52.dp)) { Text("More details") }
                    Button(onClick = ::save, enabled = amount != null && account != null && !saving, modifier = Modifier.weight(1f).height(52.dp)) { Text("Save") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickCategory) CategorySheet(lookup, type, categoryId, onDismiss = { pickCategory = false }) { categoryOverride = it }
    if (pickAccount) {
        PickerSheet("Account", lookup.activeAccounts, onDismiss = { pickAccount = false }, onPick = { accountOverride = it.id },
            searchText = { it.name }, selected = { it.id == account?.id }) { AccountListItem(it, lookup) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Examples(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Try one of these", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (e in listOf("chai 20", "450 swiggy dinner yesterday", "1200 electricity bill @bank", "+50000 salary", "250 uber 12 oct #work")) {
            Text(
                e, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = 6.dp),
            )
        }
        Text(
            "The first number is the amount; + makes it income. Add a day (yesterday, monday, 12 oct), " +
                "@account and #tags anywhere. Your rules and past entries pick the category.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
