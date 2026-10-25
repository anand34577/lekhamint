package app.kosh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kosh.AppContainer
import app.kosh.core.Currencies
import app.kosh.sync.ApiLedger
import app.kosh.sync.KoshApi
import app.kosh.sync.SyncEngine
import app.kosh.ui.LocalNav
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.ago
import app.kosh.ui.koshViewModel
import kotlinx.coroutines.launch

class SyncViewModel(private val c: AppContainer) : ViewModel() {
    var url by mutableStateOf(c.settings.current.serverUrl.orEmpty())
    var email by mutableStateOf(c.settings.current.accountEmail.orEmpty())
    var password by mutableStateOf("")
    var name by mutableStateOf("")
    var register by mutableStateOf(false)
    /** The account has two-step sign-in on: the password was right and a code is needed. */
    var needsCode by mutableStateOf(false)
        private set
    var code by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    /** Set after signing in, until the user picks which book to sync. */
    var serverLedgers by mutableStateOf<List<ApiLedger>?>(null)
        private set
    var joined by mutableStateOf(false)
        private set
    private var pendingToken: String? = null
    private var pendingUrl: String = ""

    val insecure: Boolean get() {
        val u = KoshApi.normalize(url.ifBlank { "x" })
        if (!u.startsWith("http://")) return false
        return !KoshApi.isLocalHost(u)
    }

    fun signIn() {
        if (url.isBlank() || email.isBlank() || password.length < (if (register) 8 else 1)) {
            error = if (register) "Enter the server, your email and a password of at least 8 characters" else "Enter the server, email and password"
            return
        }
        launchBusy {
            pendingUrl = KoshApi.normalize(url)
            val api = KoshApi(pendingUrl) { pendingToken }
            val meta = api.meta()
            if (meta.schema_version in 1 until SyncEngine.SCHEMA_VERSION) error("This server is too old for this app. Update the server.")
            val device = android.os.Build.MODEL ?: "Android"
            val auth = try {
                if (register) api.register(email.trim(), password, name.trim(), device) else api.login(email.trim(), password, device, code.trim().takeIf { needsCode })
            } catch (e: app.kosh.sync.ApiException) {
                if (e.code == "otp_required") { needsCode = true; return@launchBusy }
                throw e
            }
            pendingToken = auth.token
            password = ""
            code = ""
            needsCode = false
            val list = api.ledgers()
            val localId = c.settings.current.ledgerId
            if (c.settings.current.onboarded && list.any { it.id == localId }) {
                finishConnect()
                message = "Connected. This book is already on the server."
            } else {
                serverLedgers = list
            }
        }
    }

    /** Send this phone's book to the server (it becomes a server book). */
    fun uploadThisBook() = launchBusy {
        finishConnect()
        c.repo.markEverythingDirty()
        c.sync.sync()
        message = "This book is now synced."
    }

    /** Replace this phone's data with a book that already exists on the server. */
    fun useServerBook(l: ApiLedger) = launchBusy {
        if (c.settings.current.onboarded) c.backups.safetyBackup("pre-join")
        c.repo.wipeAll()
        c.settings.setLedger(l.id, l.name, l.base_currency)
        finishConnect()
        c.settings.setOnboarded()
        c.sync.sync()
        joined = true
    }

    /** New install with nothing on the server yet: start a fresh book and upload it. */
    fun startNewBook() = launchBusy {
        c.repo.createLedger("Personal", Currencies.forLocale())
        finishConnect()
        c.sync.sync()
        joined = true
    }

    private fun finishConnect() {
        c.settings.connect(pendingUrl, email.trim(), pendingToken ?: error("Not signed in"))
        serverLedgers = null
        c.scheduler.schedulePeriodicSync(true)
    }

    fun syncNow() = launchBusy {
        val r = c.sync.sync()
        message = "Synced: sent ${r.pushed}, received ${r.pulled}" + (if (r.rejected > 0) ", ${r.rejected} refused by the server" else "")
    }

    fun signOut() = launchBusy {
        runCatching { c.sync.api()?.logout() }
        c.settings.disconnect()
        c.scheduler.schedulePeriodicSync(false)
        message = "Signed out. Your data stays on this phone."
    }

    /** Deletes the server account. Data on this phone stays; sync is turned off. */
    fun deleteAccount(password: String) = launchBusy {
        val api = c.sync.api() ?: error("Not signed in")
        try {
            api.deleteAccount(password)
        } catch (e: app.kosh.sync.ApiException) {
            if (e.code == "invalid_credentials") error("Wrong password. Your account was not deleted.")
            throw e
        }
        c.settings.disconnect()
        c.scheduler.schedulePeriodicSync(false)
        message = "Your server account was deleted. Your data stays on this phone."
    }

    fun cancelChoice() {
        serverLedgers = null
        pendingToken = null
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = SyncEngine.describe(e)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun SyncScreen(onJoined: () -> Unit) {
    val vm = koshViewModel { SyncViewModel(it) }
    val c = LocalContainer.current
    val nav = LocalNav.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val pending by c.repo.pendingCount.collectAsStateWithLifecycle(0)
    val rejected by c.repo.rejectedCount.collectAsStateWithLifecycle(0)
    var confirmUse by androidx.compose.runtime.remember { mutableStateOf<ApiLedger?>(null) }
    var confirmDelete by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(vm.joined) { if (vm.joined) onJoined() }

    Scaffold(topBar = { KoshTopBar("Sync", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val ledgers = vm.serverLedgers
            when {
                ledgers != null -> {
                    Text("Which book should this phone use?", style = MaterialTheme.typography.titleLarge)
                    if (s.onboarded) {
                        KoshCard(Modifier.fillMaxWidth(), onClick = { vm.uploadThisBook() }) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Upload \"${s.ledgerName}\" from this phone", style = MaterialTheme.typography.titleMedium)
                                Text("Your data here goes to the server. Other devices can then join it.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else if (ledgers.isEmpty()) {
                        KoshCard(Modifier.fillMaxWidth(), onClick = { vm.startNewBook() }) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Start a new book", style = MaterialTheme.typography.titleMedium)
                                Text("Nothing on the server yet. We'll create one and sync it.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    for (l in ledgers) {
                        KoshCard(Modifier.fillMaxWidth(), onClick = { if (s.onboarded) confirmUse = l else vm.useServerBook(l) }) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Use \"${l.name}\" from the server", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${l.base_currency} · ${l.role}" + if (s.onboarded) " · replaces this phone's data (a backup is made first)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    TextButton(onClick = vm::cancelChoice) { Text("Cancel") }
                }
                s.syncEnabled -> {
                    KoshCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Text("Connected", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("${s.accountEmail} on ${s.serverUrl}", style = MaterialTheme.typography.bodyMedium)
                            Text("Book: ${s.ledgerName}", style = MaterialTheme.typography.bodyMedium)
                            Text("Last synced ${ago(s.lastSyncAt)}", style = MaterialTheme.typography.bodyMedium)
                            Text(if (pending == 0) "Everything is uploaded" else "$pending changes waiting to upload", style = MaterialTheme.typography.bodyMedium)
                            if (rejected > 0) Text("$rejected items were refused by the server. Edit them to try again.", color = MaterialTheme.colorScheme.error)
                            s.lastSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Button(onClick = vm::syncNow, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                    OutlinedButton(onClick = vm::signOut, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                    TextButton(onClick = { confirmDelete = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete account", color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "LekhaMint syncs in the background when you make changes and every 15 minutes. It always works offline; changes upload when you're back online.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text("Sync is optional", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Run the free LekhaMint server on a home computer, NAS or VPS to sync phones and the web app. Nothing goes to anyone else's cloud.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!vm.register, { vm.register = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sign in") }
                        SegmentedButton(vm.register, { vm.register = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Create account") }
                    }
                    OutlinedTextField(
                        vm.url, { vm.url = it }, label = { Text("Server address") }, placeholder = { Text("kosh.example.com or 192.168.1.10:8080") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.insecure) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Plain http:// outside your home network sends your password unencrypted. Use https.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (vm.register) OutlinedTextField(vm.name, { vm.name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vm.email, { vm.email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        vm.password, { vm.password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = if (vm.register) ({ Text("At least 8 characters") }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.needsCode) {
                        Text("Two-step sign-in is on for this account. Open your authenticator app and type the 6-digit code for LekhaMint, or one of your recovery codes.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            vm.code, { vm.code = it }, label = { Text("Code from your app") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(onClick = vm::signIn, enabled = !vm.busy && (!vm.needsCode || vm.code.trim().length >= 6), modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.register) "Create account & connect" else if (vm.needsCode) "Check code & connect" else "Connect")
                    }
                    if (!vm.register && vm.url.isNotBlank()) {
                        val uri = androidx.compose.ui.platform.LocalUriHandler.current
                        TextButton(onClick = { runCatching { uri.openUri(KoshApi.normalize(vm.url) + "/#/login") } }) { Text("Forgot password? Reset it on the web") }
                    }
                }
            }
            if (vm.busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text("Working…") }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) DeleteAccountDialog(
        email = s.accountEmail.orEmpty(),
        onConfirm = { pw -> confirmDelete = false; vm.deleteAccount(pw) },
        onDismiss = { confirmDelete = false },
    )
    confirmUse?.let { l ->
        ConfirmDialog(
            "Replace this phone's data?",
            "Everything on this phone is replaced with \"${l.name}\" from the server. A backup of the current data is saved first (Backup & restore).",
            "Replace", destructive = true, typeToConfirm = "REPLACE",
            onConfirm = { vm.useServerBook(l) }, onDismiss = { confirmUse = null },
        )
    }
}

@Composable
private fun DeleteAccountDialog(email: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This permanently deletes $email from the server, every book that only you own, and their data. " +
                        "You are removed from books shared with another owner. This cannot be undone.",
                )
                Text("The data on this phone is kept. Make a backup first if you want a copy of the server books.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text("Delete account", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
