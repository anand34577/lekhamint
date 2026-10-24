package app.kosh.work

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.kosh.KoshApplication
import app.kosh.MainActivity
import app.kosh.R
import app.kosh.core.Money
import app.kosh.domain.Lookup
import app.kosh.domain.Reports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * Home-screen widget: month-to-date spending and available money in the main
 * currency, plus a button that opens quick add. It reads the local database,
 * so it works offline and never needs the server.
 */
class SpendWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Re-renders every placed widget; cheap no-op when there are none. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { runCatching { render(context, manager, ids) } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val c = (context.applicationContext as KoshApplication).container
            val s = c.settings.current
            val views = RemoteViews(context.packageName, R.layout.widget_spend)
            if (!s.onboarded) {
                views.setTextViewText(R.id.widget_spent, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_available, "Tap + to set up")
            } else {
                val month = YearMonth.now()
                val categories = c.repo.categories.first()
                val rows = c.repo.reportRows(month.atDay(1), month.atEndOfMonth()).first()
                val spent = Reports.summarize(rows, categories.associateBy { it.id }, s.baseCurrency)[s.baseCurrency]?.expense ?: 0
                val lookup = combine(c.repo.accounts, c.repo.balances) { a, b ->
                    Lookup(a, b.associate { it.accountId to it.balance }, categories, baseCurrency = s.baseCurrency)
                }.first()
                val available = lookup.position.available[s.baseCurrency] ?: 0
                // Hidden amounts and app lock both keep numbers off the home screen.
                fun fmt(v: Long) = if (s.hideAmounts || s.appLock) "••••" else Money.format(v, s.baseCurrency)
                views.setTextViewText(R.id.widget_spent, fmt(spent))
                views.setTextViewText(R.id.widget_available, "Available ${fmt(available)}")
            }
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val add = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ADD).putExtra("type", "expense"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_add, add)
            manager.updateAppWidget(ids, views)
        }
    }
}
