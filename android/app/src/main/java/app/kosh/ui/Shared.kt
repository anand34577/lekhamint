package app.kosh.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kosh.AppContainer
import app.kosh.core.Dates
import app.kosh.domain.BudgetStatus
import app.kosh.domain.Budgets
import app.kosh.ui.components.LocalContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A coroutine scope for button handlers (save, delete, record a payment). Unlike a bare
 * rememberCoroutineScope, a failure shows a message instead of crashing the app, and later
 * actions on the same screen still run.
 */
@Composable
fun rememberSafeScope(): kotlinx.coroutines.CoroutineScope {
    val base = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    return androidx.compose.runtime.remember(base, snackbar) {
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            android.util.Log.e("LekhaMint", "action failed", e)
            base.launch { snackbar.showSnackbar("Something went wrong: ${e.message ?: e.javaClass.simpleName}") }
        }
        kotlinx.coroutines.CoroutineScope(base.coroutineContext + kotlinx.coroutines.SupervisorJob(base.coroutineContext[kotlinx.coroutines.Job]) + handler)
    }
}

/** True when the phone has a speech recogniser LekhaMint can hand a request to (no extra library is bundled). */
fun speechAvailable(context: android.content.Context): Boolean = runCatching {
    android.speech.SpeechRecognizer.isRecognitionAvailable(context) ||
        context.packageManager.queryIntentActivities(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
}.getOrDefault(false)

/** ViewModel built from the app container (manual DI, no reflection). */
@Composable
inline fun <reified VM : ViewModel> koshViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(key = key) { create(c) }
}

/** Today's date, re-emitted when midnight passes so screens left open overnight (or over a month end) roll over. */
fun todayFlow(): Flow<java.time.LocalDate> = kotlinx.coroutines.flow.flow {
    while (true) {
        emit(Dates.today())
        kotlinx.coroutines.delay(30_000)
    }
}.distinctUntilChanged()

/** Live status of every active budget, including rollover from earlier periods. */
@OptIn(ExperimentalCoroutinesApi::class)
fun budgetStatuses(c: AppContainer): Flow<List<BudgetStatus>> =
    combine(c.repo.budgets, todayFlow()) { budgets, today -> budgets to today }.flatMapLatest { (budgets, today) ->
        val active = budgets.filter { !it.archived }
        if (active.isEmpty()) return@flatMapLatest flowOf(emptyList())
        val from = active.minOf { Budgets.neededFrom(it, today) }
        val to = active.maxOf { b -> Budgets.window(b, today)?.end ?: today }.coerceAtLeast(today)
        combine(c.repo.reportRows(from, to), c.lookup) { rows, lookup -> rows to lookup }.map { (rows, lookup) ->
            active.mapNotNull { Budgets.status(it, rows, lookup.categoryById, lookup.baseCurrency, today) }
        }
    }
