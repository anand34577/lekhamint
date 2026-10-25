package app.kosh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import app.kosh.ai.AiSettings
import app.kosh.ai.AiStore
import app.kosh.ai.Assistant
import app.kosh.ai.Turn
import app.kosh.core.Links
import app.kosh.ui.LocalNav
import app.kosh.ui.components.AssumptionNote
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import kotlinx.coroutines.launch

private val suggestions = listOf(
    "How much did I spend this month, and on what?",
    "What EMIs and bills are coming up?",
    "How much do I still owe on my loans?",
    "Who owes me money?",
    "Which deposits mature soon?",
)

@Composable
fun AssistantScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val context = LocalContext.current
    val store = remember { AiStore(context) }
    val assistant = remember { Assistant(c, store) }
    var s by remember { mutableStateOf(store.load()) }
    val turns = remember { mutableStateListOf<Turn>() }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(!s.configured) }
    var consent by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }

    fun send(text: String, agreed: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        if (!s.configured) { setup = true; return }
        if (!agreed && s.consentFor != s.baseUrl) { consent = t; return }
        busy = true; q = ""
        scope.launch {
            val turn = runCatching { assistant.ask(turns.toList(), t) }.getOrElse { Turn(t, "", emptyList(), false, it.message ?: "Something went wrong") }
            turns += turn
            busy = false
        }
    }

    Scaffold(topBar = {
        KoshTopBar("Assistant", onBack = { nav.popBackStack() }) { IconButton(onClick = { setup = true }) { Icon(Icons.Rounded.Settings, "Assistant settings") } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (turns.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ask about your money in plain words. Answers come from your own records through read-only lookups; the assistant can never change anything.", style = MaterialTheme.typography.bodyMedium)
                        suggestions.forEach { AssistChip(onClick = { send(it) }, label = { Text(it) }) }
                    }
                }
                items(turns) { t -> TurnBubble(t, onReport = { reporting = true }) }
                if (busy) item { Column { Text("Looking through your records…", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Ask a question") }, maxLines = 4)
                FilledIconButton(onClick = { send(q) }, enabled = q.isNotBlank() && !busy) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
            }
            AssumptionNote("Answers are written by a language model from your records. Balances and totals come from LekhaMint, not the model.")
        }
    }
    if (setup) AiSetupDialog(s, store, onDismiss = { setup = false }) { s = it; setup = false }
    if (reporting) {
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("Report this answer") },
            text = {
                Text(
                    "If an answer was offensive, harmful or badly wrong, tell the LekhaMint developers. The report form opens in your browser. " +
                        "The answer is not attached, because it may contain your financial details; describe the problem in your own words. " +
                        "Answers come from the model service you chose (${s.host}), so you can also report it to them.",
                )
            },
            confirmButton = { TextButton(onClick = { reporting = false; Links.open(context, Links.aiReport(s.host, s.model)) }) { Text("Open report form") } },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("Cancel") } },
        )
    }
    consent?.let { pending ->
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text("Share your records with the model?") },
            text = { Text("To answer, your question and the records the assistant looks up (totals, balances, loans, deposits${if (s.shareDetails) ", payee names and notes" else ""}) are sent to ${s.host}. ${if (s.host in setOf("localhost", "127.0.0.1")) "That is on this phone." else "Its own privacy terms apply."} The assistant cannot change your data.") },
            confirmButton = { TextButton(onClick = { s = s.copy(consentFor = s.baseUrl); store.save(s); consent = null; send(pending, agreed = true) }) { Text("Agree and ask") } },
            dismissButton = { TextButton(onClick = { consent = null }) { Text("Not now") } },
        )
    }
}

@Composable
private fun TurnBubble(t: Turn, onReport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(t.question, Modifier.widthIn(max = 320.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).padding(10.dp), color = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.widthIn(max = 360.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(12.dp)) {
            if (t.error != null) Text(t.error, color = MaterialTheme.colorScheme.error) else Text(t.answer.replace("**", ""))
            if (t.snapshot) Text("This model can't look things up, so it answered from a summary.", style = MaterialTheme.typography.bodySmall)
            if (t.runs.isNotEmpty()) {
                Text(if (open) "Hide lookups" else "Show the ${t.runs.size} lookups behind this answer", Modifier.clickable { open = !open }.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (open) t.runs.forEach { r -> Text("${r.name} ${r.args}\n${r.result.toString().take(1500)}", style = MaterialTheme.typography.bodySmall) }
            }
            if (t.error == null) {
                Row(Modifier.clickable(onClick = onReport).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Flag, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" Report", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AiSetupDialog(current: AiSettings, store: AiStore, onDismiss: () -> Unit, onSave: (AiSettings) -> Unit) {
    var url by remember { mutableStateOf(current.baseUrl) }
    var model by remember { mutableStateOf(current.model) }
    var key by remember { mutableStateOf(store.apiKey.orEmpty()) }
    var tools by remember { mutableStateOf(current.tools) }
    var share by remember { mutableStateOf(current.shareDetails) }
    val presets = listOf("OpenAI" to "https://api.openai.com/v1", "OpenRouter" to "https://openrouter.ai/api/v1", "On this phone" to "http://127.0.0.1:8080/v1", "Home server (Ollama)" to "http://192.168.1.10:11434/v1")
    val valid = url.isBlank() || Regex("^https?://\\S+$").matches(url.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assistant settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Any OpenAI-compatible service: cloud, a home server, or a small model on this phone served at a localhost address.", style = MaterialTheme.typography.bodySmall)
                presets.forEach { (n, u) -> AssistChip(onClick = { url = u }, label = { Text(n) }) }
                OutlinedTextField(url, { url = it }, label = { Text("Base URL (up to /v1)") }, singleLine = true, isError = !valid, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), supportingText = { if (!valid) Text("Start with http:// or https://") })
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, placeholder = { Text("gpt-4o-mini, llama3.1:8b…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API key (if needed)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Stored encrypted on this phone") })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { share = !share }) { Checkbox(share, { share = it }); Text("Include payee names, notes and people's names") }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { tools = !tools }) { Checkbox(tools, { tools = it }); Text("The model can call tools (look things up)") }
                Text("Untick tools for small models that cannot use them; a short summary is sent instead. Plain http:// works only for addresses on your phone or home network.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                store.apiKey = key
                val n = current.copy(baseUrl = url.trim().trimEnd('/'), model = model.trim(), tools = tools, shareDetails = share)
                store.save(n)
                onSave(store.load())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
