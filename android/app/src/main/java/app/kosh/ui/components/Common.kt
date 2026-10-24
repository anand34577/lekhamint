package app.kosh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kosh.AppContainer
import app.kosh.core.Currencies
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.ui.theme.LocalMoneyColors
import java.time.YearMonth

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }
val LocalHideAmounts = compositionLocalOf { false }

/** Colour for a transaction type. Always paired with a sign or icon, never colour alone. */
@Composable
fun typeColor(type: String): Color {
    val m = LocalMoneyColors.current
    return when (type) {
        "income" -> m.income
        "expense" -> m.expense
        else -> m.transfer
    }
}

/**
 * An amount in minor units. Screen readers hear the full amount ("minus 1,250
 * rupees") because the visible text uses the currency symbol and sign.
 */
@Composable
fun MoneyText(
    minor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    signed: Boolean = false,
    fontWeight: FontWeight? = null,
) {
    val hidden = LocalHideAmounts.current
    val text = if (hidden) "••••" else Money.format(minor, currency, signed = signed)
    val spoken = if (hidden) "Amount hidden" else {
        val name = Currencies.get(currency).name
        (if (minor < 0) "minus " else "") + Money.format(kotlin.math.abs(minor), currency, showSymbol = false) + " $name"
    }
    Text(
        text, modifier.semantics { contentDescription = spoken }, style = style, color = color,
        fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoshTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    closeIcon: Boolean = false,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        if (closeIcon) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = if (closeIcon) "Close" else "Back",
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun KoshCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors) { content() }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors) { content() }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    /** If set, the user must type this word to enable the button (irreversible actions). */
    typeToConfirm: String? = null,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                if (typeToConfirm != null) {
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true,
                        label = { Text("Type $typeToConfirm to confirm") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                enabled = typeToConfirm == null || typed.trim().equals(typeToConfirm, ignoreCase = true),
            ) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A searchable list in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickerSheet(
    title: String,
    items: List<T>,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
    searchText: ((T) -> String)? = null,
    selected: (T) -> Boolean = { false },
    row: @Composable (T) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = items.size > 8)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (searchText != null && items.size > 8) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val shown = if (query.isBlank() || searchText == null) items else items.filter { searchText(it).contains(query.trim(), ignoreCase = true) }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(shown) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(item); onDismiss() }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { row(item) }
                    if (selected(item)) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp))
                }
            }
        }
    }
}

@Composable
fun CurrencyPickerSheet(onDismiss: () -> Unit, selected: String?, onPick: (String) -> Unit) {
    PickerSheet(
        title = "Currency",
        items = Currencies.all,
        onDismiss = onDismiss,
        onPick = { onPick(it.code) },
        searchText = { "${it.code} ${it.name}" },
        selected = { it.code == selected },
    ) { c ->
        ListItem(
            headlineContent = { Text(c.name) },
            supportingContent = { Text(c.code) },
            leadingContent = { Text(c.symbol.trim(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
        Text(
            Dates.month(month), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clickable { onChange(YearMonth.now()) }.padding(horizontal = 12.dp),
        )
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = month < YearMonth.now().plusMonths(12)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
        }
    }
}

/** Text field for an amount in a given currency. Holds text; parse with Money.parse. */
@Composable
fun AmountField(
    value: String,
    onChange: (String) -> Unit,
    currency: String,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    supporting: String? = null,
) {
    val valid = value.isBlank() || Money.parse(value.removePrefix("-"), currency) != null
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || (allowNegative && it == '-') }) },
        label = { Text(label) },
        prefix = { Text(Currencies.get(currency).symbol) },
        singleLine = true,
        isError = !valid,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** Relative "2 min ago" style label for timestamps. */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "never"
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} days ago"
    }
}
