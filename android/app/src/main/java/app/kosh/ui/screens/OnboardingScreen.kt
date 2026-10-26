package app.kosh.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.kosh.ui.components.IconTile
import app.kosh.ui.components.KoshCard
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
    val scope = app.kosh.ui.rememberSafeScope()
    var currency by remember { mutableStateOf(Currencies.forLocale()) }
    var name by remember { mutableStateOf("Personal") }
    var pick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Brand header.
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.3f))))
                .safeDrawingPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.size(48.dp), tint = Color.White)
                }
                Spacer(Modifier.height(16.dp))
                Text("LekhaMint", style = MaterialTheme.typography.displaySmall, color = Color.White)
                Text(
                    "A clear, private record of your money.",
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Promise(Icons.Rounded.Bolt, "Add an expense in seconds", "A calculator keypad, your usual categories first, and it learns your payees.")
            Promise(Icons.Rounded.Insights, "See where it goes", "Budgets, charts and a monthly pace that show what you can still spend.")
            Promise(Icons.Rounded.Lock, "Yours alone", "Works offline with no account. Sync with your own server only if you want to.")

            KoshCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Set up your book", style = MaterialTheme.typography.titleMedium)
                    val info = Currencies.get(currency)
                    Surface(
                        onClick = { pick = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Text(info.symbol.trim(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Main currency", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${info.name} (${info.code})", style = MaterialTheme.typography.bodyLarge)
                            }
                            Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Book name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("For example Personal, Household or Business") },
                    )
                    Button(
                        enabled = !busy && name.isNotBlank(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                // A failure (for example a full disk) must not leave the button stuck.
                                val made = runCatching { c.repo.createLedger(name.trim(), currency) }
                                busy = false
                                made.onFailure { error = "Couldn't create the book: ${it.message ?: it.javaClass.simpleName}. Try again." }
                                if (made.isSuccess) {
                                    if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) { Text(if (busy) "Setting up…" else "Get started", style = MaterialTheme.typography.titleSmall) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "We'll add a Cash account and common categories. You can change everything later.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("Already have your data somewhere?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.SYNC) }, modifier = Modifier.weight(1f)) { Text("Sign in to server") }
                OutlinedButton(onClick = { nav.navigate(Routes.BACKUP) }, modifier = Modifier.weight(1f)) { Text("Restore backup") }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
    if (pick) CurrencyPickerSheet({ pick = false }, currency) { currency = it }
}

@Composable
private fun Promise(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        IconTile(icon, MaterialTheme.colorScheme.primary, size = 42.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
