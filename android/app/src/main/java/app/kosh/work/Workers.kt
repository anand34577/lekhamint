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
import app.kosh.sync.ApiException
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
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

    /**
     * Two runs at set times, each booking the next: reminders at 9 am (so bills are not announced at 3 am
     * when the 12-hourly job happens to run) and the weekly check-in on Sunday evening.
     */
    fun scheduleTimed(replace: Boolean = false) {
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        wm.enqueueUniqueWork(MORNING, policy, OneTimeWorkRequestBuilder<MorningWorker>().setInitialDelay(delayUntil(nextMorning()), TimeUnit.MILLISECONDS).build())
        wm.enqueueUniqueWork(WEEKLY, policy, OneTimeWorkRequestBuilder<WeeklyWorker>().setInitialDelay(delayUntil(nextWeekly()), TimeUnit.MILLISECONDS).build())
    }

    fun scheduleNextWeekly() {
        wm.enqueueUniqueWork(WEEKLY, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WeeklyWorker>().setInitialDelay(delayUntil(nextWeekly()), TimeUnit.MILLISECONDS).build())
    }

    fun scheduleNextMorning() {
        wm.enqueueUniqueWork(MORNING, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MorningWorker>().setInitialDelay(delayUntil(nextMorning()), TimeUnit.MILLISECONDS).build())
    }

    private fun delayUntil(at: LocalDateTime): Long = Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(60_000)

    companion object {
        private const val SYNC_SOON = "sync-soon"
        private const val SYNC_PERIODIC = "sync-periodic"
        private const val DAILY = "daily"
        private const val DAILY_NOW = "daily-now"
        private const val MORNING = "morning"
        private const val WEEKLY = "weekly"

        fun nextMorning(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
            val today = now.toLocalDate().atTime(9, 0)
            return if (now.isBefore(today)) today else today.plusDays(1)
        }

        /** Sunday 6 pm: the week is nearly done and there is time to look back before Monday. */
        fun nextWeekly(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
            var d = now.toLocalDate().atTime(18, 0)
            while (d.dayOfWeek != DayOfWeek.SUNDAY || !d.isAfter(now)) d = d.plusDays(1)
            return d
        }
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

/** Bill, EMI and budget reminders at 9 am; then the next morning's run is booked. */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as KoshApplication).container
        try {
            if (c.settings.current.onboarded) {
                runCatching { c.recurring.runAuto() }
                Reminders.runAll(applicationContext, c)
            }
        } finally {
            c.scheduler.scheduleNextMorning()
        }
        return Result.success()
    }
}

/** Sunday evening: a short summary of the week with what needs a look; then next Sunday is booked. */
class WeeklyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as KoshApplication).container
        try {
            val st = c.settings.current
            if (st.onboarded && st.weeklyCheckIn) runCatching { WeeklyCheckIn.notify(applicationContext, c) }
        } finally {
            c.scheduler.scheduleNextWeekly()
        }
        return Result.success()
    }
}

/** Recurring transactions, the automatic backup, budget alerts and bill reminders. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as KoshApplication).container
        if (!c.settings.current.onboarded) return Result.success()
        runCatching { c.recurring.runAuto() }
        // Reminders wait for the daytime; the 9 am run (MorningWorker) sends whatever came due overnight.
        if (Reminders.daytime()) Reminders.runAll(applicationContext, c)
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
        nm.createNotificationChannel(NotificationChannel(app.kosh.capture.PaymentListener.CHANNEL, "Payments found", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A payment was read from a bank or payment app alert: tap to add it"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
    }

    /** Posts a reminder; tapping it opens [route] (or Home), and [actions] add buttons. */
    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        route: String? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
        silent: Boolean = false,
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = PendingIntent.getActivity(
            context, id, route?.let { MainActivity.openIntent(context, it) } ?: Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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
            .setSilent(silent)
            .apply { actions.forEach { addAction(it) } }
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
