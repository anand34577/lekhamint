package app.kosh.work

import android.content.Context
import app.kosh.AppContainer
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.core.plural
import app.kosh.data.db.AccountEntity
import app.kosh.data.db.TransactionEntity
import app.kosh.domain.BudgetStatus
import app.kosh.domain.Budgets
import app.kosh.domain.Reports
import app.kosh.domain.Upcoming
import app.kosh.ui.Routes
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The weekly check-in: what the week cost, and the few things worth a look (entries without a category,
 * payments found in alerts, budgets running hot, bills coming up, an account not checked in a while).
 * Built so it takes half a minute, not an evening.
 */
object WeeklyCheckIn {
    data class Summary(
        val from: LocalDate,
        val to: LocalDate,
        val currency: String,
        val spent: Long,
        val lastWeek: Long,
        /** Root category ID (null = uncategorised) and amount, largest first. */
        val top: List<Pair<String?, Long>>,
        val uncategorized: List<TransactionEntity>,
        val foundWaiting: Int,
        val budgets: List<BudgetStatus>,
        val bills: List<Upcoming>,
        /** An account whose balance was not confirmed for a month or more. */
        val toCheck: AccountEntity?,
    ) {
        val todo: Int get() = uncategorized.size + foundWaiting + (if (toCheck != null) 1 else 0)
    }

    /** The week runs Monday to Sunday; on a Monday morning the check-in looks back at the week just ended. */
    fun week(today: LocalDate = Dates.today()): Pair<LocalDate, LocalDate> {
        val start = Dates.weekStart(today)
        return if (today == start) start.minusDays(7) to start.minusDays(1) else start to today
    }

    suspend fun summary(c: AppContainer, today: LocalDate = Dates.today()): Summary {
        val (from, to) = week(today)
        val base = c.settings.current.baseCurrency
        val categories = c.repo.categories.first().associateBy { it.id }
        val rows = c.repo.reportRows(from.minusDays(7), to).first()
        val (thisWeek, before) = rows.partition { !LocalDate.parse(it.localDate).isBefore(from) }
        val cur = Reports.summarize(thisWeek, categories, base)[base]
        val prev = Reports.summarize(before, categories, base)[base]
        val budgets = c.repo.budgets.first().filter { !it.archived }.mapNotNull { b ->
            val bRows = c.repo.reportRows(Budgets.neededFrom(b, today), today).first()
            Budgets.status(b, bRows, categories, base, today)
        }.filter { it.overAlert || it.aheadOfPace }
        val bills = c.recurring.upcoming(c.repo.recurringRules.first(), days = 7, today = today)
        return Summary(
            from, to, base,
            spent = cur?.expense ?: 0, lastWeek = prev?.expense ?: 0,
            top = cur?.expenseByCategory?.entries?.filter { it.value > 0 }?.sortedByDescending { it.value }?.take(3)?.map { it.key to it.value }.orEmpty(),
            uncategorized = c.repo.uncategorized(30).first(),
            foundWaiting = c.captures.items.value.count { it.state == "new" },
            budgets = budgets,
            bills = bills,
            toCheck = accountToCheck(c),
        )
    }

    /** The account used most that was not confirmed against the bank in 30 days (cash and bank accounts, cards). */
    suspend fun accountToCheck(c: AppContainer): AccountEntity? {
        val month = TimeUnit.DAYS.toMillis(30)
        val now = System.currentTimeMillis()
        val candidates = c.lookup.value.activeAccounts.filter { it.type in setOf("cash", "bank", "wallet", "credit_card", "savings", "checking") }
        return candidates
            .filter { now - c.settings.balanceCheckedAt(it.id) > month && now - it.createdAt > TimeUnit.DAYS.toMillis(7) }
            .maxByOrNull { c.repo.transactionCountForAccount(it.id) }
            ?.takeIf { c.repo.transactionCountForAccount(it.id) >= 5 }
    }

    suspend fun notify(context: Context, c: AppContainer) {
        val s = summary(c)
        if (s.spent == 0L && s.todo == 0 && s.bills.isEmpty()) return
        val hide = c.settings.current.hideAmounts
        val title = if (hide) "Your week in LekhaMint" else "This week: ${Money.ui(s.spent, s.currency)} spent"
        val bits = buildList {
            if (s.uncategorized.isNotEmpty()) add("${plural(s.uncategorized.size, "entry", "entries")} need a category")
            if (s.foundWaiting > 0) add("${plural(s.foundWaiting, "payment")} found to review")
            s.budgets.firstOrNull()?.let { add("${it.budget.name} ${(it.fraction * 100).toInt()}% used") }
            if (s.bills.isNotEmpty()) add("${plural(s.bills.size, "bill")} due in the next 7 days")
            if (s.toCheck != null) add("check ${s.toCheck.name}'s balance")
        }
        val text = if (bits.isEmpty()) "All caught up. Tap for the week's summary." else bits.joinToString(" · ").replaceFirstChar { it.uppercase() }
        Notifications.show(
            context, "weekly".hashCode(), title, text, route = Routes.WEEKLY,
            actions = listOf(Notifications.openAction(context, "Review (30 s)", Routes.WEEKLY, "weekly")),
        )
    }
}
