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

/**
 * A request coming from a shortcut, the Quick Settings tile, a notification or the share sheet:
 * open the add form, or (with [route]) one of the screens a notification may point at.
 */
data class LaunchRequest(val addType: String?, val amount: String? = null, val nonce: Long = System.nanoTime(), val route: String? = null)

/**
 * What was shared into the app (a bank SMS, a receipt photo or a PDF bill), waiting for the
 * next new-transaction form to pick it up. The file is already copied into app storage.
 */
object SharedInbox {
    data class Shared(
        val payee: String?,
        val note: String?,
        val file: app.kosh.data.BlobStore.Stored?,
        /** A found payment this form was opened for: marked as added once saved. */
        val captureId: String? = null,
        /** Last digits of the account or card the message named, to pick the matching account. */
        val accountHint: String? = null,
        /** When the payment happened, if known. */
        val at: Long? = null,
        /** Already decided elsewhere (a one-line entry or a scanned receipt): used as they are. */
        val accountId: String? = null,
        val categoryId: String? = null,
        val tagIds: List<String> = emptyList(),
        /** A day without a time: the form keeps its own time of day. */
        val date: java.time.LocalDate? = null,
    )
    @Volatile var pending: Shared? = null
    fun take(): Shared? = pending.also { pending = null }
}

/** A statement file shared into the app or opened with it, read while permission lasts, waiting for Import. */
object ImportInbox {
    @Volatile var pending: Pair<String, ByteArray>? = null
    fun take(): Pair<String, ByteArray>? = pending.also { pending = null }

    private val TYPES = setOf(
        "text/csv", "text/comma-separated-values", "application/csv", "text/tab-separated-values",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/x-ofx", "application/ofx", "application/vnd.intu.qfx", "application/x-qif", "application/qif",
    )
    private val EXTENSIONS = Regex("""\.(csv|tsv|xls|xlsx|ofx|qfx|qif)$""", RegexOption.IGNORE_CASE)

    /** True for spreadsheets and money-app files; a PDF stays a bill or receipt for the add form. */
    fun isStatement(mime: String?, name: String?): Boolean = mime?.lowercase() in TYPES || (name != null && EXTENSIONS.containsMatchIn(name))
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
            ACTION_QUICK -> launch.value = LaunchRequest(null, route = app.kosh.ui.Routes.QUICK)
            ACTION_OPEN -> intent.getStringExtra(EXTRA_ROUTE)?.takeIf(::openable)?.let { launch.value = LaunchRequest(null, route = it) }
            app.kosh.capture.PaymentListener.ACTION_CAPTURE -> {
                val c = (application as KoshApplication).container.captures.find(intent.getStringExtra(app.kosh.capture.PaymentListener.EXTRA_ID).orEmpty())
                if (c != null && c.state == "new") {
                    SharedInbox.pending = SharedInbox.Shared(c.payee, c.text.take(200), null, captureId = c.id, accountHint = c.accountHint, at = c.at)
                    launch.value = LaunchRequest(c.type, c.amount.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: c.amount)
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { openStatement(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
                if (uri != null && ImportInbox.isStatement(intent.type, fileName(uri))) { openStatement(uri); return }
                val blobs = (application as KoshApplication).container.repo.blobs
                lifecycleScope.launch {
                    // Copy the file now, while this app has permission to read it.
                    val file = uri?.let { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { blobs.import(u) }.getOrNull() } }
                    val sms = text?.let(app.kosh.domain.SmsParser::parse)
                    SharedInbox.pending = SharedInbox.Shared(sms?.payee, text?.takeIf { sms?.amount != null }?.let { sms!!.note }, file, accountHint = sms?.accountHint)
                    launch.value = LaunchRequest(sms?.type ?: "expense", sms?.amount)
                }
            }
        }
    }

    private fun fileName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Reads a shared or opened statement now (the permission to read it ends with this intent) and opens Import. */
    private fun openStatement(uri: android.net.Uri) {
        lifecycleScope.launch {
            val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { app.kosh.data.StatementReader.bytes(this@MainActivity, uri) }.getOrNull()
            }
            if (read != null) {
                ImportInbox.pending = read
                launch.value = LaunchRequest(null, route = app.kosh.ui.Routes.IMPORT)
            }
        }
    }

    companion object {
        const val ACTION_ADD = "app.kosh.action.ADD"
        const val ACTION_QUICK = "app.kosh.action.QUICK"
        const val ACTION_OPEN = "app.kosh.action.OPEN"
        const val EXTRA_ROUTE = "route"

        /**
         * The activity is exported (for shortcuts and the share sheet), so another app could send ACTION_OPEN:
         * only screens that are safe to land on are accepted.
         */
        fun openable(route: String): Boolean =
            Regex("""tx\?id=([0-9a-f-]{36})?(&[a-z]+=[0-9A-Za-z.\-]*)*""").matches(route) ||
                Regex("""(account|loan|deposit)/[0-9a-f-]{36}""").matches(route) ||
                route in setOf(
                    app.kosh.ui.Routes.IMPORT, app.kosh.ui.Routes.WEEKLY, app.kosh.ui.Routes.RECURRING, app.kosh.ui.Routes.ACCOUNTS, app.kosh.ui.Routes.BUDGETS,
                    app.kosh.ui.Routes.DEBTS, app.kosh.ui.Routes.QUICK, app.kosh.ui.screens.FOUND_PAYMENTS_ROUTE,
                )

        /** Opens [route] in the app (used by notifications). */
        fun openIntent(context: android.content.Context, route: String): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
