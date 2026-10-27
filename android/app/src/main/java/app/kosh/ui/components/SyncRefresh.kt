package app.kosh.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pull down to sync with the server and refresh what the screen shows. Only when a server is
 * connected: without one there is nothing to fetch, so the gesture would only bounce.
 * The spinner stays until the sync is done, and the result is said in a short message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    if (!settings.syncEnabled) {
        Box(modifier, content = content)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        modifier = modifier,
        content = content,
        onRefresh = {
            if (refreshing) return@PullToRefreshBox
            refreshing = true
            scope.launch {
                val started = System.currentTimeMillis()
                val result = c.syncNowAndWait()
                // A very quick sync still shows the spinner long enough to read as "done".
                delay((MIN_SPIN_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                refreshing = false
                val text = result.fold(
                    onSuccess = { r ->
                        when {
                            r.rejected > 0 -> "Synced, but the server refused ${r.rejected} ${if (r.rejected == 1) "change" else "changes"}. See More → Sync."
                            r.pulled > 0 -> "Up to date · ${r.pulled} ${if (r.pulled == 1) "change" else "changes"} from your other devices"
                            else -> "Up to date"
                        }
                    },
                    onFailure = {
                        val why = (c.settings.current.lastSyncError ?: "the server can't be reached").trimEnd('.', ' ')
                        "Couldn't sync: $why. Your changes are safe on this phone."
                    },
                )
                Toast.makeText(context, text, if (result.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        },
    )
}

private const val MIN_SPIN_MS = 600L
