package app.kosh

import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.kosh.ui.KoshRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A request coming from a shortcut, the Quick Settings tile, a notification or the share sheet. */
data class LaunchRequest(val addType: String?, val amount: String? = null, val nonce: Long = System.nanoTime())

/**
 * What was shared into the app (a bank SMS, a receipt photo or a PDF bill), waiting for the
 * next new-transaction form to pick it up. The file is already copied into app storage.
 */
object SharedInbox {
    data class Shared(val payee: String?, val note: String?, val file: app.kosh.data.BlobStore.Stored?)
    @Volatile var pending: Shared? = null
    fun take(): Shared? = pending.also { pending = null }
}

/** FragmentActivity (not ComponentActivity) because BiometricPrompt needs it for app lock. */
class MainActivity : FragmentActivity() {
    private val launch = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as KoshApplication).container
        handle(intent)
        // Hide content from the recent-apps screenshot when app lock is on.
        lifecycleScope.launch {
            container.settings.state.map { it.appLock }.distinctUntilChanged().collect { secure ->
                if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent { KoshRoot(container, launch) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_ADD -> launch.value = LaunchRequest(intent.getStringExtra("type") ?: "expense")
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
                val blobs = (application as KoshApplication).container.repo.blobs
                lifecycleScope.launch {
                    // Copy the file now, while this app has permission to read it.
                    val file = uri?.let { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { blobs.import(u) }.getOrNull() } }
                    val sms = text?.let(app.kosh.domain.SmsParser::parse)
                    SharedInbox.pending = SharedInbox.Shared(sms?.payee, text?.takeIf { sms?.amount != null }?.let { sms!!.note }, file)
                    launch.value = LaunchRequest(sms?.type ?: "expense", sms?.amount)
                }
            }
        }
    }

    companion object {
        const val ACTION_ADD = "app.kosh.action.ADD"
    }
}

/** Quick Settings tile: one tap to log an expense from anywhere. */
class QuickAddTileService : TileService() {
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_ADD)
            .putExtra("type", "expense")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            // The PendingIntent overload only exists from API 34.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
