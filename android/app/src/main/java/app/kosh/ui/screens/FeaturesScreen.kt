package app.kosh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.AppContainer
import app.kosh.data.AppSettings
import app.kosh.ui.LocalNav
import app.kosh.ui.Routes
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.SettingRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A tool that simple mode can leave out of the menus until it is wanted. */
data class Feature(val key: String, val title: String, val subtitle: String, val icon: ImageVector, val tint: Color, val route: String)

/**
 * Simple mode: the everyday screens (adding, accounts, budgets, reports, bills, found payments) always show;
 * these appear once turned on here, or by themselves once the book has something of that kind
 * (a loan, a goal, a tag), so nothing a person already uses ever disappears.
 */
object Features {
    private val plan = Color(0xFF2563EB)
    private val people = Color(0xFF0891B2)
    private val organise = Color(0xFF7C3AED)
    private val tools = Color(0xFFD97706)

    val all = listOf(
        Feature("goals", "Goals", "Save for a trip, a gadget or an emergency fund", Icons.Rounded.Flag, plan, Routes.GOALS),
        Feature("loans", "Loans & EMIs", "What's left, next EMI, prepayment", Icons.Rounded.AccountBalance, plan, Routes.LOANS),
        Feature("deposits", "Deposits", "Fixed and recurring deposits, maturity", Icons.Rounded.Savings, plan, Routes.DEPOSITS),
        Feature("debts", "Lent & borrowed", "Friends and family: who owes what", Icons.Rounded.Handshake, people, Routes.DEBTS),
        Feature("tags", "Tags", "Trips, projects, anything across categories", Icons.Rounded.Sell, organise, Routes.TAGS),
        Feature("rules", "Auto-categorise", "Rules that fill in category, payee and tags", Icons.Rounded.AutoFixHigh, organise, Routes.CAT_RULES),
        Feature("rates", "Exchange rates", "Add up money held in other currencies", Icons.Rounded.CurrencyExchange, organise, Routes.RATES),
        Feature("tools", "Calculators", "EMI, FD and RD, split a bill, afford a loan", Icons.Rounded.Calculate, tools, Routes.TOOLS),
        Feature("tax", "Year & tax", "Your financial year and tax-saving spending", Icons.Rounded.Percent, tools, Routes.YEAR_TAX),
        Feature("statements", "Statements & export", "PDF and Excel for any account, loan or person", Icons.Rounded.PictureAsPdf, tools, Routes.STATEMENTS),
        Feature("assistant", "Assistant", "Ask about your money in plain words", Icons.Rounded.AutoAwesome, tools, Routes.ASSISTANT),
    )

    fun byKey(key: String): Feature = all.first { it.key == key }

    /** Keys of the tools this book already uses. */
    fun inUse(c: AppContainer): Flow<Set<String>> = combine(
        combine(c.repo.loans, c.repo.deposits, c.repo.debts) { l, d, b ->
            buildSet {
                if (l.any { !it.deleted }) add("loans")
                if (d.any { !it.deleted }) add("deposits")
                if (b.any { !it.deleted }) add("debts")
            }
        },
        combine(c.repo.goals, c.repo.tags, c.repo.rules, c.repo.exchangeRates, c.repo.accounts) { g, t, r, x, a ->
            buildSet {
                if (g.isNotEmpty()) add("goals")
                if (t.any { !it.deleted }) add("tags")
                if (r.any { !it.deleted }) add("rules")
                if (x.isNotEmpty() || a.map { it.currency }.distinct().size > 1) add("rates")
            }
        },
    ) { a, b -> a + b + if (c.settings.fyStartMonth != null) setOf("tax") else emptySet() }

    fun visible(s: AppSettings, inUse: Set<String>, key: String): Boolean = !s.simpleMode || key in s.features || key in inUse
}

@Composable
fun rememberFeaturesInUse(c: AppContainer): State<Set<String>> =
    remember(c) { Features.inUse(c) }.collectAsStateWithLifecycle(emptySet())

@Composable
fun FeaturesScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val inUse by rememberFeaturesInUse(c)
    Scaffold(topBar = { KoshTopBar("Features", onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        SettingRow(
                            "Simple mode",
                            if (s.simpleMode) "Menus show the everyday screens and the tools you turn on below"
                            else "Every tool is shown. Turn on to keep menus short.",
                            Icons.Rounded.SpaceDashboard, onClick = { c.settings.setSimpleMode(!s.simpleMode) },
                        ) { Switch(s.simpleMode, { c.settings.setSimpleMode(it) }) }
                    }
                }
            }
            item {
                Text(
                    if (s.simpleMode) "Turn on what you use. A tool also shows by itself once your book has something of that kind."
                    else "Simple mode is off, so all of these are in the More menu.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item {
                KoshCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        for (f in Features.all) {
                            val used = f.key in inUse
                            val on = Features.visible(s, inUse, f.key)
                            SettingRow(
                                f.title, if (used && s.simpleMode) "In use · ${f.subtitle}" else f.subtitle, f.icon, tint = f.tint,
                                onClick = { if (s.simpleMode && !used) c.settings.setFeature(f.key, !on) else nav.navigate(f.route) },
                            ) {
                                Switch(on, { c.settings.setFeature(f.key, it) }, enabled = s.simpleMode && !used)
                            }
                        }
                    }
                }
            }
        }
    }
}
