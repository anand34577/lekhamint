package app.kosh.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.kosh.data.ThemePalette
import app.kosh.ui.components.KoshCard
import app.kosh.ui.theme.paletteSwatch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.BuildConfig
import app.kosh.core.Links
import app.kosh.data.ThemeMode
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.Routes
import app.kosh.ui.components.CurrencyPickerSheet
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.SettingRow
import app.kosh.ui.components.ago
import kotlinx.coroutines.launch

// Icon colours per group, so the menu can be scanned by colour as well as by word.
private val CMoney = Color(0xFF0E9F6E)
private val CPlan = Color(0xFF2563EB)
private val CPeople = Color(0xFF0891B2)
private val COrganise = Color(0xFF7C3AED)
private val CTools = Color(0xFFD97706)
private val CData = Color(0xFF0B7A6E)
private val CLook = Color(0xFFDB2777)
private val CSafe = Color(0xFF475569)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val pending by c.repo.pendingCount.collectAsStateWithLifecycle(0)
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = app.kosh.ui.rememberSafeScope()
    var themeSheet by remember { mutableStateOf(false) }
    var currencySheet by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var notificationsGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
    val inUse by rememberFeaturesInUse(c)
    // In simple mode a tool shows once it is turned on in Features or the book already uses it.
    fun show(key: String) = Features.visible(s, inUse, key)
    @Composable
    fun FeatureRow(key: String) {
        if (!show(key)) return
        val f = Features.byKey(key)
        SettingRow(f.title, f.subtitle, f.icon, tint = f.tint, onClick = { nav.navigate(f.route) })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("More") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                // Find a page or setting by name or by everyday words ("dark", "password", "export").
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    placeholder = { Text("Search settings and tools") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (query.isNotBlank()) {
                val links = moreLinks(
                    open = { r -> if (r in Routes.tabs) nav.navigate(r) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true } else nav.navigate(r) }, theme = { themeSheet = true }, currency = { currencySheet = true },
                    hide = { c.settings.setHideAmounts(!s.hideAmounts) }, notices = { notices = true }, rename = { renameDialog = true },
                )
                val words = query.trim().lowercase().split(Regex("\\s+"))
                val hits = links.filter { l -> words.all { w -> l.text.contains(w) } }
                item {
                    if (hits.isEmpty()) {
                        Text(
                            "Nothing matches “${query.trim()}”. Try a simpler word, like budget, bank or backup.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                        )
                    } else {
                        Group("Results") {
                            for (l in hits) SettingRow(l.title, l.subtitle, l.icon, tint = l.tint, onClick = { query = ""; l.go() })
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                // The book: name, currency and sync state in one card.
                KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = { renameDialog = true }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Text(s.ledgerName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary) }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(s.ledgerName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Main currency ${s.baseCurrency} · " + if (s.syncEnabled) "synced as ${s.accountEmail}" else "on this phone only",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.Edit, "Rename book", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Group("Money") {
                    SettingRow("Accounts", "Cash, bank, cards, wallets", Icons.Rounded.AccountBalanceWallet, tint = CMoney, onClick = { nav.navigate(Routes.ACCOUNTS) })
                    SettingRow("Reports", "Charts of spending, trends and 50/30/20", Icons.AutoMirrored.Rounded.ShowChart, tint = CMoney, onClick = { nav.navigate(Routes.REPORTS) })
                    SettingRow(if (app.kosh.BuildConfig.READS_ALERTS) "Find payments automatically" else "Add from bank messages",
                        if (app.kosh.BuildConfig.READS_ALERTS) "Read bank and payment alerts, add with one tap" else "Share an SMS or alert to LekhaMint", Icons.Rounded.MarkEmailRead, tint = CMoney, onClick = { nav.navigate(FOUND_PAYMENTS_ROUTE) })
                    SettingRow("Import a statement", "From your bank: CSV, Excel, PDF, OFX or QIF", Icons.Rounded.UploadFile, tint = CMoney, onClick = { nav.navigate(Routes.IMPORT) })
                    SettingRow("Weekly check-in", if (s.weeklyCheckIn) "Sunday evening: the week and what needs a look" else "Off · open it any time", Icons.Rounded.EventAvailable, tint = CMoney, onClick = { nav.navigate(Routes.WEEKLY) }) {
                        Switch(s.weeklyCheckIn, { c.settings.setWeeklyCheckIn(it) })
                    }
                    SettingRow("Insights & forecast", "Safe to spend, cash forecast, subscriptions", Icons.Rounded.Insights, tint = CMoney, onClick = { nav.navigate(Routes.INSIGHTS) })
                    SettingRow("Recurring & bills", "Subscriptions, rent, salary", Icons.Rounded.Repeat, tint = CPlan, onClick = { nav.navigate(Routes.RECURRING) })
                    FeatureRow("goals")
                    FeatureRow("loans")
                    FeatureRow("deposits")
                    FeatureRow("debts")
                }
            }
            item {
                Group("Organise") {
                    SettingRow("Categories", "Groups for income and spending", Icons.Rounded.Category, tint = COrganise, onClick = { nav.navigate(Routes.CATEGORIES) })
                    FeatureRow("tags")
                    FeatureRow("rules")
                    FeatureRow("rates")
                    SettingRow("Trash", "Restore deleted items", Icons.Rounded.Delete, tint = CSafe, onClick = { nav.navigate(Routes.TRASH) })
                }
            }
            item {
                Group("Tools") {
                    FeatureRow("tools")
                    FeatureRow("tax")
                    FeatureRow("statements")
                    FeatureRow("assistant")
                    val hidden = Features.all.count { !show(it.key) }
                    SettingRow(
                        "Features",
                        if (s.simpleMode) "Simple mode · ${if (hidden > 0) "$hidden more tools to turn on" else "everything you use is on"}" else "All tools shown · turn on simple mode",
                        Icons.Rounded.SpaceDashboard, tint = CTools, onClick = { nav.navigate(Routes.FEATURES) },
                    )
                }
            }
            item {
                Group("Your data") {
                    SettingRow(
                        "Sync",
                        when {
                            !s.syncEnabled -> "Off · optional, with your own LekhaMint server"
                            s.lastSyncError != null -> "Problem: ${s.lastSyncError}"
                            pending > 0 -> "$pending ${if (pending == 1) "change" else "changes"} waiting · last synced ${ago(s.lastSyncAt)}"
                            else -> "Up to date · ${ago(s.lastSyncAt)}"
                        },
                        Icons.Rounded.CloudSync, tint = if (s.lastSyncError != null) MaterialTheme.colorScheme.error else CData, onClick = { nav.navigate(Routes.SYNC) },
                    )
                    SettingRow(
                        "Share with family",
                        if (s.syncEnabled) "Let someone else add to this book from their phone" else "Needs sync with your LekhaMint server first",
                        Icons.Rounded.GroupAdd, tint = CPeople, onClick = { nav.navigate(if (s.syncEnabled) Routes.SHARE else Routes.SYNC) },
                    )
                    SettingRow(
                        "Backup & restore",
                        if (s.lastBackupError != null) "Problem: ${s.lastBackupError}" else if (s.autoBackup) "Daily backup on · last ${ago(s.lastBackupAt)}" else "Automatic backup is off",
                        Icons.Rounded.Backup, tint = if (s.lastBackupError != null) MaterialTheme.colorScheme.error else CData, onClick = { nav.navigate(Routes.BACKUP) },
                    )
                }
            }
            item {
                Group("Appearance") {
                    SettingRow(
                        "Theme",
                        "${s.palette.label} · " + when (s.theme) { ThemeMode.SYSTEM -> "follows system"; ThemeMode.LIGHT -> "light"; ThemeMode.DARK -> "dark" } +
                            if (s.dynamicColor) " · wallpaper colours" else "",
                        Icons.Rounded.Palette, tint = CLook, onClick = { themeSheet = true },
                    ) { Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                    SettingRow("Main currency", "Used for totals and new accounts: ${s.baseCurrency}", Icons.Rounded.CurrencyExchange, tint = CLook, onClick = { currencySheet = true })
                }
            }
            item {
                Group("Privacy & security") {
                    SettingRow("Hide amounts", "Show •••• instead of numbers", Icons.Rounded.Visibility, tint = CSafe, onClick = { c.settings.setHideAmounts(!s.hideAmounts) }) {
                        Switch(s.hideAmounts, { c.settings.setHideAmounts(it) })
                    }
                    val canLock = BiometricManager.from(context).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                    SettingRow(
                        "App lock", if (canLock) "Fingerprint, face or screen lock; hides the app in recents" else "Set a screen lock on your phone first",
                        Icons.Rounded.Fingerprint, tint = CSafe, onClick = { if (canLock) c.settings.setAppLock(!s.appLock) },
                    ) { Switch(s.appLock, { if (canLock) c.settings.setAppLock(it) }, enabled = canLock) }
                    if (!notificationsGranted) {
                        SettingRow("Allow reminders", "Bill reminders and budget alerts", Icons.Rounded.Notifications, tint = CTools, onClick = {
                            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        })
                    }
                }
            }
            item {
                Group("About") {
                    SettingRow(
                        "LekhaMint ${BuildConfig.VERSION_NAME}",
                        "Works fully offline. Your data stays on this phone unless you turn on sync with your own server.",
                        Icons.Rounded.Info, tint = CSafe,
                    )
                    SettingRow("Privacy policy", "What the app stores and where it goes", Icons.Rounded.Policy, tint = CSafe, onClick = { Links.open(context, Links.PRIVACY) })
                    SettingRow("Help & source code", "Guides, questions and bug reports", Icons.AutoMirrored.Rounded.HelpOutline, tint = CSafe, onClick = { Links.open(context, Links.HELP) })
                    SettingRow("Open-source notices", "The fonts, receipt reader and libraries inside the app", Icons.Rounded.Gavel, tint = CSafe, onClick = { notices = true })
                }
            }
        }
    }

    if (themeSheet) ThemeSheet(onDismiss = { themeSheet = false })
    if (notices) NoticesDialog(onDismiss = { notices = false })
    if (currencySheet) {
        CurrencyPickerSheet({ currencySheet = false }, s.baseCurrency) { code ->
            c.settings.setBaseCurrency(code)
            scope.launch { snackbar.showSnackbar("Totals now show $code. Existing accounts keep their own currency.") }
        }
    }
    if (renameDialog) {
        var name by remember { mutableStateOf(s.ledgerName) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Book name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) c.settings.setLedgerName(name.trim()); renameDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } },
        )
    }
}

/** One searchable place on the More screen; [text] holds its title, subtitle and other words people use for it. */
private class MoreLink(val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: Color, words: String, val go: () -> Unit) {
    val text = "$title $subtitle $words".lowercase()
}

/** Everything More leads to, including tools hidden in simple mode, so search finds them all. */
private fun moreLinks(open: (String) -> Unit, theme: () -> Unit, currency: () -> Unit, hide: () -> Unit, notices: () -> Unit, rename: () -> Unit): List<MoreLink> =
    listOf(
        MoreLink("Accounts", "Cash, bank, cards, wallets", Icons.Rounded.AccountBalanceWallet, CMoney, "balance net worth credit card upi") { open(Routes.ACCOUNTS) },
        MoreLink("Reports", "Charts of spending, trends and 50/30/20", Icons.AutoMirrored.Rounded.ShowChart, CMoney, "graph chart category month year where money went") { open(Routes.REPORTS) },
        MoreLink("Budgets", "Monthly limits and what's left", Icons.Rounded.SpaceDashboard, CMoney, "limit plan spend") { open(Routes.BUDGETS) },
        MoreLink("Bank messages", "Add payments from SMS and alerts", Icons.Rounded.MarkEmailRead, CMoney, "sms notification found payments alert") { open(FOUND_PAYMENTS_ROUTE) },
        MoreLink("Import a statement", "From your bank: CSV, Excel, PDF, OFX or QIF", Icons.Rounded.UploadFile, CMoney, "bank statement pdf excel csv upload") { open(Routes.IMPORT) },
        MoreLink("Weekly check-in", "The week and what needs a look", Icons.Rounded.EventAvailable, CMoney, "review sunday summary") { open(Routes.WEEKLY) },
        MoreLink("Insights & forecast", "Safe to spend, cash forecast, subscriptions", Icons.Rounded.Insights, CMoney, "predict future subscription") { open(Routes.INSIGHTS) },
        MoreLink("Recurring & bills", "Subscriptions, rent, salary", Icons.Rounded.Repeat, CPlan, "reminder emi bill due repeat") { open(Routes.RECURRING) },
        MoreLink("Categories", "Groups for income and spending", Icons.Rounded.Category, COrganise, "category group") { open(Routes.CATEGORIES) },
        MoreLink("Trash", "Restore deleted items", Icons.Rounded.Delete, CSafe, "deleted undo restore bin") { open(Routes.TRASH) },
        MoreLink("Features", "Simple mode and which tools show", Icons.Rounded.SpaceDashboard, CTools, "simple mode tools hide show") { open(Routes.FEATURES) },
        MoreLink("Sync", "With your own LekhaMint server", Icons.Rounded.CloudSync, CData, "server sign in login password account connect web qr code") { open(Routes.SYNC) },
        MoreLink("Share with family", "Let someone else add to this book", Icons.Rounded.GroupAdd, CPeople, "family invite member partner spouse") { open(Routes.SHARE) },
        MoreLink("Backup & restore", "Daily backup, export and restore", Icons.Rounded.Backup, CData, "export save file restore copy") { open(Routes.BACKUP) },
        MoreLink("Theme", "Colours, light and dark", Icons.Rounded.Palette, CLook, "dark mode light night colour color appearance") { theme() },
        MoreLink("Main currency", "Used for totals and new accounts", Icons.Rounded.CurrencyExchange, CLook, "currency rupee dollar euro") { currency() },
        MoreLink("Hide amounts", "Show •••• instead of numbers", Icons.Rounded.Visibility, CSafe, "privacy hide numbers") { hide() },
        MoreLink("Rename book", "Change the book's name", Icons.Rounded.Edit, CSafe, "book name ledger") { rename() },
        MoreLink("Open-source notices", "Licences of what's inside the app", Icons.Rounded.Gavel, CSafe, "licence license about") { notices() },
    ) + Features.all.map { f -> MoreLink(f.title, f.subtitle, f.icon, f.tint, f.key) { open(f.route) } }

/** A titled group of rows on one card. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp),
    )
    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
}

/** Colour theme, light/dark and the extras, with live swatches: the choice is visible before it is made. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleLarge)
            Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (p in ThemePalette.entries) {
                    val selected = s.palette == p && !s.dynamicColor
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).clickable { c.settings.setPalette(p); if (s.dynamicColor) c.settings.setDynamicColor(false) }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(paletteSwatch(p))
                                .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Rounded.Check, "Selected", tint = Color.White) }
                        Text(p.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingRow("Colours from wallpaper", "Material You: follow your phone's wallpaper instead", Icons.Rounded.Wallpaper, tint = CLook, onClick = { c.settings.setDynamicColor(!s.dynamicColor) }) {
                    Switch(s.dynamicColor, { c.settings.setDynamicColor(it) })
                }
            }
            Text("Brightness", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(ThemeMode.SYSTEM to "Auto", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEachIndexed { i, (m, l) ->
                    SegmentedButton(s.theme == m, { c.settings.setTheme(m) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
            SettingRow("Pure black in dark mode", "Saves battery on OLED screens", Icons.Rounded.DarkMode, tint = CSafe, onClick = { c.settings.setAmoled(!s.amoled) }) {
                Switch(s.amoled, { c.settings.setAmoled(it) })
            }
        }
    }
}

/** The licences of everything the app ships, read from assets/licenses: a list, then one licence's text. */
@Composable
private fun NoticesDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val files = remember { runCatching { context.assets.list("licenses")?.sortedBy { it != "NOTICE.txt" } }.getOrNull().orEmpty() }
    var open by remember { mutableStateOf("NOTICE.txt") }
    val text = remember(open) { runCatching { context.assets.open("licenses/$open").bufferedReader().use { it.readText() } }.getOrDefault("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (open == "NOTICE.txt") "Open-source notices" else open.removeSuffix(".txt")) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                if (open == "NOTICE.txt") files.filter { it != "NOTICE.txt" }.forEach { f ->
                    TextButton(onClick = { open = f }) { Text("Read ${f.removeSuffix(".txt")}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (open != "NOTICE.txt") ({ TextButton(onClick = { open = "NOTICE.txt" }) { Text("Back") } }) else null,
    )
}
