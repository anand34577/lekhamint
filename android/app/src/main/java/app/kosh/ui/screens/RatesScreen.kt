package app.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Ids
import app.kosh.domain.Fx
import app.kosh.ui.LocalNav
import app.kosh.ui.LocalSnackbar
import app.kosh.ui.components.CurrencyPickerSheet
import app.kosh.ui.components.DateField
import app.kosh.ui.components.EmptyState
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Rates are typed in by you: they only turn totals in several currencies into one number. */
@Composable
fun RatesScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = app.kosh.ui.rememberSafeScope()
    val rates by c.repo.exchangeRates.collectAsStateWithLifecycle(emptyList())
    val base = c.settings.current.baseCurrency
    var editing by remember { mutableStateOf<Fx.Rate?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { KoshTopBar("Exchange rates", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Rate") }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text(
                    "LekhaMint adds up money held in different currencies using the newest rate you enter on or before each date. Nothing is fetched from the internet, and a currency without a rate is left out and flagged, never counted 1:1.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rates.isEmpty()) item { EmptyState(Icons.Rounded.CurrencyExchange, "No rates yet", "Add one when you hold money in more than one currency, for example 1 USD = 83 $base.") }
            items(rates.sortedWith(compareByDescending<Fx.Rate> { it.date }.thenBy { it.base }), key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text("1 ${r.base} = ${r.rate.stripTrailingZeros().toPlainString()} ${r.quote}") },
                    supportingContent = { Text("From ${Dates.medium(LocalDate.parse(r.date))}") },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch {
                                c.repo.deleteRate(r.id)
                                val res = snackbar.showSnackbar("Rate deleted", actionLabel = "Undo", withDismissAction = true)
                                if (res == SnackbarResult.ActionPerformed) c.repo.saveRate(r.id, r.base, r.quote, r.rate.stripTrailingZeros().toPlainString(), r.date)
                            }
                        }) { Icon(Icons.Rounded.DeleteOutline, "Delete rate") }
                    },
                    modifier = Modifier.clickable { editing = r },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
    if (adding) RateSheet(null, base, onDismiss = { adding = false })
    editing?.let { RateSheet(it, base, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RateSheet(existing: Fx.Rate?, base: String, onDismiss: () -> Unit, presetFrom: String? = null) {
    val c = LocalContainer.current
    val scope = app.kosh.ui.rememberSafeScope()
    var from by remember { mutableStateOf(existing?.base ?: presetFrom ?: if (base == "USD") "EUR" else "USD") }
    var to by remember { mutableStateOf(existing?.quote ?: base) }
    var rate by remember { mutableStateOf(existing?.rate?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var date by remember { mutableStateOf(existing?.date?.let(LocalDate::parse) ?: Dates.today()) }
    var pick by remember { mutableStateOf<String?>(null) }
    val id = remember { existing?.id ?: Ids.newV7() }
    val parsed = rate.trim().toBigDecimalOrNull()
    val error = when {
        rate.isBlank() -> null
        parsed == null || parsed.signum() <= 0 -> "Enter a number above zero, like 83.25"
        parsed.precision() > 18 -> "Too many digits"
        else -> null
    }
    val ok = from != to && parsed != null && parsed.signum() > 0 && error == null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) "New exchange rate" else "Edit rate", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pick = "from" }, modifier = Modifier.weight(1f)) { Text("1 $from") }
                OutlinedButton(onClick = { pick = "to" }, modifier = Modifier.weight(1f)) { Text("in $to") }
            }
            OutlinedTextField(
                rate, { rate = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("How many $to is 1 $from?") }, singleLine = true,
                isError = error != null, supportingText = { Text(error ?: if (from == to) "Pick two different currencies" else "For example 83.25") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            DateField("Valid from", date, { date = it }, Modifier.fillMaxWidth())
            Button(
                enabled = ok,
                onClick = {
                    scope.launch {
                        c.repo.saveRate(id, from, to, parsed!!.stripTrailingZeros().toPlainString(), date.toString())
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save rate") }
        }
    }
    when (pick) {
        "from" -> CurrencyPickerSheet({ pick = null }, from) { from = it }
        "to" -> CurrencyPickerSheet({ pick = null }, to) { to = it }
    }
}
