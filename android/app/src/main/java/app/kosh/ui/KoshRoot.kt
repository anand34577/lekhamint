package app.kosh.ui

import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kosh.AppContainer
import app.kosh.LaunchRequest
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.LocalHideAmounts
import app.kosh.ui.screens.AccountDetailScreen
import app.kosh.ui.screens.AccountsScreen
import app.kosh.ui.screens.BackupScreen
import app.kosh.ui.screens.BudgetsScreen
import app.kosh.ui.screens.CategoriesScreen
import app.kosh.ui.screens.AssistantScreen
import app.kosh.ui.screens.DepositDetailScreen
import app.kosh.ui.screens.DepositsScreen
import app.kosh.ui.screens.LoanDetailScreen
import app.kosh.ui.screens.LoansScreen
import app.kosh.ui.screens.StatementsScreen
import app.kosh.ui.screens.ToolsScreen
import app.kosh.ui.screens.DebtsScreen
import app.kosh.ui.screens.EditAccountScreen
import app.kosh.ui.screens.EditBudgetScreen
import app.kosh.ui.screens.EditRuleScreen
import app.kosh.ui.screens.EditTransactionScreen
import app.kosh.ui.screens.HomeScreen
import app.kosh.ui.screens.MoreScreen
import app.kosh.ui.screens.OnboardingScreen
import app.kosh.ui.screens.RecurringScreen
import app.kosh.ui.screens.ReportsScreen
import app.kosh.ui.screens.SyncScreen
import app.kosh.ui.screens.TransactionsScreen
import app.kosh.ui.screens.TrashScreen
import app.kosh.ui.screens.TagsScreen
import app.kosh.ui.screens.CategorizationRulesScreen
import app.kosh.ui.screens.EditCategorizationRuleScreen
import app.kosh.ui.theme.KoshTheme
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val BUDGETS = "budgets"
    const val MORE = "more"
    const val EDIT_TX = "tx?id={id}&type={type}&copy={copy}&account={account}"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT = "account/{id}"
    const val EDIT_ACCOUNT = "account_edit?id={id}"
    const val CATEGORIES = "categories"
    const val REPORTS = "reports"
    const val RECURRING = "recurring"
    const val EDIT_RULE = "rule_edit?id={id}"
    const val EDIT_BUDGET = "budget_edit?id={id}"
    const val DEBTS = "debts"
    const val LOANS = "loans"
    const val LOAN = "loan/{id}"
    const val DEPOSITS = "deposits"
    const val DEPOSIT = "deposit/{id}"
    const val TOOLS = "tools"
    const val STATEMENTS = "statements"
    const val ASSISTANT = "assistant"
    const val TRASH = "trash"
    const val SYNC = "sync"
    const val BACKUP = "backup"
    const val TAGS = "tags"
    const val CAT_RULES = "cat_rules"
    const val EDIT_CAT_RULE = "cat_rule_edit?id={id}"

    fun editTx(id: String? = null, type: String = "expense", copy: Boolean = false, account: String? = null) =
        "tx?id=${id.orEmpty()}&type=$type&copy=$copy&account=${account.orEmpty()}"
    fun account(id: String) = "account/$id"
    fun editAccount(id: String? = null) = "account_edit?id=${id.orEmpty()}"
    fun editRule(id: String? = null) = "rule_edit?id=${id.orEmpty()}"
    fun editBudget(id: String? = null) = "budget_edit?id=${id.orEmpty()}"
    fun editCatRule(id: String? = null) = "cat_rule_edit?id=${id.orEmpty()}"

    val tabs = setOf(HOME, TRANSACTIONS, BUDGETS, MORE)
}

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No snackbar host") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("No nav controller") }

@Composable
fun KoshRoot(container: AppContainer, launch: MutableStateFlow<LaunchRequest?>) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    KoshTheme(settings) {
        CompositionLocalProvider(LocalContainer provides container, LocalHideAmounts provides settings.hideAmounts) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppLockGate(settings.appLock) { MainNav(container, settings.onboarded, launch) }
            }
        }
    }
}

@Composable
private fun MainNav(container: AppContainer, onboarded: Boolean, launch: MutableStateFlow<LaunchRequest?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val request by launch.collectAsStateWithLifecycle()
    LaunchedEffect(request, onboarded) {
        val r = request ?: return@LaunchedEffect
        if (onboarded) {
            nav.navigate(Routes.editTx(type = r.addType ?: "expense"))
            launch.value = null
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in Routes.tabs
    // Fixed for the lifetime of the graph; later changes navigate explicitly.
    val start = remember { if (onboarded) Routes.HOME else Routes.ONBOARDING }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalNav provides nav) {
        Scaffold(
            bottomBar = { if (showBar) KoshBottomBar(nav, route) },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = start,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(0) } })
                }
                composable(Routes.HOME) { HomeScreen() }
                composable(Routes.TRANSACTIONS) { TransactionsScreen() }
                composable(Routes.BUDGETS) { BudgetsScreen() }
                composable(Routes.MORE) { MoreScreen() }
                composable(
                    Routes.EDIT_TX,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType; defaultValue = "" },
                        navArgument("type") { type = NavType.StringType; defaultValue = "expense" },
                        navArgument("copy") { type = NavType.BoolType; defaultValue = false },
                        navArgument("account") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { e ->
                    val a = e.arguments!!
                    EditTransactionScreen(
                        id = a.getString("id").orEmpty().ifEmpty { null },
                        initialType = a.getString("type") ?: "expense",
                        copy = a.getBoolean("copy"),
                        presetAccount = a.getString("account").orEmpty().ifEmpty { null },
                    )
                }
                composable(Routes.ACCOUNTS) { AccountsScreen() }
                composable(Routes.ACCOUNT) { e -> AccountDetailScreen(e.arguments?.getString("id").orEmpty()) }
                composable(Routes.EDIT_ACCOUNT, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { e ->
                    EditAccountScreen(e.arguments?.getString("id").orEmpty().ifEmpty { null })
                }
                composable(Routes.CATEGORIES) { CategoriesScreen() }
                composable(Routes.REPORTS) { ReportsScreen() }
                composable(Routes.RECURRING) { RecurringScreen() }
                composable(Routes.EDIT_RULE, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { e ->
                    EditRuleScreen(e.arguments?.getString("id").orEmpty().ifEmpty { null })
                }
                composable(Routes.EDIT_BUDGET, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { e ->
                    EditBudgetScreen(e.arguments?.getString("id").orEmpty().ifEmpty { null })
                }
                composable(Routes.DEBTS) { DebtsScreen() }
                composable(Routes.LOANS) { LoansScreen() }
                composable(Routes.LOAN) { e -> LoanDetailScreen(e.arguments?.getString("id").orEmpty()) }
                composable(Routes.DEPOSITS) { DepositsScreen() }
                composable(Routes.DEPOSIT) { e -> DepositDetailScreen(e.arguments?.getString("id").orEmpty()) }
                composable(Routes.TOOLS) { ToolsScreen() }
                composable(Routes.STATEMENTS) { StatementsScreen() }
                composable(Routes.ASSISTANT) { AssistantScreen() }
                composable(Routes.TRASH) { TrashScreen() }
                composable(Routes.SYNC) { SyncScreen(onJoined = { nav.navigate(Routes.HOME) { popUpTo(0) } }) }
                composable(Routes.BACKUP) { BackupScreen(onRestored = { nav.navigate(Routes.HOME) { popUpTo(0) } }) }
                composable(Routes.TAGS) { TagsScreen() }
                composable(Routes.CAT_RULES) { CategorizationRulesScreen() }
                composable(Routes.EDIT_CAT_RULE, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { e ->
                    EditCategorizationRuleScreen(e.arguments?.getString("id").orEmpty().ifEmpty { null })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KoshBottomBar(nav: NavHostController, route: String?) {
    fun go(r: String) = nav.navigate(r) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    NavigationBar {
        NavigationBarItem(route == Routes.HOME, { go(Routes.HOME) }, { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
        NavigationBarItem(route == Routes.TRANSACTIONS, { go(Routes.TRANSACTIONS) }, { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, null) }, label = { Text("Activity") })
        Box(Modifier.weight(1f).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 3.dp,
                modifier = Modifier.size(56.dp)
                    .semantics { contentDescription = "Add transaction. Long press for transfer." }
                    .combinedClickable(
                        onClick = { nav.navigate(Routes.editTx()) },
                        onLongClick = { nav.navigate(Routes.editTx(type = "transfer")) },
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, Modifier.size(30.dp)) }
            }
        }
        NavigationBarItem(route == Routes.BUDGETS, { go(Routes.BUDGETS) }, { Icon(Icons.Rounded.PieChart, null) }, label = { Text("Budgets") })
        NavigationBarItem(route == Routes.MORE, { go(Routes.MORE) }, { Icon(Icons.Rounded.Menu, null) }, label = { Text("More") })
    }
}

/** Device-credential or biometric lock, re-armed after a minute in the background. */
@Composable
private fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    var locked by rememberSaveable { mutableStateOf(enabled) }
    var backgroundAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(enabled) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { backgroundAt = System.currentTimeMillis() }
            override fun onStart(owner: LifecycleOwner) {
                if (enabled && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > 60_000) locked = true
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!enabled || !locked) {
        content()
        return
    }
    val activity = LocalActivity.current as? FragmentActivity
    val prompt = {
        if (activity != null) {
            val p = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false }
                },
            )
            p.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock LekhaMint")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build(),
            )
        }
    }
    LaunchedEffect(Unit) { prompt() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("LekhaMint is locked", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Button(onClick = prompt) { Text("Unlock") }
    }
}
