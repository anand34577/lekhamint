package app.kosh.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.sync.ApiException
import app.kosh.sync.ApiMember
import app.kosh.ui.LocalNav
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.HeroCard
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.LocalOnHero
import app.kosh.ui.components.SectionHeader
import app.kosh.ui.components.StatusPill
import app.kosh.ui.rememberSafeScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Share this book with family: everyone added sees the same accounts and entries and can add from their own
 * phone or the web app, all through your own server. Only the owner can add or remove people.
 */
@Composable
fun ShareScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberSafeScope()
    val s by c.settings.state.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf<List<ApiMember>?>(null) }
    var myRole by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("editor") }
    var busy by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ApiMember?>(null) }
    val host = s.serverUrl?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/') ?: ""

    fun explain(e: Throwable): String = when {
        e is ApiException && e.code == "user_not_found" -> "No account with that email on $host yet. Send them an invite, then add them once they have signed up."
        e is ApiException && e.code == "forbidden" -> "Only the book's owner can add or remove people."
        e is ApiException && e.code == "last_owner" -> "A book needs at least one owner."
        e is ApiException && e.status == 401 -> "Sign in again under Sync."
        e is IOException -> "Can't reach $host. Check the connection and try again."
        else -> e.message ?: "Something went wrong"
    }

    LaunchedEffect(reload) {
        val api = c.sync.api() ?: run { error = "Turn on sync first."; return@LaunchedEffect }
        runCatching {
            myRole = api.ledgers().firstOrNull { it.id == s.ledgerId }?.role
            members = api.members(s.ledgerId).sortedWith(compareBy({ it.role != "owner" }, { it.created_at }))
            error = null
        }.onFailure { error = explain(it) }
    }

    fun invite() {
        val text = buildString {
            append("Join my LekhaMint book “${s.ledgerName}” so we can track money together.\n\n")
            append("1. Create an account at https://$host (or in the LekhaMint app: More → Sync → $host).\n")
            append("2. Send me the email you used and I'll add you.\n")
            append("3. In the app, sign in and pick “${s.ledgerName}”.")
        }
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite to LekhaMint")) }
    }

    Scaffold(topBar = { KoshTopBar("Share with family", onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val on = LocalOnHero.current
                    Text("One book, the whole family", style = MaterialTheme.typography.titleLarge, color = on)
                    Text(
                        "Everyone you add sees “${s.ledgerName}” and can add expenses from their own phone or the web. " +
                            "It all stays on your server, $host.",
                        style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.88f), modifier = Modifier.padding(top = 6.dp),
                    )
                    OutlinedButton(onClick = ::invite, modifier = Modifier.padding(top = 12.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = on)) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Text("  Send an invite")
                    }
                }
            }
            error?.let { msg ->
                item {
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            item { SectionHeader("People with access") }
            val list = members
            if (list == null && error == null) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
            if (list != null) {
                item {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            for (m in list) MemberRow(m, me = m.email.equals(s.accountEmail, ignoreCase = true), canRemove = myRole == "owner" && m.role != "owner") { removing = m }
                        }
                    }
                }
            }
            if (myRole == "owner") {
                item { SectionHeader("Add someone") }
                item {
                    KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                email, { email = it; addError = null }, label = { Text("Their email on $host") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
                            )
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(role == "editor", { role = "editor" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Can add & edit") }
                                SegmentedButton(role == "viewer", { role = "viewer" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Can only view") }
                            }
                            addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Button(
                                enabled = !busy && email.contains('@'),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val api = c.sync.api()
                                        runCatching { api!!.addMember(s.ledgerId, email, role) }
                                            .onSuccess { email = ""; reload++ }
                                            .onFailure { addError = explain(it) }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) { Text(if (busy) "Adding…" else "Add to ${s.ledgerName}") }
                            Text(
                                "They need an account on your server first. Once added, they sign in on their phone and pick this book.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (myRole != null) {
                item {
                    Text(
                        "Only the owner can add or remove people. Ask them to add someone from their phone or the web app.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    removing?.let { m ->
        ConfirmDialog(
            title = "Remove ${m.name.ifBlank { m.email }}?",
            text = "They lose access to “${s.ledgerName}” on every device. What they added stays in the book.",
            confirm = "Remove",
            destructive = true,
            onDismiss = { removing = null },
            onConfirm = {
                scope.launch {
                    runCatching { c.sync.api()!!.removeMember(s.ledgerId, m.user_id) }.onSuccess { reload++ }.onFailure { error = explain(it) }
                }
            },
        )
    }
}

@Composable
private fun MemberRow(m: ApiMember, me: Boolean, canRemove: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val label = m.name.ifBlank { m.email.substringBefore('@') }
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(if (me) "$label (you)" else label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusPill(
            when (m.role) { "owner" -> "Owner"; "viewer" -> "Can view"; else -> "Can edit" },
            if (m.role == "owner") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        )
        if (canRemove) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove ${m.email}") }
    }
}
