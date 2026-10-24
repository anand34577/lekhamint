package app.kosh.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kosh.core.Currencies
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.components.CurrencyPickerSheet
import app.kosh.ui.components.LocalContainer
import kotlinx.coroutines.launch

/** First launch: one question (currency), then straight in. No account, no sign-up wall. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var currency by remember { mutableStateOf(Currencies.forLocale()) }
    var name by remember { mutableStateOf("Personal") }
    var pick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text("LekhaMint", style = MaterialTheme.typography.displaySmall)
        Text(
            "Your money, on your phone. Works offline, no account needed. Sync with your own server if you want.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        val info = Currencies.get(currency)
        OutlinedButton(onClick = { pick = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Currency: ${info.name} (${info.symbol.trim()})")
        }
        OutlinedTextField(name, { name = it }, label = { Text("Book name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !busy && name.isNotBlank(),
            onClick = {
                busy = true
                scope.launch {
                    c.repo.createLedger(name.trim(), currency)
                    if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Get started") }
        Text(
            "We'll add a Cash account and common categories. You can change everything later.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { nav.navigate(Routes.BACKUP) }) { Text("Restore from a backup") }
        TextButton(onClick = { nav.navigate(Routes.SYNC) }) { Text("I already use LekhaMint on a server") }
    }
    if (pick) CurrencyPickerSheet({ pick = false }, currency) { currency = it }
}
