package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.data.db.RuleEntity
import app.kosh.domain.Lookup
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.Routes
import app.kosh.ui.components.ConfirmDialog
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.IconBadge
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.PickerSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Payee contains swiggy → Food · Delivery": one line describing a rule. */
fun describeRule(r: RuleEntity, lookup: Lookup): String {
    val field = when (r.matchField) { "note" -> "Note"; "any" -> "Payee or note"; else -> "Payee" }
    val how = when (r.matchType) { "equals" -> "is"; "starts_with" -> "starts with"; else -> "contains" }
    val actions = buildList {
        r.setCategoryId?.let { add(lookup.categoryPath(it)) }
        r.setPayee?.let { add("payee “$it”") }
        lookup.tagNames(r.addTagIds).forEach { add("#$it") }
    }
    return "$field $how “${r.pattern}” → ${actions.joinToString(", ").ifEmpty { "nothing" }}"
}

@Composable
fun CategorizationRulesScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val rules by c.rules.collectAsStateWithLifecycle()
    val ordered = rules.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))

    Scaffold(
        topBar = { KoshTopBar("Auto-categorise", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.editCatRule()) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Rule") })
        },
    ) { padding ->
        if (ordered.isEmpty()) {
            EmptyState(
                Icons.Rounded.AutoFixHigh, "No rules yet",
                "Rules fill in the category for you. For example: payee contains “swiggy” → Food · Delivery. " +
                    "LekhaMint also remembers the last category you used for each payee, so rules are for the cases that need more.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text(
                    "Rules run top to bottom. The first rule that sets a category wins; tags from every matching rule are added.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(ordered, key = { _, r -> r.id }) { i, r ->
                val (icon, color) = lookup.categoryLook(r.setCategoryId)
                ListItem(
                    headlineContent = { Text(r.name ?: r.pattern, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(describeRule(r, lookup), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { IconBadge(if (r.setCategoryId != null) icon else "tag", color) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                IconButton(onClick = { scope.launch { c.repo.reorderRules(ordered.toMutableList().apply { add(i - 1, removeAt(i)) }) } }, enabled = i > 0) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, "Move up")
                                }
                                IconButton(onClick = { scope.launch { c.repo.reorderRules(ordered.toMutableList().apply { add(i + 1, removeAt(i)) }) } }, enabled = i < ordered.lastIndex) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, "Move down")
                                }
                            }
                            Switch(r.enabled, { on -> scope.launch { c.repo.saveCategorizationRule(r.copy(enabled = on)) } })
                        }
                    },
                    modifier = Modifier.clickable { nav.navigate(Routes.editCatRule(r.id)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditCategorizationRuleScreen(id: String?, prefillPayee: String? = null) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val lookup by c.lookup.collectAsStateWithLifecycle()
    val rules by c.rules.collectAsStateWithLifecycle()
    val existing = id?.let { rid -> rules.firstOrNull { it.id == rid } }

    var loaded by remember { mutableStateOf(id == null) }
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(prefillPayee.orEmpty()) }
    var field by remember { mutableStateOf("payee") }
    var how by remember { mutableStateOf("contains") }
    var txType by remember { mutableStateOf<String?>("expense") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var minText by remember { mutableStateOf("") }
    var maxText by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var setPayee by remember { mutableStateOf("") }
    var tagIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var pick by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        val r = existing ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        name = r.name.orEmpty(); pattern = r.pattern; field = r.matchField; how = r.matchType; txType = r.txType
        accountId = r.accountId; categoryId = r.setCategoryId; setPayee = r.setPayee.orEmpty(); tagIds = r.addTagIds
        val cur = lookup.currencyOf(r.accountId)
        minText = r.minAmount?.let { Money.toPlain(it, cur) }.orEmpty()
        maxText = r.maxAmount?.let { Money.toPlain(it, cur) }.orEmpty()
        loaded = true
    }

    val currency = lookup.currencyOf(accountId)
    val minAmount = minText.takeIf { it.isNotBlank() }?.let { Money.parse(it, currency) }
    val maxAmount = maxText.takeIf { it.isNotBlank() }?.let { Money.parse(it, currency) }
    val amountsOk = (minText.isBlank() || minAmount != null) && (maxText.isBlank() || maxAmount != null) &&
        (minAmount == null || maxAmount == null || maxAmount >= minAmount)
    val hasAction = categoryId != null || setPayee.isNotBlank() || tagIds.isNotEmpty()
    val valid = pattern.isNotBlank() && hasAction && amountsOk

    fun build(): RuleEntity {
        val now = System.currentTimeMillis()
        val base = existing ?: RuleEntity(
            id = Ids.newV7(now), pattern = pattern.trim(), createdAt = now,
            sortOrder = (rules.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        return base.copy(
            name = name.trim().ifEmpty { null }, pattern = pattern.trim(), matchField = field, matchType = how, txType = txType,
            accountId = accountId, minAmount = minAmount, maxAmount = maxAmount, setCategoryId = categoryId,
            setPayee = setPayee.trim().ifEmpty { null }, addTagIds = tagIds,
        )
    }

    // Live preview: how many past transactions this rule would change.
    LaunchedEffect(pattern, field, how, txType, accountId, minAmount, maxAmount, categoryId, setPayee, tagIds) {
        matches = null
        if (!valid) return@LaunchedEffect
        delay(300)
        matches = c.ruleService.matches(build()).size
    }

    Scaffold(topBar = { KoshTopBar(if (existing == null) "New rule" else "Edit rule", onBack = { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("When", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((f, l) in listOf("payee" to "Payee", "note" to "Note", "any" to "Payee or note")) FilterChip(field == f, { field = f }, { Text(l) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((m, l) in listOf("contains" to "contains", "starts_with" to "starts with", "equals" to "is exactly")) FilterChip(how == m, { how = m }, { Text(l) })
            }
            OutlinedTextField(
                pattern, { pattern = it }, label = { Text("Text to look for") }, singleLine = true,
                supportingText = { Text("Not case-sensitive. Example: swiggy") }, modifier = Modifier.fillMaxWidth(),
            )
            Text("Only for", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(txType == "expense", { txType = "expense" }, { Text("Expenses") })
                FilterChip(txType == "income", { txType = "income" }, { Text("Income") })
                FilterChip(txType == null, { txType = null }, { Text("Both") })
                if (accountId != null) {
                    InputChip(true, { accountId = null }, { Text(lookup.accountName(accountId)) })
                } else {
                    AssistChip(onClick = { pick = "account" }, label = { Text("Any account") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minText, { minText = it }, label = { Text("Min amount") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                OutlinedTextField(maxText, { maxText = it }, label = { Text("Max amount") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            }
            if (!amountsOk) Text("Check the amounts: max must not be below min.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            Text("Then", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Category", modifier = Modifier.weight(1f))
                if (categoryId != null) {
                    val (icon, color) = lookup.categoryLook(categoryId)
                    InputChip(true, { categoryId = null }, { Text(lookup.categoryPath(categoryId)) }, leadingIcon = { IconBadge(icon, color, size = 20.dp) })
                } else {
                    AssistChip(onClick = { pick = "category" }, label = { Text("Choose") })
                }
            }
            OutlinedTextField(
                setPayee, { setPayee = it }, label = { Text("Rename payee to (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                supportingText = { Text("Cleans up names like “SWIGGY*ORDER 8812” to “Swiggy”") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add tags", modifier = Modifier.weight(1f))
                AssistChip(onClick = { pick = "tags" }, label = { Text(if (tagIds.isEmpty()) "Choose" else lookup.tagNames(tagIds).joinToString(" ") { "#$it" }) })
            }
            OutlinedTextField(name, { name = it }, label = { Text("Rule name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!hasAction) Text("Pick a category, a payee name or tags.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Button(
                onClick = {
                    scope.launch {
                        c.repo.saveCategorizationRule(build())
                        nav.popBackStack()
                    }
                },
                enabled = valid, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Save rule") }
            val n = matches
            if (valid && n != null && n > 0) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val rule = build()
                            c.repo.saveCategorizationRule(rule)
                            val changed = c.ruleService.applyToPast(rule)
                            nav.popBackStack()
                            snackbar.showSnackbar("Rule saved and applied to $changed past ${if (changed == 1) "transaction" else "transactions"}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and apply to $n past ${if (n == 1) "transaction" else "transactions"}") }
            } else if (valid && n == 0) {
                Text("No past transactions would change. The rule applies to new ones.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete rule", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    when (pick) {
        "category" -> CategorySheet(lookup, if (txType == "income") "income" else "expense", categoryId, onDismiss = { pick = null }) { categoryId = it }
        "account" -> PickerSheet("Account", lookup.activeAccounts, { pick = null }, { accountId = it.id }, searchText = { it.name }) { a -> AccountListItem(a, lookup) }
        "tags" -> TagPickerSheet(lookup, tagIds, onDismiss = { pick = null }) { tagIds = it }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            "Delete this rule?", "Transactions it already categorised stay as they are.", "Delete", destructive = true,
            onConfirm = { scope.launch { c.repo.deleteCategorizationRule(existing.id); nav.popBackStack() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
