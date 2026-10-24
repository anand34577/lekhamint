package app.kosh.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Category
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
    var themeDialog by remember { mutableStateOf(false) }
    var currencySheet by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var notificationsGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SettingRow(s.ledgerName, "Your book · main currency ${s.baseCurrency}", Icons.Rounded.Edit, onClick = { renameDialog = true })
                SectionHeader("Money")
                SettingRow("Accounts", "Cash, bank, cards, wallets", Icons.Rounded.AccountBalanceWallet, onClick = { nav.navigate(Routes.ACCOUNTS) })
                SettingRow("Reports", "Spending, trends, 50/30/20", Icons.AutoMirrored.Rounded.ShowChart, onClick = { nav.navigate(Routes.REPORTS) })
                SettingRow("Recurring & bills", "Subscriptions, rent, salary", Icons.Rounded.Repeat, onClick = { nav.navigate(Routes.RECURRING) })
                SettingRow("Loans & EMIs", "Schedules, EMI split, prepayment, reminders", Icons.Rounded.AccountBalance, onClick = { nav.navigate(Routes.LOANS) })
                SettingRow("Deposits", "Fixed and recurring deposits, maturity dates", Icons.Rounded.Savings, onClick = { nav.navigate(Routes.DEPOSITS) })
                SettingRow("Lent & borrowed", "Friends and family: who owes what", Icons.Rounded.Handshake, onClick = { nav.navigate(Routes.DEBTS) })
                SettingRow("Categories", null, Icons.Rounded.Category, onClick = { nav.navigate(Routes.CATEGORIES) })
                SettingRow("Tags", "Group spending across categories: trips, projects", Icons.Rounded.Sell, onClick = { nav.navigate(Routes.TAGS) })
                SettingRow("Auto-categorise", "Rules that fill in category, payee and tags", Icons.Rounded.AutoFixHigh, onClick = { nav.navigate(Routes.CAT_RULES) })
                SettingRow("Trash", "Restore deleted items", Icons.Rounded.Delete, onClick = { nav.navigate(Routes.TRASH) })

                SectionHeader("Tools")
                SettingRow("Calculators", "EMI, FD and RD, bill splitter, loan affordability", Icons.Rounded.Calculate, onClick = { nav.navigate(Routes.TOOLS) })
                SettingRow("Statements & export", "PDF and Excel: account, income & spending, loan, person", Icons.Rounded.PictureAsPdf, onClick = { nav.navigate(Routes.STATEMENTS) })
                SettingRow("Assistant", "Ask about your money; cloud, home server or on-phone model", Icons.Rounded.AutoAwesome, onClick = { nav.navigate(Routes.ASSISTANT) })

                SectionHeader("Your data")
                SettingRow(
                    "Sync",
                    when {
                        !s.syncEnabled -> "Off · optional, with your own LekhaMint server"
                        s.lastSyncError != null -> "Problem: ${s.lastSyncError}"
                        pending > 0 -> "$pending changes waiting · last synced ${ago(s.lastSyncAt)}"
                        else -> "Up to date · ${ago(s.lastSyncAt)}"
                    },
                    Icons.Rounded.CloudSync, onClick = { nav.navigate(Routes.SYNC) },
                )
                SettingRow(
                    "Backup & restore",
                    if (s.lastBackupError != null) "Problem: ${s.lastBackupError}" else if (s.autoBackup) "Daily backup on · last ${ago(s.lastBackupAt)}" else "Automatic backup is off",
                    Icons.Rounded.Backup, onClick = { nav.navigate(Routes.BACKUP) },
                )

                SectionHeader("Appearance")
                SettingRow("Theme", when (s.theme) { ThemeMode.SYSTEM -> "Follow system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }, Icons.Rounded.DarkMode, onClick = { themeDialog = true })
                SettingRow("Pure black in dark theme", "Saves battery on OLED screens", Icons.Rounded.DarkMode, onClick = { c.settings.setAmoled(!s.amoled) }) {
                    Switch(s.amoled, { c.settings.setAmoled(it) })
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingRow("Colours from wallpaper", "Material You", Icons.Rounded.Palette, onClick = { c.settings.setDynamicColor(!s.dynamicColor) }) {
                        Switch(s.dynamicColor, { c.settings.setDynamicColor(it) })
                    }
                }
                SettingRow("Main currency", "Used for totals and new accounts: ${s.baseCurrency}", Icons.Rounded.CurrencyExchange, onClick = { currencySheet = true })

                SectionHeader("Privacy & security")
                SettingRow("Hide amounts", "Show •••• instead of numbers", Icons.Rounded.Visibility, onClick = { c.settings.setHideAmounts(!s.hideAmounts) }) {
                    Switch(s.hideAmounts, { c.settings.setHideAmounts(it) })
                }
                val canLock = BiometricManager.from(context).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                ) == BiometricManager.BIOMETRIC_SUCCESS
                SettingRow(
                    "App lock", if (canLock) "Fingerprint, face or screen lock; hides the app in recents" else "Set a screen lock on your phone first",
                    Icons.Rounded.Fingerprint, onClick = { if (canLock) c.settings.setAppLock(!s.appLock) },
                ) { Switch(s.appLock, { if (canLock) c.settings.setAppLock(it) }, enabled = canLock) }
                if (!notificationsGranted) {
                    SettingRow("Allow reminders", "Bill reminders and budget alerts", Icons.Rounded.Notifications, onClick = {
                        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }

                SectionHeader("About")
                SettingRow(
                    "LekhaMint ${BuildConfig.VERSION_NAME}",
                    "Works fully offline. Your data stays on this phone unless you turn on sync with your own server.",
                    Icons.Rounded.Info,
                )
            }
        }
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    for ((m, l) in listOf(ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")) {
                        SettingRow(l, onClick = { c.settings.setTheme(m); themeDialog = false }) { RadioButton(s.theme == m, { c.settings.setTheme(m); themeDialog = false }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Close") } },
        )
    }
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
