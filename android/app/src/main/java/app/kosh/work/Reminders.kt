package app.kosh.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kosh.AppContainer
import app.kosh.KoshApplication
import app.kosh.MainActivity
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.domain.Budgets
import app.kosh.domain.Deposits
import app.kosh.domain.Loans
import app.kosh.ui.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Bills, EMIs, deposits, money owed and budget alerts. Each reminder is sent once per due date, only in the
 * daytime, and amounts stay off the lock screen. Bills carry "Mark paid" and "Skip" so the payment can be
 * recorded without opening the app.
 */
object Reminders {
    /** Reminders are not sent at night; the 9 am run picks up anything that came due overnight. */
    fun daytime(now: LocalTime = LocalTime.now()): Boolean = now.hour in 8..21

    suspend fun runAll(context: Context, c: AppContainer) {
        runCatching { bills(context, c) }
        runCatching { loansDepositsDebts(context, c) }
        runCatching { budgetAlerts(context, c) }
    }

    private fun billKey(ruleId: String, date: LocalDate) = "remind.$ruleId.$date"

    suspend fun bills(context: Context, c: AppContainer) {
        val today = Dates.today()
        val due = c.recurring.upcoming(c.repo.recurringRules.first(), days = 0).filter { it.rule.mode == "remind" }
        for (u in due) {
            val key = billKey(u.rule.id, u.date)
            if (c.settings.flag(key)) continue
            val account = c.repo.findAccount(u.rule.accountId)
            val amount = Money.format(u.rule.amount, account?.currency ?: c.settings.current.baseCurrency)
            val title = u.rule.name ?: u.rule.payee ?: "Bill"
            val text = if (u.date.isBefore(today)) "$amount was due ${Dates.relativeDay(u.date)}" else "$amount is due today"
            val extras = { i: Intent -> i.putExtra(EXTRA_RULE, u.rule.id).putExtra(EXTRA_DATE, u.date.toString()) }
            Notifications.show(
                context, key.hashCode(), title, "$text${account?.let { " · from ${it.name}" } ?: ""}",
                route = Routes.RECURRING,
                actions = listOf(
                    Notifications.action(context, ReminderActionReceiver::class.java, ACTION_PAID, "Mark paid", key, extras),
                    Notifications.action(context, ReminderActionReceiver::class.java, ACTION_SKIP, "Skip this time", key, extras),
                ),
            )
            c.settings.setFlag(key)
        }
    }

    /**
     * EMIs due within each loan's reminder window (or overdue), deposits about to mature, card bills
     * and personal debts due.
     */
    suspend fun loansDepositsDebts(context: Context, c: AppContainer) {
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
            Notifications.show(context, key.hashCode(), "${l.name} EMI", text, route = "loan/${l.id}")
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
            Notifications.show(context, key.hashCode(), d.name, text, route = "deposit/${d.id}")
            c.settings.setFlag(key)
        }
        for (a in accounts) {
            val due = app.kosh.domain.CardBilling.due(a, balances[a.id] ?: a.openingBalance, today) ?: continue
            if (due.daysLeft > 3) continue
            val key = "remind.card.${a.id}.${due.date}"
            if (c.settings.flag(key)) continue
            val text = if (due.daysLeft == 0L) "Payment is due today" else "Payment is due ${Dates.relativeDay(due.date)}"
            Notifications.show(
                context, key.hashCode(), "${a.name} bill", text, route = Routes.account(a.id),
                actions = listOf(Notifications.openAction(context, "Pay now", Routes.editTx(type = "transfer", to = a.id), key)),
            )
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
            Notifications.show(context, key.hashCode(), if (d.direction == "lent") "Money owed to you" else "Money you owe", "$what ${Dates.relativeDay(due)}", route = Routes.DEBTS)
            c.settings.setFlag(key)
        }
    }

    suspend fun budgetAlerts(context: Context, c: AppContainer) {
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
                "${(st.fraction * 100).toInt()}% used · ${Money.format(st.remaining, b.currency)} left for ${app.kosh.core.plural(st.daysLeft, "day")}"
            }
            Notifications.show(context, key.hashCode(), "Budget: ${b.name}", text, route = Routes.BUDGETS)
            c.settings.setFlag(key)
        }
    }

    const val ACTION_PAID = "app.kosh.remind.PAID"
    const val ACTION_SKIP = "app.kosh.remind.SKIP"
    const val EXTRA_RULE = "rule"
    const val EXTRA_DATE = "date"
}

/** "Mark paid" records the bill (with its usual amount; Change opens it) and "Skip" moves the rule on. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(Reminders.EXTRA_RULE) ?: return
        val date = intent.getStringExtra(Reminders.EXTRA_DATE)?.let(Dates::parse) ?: return
        val app = context.applicationContext as KoshApplication
        val c = app.container
        val id = "remind.$ruleId.$date".hashCode()
        val pending = goAsync()
        c.appScope.launch {
            try {
                val rule = c.repo.findRule(ruleId) ?: return@launch NotificationManagerCompat.from(app).cancel(id)
                when (intent.action) {
                    Reminders.ACTION_PAID -> {
                        c.recurring.confirm(rule, date)
                        val tx = Ids.recurringOccurrence(rule.id, date)
                        val cur = c.repo.findAccount(rule.accountId)?.currency ?: c.settings.current.baseCurrency
                        val amount = if (c.settings.current.hideAmounts) "" else Money.ui(rule.amount, cur) + " "
                        Notifications.show(
                            app, id, "Paid: ${rule.name ?: rule.payee ?: "bill"}",
                            "Recorded ${amount}for ${Dates.relativeDay(date)}. Tap Change if the amount was different.",
                            route = Routes.editTx(id = tx), silent = true,
                            actions = listOf(Notifications.openAction(app, "Change", Routes.editTx(id = tx), "paid.$ruleId.$date")),
                        )
                    }
                    Reminders.ACTION_SKIP -> {
                        c.recurring.skip(rule, date)
                        NotificationManagerCompat.from(app).cancel(id)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Builds the small notification parts shared by reminders, the weekly check-in and found payments. */
internal fun Notifications.action(
    context: Context,
    receiver: Class<out BroadcastReceiver>,
    what: String,
    label: String,
    key: String,
    extras: (Intent) -> Intent = { it },
): NotificationCompat.Action {
    val pi = PendingIntent.getBroadcast(
        context, (key + what).hashCode(), extras(Intent(context, receiver).setAction(what)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Action.Builder(0, label, pi).setAuthenticationRequired(true).build()
}

internal fun Notifications.openAction(context: Context, label: String, route: String, key: String): NotificationCompat.Action {
    val pi = PendingIntent.getActivity(
        context, (key + route).hashCode(), MainActivity.openIntent(context, route),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Action.Builder(0, label, pi).build()
}
