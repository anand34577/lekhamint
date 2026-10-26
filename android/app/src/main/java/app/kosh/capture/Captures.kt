package app.kosh.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.kosh.KoshApplication
import app.kosh.data.KoshJson
import app.kosh.domain.SmsParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest

/** A payment read from a bank, card or payment app alert, waiting for the person to add or ignore it. */
@Serializable
data class Captured(
    val id: String,
    val at: Long,
    val amount: String,
    val type: String,
    val payee: String? = null,
    val text: String,
    /** The app the alert came from, e.g. "Messages" or "Google Pay". */
    val source: String,
    val accountHint: String? = null,
    /** new, added or ignored. */
    val state: String = "new",
    /** The transaction saved for it straight from the notification, so Undo can remove it. */
    val txId: String? = null,
)

/**
 * Found payments, kept in a small file on this phone only (never synced or backed up: the alert text can be
 * private). The latest 100 are kept.
 */
class CaptureStore(context: Context) {
    private val file = File(context.filesDir, "captured.json")
    private val serializer = ListSerializer(Captured.serializer())
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Captured>> = _items.asStateFlow()

    private fun load(): List<Captured> =
        runCatching { KoshJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun store(list: List<Captured>) {
        _items.value = list
        runCatching {
            val tmp = File(file.parentFile, "captured.json.tmp")
            tmp.writeText(KoshJson.encodeToString(serializer, list))
            tmp.renameTo(file)
        }
    }

    /**
     * Adds a found payment unless it is a repeat: the same text in the last three days, or the same amount and
     * direction within 15 minutes (the bank's SMS and its app often both announce one payment).
     */
    @Synchronized
    fun add(r: SmsParser.Result, text: String, source: String, at: Long = System.currentTimeMillis()): Captured? {
        val amount = r.amount ?: return null
        val list = _items.value
        val id = digest(text.lowercase().replace(Regex("""\s+"""), " ").trim())
        if (list.any { it.id == id && at - it.at < 3 * DAY }) return null
        if (list.any { it.amount.toBigDecimalOrNull() == amount.toBigDecimalOrNull() && it.type == r.type && kotlin.math.abs(at - it.at) < 15 * 60_000 }) return null
        val c = Captured(id, at, amount, r.type, r.payee, text.take(400), source, r.accountHint)
        store((listOf(c) + list.filter { it.id != id }).take(100))
        return c
    }

    @Synchronized
    fun mark(id: String, state: String, txId: String? = null) =
        store(_items.value.map { if (it.id == id) it.copy(state = state, txId = txId) else it })

    @Synchronized
    fun ignoreAllNew() = store(_items.value.map { if (it.state == "new") it.copy(state = "ignored") else it })

    @Synchronized
    fun clear() = store(emptyList())

    fun find(id: String): Captured? = _items.value.firstOrNull { it.id == id }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    companion object {
        private const val DAY = 24 * 60 * 60_000L

        /** True when the person has given LekhaMint notification access in system settings. */
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun accessSettingsIntent(context: Context): Intent =
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, PaymentListener::class.java).flattenToString())
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
    }
}

/**
 * Reads alerts as they arrive and keeps the ones that look like a debit or credit. Everything is parsed on the
 * phone; nothing is sent anywhere. Chat apps are skipped, and other apps' alerts must carry a bank-style detail
 * (an account or card number, UPI, a reference) unless they come from a known payment app.
 */
class PaymentListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in CHAT_APPS || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val text = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString()
            ?.takeIf { it.isNotBlank() } ?: return
        val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val full = if (pkg in PAYMENT_APPS && title.isNotBlank() && !text.contains(title)) "$title. $text" else text
        val r = SmsParser.parse(full)
        if (!r.isTransaction) return
        if (pkg !in PAYMENT_APPS && r.accountHint == null && !BANKISH.containsMatchIn(full)) return
        val container = (application as KoshApplication).container
        val source = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val item = container.captures.add(r, full, source, sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()) ?: return
        if (container.settings.current.captureNotify) {
            val app = applicationContext
            container.appScope.launch { runCatching { CaptureNotifier.found(app, container, item) } }
        }
    }

    companion object {
        private val BANKISH = Regex("""(?i)\b(upi|a/?c|acct|bank|card|wallet|neft|imps|rtgs|ref(?:\s*no)?|txn|transaction|vpa)\b""")
        private val CHAT_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.instagram.android",
            "com.facebook.orca", "com.facebook.katana", "com.discord", "com.Slack", "com.snapchat.android", "com.google.android.apps.dynamite",
            "com.microsoft.teams", "jp.naver.line.android", "com.viber.voip", "com.skype.raider",
        )
        private val PAYMENT_APPS = setOf(
            "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "com.dreamplug.androidapp",
            "in.amazon.mShop.android.shopping", "com.mobikwik_new", "com.freecharge.android", "com.paypal.android.p2pmobile", "com.venmo",
            "com.squareup.cash", "com.revolut.revolut", "co.uk.getmondo", "com.transferwise.android", "com.google.android.apps.walletnfcrel",
        )
        const val CHANNEL = "found_payments"
        const val EXTRA_ID = "capture_id"
        const val ACTION_CAPTURE = "app.kosh.action.CAPTURE"
    }
}
