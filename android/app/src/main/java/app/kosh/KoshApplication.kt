package app.kosh

import android.app.Application
import app.kosh.backup.BackupManager
import app.kosh.core.HlcClock
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
import app.kosh.sync.SyncEngine
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
        if (settings.current.syncEnabled) scheduler.syncSoon()
        SpendWidget.refresh(app)
    }
    val sync = SyncEngine(repo)
    val recurring = RecurringEngine(repo)
    val ruleService = RuleService(repo)
    val backups = BackupManager(app, repo, sync, SecretStore(app))

    /** Lives as long as the process; used for shared, always-fresh reference data. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val lookup: StateFlow<Lookup> = combine(
        combine(repo.accounts, repo.balances, ::Pair), repo.categories, repo.tags, settings.state,
        combine(repo.allSplits, repo.debts, repo.debtMoves, ::Triple),
    ) { (accounts, balances), categories, tags, s, (splits, debts, moves) ->
        val (owedToMe, iOwe) = Debts.totals(debts, moves)
        Lookup(
            accounts, balances.associate { it.accountId to it.balance }, categories, tags, s.baseCurrency,
            splitCategories = splits.groupBy({ it.transactionId }, { it.categoryId }),
            receivable = owedToMe, payable = iOwe,
        )
    }.stateIn(appScope, SharingStarted.Eagerly, Lookup(baseCurrency = settings.current.baseCurrency))

    /** Categorisation rules, always fresh (used while typing a payee). */
    val rules: StateFlow<List<RuleEntity>> = repo.rules.stateIn(appScope, SharingStarted.Eagerly, emptyList())
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
        container.scheduler.schedulePeriodicSync(container.settings.current.syncEnabled)
    }
}
