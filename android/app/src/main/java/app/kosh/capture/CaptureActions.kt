package app.kosh.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kosh.AppContainer
import app.kosh.KoshApplication
import app.kosh.MainActivity
import app.kosh.R
import app.kosh.core.Money
import app.kosh.data.db.TransactionEntity
import app.kosh.ui.Routes
import kotlinx.coroutines.launch

/**
 * Saving a found payment without opening the app: the same guesses the add form would make
 * (account from the card or account digits, category from your rules or this payee's last entry).
 */
object CaptureSaver {
    data class Plan(val tx: TransactionEntity, val currency: String, val summary: String, val duplicate: TransactionEntity?, val hasCategory: Boolean)

    suspend fun plan(c: AppContainer, x: Captured): Plan? {
        if (!c.settings.current.onboarded) return null
        val l = c.lookup.value
        val account = c.autoFill.pickAccount(l, null, x.accountHint, cardAlert = x.text.contains("credit card", ignoreCase = true)) ?: return null
        val amount = Money.parse(x.amount, account.currency)?.takeIf { it > 0 } ?: return null
        val g = c.autoFill.guess(x.type, x.payee, amount = amount, accountHint = x.accountHint, cardAlert = x.text.contains("credit card", ignoreCase = true))
        val acc = g.account ?: account
        val tx = c.autoFill.build(x.type, acc, amount, g.categoryId, g.payee ?: x.payee, x.text.take(200), g.tagIds, at = x.at)
        val summary = listOfNotNull(g.categoryId?.let { l.categoryPath(it) }, acc.name).joinToString(" · ")
        return Plan(tx, acc.currency, summary, c.repo.likelyDuplicate(tx), g.categoryId != null)
    }

    /** Saves the payment; returns the saved transaction, or null when there is nothing to save it to. */
    suspend fun save(c: AppContainer, x: Captured): Plan? {
        val fresh = c.captures.find(x.id) ?: x
        if (fresh.state != "new") return null
        val p = plan(c, fresh) ?: return null
        c.repo.saveTransaction(p.tx)
        c.captures.mark(fresh.id, "added", p.tx.id)
        return p
    }

    /** Takes back a payment saved from its notification: the entry goes to Trash and the payment waits for review again. */
    suspend fun undo(c: AppContainer, id: String) {
        val x = c.captures.find(id) ?: return
        x.txId?.let { c.repo.deleteTransaction(it) }
        c.captures.mark(id, "new")
    }
}

/** The "payment found" notification and its buttons. */
object CaptureNotifier {
    private fun canPost(context: Context) = android.os.Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun action(context: Context, x: Captured, what: String, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, (x.id + what).hashCode(),
            Intent(context, CaptureActionReceiver::class.java).setAction(what).putExtra(PaymentListener.EXTRA_ID, x.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Saving or ignoring needs the phone unlocked, so nobody can file payments from the lock screen.
        return NotificationCompat.Action.Builder(0, label, pi).setAuthenticationRequired(true).build()
    }

    private fun openForm(context: Context, x: Captured): PendingIntent = PendingIntent.getActivity(
        context, x.id.hashCode(),
        Intent(context, MainActivity::class.java).setAction(PaymentListener.ACTION_CAPTURE).putExtra(PaymentListener.EXTRA_ID, x.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun base(context: Context) = NotificationCompat.Builder(context, PaymentListener.CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_kosh)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, PaymentListener.CHANNEL).setSmallIcon(R.drawable.ic_stat_kosh)
                .setContentTitle("LekhaMint").setContentText("A payment was found").build(),
        )

    /** "₹450 paid to Swiggy" with Save / Edit / Ignore, or Already added / Add when it looks recorded. */
    suspend fun found(context: Context, c: AppContainer, x: Captured) {
        if (!canPost(context)) return
        val st = c.settings.current
        val cur = st.baseCurrency
        val what = if (x.type == "income") "received" else "paid"
        val amount = Money.parse(x.amount, cur)?.let { Money.ui(it, cur) } ?: x.amount
        val title = if (st.hideAmounts) "Payment found" else "$amount $what${x.payee?.let { if (x.type == "income") " from $it" else " to $it" } ?: ""}"
        val plan = runCatching { CaptureSaver.plan(c, x) }.getOrNull()
        val b = base(context).setContentTitle(title).setContentIntent(openForm(context, x)).setAutoCancel(true).setOnlyAlertOnce(true)
        when {
            plan == null -> b.setContentText("Tap to add it, from ${x.source}")
            plan.duplicate != null -> {
                val d = plan.duplicate
                b.setContentText("Looks already recorded${d.payee?.let { " ($it)" } ?: ""}. Tap to check.")
                b.addAction(action(context, x, ACTION_IGNORE, "Already added"))
                b.addAction(action(context, x, ACTION_SAVE, "Add anyway"))
            }
            else -> {
                val text = if (plan.hasCategory) "Save as ${plan.summary}" else "Save to ${plan.summary} · no category yet"
                b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText("$text\nFrom ${x.source}"))
                b.addAction(action(context, x, ACTION_SAVE, "Save"))
                b.addAction(NotificationCompat.Action.Builder(0, "Edit", openForm(context, x)).build())
                b.addAction(action(context, x, ACTION_IGNORE, "Ignore"))
            }
        }
        post(context, x.id.hashCode(), b.build())
    }

    /** Notifications can be turned off at any moment in system settings; then there is nothing to show. */
    private fun post(context: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Permission withdrawn since canPost() checked.
        }
    }

    /** After Save: what was saved, with Undo and Change. Goes away by itself after a few minutes. */
    fun saved(context: Context, c: AppContainer, x: Captured, plan: CaptureSaver.Plan) {
        if (!canPost(context)) return
        val hide = c.settings.current.hideAmounts
        val change = PendingIntent.getActivity(
            context, (x.id + "edit").hashCode(), MainActivity.openIntent(context, Routes.editTx(id = plan.tx.id)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val amount = if (hide) "" else Money.ui(plan.tx.amount, plan.currency) + " · "
        val n = base(context)
            .setContentTitle("Saved: ${plan.tx.payee ?: if (plan.tx.type == "income") "money received" else "payment"}")
            .setContentText(amount + plan.summary)
            .setContentIntent(change)
            .addAction(action(context, x, ACTION_UNDO, "Undo"))
            .addAction(NotificationCompat.Action.Builder(0, "Change", change).build())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        post(context, x.id.hashCode(), n)
    }

    fun cancel(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(id.hashCode())

    const val ACTION_SAVE = "app.kosh.capture.SAVE"
    const val ACTION_IGNORE = "app.kosh.capture.IGNORE"
    const val ACTION_UNDO = "app.kosh.capture.UNDO"
}

/** Handles the notification buttons in the background; the app does not open. */
class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PaymentListener.EXTRA_ID) ?: return
        val app = context.applicationContext as KoshApplication
        val c = app.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    CaptureNotifier.ACTION_SAVE -> {
                        val x = c.captures.find(id)
                        val plan = x?.let { runCatching { CaptureSaver.save(c, it) }.getOrNull() }
                        if (x != null && plan != null) CaptureNotifier.saved(app, c, x, plan) else CaptureNotifier.cancel(app, id)
                    }
                    CaptureNotifier.ACTION_IGNORE -> {
                        c.captures.mark(id, "ignored")
                        CaptureNotifier.cancel(app, id)
                    }
                    CaptureNotifier.ACTION_UNDO -> {
                        CaptureSaver.undo(c, id)
                        c.captures.find(id)?.let { CaptureNotifier.found(app, c, it) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

