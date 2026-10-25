package app.kosh.ui.screens

import app.kosh.ui.components.FormSheet
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.backup.BackupDecryptException
import app.kosh.backup.BackupPreview
import app.kosh.backup.CsvExport
import app.kosh.backup.RestoreMode
import app.kosh.core.Dates
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.SettingRow
import app.kosh.ui.components.ago
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId

/** Where a restore reads from: a user-picked document or an automatic backup file. */
private sealed interface RestoreSource {
    fun open(context: android.content.Context): InputStream
    data class Doc(val uri: Uri) : RestoreSource {
        override fun open(context: android.content.Context) = context.contentResolver.openInputStream(uri) ?: error("Can't open the file")
    }
    data class Local(val file: File) : RestoreSource {
        override fun open(context: android.content.Context) = file.inputStream()
    }
}

@Composable
fun BackupScreen(onRestored: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    var restoreSource by remember { mutableStateOf<RestoreSource?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var showLocal by remember { mutableStateOf(false) }
    var askAutoPassword by remember { mutableStateOf(false) }
    var hasAutoPass by remember { mutableStateOf(c.backups.hasAutoPassphrase) }

    fun run(label: String, block: suspend () -> String) {
        busy = label
        scope.launch {
            val msg = try { block() } catch (e: Exception) { e.message ?: "Something went wrong" }
            busy = null
            snackbar.showSnackbar(msg)
        }
    }

    val exportKosh = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val pass = exportPassword
        run("Writing backup…") {
            val n = context.contentResolver.openOutputStream(uri, "wt")!!.use { c.backups.writeBackup(it, pass?.toCharArray()) }
            "Backup saved ($n records${if (pass != null) ", encrypted" else ""})"
        }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run("Exporting…") {
            val n = context.contentResolver.openOutputStream(uri, "wt")!!.use { c.backups.writeLedgerJson(it) }
            "Exported $n records"
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run("Exporting…") {
            val rows = c.db.transactions().everything()
            val n = context.contentResolver.openOutputStream(uri, "wt")!!.use { CsvExport.write(rows, c.lookup.value, it) }
            "Exported $n transactions"
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val src = RestoreSource.Doc(uri)
        scope.launch {
            try {
                preview = c.backups.preview { src.open(context) }
                restoreSource = src
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Not a LekhaMint backup")
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        c.settings.setBackupFolder(uri.toString())
    }

    Scaffold(topBar = { KoshTopBar("Backup & restore", onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                busy?.let {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text(it)
                    }
                }
                SectionHeader("Automatic")
                SettingRow(
                    "Daily backup", if (s.lastBackupError != null) "Last attempt failed: ${s.lastBackupError}" else "Last backup ${ago(s.lastBackupAt)} · keeps 7 daily and 4 weekly copies",
                    Icons.Rounded.Backup, onClick = { c.settings.setAutoBackup(!s.autoBackup) },
                ) { Switch(s.autoBackup, { c.settings.setAutoBackup(it) }) }
                SettingRow(
                    "Also copy to a folder",
                    s.backupFolderUri?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') ?: "Chosen folder" } ?: "Pick Google Drive, OneDrive, SD card or Nextcloud through Android's file picker",
                    Icons.Rounded.Folder, onClick = { pickFolder.launch(null) },
                ) { if (s.backupFolderUri != null) TextButton(onClick = { c.settings.setBackupFolder(null) }) { Text("Remove") } }
                SettingRow(
                    "Password for folder copies", if (hasAutoPass) "On · copies are encrypted (AES-256)" else "Off · recommended for cloud folders",
                    Icons.Rounded.Key, onClick = { askAutoPassword = true },
                )
                SettingRow("Back up now", null, Icons.Rounded.Backup, onClick = { run("Backing up…") { c.backups.autoBackup(); "Backup done" } })

                SectionHeader("Export")
                SettingRow("Save a backup file (.kosh)", "Everything, optionally password-protected. Restores on any device.", Icons.Rounded.Backup, onClick = { askExportPassword = true })
                SettingRow("Export to spreadsheet (CSV)", "Transactions for Excel, Sheets or another app", Icons.Rounded.TableChart, onClick = { exportCsv.launch("kosh-transactions-${Dates.today()}.csv") })
                SettingRow("Export for the server (JSON)", "Same format as the server's import/export API", Icons.Rounded.DataObject, onClick = { exportJson.launch("kosh-ledger-${Dates.today()}.json") })

                SectionHeader("Restore")
                SettingRow("Restore from a file", ".kosh backup or LekhaMint JSON export", Icons.Rounded.Restore, onClick = { openBackup.launch(arrayOf("*/*")) })
                SettingRow("Restore an automatic backup", "${c.backups.localBackups().size} on this phone", Icons.Rounded.History, onClick = { showLocal = true })
                Text(
                    "To bring back a single deleted item, use Trash instead. Restoring merges by default: nothing on this phone is removed, and the newer version of each item wins.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (askExportPassword) {
        PasswordDialog(
            title = "Protect with a password?",
            body = "Recommended if you store the file in the cloud. If you lose the password, the backup cannot be opened by anyone, including you.",
            confirm = "Save", allowEmpty = true,
            onDismiss = { askExportPassword = false },
        ) { p ->
            exportPassword = p.ifEmpty { null }
            exportKosh.launch("lekhamint-backup-${Dates.today()}.kosh")
        }
    }
    if (askAutoPassword) {
        PasswordDialog(
            title = "Password for folder copies",
            body = "Stored securely on this phone (Android Keystore) so daily copies can be encrypted. Leave empty to turn off.",
            confirm = "Save", allowEmpty = true,
            onDismiss = { askAutoPassword = false },
        ) { p -> c.backups.setAutoPassphrase(p.ifEmpty { null }); hasAutoPass = p.isNotEmpty() }
    }
    if (showLocal) {
        val files = remember { c.backups.localBackups() }
        AlertDialog(
            onDismissRequest = { showLocal = false },
            title = { Text("Automatic backups") },
            text = {
                Column {
                    if (files.isEmpty()) Text("None yet. The first one is made within a day, or tap Back up now.")
                    for (f in files.take(12)) {
                        val at = Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
                        SettingRow(Dates.medium(at), "${f.name} · ${f.length() / 1024} KB", onClick = {
                            showLocal = false
                            val src = RestoreSource.Local(f)
                            scope.launch {
                                preview = runCatching { c.backups.preview { src.open(context) } }.getOrNull()
                                restoreSource = src
                            }
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLocal = false }) { Text("Close") } },
        )
    }
    val src = restoreSource
    val pv = preview
    if (src != null && pv != null) {
        RestoreDialog(pv, onDismiss = { restoreSource = null; preview = null }) { mode, password ->
            restoreSource = null; preview = null
            busy = "Restoring…"
            scope.launch {
                val msg = try {
                    val r = c.backups.restore({ src.open(context) }, password?.toCharArray(), mode)
                    if (s.syncEnabled) c.scheduler.syncNow()
                    "Restored: ${r.applied} items added or updated, ${r.skipped} already newer here" + if (r.invalid > 0) ", ${r.invalid} invalid skipped" else ""
                } catch (e: BackupDecryptException) {
                    e.message ?: "Wrong password"
                } catch (e: Exception) {
                    "Restore failed, nothing was changed: ${e.message}"
                }
                busy = null
                snackbar.showSnackbar(msg)
                if (msg.startsWith("Restored")) onRestored()
            }
        }
    }
}

@Composable
private fun PasswordDialog(title: String, body: String, confirm: String, allowEmpty: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var p by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val ok = (allowEmpty && p.isEmpty()) || (p.length >= 8 && p == p2)
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(p, { p = it }, label = { Text("Password (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (p.isNotEmpty()) OutlinedTextField(p2, { p2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), isError = p2.isNotEmpty() && p2 != p)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDismiss(); onConfirm(p) }) { Text(if (p.isEmpty() && allowEmpty) "$confirm without password" else confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestoreDialog(p: BackupPreview, onDismiss: () -> Unit, onRestore: (RestoreMode, String?) -> Unit) {
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var password by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf(false) }
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("Restore \"${p.ledgerName}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val at = if (p.createdAt > 0) Dates.medium(Instant.ofEpochMilli(p.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()) else "unknown date"
                Text("Made $at" + if (p.records >= 0) " · ${p.records} records" else "")
                if (p.encrypted) {
                    OutlinedTextField(password, { password = it }, label = { Text("Backup password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == RestoreMode.MERGE, { mode = RestoreMode.MERGE })
                    Column { Text("Merge (recommended)"); Text("Adds what's missing; keeps newer changes on this phone", style = MaterialTheme.typography.bodySmall) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == RestoreMode.REPLACE, { mode = RestoreMode.REPLACE })
                    Column { Text("Replace"); Text("Wipes this phone's data first. A safety backup is made.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !p.encrypted || password.isNotEmpty(), onClick = {
                if (mode == RestoreMode.REPLACE) confirmReplace = true else onRestore(mode, password.ifEmpty { null })
            }) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmReplace) {
        ConfirmDialog(
            "Replace all data on this phone?",
            "Everything here is replaced with the backup. If sync is on, newer changes from the server will merge back in.",
            "Replace", destructive = true, typeToConfirm = "REPLACE",
            onConfirm = { onRestore(RestoreMode.REPLACE, password.ifEmpty { null }) },
            onDismiss = { confirmReplace = false },
        )
    }
}
