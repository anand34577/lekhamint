package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Ids
import app.kosh.data.db.TagEntity
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshIcons
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import kotlinx.coroutines.launch

/** Tags answer "what was it for?" across categories: a trip, a project, a wedding. */
@Composable
fun TagsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<TagEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(lookup.tags) {
        usage = c.repo.db.transactions().everything()
            .filter { !it.deleted }
            .flatMap { it.tagIds }
            .groupingBy { it }.eachCount()
    }

    Scaffold(
        topBar = { KoshTopBar("Tags", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Tag") })
        },
    ) { padding ->
        if (lookup.tags.isEmpty()) {
            EmptyState(
                Icons.Rounded.Sell, "No tags yet",
                "Tags group spending across categories, like #goa-trip or #wedding. Add them while recording a transaction, or here.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(lookup.tags.sortedWith(compareBy({ it.archived }, { it.name.lowercase() })), key = { it.id }) { t ->
                val n = usage[t.id] ?: 0
                ListItem(
                    headlineContent = { Text("#" + t.name) },
                    supportingContent = {
                        Text(
                            (if (n == 1) "1 transaction" else "${app.kosh.core.plural(n, "transaction")}") + if (t.archived) " · hidden from pickers" else "",
                        )
                    },
                    leadingContent = { IconBadge("tag", t.color) },
                    modifier = Modifier.clickable { edit = t },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
    if (creating || edit != null) TagEditor(edit, onDismiss = { creating = false; edit = null })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(existing: TagEntity?, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val snackbar = LocalSnackbar.current
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember { mutableStateOf(existing?.color ?: KoshIcons.palette.random()) }
    var archived by remember { mutableStateOf(existing?.archived ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val clash = lookup.tags.any { it.id != existing?.id && it.name.equals(cleanTag(name), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (existing == null) "New tag" else "Edit tag", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                name, { name = it }, label = { Text("Name") }, singleLine = true, prefix = { Text("#") },
                isError = clash, supportingText = if (clash) ({ Text("A tag with this name already exists") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            ColorRow(color) { color = it }
            SwitchRow("Hide from pickers", "Keeps the tag on past transactions", archived) { archived = it }
            Button(
                onClick = {
                    val clean = cleanTag(name)
                    if (clean.isEmpty() || clash) return@Button
                    val now = System.currentTimeMillis()
                    val base = existing ?: TagEntity(id = Ids.newV7(now), name = clean, createdAt = now)
                    scope.launch {
                        c.repo.saveTag(base.copy(name = clean, color = color, archived = archived))
                        onDismiss()
                    }
                },
                enabled = cleanTag(name).isNotEmpty() && !clash,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            "Delete #${existing.name}?",
            "Transactions keep their amounts and categories; the tag just disappears from them.",
            "Delete", destructive = true,
            onConfirm = {
                scope.launch {
                    c.repo.deleteTag(existing.id)
                    onDismiss()
                    snackbar.showSnackbar("Tag deleted")
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Tag names are single words or short phrases; a leading # is optional when typing. */
fun cleanTag(s: String): String = s.trim().removePrefix("#").trim().replace(Regex("\\s+"), " ").take(50)

/**
 * Multi-select tag chooser with search; typing a new name offers to create it.
 * [onChange] receives the full new selection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(lookup: Lookup, selected: List<String>, onDismiss: () -> Unit, onChange: (List<String>) -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(selected) }
    val q = cleanTag(query)
    val shown = lookup.tags.filter { (!it.archived || it.id in current) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
        .sortedBy { it.name.lowercase() }
    val exact = lookup.tags.any { it.name.equals(q, ignoreCase = true) }
    fun toggle(id: String) {
        current = if (id in current) current - id else current + id
        onChange(current)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tags", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Search or create a tag") }, singleLine = true, prefix = { Text("#") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (q.isNotEmpty() && !exact) {
                Button(onClick = {
                    val now = System.currentTimeMillis()
                    val tag = TagEntity(id = Ids.newV7(now), name = q, color = KoshIcons.palette.random(), createdAt = now)
                    scope.launch {
                        c.repo.saveTag(tag)
                        toggle(tag.id)
                        query = ""
                    }
                }) { Text("Create #$q") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in shown) {
                    FilterChip(
                        selected = t.id in current, onClick = { toggle(t.id) }, label = { Text("#" + t.name) },
                        leadingIcon = if (t.id in current) ({ Icon(Icons.Rounded.Check, null) }) else null,
                    )
                }
            }
            if (shown.isEmpty() && q.isEmpty()) {
                Text("No tags yet. Type a name to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
