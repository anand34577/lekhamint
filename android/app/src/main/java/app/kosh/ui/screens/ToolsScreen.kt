package app.kosh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import app.kosh.ui.components.DonutChart
import app.kosh.ui.components.HeroCard
import app.kosh.ui.components.LegendDot
import app.kosh.ui.components.LocalOnHero
import app.kosh.ui.components.RankedBars
import app.kosh.ui.components.SectionCard
import app.kosh.ui.components.Slice
import app.kosh.ui.components.Sparkline
import app.kosh.ui.components.StackedBar
import app.kosh.ui.components.StatusPill
import app.kosh.ui.theme.LocalMoneyColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.data.db.DepositEntity
import app.kosh.domain.Deposits
import app.kosh.domain.Loans
import app.kosh.domain.Tools
import app.kosh.ui.LocalNav
import app.kosh.ui.components.AmountField
import app.kosh.ui.components.AssumptionNote
import app.kosh.ui.components.FactRow
import app.kosh.ui.components.KoshCard
import app.kosh.ui.components.KoshTopBar
import app.kosh.ui.components.LocalContainer
import app.kosh.ui.components.MoneyText

/** Everyday calculators. Each states its assumptions; results are estimates, not advice. */
@Composable
fun ToolsScreen() {
    val nav = LocalNav.current
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Loan EMI", "FD / RD", "Split a bill", "Can I afford it?")
    Scaffold(topBar = { KoshTopBar("Calculators", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> EmiCalc()
                    1 -> DepositCalc()
                    2 -> SplitCalc()
                    else -> AffordCalc()
                }
            }
        }
    }
}

/**
 * A number with a slider under it: drag for a quick estimate, or type an exact value.
 * [value] is text so a half-typed number is kept as typed.
 */
@Composable
private fun SliderInput(
    label: String, value: String, onChange: (String) -> Unit, range: ClosedFloatingPointRange<Float>, step: Float,
    suffix: String = "", prefix: String = "", display: (Float) -> String = { if (step >= 1f) it.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, it).trimEnd('0').trimEnd('.') },
) {
    val v = value.replace(",", "").toFloatOrNull()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value, { t -> onChange(t.filter { it.isDigit() || it == '.' }) }, singleLine = true,
                prefix = if (prefix.isNotEmpty()) ({ Text(prefix) }) else null, suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
                textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.width(170.dp), isError = v == null && value.isNotBlank(),
            )
        }
        Slider(
            value = (v ?: range.start).coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(display((Math.round(it / step) * step).coerceIn(range.start, range.endInclusive))) },
            valueRange = range,
        )
    }
}

/** The answer of a calculator: one big figure with a sentence that says what it means. */
@Composable
private fun Answer(label: String, minor: Long, currency: String, sentence: String?, content: @Composable () -> Unit = {}) {
    HeroCard(Modifier.fillMaxWidth()) {
        val on = LocalOnHero.current
        Text(label, style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.85f))
        MoneyText(minor, currency, style = MaterialTheme.typography.displaySmall, color = on)
        if (sentence != null) Text(sentence, style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.9f), modifier = Modifier.padding(top = 4.dp))
        content()
    }
}

@Composable
private fun EmiCalc() {
    val cur = LocalContainer.current.lookup.collectAsStateWithLifecycle().value.baseCurrency
    val money = LocalMoneyColors.current
    var amount by remember { mutableStateOf("2000000") }
    var rate by remember { mutableStateOf("8.5") }
    var years by remember { mutableStateOf("20") }
    val p = Money.parse(amount, cur)
    val n = years.toDoubleOrNull()?.let { (it * 12).toInt() }
    val sym = app.kosh.core.Currencies.get(cur).symbol.trim()

    Text("Step 1 · Tell us about the loan", style = MaterialTheme.typography.titleSmall)
    SliderInput("Loan amount", amount, { amount = it }, 10_000f..20_000_000f, 10_000f, prefix = sym)
    SliderInput("Interest rate", rate, { rate = it }, 0f..24f, 0.05f, suffix = "% a year")
    SliderInput("Repay over", years, { years = it }, 1f..30f, 1f, suffix = "years")

    if (p != null && p > 0 && n != null && n in 1..600 && Loans.rateError(rate, allowZero = true) == null) {
        val emi = Loans.emi(p, rate.ifBlank { null }, n)
        val s = Loans.schedule(p, rate.ifBlank { null }, emi, Dates.today())
        val per100 = if (p > 0) s.totalPayment * 100.0 / p else 0.0
        Text("Step 2 · What it costs you", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Answer("Monthly instalment (EMI)", emi, cur, "for ${n / 12} years${if (n % 12 != 0) " ${n % 12} months" else ""}, ${app.kosh.core.plural(n, "payment")} in all")
        SectionCard("Where your money goes", subtitle = "For every ${sym}100 you borrow, you pay back $sym${"%.0f".format(per100)}") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    listOf(Slice(p, MaterialTheme.colorScheme.primary, "Loan"), Slice(s.totalInterest, money.expense, "Interest")),
                    "Loan ${Money.ui(p, cur)}, interest ${Money.ui(s.totalInterest, cur)}", size = 120.dp, thickness = 18.dp,
                ) { Text("${(s.totalInterest * 100 / maxOf(1, s.totalPayment))}%", style = MaterialTheme.typography.titleMedium, color = money.expense) }
                Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LegendFigure("You borrow", p, cur, MaterialTheme.colorScheme.primary)
                    LegendFigure("Interest", s.totalInterest, cur, money.expense)
                    LegendFigure("Total you repay", s.totalPayment, cur, MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (s.rows.size >= 12) {
            // Year-end balances, and how each year's payments split between interest and the loan.
            val yearsList = s.rows.chunked(12)
            SectionCard("How the loan shrinks", subtitle = "Early payments are mostly interest; later ones mostly repay the loan") {
                Sparkline(listOf(p) + yearsList.map { it.last().closing }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(90.dp))
                Spacer(Modifier.height(12.dp))
                for ((i, yr) in yearsList.withIndex()) {
                    if (i != 0 && i != yearsList.size - 1 && (i + 1) % maxOf(1, yearsList.size / 5) != 0) continue
                    val interest = yr.sumOf { it.interest }
                    val principal = yr.sumOf { it.principal }
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Year ${i + 1}", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
                        StackedBar(listOf(interest.toFloat() / (interest + principal) to money.expense, principal.toFloat() / (interest + principal) to MaterialTheme.colorScheme.primary), Modifier.weight(1f))
                        Text("${interest * 100 / maxOf(1, interest + principal)}% interest", Modifier.width(96.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot("Interest", money.expense); LegendDot("Repays the loan", MaterialTheme.colorScheme.primary)
                }
            }
            SectionCard("Owed after…") {
                for (y in listOf(1, 5, 10, 15, 20, 25).filter { it * 12 < s.rows.size }) FactRow("$y ${if (y == 1) "year" else "years"}") { MoneyText(s.rows[y * 12 - 1].closing, cur) }
            }
        }
    }
    AssumptionNote("EMI = P·r·(1+r)ⁿ ⁄ ((1+r)ⁿ − 1), r = annual rate ÷ 12. Interest each month is on the balance still owed; the last instalment absorbs rounding. Lenders may differ slightly.")
}

@Composable
private fun LegendFigure(label: String, value: Long, currency: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top) {
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 5.dp).size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(value, currency, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun DepositCalc() {
    val cur = LocalContainer.current.lookup.collectAsStateWithLifecycle().value.baseCurrency
    val money = LocalMoneyColors.current
    var rd by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("100000") }
    var rate by remember { mutableStateOf("7.1") }
    var months by remember { mutableStateOf("12") }
    var compounding by remember { mutableStateOf("quarterly") }
    val sym = app.kosh.core.Currencies.get(cur).symbol.trim()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!rd, { rd = false }, label = { Text("Fixed deposit (one time)") })
        FilterChip(rd, { rd = true }, label = { Text("Recurring (monthly)") })
    }
    SliderInput(if (rd) "Each month" else "Amount", amount, { amount = it }, (if (rd) 500f else 5_000f)..(if (rd) 200_000f else 10_000_000f), if (rd) 500f else 5_000f, prefix = sym)
    SliderInput("Interest rate", rate, { rate = it }, 1f..12f, 0.05f, suffix = "% a year")
    SliderInput("Term", months, { months = it }, 3f..120f, 1f, suffix = "months")
    Text("Interest is added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in listOf("quarterly" to "Every quarter", "monthly" to "Every month", "yearly" to "Every year", "simple" to "Not compounded")) FilterChip(compounding == k, { compounding = k }, label = { Text(l) })
    }
    val p = Money.parse(amount, cur)
    val n = months.toIntOrNull()
    if (p != null && p > 0 && n != null && n in 1..600 && Loans.rateError(rate) == null) {
        val start = Dates.today()
        val dep = DepositEntity("", name = "", accountId = "", currency = cur, amount = p, annualRate = rate.trim(), startDate = start.toString(),
            maturityDate = start.plusMonths(n.toLong()).toString(), kind = if (rd) "rd" else "fd", compounding = compounding)
        val e = Deposits.estimate(dep, start)
        val growth = (0..n).map { m -> Deposits.estimate(dep, start.plusMonths(m.toLong())).valueToday }
        Answer("You get at maturity", e.maturity, cur, "on ${Dates.medium(start.plusMonths(n.toLong()))}: ${Money.ui(e.interest, cur)} more than you put in")
        SectionCard("How it grows", subtitle = "Value month by month") {
            Sparkline(growth, money.income, Modifier.fillMaxWidth().height(90.dp))
            Spacer(Modifier.height(12.dp))
            StackedBar(listOf(e.invested.toFloat() / e.maturity to MaterialTheme.colorScheme.primary, e.interest.toFloat() / e.maturity to money.income))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendFigure("You put in", e.invested, cur, MaterialTheme.colorScheme.primary)
                LegendFigure("Interest earned", e.interest, cur, money.income)
            }
        }
    }
    AssumptionNote("Interest compounds every full period; leftover days earn simple interest on 365 days. RD instalments each compound for the months they stay invested. Banks may differ slightly; early closure usually pays less, and interest may be taxed.")
}

@Composable
private fun SplitCalc() {
    val cur = LocalContainer.current.lookup.collectAsStateWithLifecycle().value.baseCurrency
    var total by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("") }
    var byShares by remember { mutableStateOf(false) }
    val names = remember { mutableStateListOf("Me", "") }
    val shares = remember { mutableStateListOf("1", "1") }
    val paid = remember { mutableStateListOf("", "") }
    Text("Step 1 · The bill", style = MaterialTheme.typography.titleSmall)
    AmountField(total, { total = it }, cur, "Bill amount")
    OutlinedTextField(tip, { tip = it }, label = { Text("Tax / tip / service (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Step 2 · Who shared it", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!byShares, { byShares = false }, label = { Text("Split equally") })
        FilterChip(byShares, { byShares = true }, label = { Text("By shares") })
    }
    names.indices.forEach { i ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(names[i], { names[i] = it }, label = { Text("Person ${i + 1}") }, singleLine = true, modifier = Modifier.weight(1.4f))
            if (byShares) OutlinedTextField(shares[i], { shares[i] = it }, label = { Text("Shares") }, singleLine = true, modifier = Modifier.weight(0.8f))
            OutlinedTextField(paid[i], { paid[i] = it }, label = { Text("Paid") }, singleLine = true, modifier = Modifier.weight(1f))
            if (names.size > 1) IconButton(onClick = { names.removeAt(i); shares.removeAt(i); paid.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    TextButton(onClick = { names.add(""); shares.add("1"); paid.add("") }) { Text("+ Add person") }
    val t = Money.parse(total, cur)
    if (t != null && t > 0) {
        val extra = tip.toDoubleOrNull()?.let { Math.round(t * it / 100) } ?: 0L
        val weights = names.indices.map { if (byShares) shares[it].toDoubleOrNull() ?: 0.0 else 1.0 }
        val base = Tools.allocate(t, weights)
        val owes = base.zip(if (extra > 0) Tools.allocate(extra, base.map { it.toDouble() }) else base.map { 0L }) { a, b -> a + b }
        val paidMinor = paid.map { if (it.isBlank()) 0L else Money.parse(it, cur) ?: 0L }
        val paidSum = paidMinor.sum()
        val name = { i: Int -> names[i].ifBlank { "Person ${i + 1}" } }
        val palette = listOf(MaterialTheme.colorScheme.primary, LocalMoneyColors.current.transfer, LocalMoneyColors.current.warning, androidx.compose.ui.graphics.Color(0xFF7C3AED), androidx.compose.ui.graphics.Color(0xFF0891B2), LocalMoneyColors.current.expense)
        Text("Step 3 · The result", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Answer("Total with extras", t + extra, cur, if (extra > 0) "Includes ${Money.ui(extra, cur)} tax, tip or service" else null)
        SectionCard("Each person's share") {
            RankedBars(names.indices.map { Triple(name(it), owes[it], palette[it % palette.size]) }, cur, total = t + extra)
        }
        if (paidSum > 0 && paidSum != t + extra) Text("Payments add up to ${Money.ui(paidSum, cur)}, but the bill with extras is ${Money.ui(t + extra, cur)}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (paidSum == t + extra) {
            val moves = Tools.settle(paidMinor.zip(owes) { a, b -> a - b })
            SectionCard("To settle up", subtitle = if (moves.isEmpty()) "Everyone is square" else "${moves.size} ${if (moves.size == 1) "payment" else "payments"} and everyone is even") {
                for (x in moves) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(name(x.from), palette[x.from % palette.size])
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "pays", Modifier.padding(horizontal = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusPill(name(x.to), palette[x.to % palette.size])
                        Spacer(Modifier.weight(1f))
                        MoneyText(x.amount, cur, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        } else if (paidSum == 0L) {
            Text("Add who paid what to see who owes whom.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Shares add up to exactly ${Money.ui(t + extra, cur)}; any leftover paise go to the first people.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AffordCalc() {
    val lookup by LocalContainer.current.lookup.collectAsStateWithLifecycle()
    val cur = lookup.baseCurrency
    val money = LocalMoneyColors.current
    val existing = rememberLoanEntries().filter { !it.loan.closed && it.loan.currency == cur }.sumOf { it.status.emi }
    var income by remember { mutableStateOf("") }
    var spend by remember { mutableStateOf("") }
    var emis by remember { mutableStateOf(Money.toPlain(existing, cur)) }
    var ratio by remember { mutableStateOf("40") }
    var rate by remember { mutableStateOf("9") }
    var years by remember { mutableStateOf("15") }
    Text("Step 1 · Your month", style = MaterialTheme.typography.titleSmall)
    AmountField(income, { income = it }, cur, "Monthly take-home income")
    AmountField(spend, { spend = it }, cur, "Monthly spending (without EMIs)")
    AmountField(emis, { emis = it }, cur, "Existing EMIs", supporting = "Filled in from your open loans")
    Text("Step 2 · The new loan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    SliderInput("Interest rate", rate, { rate = it }, 5f..20f, 0.05f, suffix = "% a year")
    SliderInput("Repay over", years, { years = it }, 1f..30f, 1f, suffix = "years")
    SliderInput("EMIs may take up to", ratio, { ratio = it }, 10f..60f, 5f, suffix = "% of income")
    val inc = Money.parse(income, cur) ?: 0
    val exp = Money.parse(spend, cur) ?: 0
    val ex = Money.parse(emis, cur) ?: 0
    val r = ratio.toDoubleOrNull()
    val n = years.toDoubleOrNull()?.let { (it * 12).toInt() }
    if (inc > 0 && r != null && r in 1.0..80.0 && n != null && n in 12..480) {
        val byRatio = maxOf(0, (inc * r / 100).toLong() - ex)
        val buffer = inc / 10
        val bySurplus = maxOf(0, inc - exp - ex - buffer)
        val maxEmi = minOf(byRatio, bySurplus)
        // An estimate, so round down to a whole thousand.
        val thousand = java.math.BigDecimal.ONE.movePointRight(app.kosh.core.Currencies.digits(cur) + 3).toLong()
        val loan = Tools.maxPrincipal(maxEmi, rate.ifBlank { null }, n) / thousand * thousand
        Text("Step 3 · The answer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Answer("You could borrow about", loan, cur, "with an EMI of ${Money.ui(maxEmi, cur)} a month for ${n / 12} years") {}
        SectionCard("Where each month's income goes", subtitle = "With the new EMI, keeping 10% spare") {
            val free = maxOf(0, inc - exp - ex - maxEmi - buffer)
            val parts = listOf(
                Triple("Spending", exp, money.expense), Triple("Existing EMIs", ex, money.warning), Triple("New EMI", maxEmi, MaterialTheme.colorScheme.primary),
                Triple("Spare (10%)", buffer, money.transfer), Triple("Still free", free, money.income),
            ).filter { it.second > 0 }
            val sum = parts.sumOf { it.second }.coerceAtLeast(1).toFloat()
            StackedBar(parts.map { it.second / sum to it.third })
            Spacer(Modifier.height(10.dp))
            for ((label, v, c) in parts) Row(Modifier.padding(vertical = 2.dp)) { LegendFigure(label, v, cur, c) }
            if (exp + ex + buffer >= inc) Text(
                "Your spending and current EMIs already use nearly all your income, so a new loan isn't comfortable right now.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                if (byRatio <= bySurplus) "The limit comes from the ${r.toInt()}% of income rule." else "The limit comes from what's left after your spending.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    AssumptionNote("A planning aid, not a loan approval. Lenders also look at credit history, job and age, and future income is not guaranteed.")
}
