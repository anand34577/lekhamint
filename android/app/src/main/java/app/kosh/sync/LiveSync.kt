package app.kosh.sync

import app.kosh.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Keeps the book live while the app is on screen: syncs as soon as it opens, pushes local edits within
 * about a second, and holds a "tell me when something changes" request open to the server so edits made
 * on the web or another phone appear in a moment. In the background, WorkManager takes over (every
 * 15 minutes and after edits).
 */
class LiveSync(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val engine: SyncEngine,
    private val onPulled: () -> Unit,
) {
    private var job: Job? = null
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    /** True while the app is in the foreground and the loop runs. */
    val active: Boolean get() = job?.isActive == true

    fun start() {
        if (active || !settings.current.syncEnabled) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** A local edit: send it now rather than waiting for the next scheduled run. */
    fun nudge() {
        nudges.trySend(Unit)
    }

    // coroutineScope: the open request and timers belong to this loop and stop with it.
    private suspend fun loop() = coroutineScope {
        var failures = 0
        var needSync = true
        while (isActive && settings.current.syncEnabled) {
            if (needSync) {
                val ok = runCatching { engine.sync() }
                    .onSuccess { if (it.pulled > 0) onPulled() }
                    .isSuccess
                if (!ok) {
                    failures++
                    // Offline or the server is down: try again soon, then less often, and at once on an edit.
                    waitOrNudge(backoff(failures))
                    continue
                }
                failures = 0
                needSync = false
            }
            val api = engine.api() ?: break
            val since = settings.syncCursor
            val started = System.currentTimeMillis()
            val waiter = async { runCatching { api.waitForChange(settings.current.ledgerId, since) } }
            val changed: Boolean? = select {
                waiter.onAwait { r ->
                    r.getOrNull()?.let { it.changes.isNotEmpty() || it.next_cursor > since }
                        // Removed from the server: sync once now, which notices it and stops, instead of backing off.
                        ?: (r.exceptionOrNull() as? ApiException)?.takeIf { it.status == 401 }?.let { true }
                }
                nudges.onReceive { waiter.cancel(); true }
            }
            when {
                changed == true -> { delay(DEBOUNCE_MS); needSync = true }
                changed == null -> { failures++; needSync = true; waitOrNudge(backoff(failures)) }
                // An older server answers at once instead of waiting; poll it gently instead.
                System.currentTimeMillis() - started < FAST_ANSWER_MS -> waitOrNudge(OLD_SERVER_POLL_MS).also { needSync = true }
            }
        }
    }

    private suspend fun waitOrNudge(ms: Long) = coroutineScope {
        val timer = async { delay(ms) }
        select<Unit> {
            timer.onAwait { }
            nudges.onReceive { timer.cancel() }
        }
    }

    private fun backoff(failures: Int): Long = (5_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(60_000L)

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val FAST_ANSWER_MS = 3_000L
        const val OLD_SERVER_POLL_MS = 20_000L
    }
}
