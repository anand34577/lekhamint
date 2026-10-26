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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.kosh.ui.components.SectionHeader
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
    val scope = rememberCoroutineScope()
    var themeSheet by remember { mutableStateOf(false) }
    var currencySheet by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var notificationsGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("More") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
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
                    SettingRow("Find payments automatically", "Read bank and UPI alerts, add with one tap", Icons.Rounded.MarkEmailRead, tint = CMoney, onClick = { nav.navigate(FOUND_PAYMENTS_ROUTE) })
                    SettingRow("Insights & forecast", "Safe to spend, cash forecast, subscriptions", Icons.Rounded.Insights, tint = CMoney, onClick = { nav.navigate(Routes.INSIGHTS) })
                    SettingRow("Recurring & bills", "Subscriptions, rent, salary", Icons.Rounded.Repeat, tint = CPlan, onClick = { nav.navigate(Routes.RECURRING) })
                    SettingRow("Goals", "Save for a trip, a gadget or an emergency fund", Icons.Rounded.Flag, tint = CPlan, onClick = { nav.navigate(Routes.GOALS) })
                    SettingRow("Loans & EMIs", "What's left, next EMI, prepayment", Icons.Rounded.AccountBalance, tint = CPlan, onClick = { nav.navigate(Routes.LOANS) })
                    SettingRow("Deposits", "Fixed and recurring deposits, maturity", Icons.Rounded.Savings, tint = CPlan, onClick = { nav.navigate(Routes.DEPOSITS) })
                    SettingRow("Lent & borrowed", "Friends and family: who owes what", Icons.Rounded.Handshake, tint = CPeople, onClick = { nav.navigate(Routes.DEBTS) })
                }
            }
            item {
                Group("Organise") {
                    SettingRow("Categories", "Groups for income and spending", Icons.Rounded.Category, tint = COrganise, onClick = { nav.navigate(Routes.CATEGORIES) })
                    SettingRow("Tags", "Trips, projects, anything across categories", Icons.Rounded.Sell, tint = COrganise, onClick = { nav.navigate(Routes.TAGS) })
                    SettingRow("Auto-categorise", "Rules that fill in category, payee and tags", Icons.Rounded.AutoFixHigh, tint = COrganise, onClick = { nav.navigate(Routes.CAT_RULES) })
                    SettingRow("Exchange rates", "Add up money held in other currencies", Icons.Rounded.CurrencyExchange, tint = COrganise, onClick = { nav.navigate(Routes.RATES) })
                    SettingRow("Trash", "Restore deleted items", Icons.Rounded.Delete, tint = CSafe, onClick = { nav.navigate(Routes.TRASH) })
                }
            }
            item {
                Group("Tools") {
                    SettingRow("Calculators", "EMI, FD and RD, split a bill, afford a loan", Icons.Rounded.Calculate, tint = CTools, onClick = { nav.navigate(Routes.TOOLS) })
                    SettingRow("Year & tax", "Your financial year and tax-saving spending", Icons.Rounded.Percent, tint = CTools, onClick = { nav.navigate(Routes.YEAR_TAX) })
                    SettingRow("Statements & export", "PDF and Excel for any account, loan or person", Icons.Rounded.PictureAsPdf, tint = CTools, onClick = { nav.navigate(Routes.STATEMENTS) })
                    SettingRow("Assistant", "Ask about your money in plain words", Icons.Rounded.AutoAwesome, tint = CTools, onClick = { nav.navigate(Routes.ASSISTANT) })
                }
            }
            item {
                Group("Your data") {
                    SettingRow(
                        "Sync",
                        when {
                            !s.syncEnabled -> "Off · optional, with your own LekhaMint server"
                            s.lastSyncError != null -> "Problem: ${s.lastSyncError}"
                            pending > 0 -> "$pending changes waiting · last synced ${ago(s.lastSyncAt)}"
                            else -> "Up to date · ${ago(s.lastSyncAt)}"
                        },
                        Icons.Rounded.CloudSync, tint = if (s.lastSyncError != null) MaterialTheme.colorScheme.error else CData, onClick = { nav.navigate(Routes.SYNC) },
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
                }
            }
        }
    }

    if (themeSheet) ThemeSheet(onDismiss = { themeSheet = false })
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
