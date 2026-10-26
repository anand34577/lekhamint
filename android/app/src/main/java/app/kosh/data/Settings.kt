package app.kosh.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Colour themes. Mint is the default: calm, high-contrast and easy to read for money. */
enum class ThemePalette(val label: String) { MINT("Mint"), OCEAN("Ocean"), INDIGO("Indigo"), GRAPHITE("Graphite"), PLUM("Plum"), SUNSET("Sunset") }

/** User-facing preferences, observed by the UI. */
data class AppSettings(
    val onboarded: Boolean = false,
    val ledgerId: String = "",
    val ledgerName: String = "Personal",
    val baseCurrency: String = "USD",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.MINT,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = false,
    val hideAmounts: Boolean = false,
    val appLock: Boolean = false,
    val serverUrl: String? = null,
    val accountEmail: String? = null,
    val lastSyncAt: Long = 0,
    val lastSyncError: String? = null,
    val autoBackup: Boolean = true,
    val backupFolderUri: String? = null,
    val lastBackupAt: Long = 0,
    val lastBackupError: String? = null,
    /** Post a notification when a payment is found in a bank or payment app alert. */
    val captureNotify: Boolean = true,
) {
    val syncEnabled: Boolean get() = serverUrl != null && accountEmail != null
}

/**
 * SharedPreferences-backed settings. The file lives in app-private storage and
 * Android Auto Backup is off (see the manifest), so nothing leaks to cloud backups.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kosh", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        onboarded = prefs.getBoolean(K_ONBOARDED, false),
        ledgerId = prefs.getString(K_LEDGER_ID, "") ?: "",
        ledgerName = prefs.getString(K_LEDGER_NAME, "Personal") ?: "Personal",
        baseCurrency = prefs.getString(K_BASE_CURRENCY, "USD") ?: "USD",
        theme = runCatching { ThemeMode.valueOf(prefs.getString(K_THEME, "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        palette = runCatching { ThemePalette.valueOf(prefs.getString(K_PALETTE, "MINT")!!) }.getOrDefault(ThemePalette.MINT),
        amoled = prefs.getBoolean(K_AMOLED, false),
        dynamicColor = prefs.getBoolean(K_DYNAMIC, false),
        hideAmounts = prefs.getBoolean(K_HIDE, false),
        appLock = prefs.getBoolean(K_LOCK, false),
        serverUrl = prefs.getString(K_SERVER, null),
        accountEmail = prefs.getString(K_EMAIL, null),
        lastSyncAt = prefs.getLong(K_LAST_SYNC, 0),
        lastSyncError = prefs.getString(K_SYNC_ERROR, null),
        autoBackup = prefs.getBoolean(K_AUTO_BACKUP, true),
        backupFolderUri = prefs.getString(K_BACKUP_FOLDER, null),
        lastBackupAt = prefs.getLong(K_LAST_BACKUP, 0),
        lastBackupError = prefs.getString(K_BACKUP_ERROR, null),
        captureNotify = prefs.getBoolean(K_CAPTURE_NOTIFY, true),
    )

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        // apply(): the in-memory copy changes at once and the disk write happens off the main thread.
        prefs.edit(commit = false, action = block)
        _state.value = read()
    }

    fun setLedger(id: String, name: String, currency: String) = update {
        putString(K_LEDGER_ID, id); putString(K_LEDGER_NAME, name); putString(K_BASE_CURRENCY, currency)
    }

    fun setOnboarded() = update { putBoolean(K_ONBOARDED, true) }
    fun setTheme(mode: ThemeMode) = update { putString(K_THEME, mode.name); appearanceChanged() }
    fun setPalette(p: ThemePalette) = update { putString(K_PALETTE, p.name); appearanceChanged() }
    fun setAmoled(on: Boolean) = update { putBoolean(K_AMOLED, on); appearanceChanged() }
    fun setDynamicColor(on: Boolean) = update { putBoolean(K_DYNAMIC, on); appearanceChanged() }

    /** The look was changed on this phone: send it to the server on the next sync, so other phones follow. */
    private fun SharedPreferences.Editor.appearanceChanged() {
        putBoolean(K_APPEARANCE_DIRTY, true)
        putLong(K_APPEARANCE_AT, System.currentTimeMillis())
    }

    val appearanceDirty: Boolean get() = prefs.getBoolean(K_APPEARANCE_DIRTY, false)
    val appearanceAt: Long get() = prefs.getLong(K_APPEARANCE_AT, 0)

    fun appearanceSent(at: Long) = prefs.edit { if (prefs.getLong(K_APPEARANCE_AT, 0) == at) putBoolean(K_APPEARANCE_DIRTY, false) }

    /** A look chosen on another device (from the server); not sent back. */
    fun applyAppearance(theme: ThemeMode, palette: ThemePalette, amoled: Boolean, dynamic: Boolean, at: Long) = update {
        putString(K_THEME, theme.name); putString(K_PALETTE, palette.name); putBoolean(K_AMOLED, amoled); putBoolean(K_DYNAMIC, dynamic)
        putLong(K_APPEARANCE_AT, at); putBoolean(K_APPEARANCE_DIRTY, false)
    }
    fun setHideAmounts(on: Boolean) = update { putBoolean(K_HIDE, on) }
    fun setCaptureNotify(on: Boolean) = update { putBoolean(K_CAPTURE_NOTIFY, on) }
    fun setAppLock(on: Boolean) = update { putBoolean(K_LOCK, on) }
    fun setBaseCurrency(code: String) = update { putString(K_BASE_CURRENCY, code) }
    fun setLedgerName(name: String) = update { putString(K_LEDGER_NAME, name) }
    fun setAutoBackup(on: Boolean) = update { putBoolean(K_AUTO_BACKUP, on) }
    fun setBackupFolder(uri: String?) = update { if (uri == null) remove(K_BACKUP_FOLDER) else putString(K_BACKUP_FOLDER, uri) }

    fun setBackupResult(at: Long, error: String?) = update {
        if (error == null) putLong(K_LAST_BACKUP, at)
        putString(K_BACKUP_ERROR, error)
    }

    // --- Sync ---------------------------------------------------------------

    val token: String? get() = secrets.get(S_TOKEN)

    fun connect(serverUrl: String, email: String, token: String) {
        secrets.put(S_TOKEN, token)
        update {
            putString(K_SERVER, serverUrl); putString(K_EMAIL, email)
            putLong(K_CURSOR, 0); remove(K_SYNC_ERROR); putLong(K_LAST_SYNC, 0)
        }
    }

    fun disconnect() {
        secrets.remove(S_TOKEN)
        update { remove(K_SERVER); remove(K_EMAIL); remove(K_SYNC_ERROR); putLong(K_CURSOR, 0) }
    }

    /** Month the financial year starts (1-12) for Year & tax; null until chosen. */
    var fyStartMonth: Int?
        get() = prefs.getInt("fy_start_month", 0).takeIf { it in 1..12 }
        set(v) = prefs.edit { putInt("fy_start_month", v ?: 0) }

    var syncCursor: Long
        get() = prefs.getLong(K_CURSOR, 0)
        set(v) = prefs.edit(commit = true) { putLong(K_CURSOR, v) }

    fun setSyncResult(at: Long, error: String?) = update {
        if (error == null) putLong(K_LAST_SYNC, at)
        if (error == null) remove(K_SYNC_ERROR) else putString(K_SYNC_ERROR, error)
    }

    /** Random per-install node ID for the hybrid logical clock. */
    val nodeId: String
        get() = prefs.getString(K_NODE, null) ?: app.kosh.core.Hlc.randomNode().also {
            prefs.edit(commit = true) { putString(K_NODE, it) }
        }

    var lastHlc: String?
        get() = prefs.getString(K_HLC, null)
        set(v) = prefs.edit { putString(K_HLC, v) }

    /** Small per-feature flags (e.g. "budget alert sent for period X"). */
    fun flag(key: String): Boolean = prefs.getBoolean("flag.$key", false)
    fun setFlag(key: String) = prefs.edit { putBoolean("flag.$key", true) }

    private companion object {
        const val K_ONBOARDED = "onboarded"
        const val K_LEDGER_ID = "ledger_id"
        const val K_LEDGER_NAME = "ledger_name"
        const val K_BASE_CURRENCY = "base_currency"
        const val K_THEME = "theme"
        const val K_PALETTE = "palette"
        const val K_AMOLED = "amoled"
        const val K_DYNAMIC = "dynamic_color"
        const val K_HIDE = "hide_amounts"
        const val K_LOCK = "app_lock"
        const val K_SERVER = "server_url"
        const val K_EMAIL = "account_email"
        const val K_CURSOR = "sync_cursor"
        const val K_LAST_SYNC = "last_sync_at"
        const val K_SYNC_ERROR = "last_sync_error"
        const val K_NODE = "hlc_node"
        const val K_HLC = "hlc_last"
        const val K_AUTO_BACKUP = "auto_backup"
        const val K_BACKUP_FOLDER = "backup_folder"
        const val K_LAST_BACKUP = "last_backup_at"
        const val K_BACKUP_ERROR = "last_backup_error"
        const val K_CAPTURE_NOTIFY = "capture_notify"
        const val K_APPEARANCE_DIRTY = "appearance_dirty"
        const val K_APPEARANCE_AT = "appearance_at"
        const val S_TOKEN = "sync_token"
    }
}

/**
 * Stores small secrets (the sync token, an optional backup passphrase) encrypted
 * with an AES-GCM key that never leaves the Android Keystore.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("kosh.secrets", Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit(commit = true) { putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    fun get(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(name: String) = prefs.edit(commit = true) { remove(name) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "kosh.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
