package app.kosh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import app.kosh.SharedInbox
import app.kosh.capture.CaptureStore
import app.kosh.capture.Captured
import app.kosh.core.Money
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.components.HeroCard
import app.kosh.ui.components.IconTile
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.LocalOnHero
import app.kosh.ui.components.MoneyText
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.SettingRow
import app.kosh.ui.components.ago
import app.kosh.ui.components.StatusPill
import app.kosh.ui.theme.LocalMoneyColors

const val FOUND_PAYMENTS_ROUTE = "found_payments"

/** Opens the add form filled from a found payment. */
fun openFound(nav: androidx.navigation.NavController, c: Captured, currency: String) {
    SharedInbox.pending = SharedInbox.Shared(c.payee, c.text.take(200), null, captureId = c.id, accountHint = c.accountHint, at = c.at)
    nav.navigate(Routes.editTx(type = c.type, amount = c.amount.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: c.amount))
}

/**
 * Payments read from bank and payment app alerts: set-up (notification access), then a list to add or ignore.
 * Nothing is added on its own; the person checks each one.
 */
@Composable
fun FoundPaymentsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val items by c.captures.items.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(CaptureStore.hasAccess(context)) }
    // Coming back from system settings: check again.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { access = CaptureStore.hasAccess(context) } }
    // Asking to be told about found payments needs Android's notification permission (Android 13 and later).
    fun canNotify() = android.os.Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var notifyAllowed by remember { mutableStateOf(canNotify()) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        notifyAllowed = ok
        if (ok) c.settings.setCaptureNotify(true)
    }
    fun setNotify(on: Boolean) {
        if (on && !notifyAllowed && android.os.Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else c.settings.setCaptureNotify(on)
    }
    val cur = settings.baseCurrency
    val scope = app.kosh.ui.rememberSafeScope()
    val snackbar = app.kosh.ui.LocalSnackbar.current
    val fresh = items.filter { it.state == "new" }
    val done = items.filter { it.state != "new" }.take(10)

    Scaffold(topBar = {
        KoshTopBar("Found payments", onBack = { nav.popBackStack() }) {
            if (fresh.size > 1) TextButton(onClick = { c.captures.ignoreAllNew() }) { Text("Ignore all") }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (!access) {
                item { SetupCard(onTurnOn = { runCatching { context.startActivity(CaptureStore.accessSettingsIntent(context)) } }) }
            } else {
                item {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column {
                            SettingRow("Watching for payments", "Bank SMS, card alerts and payment apps, read on this phone", Icons.Rounded.MarkEmailRead,
                                tint = LocalMoneyColors.current.income,
                                onClick = { runCatching { context.startActivity(CaptureStore.accessSettingsIntent(context)) } }) {
                                StatusPill("On", LocalMoneyColors.current.income)
                            }
                            val notifyOn = settings.captureNotify && notifyAllowed
                            SettingRow("Tell me when one is found",
                                if (settings.captureNotify && !notifyAllowed) "Allow notifications for LekhaMint to get these" else "A notification you can tap to add it",
                                Icons.Rounded.NotificationsActive, onClick = { setNotify(!notifyOn) }) {
                                Switch(notifyOn, { setNotify(it) })
                            }
                        }
                    }
                }
            }
            if (fresh.isNotEmpty()) {
                item { SectionHeader("To review (${fresh.size})") }
                items(fresh, key = { it.id }) { x ->
                    FoundCard(x, cur, onAdd = { openFound(nav, x, cur) }, onIgnore = { c.captures.mark(x.id, "ignored") }, onSaved = { plan ->
                        scope.launch {
                            val r = snackbar.showSnackbar("Saved ${Money.ui(plan.tx.amount, plan.currency)} · ${plan.summary}", "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) app.kosh.capture.CaptureSaver.undo(c, x.id)
                        }
                    })
                }
            } else if (access) {
                item {
                    Text(
                        "Nothing to review. When a bank or payment app tells you about a payment, it shows up here ready to add.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            if (done.isNotEmpty()) {
                item { SectionHeader("Recently handled", action = "Clear", onAction = { c.captures.clear() }) }
                items(done, key = { "d-" + it.id }) { x ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(x.payee ?: x.source, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (x.state == "added") "Added" else "Ignored"} · ${ago(x.at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Money.parse(x.amount, cur)?.let { MoneyText(it, cur, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (x.state == "ignored") TextButton(onClick = { c.captures.mark(x.id, "new") }) { Text("Undo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(onTurnOn: () -> Unit) {
    HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val on = LocalOnHero.current
        Text("Log payments without typing", style = MaterialTheme.typography.titleLarge, color = on)
        Text(
            "When your bank, card or a payment app sends an alert, LekhaMint reads the amount and payee and lists it here. You check each one and tap Add.",
            style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(14.dp))
        for ((icon, text) in listOf(
            Icons.Rounded.CreditCard to "Works with bank SMS, card alerts, UPI and wallet apps",
            Icons.Rounded.Lock to "Read on this phone only. Nothing is uploaded, and chat apps are skipped",
            Icons.Rounded.TouchApp to "Nothing is saved until you tap Add",
        )) {
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = on.copy(alpha = 0.9f))
                Text(text, style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.9f), modifier = Modifier.padding(start = 10.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onTurnOn,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = on, contentColor = MaterialTheme.colorScheme.primary),
        ) { Text("Turn on in settings") }
        Text(
            "Android will ask you to allow notification access for LekhaMint. You can turn it off there at any time.",
            style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.75f), modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FoundCard(x: Captured, cur: String, onAdd: () -> Unit, onIgnore: () -> Unit, onSaved: (app.kosh.capture.CaptureSaver.Plan) -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    // What one tap on Save would record: the guessed category and account, and whether it looks recorded already.
    var plan by remember(x.id) { mutableStateOf<app.kosh.capture.CaptureSaver.Plan?>(null) }
    LaunchedEffect(x.id) { plan = runCatching { app.kosh.capture.CaptureSaver.plan(c, x) }.getOrNull() }
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val m = LocalMoneyColors.current
    val income = x.type == "income"
    var expanded by remember { mutableStateOf(false) }
    val account = x.accountHint?.let { h -> lookup.activeAccounts.firstOrNull { it.name.contains(h) || it.note?.contains(h) == true } }
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(if (income) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, if (income) m.income else m.expense, size = 40.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(x.payee ?: if (income) "Money received" else "Payment", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${x.source} · ${ago(x.at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val minor = Money.parse(x.amount, cur)
                if (minor != null) MoneyText(if (income) minor else -minor, cur, style = MaterialTheme.typography.titleMedium, color = if (income) m.income else m.expense, signed = income)
            }
            if (x.accountHint != null) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(account?.name ?: "Account ••${x.accountHint}", MaterialTheme.colorScheme.primary, icon = Icons.Rounded.CreditCard)
                }
            }
            Text(
                x.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
            val p = plan
            if (p != null) {
                Text(
                    if (p.duplicate != null) "Looks already recorded${p.duplicate.payee?.let { " ($it)" } ?: ""}"
                    else if (p.hasCategory) "Saves as ${p.summary}" else "Saves to ${p.summary} · pick a category with Edit",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (p.duplicate != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onIgnore) { Text(if (p?.duplicate != null) "Already added" else "Ignore") }
                OutlinedButton(onClick = onAdd) { Text("Edit") }
                if (p != null && p.duplicate == null) {
                    Button(onClick = {
                        scope.launch { app.kosh.capture.CaptureSaver.save(c, x)?.let(onSaved) }
                    }) { Text("Save") }
                } else if (p == null) {
                    FilledTonalButton(onClick = onAdd) { Text("Add") }
                }
            }
        }
    }
}

/** A card for Home when payments are waiting. */
@Composable
fun FoundPaymentsBanner(onOpen: () -> Unit) {
    val c = LocalContainer.current
    val items by c.captures.items.collectAsStateWithLifecycle()
    val fresh = items.count { it.state == "new" }
    if (fresh == 0) return
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onOpen) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.MarkEmailRead, MaterialTheme.colorScheme.primary, size = 38.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("$fresh ${if (fresh == 1) "payment" else "payments"} found", style = MaterialTheme.typography.titleSmall)
                Text("From your bank and payment app alerts. Tap to review.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill("Review", MaterialTheme.colorScheme.primary)
        }
    }
}
