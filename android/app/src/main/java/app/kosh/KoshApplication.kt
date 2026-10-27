package app.kosh

import android.app.Application
import app.kosh.backup.BackupManager
import app.kosh.capture.CaptureStore
import app.kosh.core.HlcClock
import app.kosh.data.AutoFill
import app.kosh.ocr.ReceiptReader
import app.kosh.data.Repository
import app.kosh.data.SecretStore
import app.kosh.data.Settings
import app.kosh.data.SyncTables
import app.kosh.data.db.KoshDatabase
import app.kosh.domain.Debts
import app.kosh.domain.Lookup
import app.kosh.domain.RecurringEngine
import app.kosh.domain.RuleService
import app.kosh.data.db.RuleEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import app.kosh.sync.LiveSync
import app.kosh.sync.SyncEngine
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.kosh.work.Notifications
import app.kosh.work.Scheduler
import app.kosh.work.SpendWidget

/** Manual dependency graph: small, explicit, no reflection or code generation. */
class AppContainer(app: Application) {
    val settings = Settings(app)
    val db = KoshDatabase.open(app)
    val clock = HlcClock(settings.nodeId, settings.lastHlc, persist = { settings.lastHlc = it })
    val tables = SyncTables(db)
    val scheduler = Scheduler(app)
    val repo = Repository(app, db, tables, clock, settings) {
        // On screen, the live loop sends the edit within a second; otherwise WorkManager does it shortly.
        if (settings.current.syncEnabled) { if (live.active) live.nudge() else scheduler.syncSoon() }
        SpendWidget.refresh(app)
    }
    val sync = SyncEngine(repo)
    val recurring = RecurringEngine(repo)
    val ruleService = RuleService(repo)
    val backups = BackupManager(app, repo, sync, SecretStore(app))
    /** Payments found in bank and payment app alerts, on this phone only. */
    val captures = CaptureStore(app)
    /** Reads receipt photos on the phone (no Google services, nothing uploaded). */
    val receipts = ReceiptReader(app)

    /** Lives as long as the process; used for shared, always-fresh reference data. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Real-time sync while the app is open. */
    val live: LiveSync by lazy { LiveSync(appScope, settings, sync) { SpendWidget.refresh(app) } }

    private val appContext = app

    /** A sync the person asked for (pull to refresh): runs now and reports how it went. */
    suspend fun syncNowAndWait(): Result<app.kosh.sync.SyncReport> =
        runCatching { sync.sync() }.onSuccess { if (it.pulled > 0) SpendWidget.refresh(appContext) }

    val lookup: StateFlow<Lookup> = combine(
        combine(repo.accounts, repo.balances, repo.accountValues, ::Triple), repo.categories, repo.tags, settings.state,
        combine(combine(repo.allSplits, repo.debts, repo.debtMoves, ::Triple), repo.exchangeRates, ::Pair),
    ) { (accounts, balances, values), categories, tags, s, (parts, rates) ->
        val (splits, debts, moves) = parts
        val (owedToMe, iOwe) = Debts.totals(debts, moves)
        Lookup(
            accounts, balances.associate { it.accountId to it.balance }, categories, tags, s.baseCurrency,
            splitCategories = splits.groupBy({ it.transactionId }, { it.categoryId }),
            receivable = owedToMe, payable = iOwe, rates = rates, values = values,
        )
    }.stateIn(appScope, SharingStarted.Eagerly, Lookup(baseCurrency = settings.current.baseCurrency))

    /** Categorisation rules, always fresh (used while typing a payee). */
    val rules: StateFlow<List<RuleEntity>> = repo.rules.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    /** Guesses account, category and payee for entries saved without the full form. */
    val autoFill = AutoFill(repo, { lookup.value }, { rules.value })
}

class KoshApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Records an older version kept as raw JSON now have typed tables.
        container.appScope.launch { container.repo.promoteRawRecords() }
        // The widget shows amounts in the main currency, unless they are hidden.
        container.appScope.launch {
            container.settings.state.map { Triple(it.hideAmounts, it.appLock, it.baseCurrency) }.distinctUntilChanged()
                .collect { SpendWidget.refresh(this@KoshApplication) }
        }
        Notifications.createChannels(this)
        container.scheduler.schedulePeriodic()
        container.scheduler.scheduleTimed()
        container.scheduler.schedulePeriodicSync(container.settings.current.syncEnabled)
        startLiveSync()
    }

    /** Sync live while any screen of the app is visible, and stop when it goes to the background. */
    private fun startLiveSync() {
        var foreground = false
        fun update() {
            if (foreground && container.settings.current.syncEnabled) container.live.start() else container.live.stop()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground = true; update() }
            override fun onStop(owner: LifecycleOwner) { foreground = false; update() }
        })
        // Signing in or out on the Sync screen starts or stops it straight away.
        container.appScope.launch(Dispatchers.Main) {
            container.settings.state.map { it.syncEnabled }.distinctUntilChanged().collect { on ->
                update()
                // Disconnected, or removed by the server: the background sync stops too.
                if (!on) container.scheduler.schedulePeriodicSync(false)
            }
        }
    }
}
