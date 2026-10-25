package app.kosh.ui.screens

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
    val tabs = listOf("EMI", "FD / RD", "Split a bill", "Affordability")
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

@Composable
private fun EmiCalc() {
    val cur = LocalContainer.current.lookup.collectAsStateWithLifecycle().value.baseCurrency
    var amount by remember { mutableStateOf("2000000") }
    var rate by remember { mutableStateOf("8.5") }
    var years by remember { mutableStateOf("20") }
    val p = Money.parse(amount, cur)
    val n = years.toDoubleOrNull()?.let { (it * 12).toInt() }
    AmountField(amount, { amount = it }, cur, "Loan amount")
    OutlinedTextField(rate, { rate = it }, label = { Text("Interest rate (% a year)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = Loans.rateError(rate, allowZero = true) != null, supportingText = { Loans.rateError(rate, allowZero = true)?.let { Text(it) } })
    OutlinedTextField(years, { years = it }, label = { Text("Tenure (years)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (p != null && p > 0 && n != null && n in 1..600 && Loans.rateError(rate, allowZero = true) == null) {
        val emi = Loans.emi(p, rate.ifBlank { null }, n)
        val s = Loans.schedule(p, rate.ifBlank { null }, emi, Dates.today())
        KoshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("EMI", style = MaterialTheme.typography.labelLarge)
                MoneyText(emi, cur, style = MaterialTheme.typography.headlineMedium)
                FactRow("Total interest") { MoneyText(s.totalInterest, cur) }
                FactRow("Total repaid") { MoneyText(s.totalPayment, cur) }
                FactRow("Interest share") { Text("${"%.1f".format(s.totalInterest * 100.0 / maxOf(1, s.totalPayment))}%") }
                for (y in listOf(1, 5, 10, 15, 20).filter { it * 12 <= s.rows.size }) FactRow("Owed after $y years") { MoneyText(s.rows[y * 12 - 1].closing, cur) }
            }
        }
    }
    AssumptionNote("EMI = P·r·(1+r)ⁿ ⁄ ((1+r)ⁿ − 1), r = annual rate ÷ 12. Interest each month is on the balance still owed; the last instalment absorbs rounding. Lenders may differ slightly.")
}

@Composable
private fun DepositCalc() {
    val cur = LocalContainer.current.lookup.collectAsStateWithLifecycle().value.baseCurrency
    var rd by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("100000") }
    var rate by remember { mutableStateOf("7.1") }
    var months by remember { mutableStateOf("12") }
    var compounding by remember { mutableStateOf("quarterly") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!rd, { rd = false }, label = { Text("Fixed deposit") })
        FilterChip(rd, { rd = true }, label = { Text("Recurring deposit") })
    }
    AmountField(amount, { amount = it }, cur, if (rd) "Monthly instalment" else "Amount")
    OutlinedTextField(rate, { rate = it }, label = { Text("Rate (% a year)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text("Term (months)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in listOf("quarterly" to "Quarterly", "monthly" to "Monthly", "yearly" to "Yearly", "simple" to "Simple")) FilterChip(compounding == k, { compounding = k }, label = { Text(l) })
    }
    val p = Money.parse(amount, cur)
    val n = months.toIntOrNull()
    if (p != null && p > 0 && n != null && n in 1..600 && Loans.rateError(rate) == null) {
        val start = Dates.today()
        val e = Deposits.estimate(DepositEntity("", name = "", accountId = "", currency = cur, amount = p, annualRate = rate.trim(), startDate = start.toString(),
            maturityDate = start.plusMonths(n.toLong()).toString(), kind = if (rd) "rd" else "fd", compounding = compounding), start)
        KoshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("At maturity", style = MaterialTheme.typography.labelLarge)
                MoneyText(e.maturity, cur, style = MaterialTheme.typography.headlineMedium)
                FactRow("You put in") { MoneyText(e.invested, cur) }
                FactRow("Interest earned") { MoneyText(e.interest, cur) }
                FactRow("Matures") { Text(Dates.medium(start.plusMonths(n.toLong()))) }
            }
        }
    }
    AssumptionNote("Interest compounds every full period; leftover days earn simple interest on 365 days. RD instalments each compound for the months they stay invested. Banks may differ slightly; early closure usually pays less.")
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
    AmountField(total, { total = it }, cur, "Bill amount")
    OutlinedTextField(tip, { tip = it }, label = { Text("Tax / tip / service (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!byShares, { byShares = false }, label = { Text("Equally") })
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
        KoshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                names.indices.forEach { i -> FactRow(names[i].ifBlank { "Person ${i + 1}" }) { MoneyText(owes[i], cur) } }
                if (paidSum > 0 && paidSum != t + extra) Text("Payments add up to ${Money.format(paidSum, cur)}, but the bill with extras is ${Money.format(t + extra, cur)}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (paidSum == t + extra) {
                    Text("To settle up", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    for (x in Tools.settle(paidMinor.zip(owes) { a, b -> a - b })) Text("${names[x.from].ifBlank { "Person ${x.from + 1}" }} pays ${names[x.to].ifBlank { "Person ${x.to + 1}" }} ${Money.format(x.amount, cur)}")
                }
                Text("Shares add up to exactly ${Money.format(t + extra, cur)}; leftover paise go to the first people.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AffordCalc() {
    val lookup by LocalContainer.current.lookup.collectAsStateWithLifecycle()
    val cur = lookup.baseCurrency
    val existing = rememberLoanEntries().filter { !it.loan.closed && it.loan.currency == cur }.sumOf { it.status.emi }
    var income by remember { mutableStateOf("") }
    var spend by remember { mutableStateOf("") }
    var emis by remember { mutableStateOf(Money.toPlain(existing, cur)) }
    var ratio by remember { mutableStateOf("40") }
    var rate by remember { mutableStateOf("9") }
    var years by remember { mutableStateOf("15") }
    AmountField(income, { income = it }, cur, "Monthly take-home income")
    AmountField(spend, { spend = it }, cur, "Monthly spending (without EMIs)")
    AmountField(emis, { emis = it }, cur, "Existing EMIs", supporting = "From your open loans")
    OutlinedTextField(ratio, { ratio = it }, label = { Text("Max share of income for all EMIs (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(rate, { rate = it }, label = { Text("New loan rate (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(years, { years = it }, label = { Text("Tenure (years)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val inc = Money.parse(income, cur) ?: 0
    val exp = Money.parse(spend, cur) ?: 0
    val ex = Money.parse(emis, cur) ?: 0
    val r = ratio.toDoubleOrNull()
    val n = years.toDoubleOrNull()?.let { (it * 12).toInt() }
    if (inc > 0 && r != null && r in 1.0..80.0 && n != null && n in 12..480) {
        val byRatio = maxOf(0, (inc * r / 100).toLong() - ex)
        val bySurplus = maxOf(0, inc - exp - ex - inc / 10)
        val maxEmi = minOf(byRatio, bySurplus)
        KoshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Comfortable new EMI", style = MaterialTheme.typography.labelLarge)
                MoneyText(maxEmi, cur, style = MaterialTheme.typography.headlineMedium)
                FactRow("Limit from the income share") { MoneyText(byRatio, cur) }
                FactRow("Limit from what is left (keeping 10% spare)") { MoneyText(bySurplus, cur) }
                FactRow("Largest loan for that EMI") { MoneyText(Tools.maxPrincipal(maxEmi, rate.ifBlank { null }, n), cur) }
            }
        }
    }
    AssumptionNote("A planning aid, not a loan approval. Lenders also look at credit history, job and age, and future income is not guaranteed.")
}
