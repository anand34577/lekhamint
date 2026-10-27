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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
    var url by mutableStateOf(c.settings.current.serverUrl ?: c.settings.current.lastServerUrl.orEmpty())
    var email by mutableStateOf(c.settings.current.accountEmail ?: c.settings.current.lastEmail.orEmpty())
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

    /** Connecting with a one-time code from the web app (Settings → Connect a phone). */
    var pairServer by mutableStateOf(c.settings.current.serverUrl ?: c.settings.current.lastServerUrl.orEmpty())
    var pairCode by mutableStateOf("")
    var showCodeEntry by mutableStateOf(false)
    /** The last problem came from connecting with a code, so it shows in that card. */
    var errorNearCode by mutableStateOf(false)
        private set

    val insecure: Boolean get() {
        val u = KoshApi.normalize(url.ifBlank { "x" })
        if (!u.startsWith("http://")) return false
        return !KoshApi.isLocalHost(u)
    }

    fun signIn() {
        errorNearCode = false
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
            password = ""
            code = ""
            needsCode = false
            afterSignIn(api, auth.token)
        }
    }

    /** Signed in: use this phone's book if the server has it, else ask which book to use. */
    private suspend fun afterSignIn(api: KoshApi, token: String) {
        pendingToken = token
        val list = api.ledgers()
        val localId = c.settings.current.ledgerId
        if (c.settings.current.onboarded && list.any { it.id == localId }) {
            finishConnect()
            message = "Connected. This book is already on the server."
        } else {
            serverLedgers = list
        }
    }

    /** A code from the QR link or typed in: no password needed on the phone. */
    fun usePairLink(p: app.kosh.sync.PairLink) {
        pairServer = p.server
        pairCode = p.code
        connectWithCode()
    }

    fun connectWithCode() {
        errorNearCode = true
        if (c.settings.current.syncEnabled) {
            error = "This phone is already connected as ${c.settings.current.accountEmail}. Sign out below first to connect another account."
            return
        }
        val code = app.kosh.sync.PairLink.normalizeCode(pairCode)
        if (pairServer.isBlank() || code.length != app.kosh.sync.PairLink.CODE_LENGTH) {
            error = "Enter the server address and the 10-character code shown on the web"
            showCodeEntry = true
            return
        }
        launchBusy {
            pendingUrl = KoshApi.normalize(pairServer)
            val api = KoshApi(pendingUrl) { pendingToken }
            val meta = api.meta()
            if (meta.schema_version in 1 until SyncEngine.SCHEMA_VERSION) error("This server is too old for this app. Update the server.")
            val auth = try {
                api.claimPairing(code, android.os.Build.MODEL ?: "Android")
            } catch (e: app.kosh.sync.ApiException) {
                if (e.status == 404) error("This server can't connect phones with a code yet. Update the server, or sign in with your email below.")
                throw e
            }
            email = auth.user.email
            url = pendingUrl
            pairCode = ""
            showCodeEntry = false
            afterSignIn(api, auth.token)
        }
    }

    /** What the camera saw: a connect code, something else, or nothing readable. */
    fun onScanned(text: String?) {
        errorNearCode = true
        val p = text?.let(app.kosh.sync.PairLink::parse)
        when {
            p != null -> usePairLink(p)
            text == null -> error = "No QR code found in that photo. Fit the whole code in the frame, hold steady and try again, or tap Enter code."
            else -> error = "That QR code isn't a LekhaMint connect code. On the web, open Settings → Signed-in devices → Connect a phone."
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

    /** Devices and browsers signed in to this account; null until loaded. */
    var devices by mutableStateOf<List<app.kosh.sync.ApiSession>?>(null)
        private set
    var devicesError by mutableStateOf<String?>(null)
        private set

    fun loadDevices() {
        val api = c.sync.api() ?: return
        viewModelScope.launch {
            try {
                devices = api.sessions().sortedWith(compareByDescending<app.kosh.sync.ApiSession> { it.current }.thenByDescending { it.last_used_at })
                devicesError = null
            } catch (e: Exception) {
                devicesError = SyncEngine.describe(e)
            }
        }
    }

    /** Removes another device from the account: it stops syncing at once; its own data stays on it. */
    fun removeDevice(d: app.kosh.sync.ApiSession) = launchBusy {
        val api = c.sync.api() ?: error("Not signed in")
        api.deleteSession(d.id)
        devices = devices?.filter { it.id != d.id }
        message = "Removed ${d.device_name.ifBlank { "that device" }}. It no longer syncs; its data stays on it."
    }

    fun signOut() = launchBusy {
        runCatching { c.sync.api()?.logout() }
        c.settings.disconnect()
        c.scheduler.schedulePeriodicSync(false)
        devices = null
        message = "This phone is disconnected and no longer listed on your account. Everything recorded here stays on this phone."
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
    var confirmRemove by androidx.compose.runtime.remember { mutableStateOf<app.kosh.sync.ApiSession?>(null) }
    var confirmDisconnect by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(s.syncEnabled) { if (s.syncEnabled) vm.loadDevices() }
    LaunchedEffect(vm.joined) { if (vm.joined) onJoined() }
    // Opened from the web app's QR code (lekhamint://pair…): connect straight away.
    val link by app.kosh.sync.PairLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(link) { link?.let { app.kosh.sync.PairLink.pending.value = null; vm.usePairLink(it) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    var shotUri by androidx.compose.runtime.remember { mutableStateOf<android.net.Uri?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri
        if (ok && u != null) scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { QrPhoto.read(context, u) }.getOrNull() }
            vm.onScanned(text)
        }
    }
    val scan = {
        val f = java.io.File(java.io.File(context.cacheDir, "camera").apply { mkdirs() }, "qr-${System.currentTimeMillis()}.jpg")
        val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        shotUri = u
        runCatching { camera.launch(u) }.onFailure { vm.onScanned(null) }
        Unit
    }
    var showPassword by androidx.compose.runtime.remember { mutableStateOf(false) }

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
                                // The heading tells the truth: a failing sync is not "Connected".
                                val failing = s.lastSyncError != null
                                Icon(
                                    if (failing) Icons.Rounded.CloudOff else Icons.Rounded.CloudSync, null,
                                    tint = if (failing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(if (failing) "Not syncing right now" else "Connected · live", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("${s.accountEmail} on ${s.serverUrl}", style = MaterialTheme.typography.bodyMedium)
                            Text("Book: ${s.ledgerName}", style = MaterialTheme.typography.bodyMedium)
                            Text("Last synced ${ago(s.lastSyncAt)}", style = MaterialTheme.typography.bodyMedium)
                            Text("While the app is open, changes from the web and other phones appear within seconds. In the background it syncs about every 15 minutes.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (pending == 0) "Everything is uploaded" else "$pending ${if (pending == 1) "change" else "changes"} waiting to upload", style = MaterialTheme.typography.bodyMedium)
                            if (rejected > 0) Text("${app.kosh.core.plural(rejected, "item")} were refused by the server. Edit them to try again.", color = MaterialTheme.colorScheme.error)
                            s.lastSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Button(onClick = vm::syncNow, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                    DevicesCard(vm.devices, vm.devicesError, busy = vm.busy, onRetry = vm::loadDevices, onRemove = { confirmRemove = it })
                    OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Disconnect this phone") }
                    TextButton(onClick = { confirmDelete = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete account", color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "LekhaMint syncs in the background when you make changes and every 15 minutes. It always works offline; changes upload when you're back online.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    if (s.removedAt > 0) RemovedNotice(s.lastServerUrl, s.removedAt, pending, onDismiss = { c.settings.clearRemoved() })
                    Text(if (s.removedAt > 0) "Connect again" else "Sync is optional", style = MaterialTheme.typography.titleLarge)
                    if (s.removedAt == 0L) Text(
                        "Run the free LekhaMint server on a home computer, NAS or VPS to sync phones and the web app. Nothing goes to anyone else's cloud.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Easiest: the web app is already signed in, so it hands this phone a one-time code.
                    KoshCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(10.dp))
                                Text("Connect with the web app", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "Signed in on the web? Open Settings → Signed-in devices → Connect a phone, and scan the code. No password to type. Your phone's own camera can open it too.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { scan() }, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Scan code")
                                }
                                OutlinedButton(onClick = { vm.showCodeEntry = !vm.showCodeEntry }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Enter code") }
                            }
                            if (vm.showCodeEntry) {
                                OutlinedTextField(
                                    vm.pairServer, { vm.pairServer = it }, label = { Text("Server address") }, singleLine = true,
                                    supportingText = { Text("Shown under the code on the web") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    vm.pairCode, { vm.pairCode = it.take(12) }, label = { Text("Code") }, placeholder = { Text("XXXXX-XXXXX") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Ascii, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                                        autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { vm.connectWithCode() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = vm::connectWithCode, modifier = Modifier.fillMaxWidth(),
                                    enabled = !vm.busy && app.kosh.sync.PairLink.normalizeCode(vm.pairCode).length == app.kosh.sync.PairLink.CODE_LENGTH && vm.pairServer.isNotBlank(),
                                ) { Text("Connect") }
                            }
                            if (vm.errorNearCode) vm.error?.let { Text(it.replaceFirstChar { ch -> ch.uppercase() }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    Text("Or sign in with your email", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!vm.register, { vm.register = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sign in") }
                        SegmentedButton(vm.register, { vm.register = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Create account") }
                    }
                    OutlinedTextField(
                        vm.url, { vm.url = it }, label = { Text("Server address") }, placeholder = { Text("money.example.com") },
                        supportingText = if (vm.insecure) null else ({ Text("Where you open the web app, like money.example.com or 192.168.1.20:8080") }),
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.insecure) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Plain http:// outside your home network sends your password unencrypted. Use https.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (vm.register) OutlinedTextField(vm.name, { vm.name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vm.email, { vm.email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Next), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        vm.password, { vm.password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (vm.needsCode) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { vm.signIn() }),
                        trailingIcon = {
                            androidx.compose.material3.IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password")
                            }
                        },
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
            if (!vm.errorNearCode || vm.serverLedgers != null || s.syncEnabled) vm.error?.let { Text(it.replaceFirstChar { ch -> ch.uppercase() }, color = MaterialTheme.colorScheme.error) }
            vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(24.dp))
        }
    }
    confirmRemove?.let { d ->
        ConfirmDialog(
            title = "Remove ${d.device_name.ifBlank { "this device" }}?",
            text = "It stops syncing straight away and leaves your account. What's recorded on it stays on that device, and it can be connected again later. Use this for a lost or old phone.",
            confirm = "Remove",
            onConfirm = { confirmRemove = null; vm.removeDevice(d) },
            onDismiss = { confirmRemove = null },
            destructive = true,
        )
    }
    if (confirmDisconnect) ConfirmDialog(
        title = "Disconnect this phone?",
        text = if (pending > 0) "It stops syncing and leaves your account. Everything stays on this phone, including $pending ${if (pending == 1) "change" else "changes"} not uploaded yet: they upload if you connect again." else "It stops syncing and leaves your account. Everything stays on this phone, and you can connect again any time.",
        confirm = "Disconnect",
        onConfirm = { confirmDisconnect = false; vm.signOut() },
        onDismiss = { confirmDisconnect = false },
    )
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
                    "This permanently deletes $email from the server. Books only you use are deleted with their data; " +
                        "a book you share passes to the person who has been in it longest. This cannot be undone.",
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

/** Every device signed in to the account, this phone first; any other can be removed. */
@Composable
private fun DevicesCard(devices: List<app.kosh.sync.ApiSession>?, error: String?, busy: Boolean, onRetry: () -> Unit, onRemove: (app.kosh.sync.ApiSession) -> Unit) {
    KoshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Devices on this account", style = MaterialTheme.typography.titleMedium)
            when {
                error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                devices == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.size(8.dp)); Text("Loading…") }
                else -> for (d in devices) {
                    val browser = d.device_name.contains("browser", ignoreCase = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (browser) Icons.Rounded.Language else Icons.Rounded.PhoneAndroid, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.device_name.ifBlank { "Unknown device" } + if (d.current) " · this phone" else "", style = MaterialTheme.typography.bodyLarge)
                            Text("Last used ${ago(d.last_used_at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!d.current) TextButton(onClick = { onRemove(d) }, enabled = !busy) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Text(
                "Removing a device stops it syncing at once; its own data stays on it. Lost a phone? Remove it here or on the web.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown after the server stopped accepting this phone: what happened, and that nothing here was lost. */
@Composable
private fun RemovedNotice(server: String?, at: Long, pending: Int, onDismiss: () -> Unit) {
    KoshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(10.dp))
                Text("This phone was removed from the server", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "It was signed out ${ago(at)}${server?.let { " from ${it.removePrefix("https://").removePrefix("http://")}" } ?: ""}, on the web or from another device, or the account's password changed. " +
                    "Everything recorded here is still on this phone" +
                    (if (pending > 0) ", including $pending ${if (pending == 1) "change" else "changes"} not uploaded yet." else ".") +
                    " Connect again below to keep syncing.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss) { Text("Keep this phone offline") }
        }
    }
}
