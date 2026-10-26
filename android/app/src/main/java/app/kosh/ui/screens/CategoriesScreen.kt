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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Ids
import app.kosh.data.db.CategoryEntity
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshIcons
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import kotlinx.coroutines.launch

/** Editing target: an existing category, or a new one (optionally under [parentId]). */
private data class CategoryEdit(val existing: CategoryEntity?, val kind: String, val parentId: String?)

@Composable
fun CategoriesScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val lookup by c.lookup.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<CategoryEdit?>(null) }
    val kind = if (tab == 0) "expense" else "income"

    Scaffold(
        topBar = { KoshTopBar("Categories", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { edit = CategoryEdit(null, kind, null) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Category") })
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Expense") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Income") })
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                for (root in lookup.roots(kind)) {
                    item(key = root.id) {
                        ListItem(
                            headlineContent = { Text(root.name, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = root.nature?.let { n -> { Text(natureLabel(n)) } },
                            leadingContent = { IconBadge(root.icon, root.color) },
                            trailingContent = { TextButton(onClick = { edit = CategoryEdit(null, kind, root.id) }) { Text("+ Sub") } },
                            modifier = Modifier.clickable { edit = CategoryEdit(root, kind, root.parentId) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    items(lookup.children(root.id), key = { it.id }) { child ->
                        ListItem(
                            headlineContent = { Text(child.name) },
                            leadingContent = { IconBadge(child.icon ?: root.icon, child.color ?: root.color, size = 32.dp) },
                            modifier = Modifier.padding(start = 40.dp).clickable { edit = CategoryEdit(child, kind, child.parentId) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
    edit?.let { e -> CategoryEditor(e, lookup, onDismiss = { edit = null }) }
}

fun natureLabel(n: String) = when (n) {
    "need" -> "Need"
    "want" -> "Want"
    "savings" -> "Savings & debt"
    else -> n
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(e: CategoryEdit, lookup: Lookup, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    val snackbar = LocalSnackbar.current
    val ex = e.existing
    var name by remember { mutableStateOf(ex?.name.orEmpty()) }
    var parent by remember { mutableStateOf(e.parentId) }
    var nature by remember { mutableStateOf(ex?.nature ?: parent?.let { lookup.categoryById[it]?.nature }) }
    var color by remember { mutableStateOf(ex?.color ?: parent?.let { lookup.categoryById[it]?.color } ?: KoshIcons.palette.random()) }
    var icon by remember { mutableStateOf(ex?.icon ?: parent?.let { lookup.categoryById[it]?.icon } ?: "dots") }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasChildren = ex != null && lookup.children(ex.id).isNotEmpty()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ex == null) "New category" else "Edit category", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            if (!hasChildren) {
                Text("Group", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(parent == null, { parent = null }, { Text("None (top level)") })
                    for (r in lookup.roots(e.kind)) if (r.id != ex?.id) FilterChip(parent == r.id, { parent = r.id }, { Text(r.name) })
                }
            }
            if (e.kind == "expense") {
                Text("Kind of spending (for the 50/30/20 view)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf("need", "want", "savings")) FilterChip(nature == n, { nature = if (nature == n) null else n }, { Text(natureLabel(n)) })
                }
            }
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            ColorRow(color) { color = it }
            Text("Icon", style = MaterialTheme.typography.labelLarge)
            IconRow(icon, KoshIcons.byKey.keys.toList()) { icon = it }
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    val now = System.currentTimeMillis()
                    val base = ex ?: CategoryEntity(id = Ids.newV7(now), name = name, kind = e.kind, createdAt = now, sortOrder = 999)
                    scope.launch {
                        c.repo.saveCategory(base.copy(name = name.trim(), parentId = parent, nature = if (e.kind == "expense") nature else null, color = color, icon = icon))
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank(),
            ) { Text("Save") }
            if (ex != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete && ex != null) {
        ConfirmDialog(
            "Delete ${ex.name}?",
            (if (hasChildren) "Its sub-categories are deleted too. " else "") +
                "Past transactions keep their amounts and show as uncategorised. You can restore it from Trash.",
            "Delete", destructive = true,
            onConfirm = {
                scope.launch {
                    c.repo.deleteCategory(ex.id)
                    onDismiss()
                    snackbar.showSnackbar("Category moved to Trash")
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
