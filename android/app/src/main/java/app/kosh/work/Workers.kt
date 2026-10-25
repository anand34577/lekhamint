package app.kosh.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kosh.KoshApplication
import app.kosh.MainActivity
import app.kosh.R
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.domain.Budgets
import app.kosh.domain.Deposits
import app.kosh.domain.Loans
import app.kosh.sync.ApiException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Schedules background work. Sync is never required for the app to work. */
class Scheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        wm.enqueueUniquePeriodicWork(
            DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWorker>(12, TimeUnit.HOURS).build(),
        )
    }

    fun schedulePeriodicSync(enabled: Boolean) {
        if (!enabled) {
            wm.cancelUniqueWork(SYNC_PERIODIC)
            return
        }
        wm.enqueueUniquePeriodicWork(
            SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
    }

    /** Debounced sync after local edits (docs/05: 5 s). */
    fun syncSoon() {
        wm.enqueueUniqueWork(
            SYNC_SOON, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun syncNow() {
        wm.enqueueUniqueWork(
            SYNC_SOON, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .build(),
        )
    }

    fun runDailyNow() {
        wm.enqueueUniqueWork(DAILY_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DailyWorker>().build())
    }

    private companion object {
        const val SYNC_SOON = "sync-soon"
        const val SYNC_PERIODIC = "sync-periodic"
        const val DAILY = "daily"
        const val DAILY_NOW = "daily-now"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KoshApplication
        val c = app.container
        if (!c.settings.current.syncEnabled) return Result.success()
        return try {
            val report = c.sync.sync()
            if (report.pulled > 0) SpendWidget.refresh(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403 || e.status == 404 || e.status == 409) Result.failure() else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure()
        }
    }

}

/** Recurring transactions, the automatic backup, budget alerts and bill reminders. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as KoshApplication).container
        if (!c.settings.current.onboarded) return Result.success()
        runCatching { c.recurring.runAuto() }
        runCatching { remindBills(c) }
        runCatching { remindLoansDepositsDebts(c) }
        runCatching { budgetAlerts(c) }
        val s = c.settings.current
        if (s.autoBackup && System.currentTimeMillis() - s.lastBackupAt > TimeUnit.HOURS.toMillis(20)) {
            runCatching { c.backups.autoBackup() }.onFailure {
                c.settings.setBackupResult(System.currentTimeMillis(), it.message ?: "Backup failed")
            }
        }
        if (s.syncEnabled) c.scheduler.syncSoon()
        SpendWidget.refresh(applicationContext) // a new month starts at zero
        return Result.success()
    }

    private suspend fun remindBills(c: app.kosh.AppContainer) {
        val today = Dates.today()
        val due = c.recurring.upcoming(c.repo.recurringRules.first(), days = 0).filter { it.rule.mode == "remind" }
        for (u in due) {
            val key = "remind.${u.rule.id}.${u.date}"
            if (c.settings.flag(key)) continue
            val account = c.repo.findAccount(u.rule.accountId)
            val amount = Money.format(u.rule.amount, account?.currency ?: c.settings.current.baseCurrency)
            val title = u.rule.name ?: u.rule.payee ?: "Bill"
            val text = if (u.date.isBefore(today)) "$amount was due ${Dates.relativeDay(u.date)}" else "$amount is due today"
            Notifications.show(applicationContext, key.hashCode(), title, text)
            c.settings.setFlag(key)
        }
    }

    /**
     * EMIs due within each loan's reminder window (or overdue), deposits
     * about to mature, and personal debts due. Each reminder is sent once per
     * due date; amounts stay off the lock screen.
     */
    private suspend fun remindLoansDepositsDebts(c: app.kosh.AppContainer) {
        val today = Dates.today()
        val accounts = c.repo.accounts.first()
        val balances = c.repo.balances.first().associate { it.accountId to it.balance }
        val loanMoves = c.repo.loanMoves.first()
        for (l in c.repo.loans.first()) {
            if (l.closed || l.remindDays <= 0) continue
            val st = Loans.status(l, loanMoves, balances[l.accountId] ?: accounts.firstOrNull { it.id == l.accountId }?.openingBalance ?: 0, today)
            val due = st.nextDue ?: continue
            if (due.isAfter(today.plusDays(l.remindDays.toLong()))) continue
            val key = "remind.loan.${l.id}.$due"
            if (c.settings.flag(key)) continue
            val amount = Money.format(st.projection.rows.firstOrNull()?.payment ?: st.emi, l.currency)
            val text = when {
                due.isBefore(today) -> "$amount was due ${Dates.relativeDay(due)}. Record it once paid."
                due == today -> "$amount is due today"
                else -> "$amount is due ${Dates.relativeDay(due)}"
            }
            Notifications.show(applicationContext, key.hashCode(), "${l.name} EMI", text)
            c.settings.setFlag(key)
        }
        for (d in c.repo.deposits.first()) {
            if (d.status != "active" || d.remindDays <= 0) continue
            val m = LocalDate.parse(d.maturityDate)
            if (m.isAfter(today.plusDays(d.remindDays.toLong()))) continue
            val key = "remind.deposit.${d.id}.${d.maturityDate}"
            if (c.settings.flag(key)) continue
            val est = Deposits.estimate(d, today)
            val text = if (m.isAfter(today)) "Matures ${Dates.relativeDay(m)}: about ${Money.format(est.maturity, d.currency)}" else "Matured ${Dates.relativeDay(m)}. Record what the bank paid."
            Notifications.show(applicationContext, key.hashCode(), d.name, text)
            c.settings.setFlag(key)
        }
        val debtMoves = c.repo.debtMoves.first()
        for (d in c.repo.debts.first()) {
            val dueDate = d.dueDate ?: continue
            if (d.closed) continue
            val due = LocalDate.parse(dueDate)
            if (due.isAfter(today.plusDays(1))) continue
            val left = app.kosh.domain.Debts.outstanding(d, debtMoves.filter { it.debtId == d.id })
            if (left <= 0) continue
            val key = "remind.debt.${d.id}.$dueDate"
            if (c.settings.flag(key)) continue
            val what = if (d.direction == "lent") "${d.person} was to repay ${Money.format(left, d.currency)}" else "You were to repay ${d.person} ${Money.format(left, d.currency)}"
            Notifications.show(applicationContext, key.hashCode(), if (d.direction == "lent") "Money owed to you" else "Money you owe", "$what ${Dates.relativeDay(due)}")
            c.settings.setFlag(key)
        }
    }

    private suspend fun budgetAlerts(c: app.kosh.AppContainer) {
        val today = LocalDate.now()
        val budgets = c.repo.budgets.first().filter { !it.archived }
        if (budgets.isEmpty()) return
        val categories = c.repo.categories.first().associateBy { it.id }
        for (b in budgets) {
            val from = Budgets.neededFrom(b, today)
            val rows = c.repo.reportRows(from, today).first()
            val st = Budgets.status(b, rows, categories, c.settings.current.baseCurrency, today) ?: continue
            if (!st.overAlert) continue
            val key = "budget.${b.id}.${st.window.start}.${if (st.over) "over" else "alert"}"
            if (c.settings.flag(key)) continue
            val text = if (st.over) {
                "Over by ${Money.format(-st.remaining, b.currency)}"
            } else {
                "${(st.fraction * 100).toInt()}% used · ${Money.format(st.remaining, b.currency)} left for ${st.daysLeft} days"
            }
            Notifications.show(applicationContext, key.hashCode(), "Budget: ${b.name}", text)
            c.settings.setFlag(key)
        }
    }
}

object Notifications {
    private const val CHANNEL = "reminders"
    private const val SYNC_CHANNEL = "sync"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bills, EMIs & budgets", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Bills and EMIs due, deposits maturing, money owed, budget alerts"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(SYNC_CHANNEL, "Sync", NotificationManager.IMPORTANCE_MIN))
    }

    fun show(context: Context, id: Int, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // On the lock screen only a neutral line shows; amounts and names appear once unlocked.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kosh)
            .setContentTitle("LekhaMint")
            .setContentText("You have a money reminder")
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kosh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
