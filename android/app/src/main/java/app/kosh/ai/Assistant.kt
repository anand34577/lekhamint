package app.kosh.ai

import android.content.Context
import app.kosh.AppContainer
import app.kosh.core.Dates
import app.kosh.core.Money
import app.kosh.data.SecretStore
import app.kosh.domain.Debts
import app.kosh.domain.Deposits
import app.kosh.domain.Loans
import app.kosh.domain.Lookup
import app.kosh.domain.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/**
 * The assistant on Android (docs/11-assistant.md): any OpenAI-compatible chat
 * endpoint — a cloud service, a model on a home server, or one running on this
 * phone (an app that serves an OpenAI-compatible API on localhost). The model
 * only sees what read-only tools return; every figure is calculated here.
 */
data class AiSettings(
    val baseUrl: String = "",
    val model: String = "",
    val tools: Boolean = true,
    val shareDetails: Boolean = true,
    val consentFor: String? = null,
) {
    val configured get() = baseUrl.isNotBlank()
    val host: String get() = runCatching { java.net.URI(baseUrl).host ?: baseUrl }.getOrDefault(baseUrl)
}

class AiStore(context: Context) {
    private val prefs = context.getSharedPreferences("kosh.ai", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    fun load() = AiSettings(
        baseUrl = prefs.getString("base_url", "") ?: "", model = prefs.getString("model", "") ?: "",
        tools = prefs.getBoolean("tools", true), shareDetails = prefs.getBoolean("share", true), consentFor = prefs.getString("consent", null),
    )
    fun save(s: AiSettings) = prefs.edit().putString("base_url", s.baseUrl.trim().trimEnd('/')).putString("model", s.model.trim())
        .putBoolean("tools", s.tools).putBoolean("share", s.shareDetails).putString("consent", s.consentFor).apply()
    var apiKey: String?
        get() = secrets.get("ai_api_key")
        set(v) { if (v.isNullOrBlank()) secrets.remove("ai_api_key") else secrets.put("ai_api_key", v.trim()) }
}

class AiException(message: String, val toolsUnsupported: Boolean = false) : IOException(message)

data class ToolRun(val name: String, val args: JsonObject, val result: JsonElement)
data class Turn(val question: String, val answer: String, val runs: List<ToolRun>, val snapshot: Boolean, val error: String? = null)

class Assistant(private val c: AppContainer, private val store: AiStore) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    private fun money(v: Long, cur: String) = buildJsonObject { put("amount", Money.format(v, cur)); put("minor_units", v) }

    private fun tool(name: String, description: String, props: JsonObject = JsonObject(emptyMap()), required: List<String> = emptyList()) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name); put("description", description)
            putJsonObject("parameters") { put("type", "object"); put("properties", props); putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } } }
        }
    }

    private val date = buildJsonObject { put("type", "string"); put("description", "YYYY-MM-DD") }
    private val toolSpecs = buildJsonArray {
        add(tool("overview", "Net worth, cash, investments, debts and money owed to/by the user, plus this month's income and spending."))
        add(tool("spending_summary", "Income and spending for a period by category. Transfers, loan principal and money lent are never spending.", buildJsonObject { put("from", date); put("to", date) }, listOf("from", "to")))
        add(tool("search_transactions", "Find transactions by text in payee or note within a period. Returns totals and up to 40 rows.",
            buildJsonObject { put("text", buildJsonObject { put("type", "string") }); put("from", date); put("to", date) }))
        add(tool("accounts", "Every account with its balance."))
        add(tool("loans", "Loans: outstanding, EMI, next due date, instalments paid and left, interest paid and still to pay."))
        add(tool("deposits", "Fixed and recurring deposits: value now, maturity date and expected maturity value."))
        add(tool("people_debts", "Money lent to and borrowed from friends and family."))
        add(tool("upcoming", "Bills, EMIs, deposit maturities and personal debts due in the next N days.", buildJsonObject { put("days", buildJsonObject { put("type", "integer") }) }))
    }

    private suspend fun runTool(name: String, args: JsonObject, share: Boolean): JsonElement = withContext(Dispatchers.Default) {
        val lookup: Lookup = c.lookup.value
        val base = lookup.baseCurrency
        val today = Dates.today()
        fun argDate(k: String) = args[k]?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        runCatching {
            when (name) {
                "overview" -> {
                    val p = lookup.position
                    val ym = YearMonth.now()
                    val s = Reports.summarize(c.repo.reportRows(ym.atDay(1), ym.atEndOfMonth()).first(), lookup.categoryById, base)
                    buildJsonObject {
                        put("as_of", today.toString())
                        put("note", "Net worth = cash + investments + owed to user − cards & loans − user owes people. Credit limits are not assets.")
                        putJsonArray("currencies") {
                            for (cur in (p.netWorth.keys + p.available.keys).toSet()) add(buildJsonObject {
                                put("currency", cur); put("net_worth", money(p.netWorth[cur] ?: 0, cur)); put("cash_and_bank", money(p.available[cur] ?: 0, cur))
                                put("investments_and_deposits", money(p.investments[cur] ?: 0, cur)); put("owed_on_cards_and_loans", money(p.debt[cur] ?: 0, cur))
                                put("owed_to_user", money(p.receivable[cur] ?: 0, cur)); put("user_owes_people", money(p.payable[cur] ?: 0, cur))
                            })
                        }
                        putJsonArray("this_month") { for ((cur, x) in s) add(buildJsonObject { put("currency", cur); put("income", money(x.income, cur)); put("spending", money(x.expense, cur)) }) }
                    }
                }
                "spending_summary" -> {
                    val from = argDate("from") ?: error("from must be YYYY-MM-DD")
                    val to = argDate("to") ?: error("to must be YYYY-MM-DD")
                    val days = java.time.temporal.ChronoUnit.DAYS.between(from, to)
                    suspend fun shape(f: LocalDate, t: LocalDate) = Reports.summarize(c.repo.reportRows(f, t).first(), lookup.categoryById, base).values.map { s ->
                        buildJsonObject {
                            put("currency", s.currency); put("income", money(s.income, s.currency)); put("spending", money(s.expense, s.currency))
                            putJsonArray("spending_by_category") { s.expenseByCategory.entries.sortedByDescending { it.value }.forEach { (k, v) -> add(buildJsonObject { put("category", lookup.categoryName(k)); put("total", money(v, s.currency)) }) } }
                            putJsonArray("income_by_category") { s.incomeByCategory.entries.sortedByDescending { it.value }.forEach { (k, v) -> add(buildJsonObject { put("category", lookup.categoryName(k)); put("total", money(v, s.currency)) }) } }
                        }
                    }
                    buildJsonObject {
                        put("period", "$from to $to"); put("current", JsonArray(shape(from, to)))
                        put("previous_period", "${from.minusDays(days + 1)} to ${from.minusDays(1)}"); put("previous", JsonArray(shape(from.minusDays(days + 1), from.minusDays(1))))
                    }
                }
                "search_transactions" -> {
                    val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
                    val from = argDate("from") ?: today.minusMonths(12)
                    val to = argDate("to") ?: today
                    val rows = c.db.transactions().everything().filter { !it.deleted && it.localDate >= from.toString() && it.localDate <= to.toString() &&
                        (text.isEmpty() || (it.payee ?: "").lowercase().contains(text) || (it.note ?: "").lowercase().contains(text)) }.sortedByDescending { it.localDate }
                    val spent = rows.filter { it.type == "expense" && it.status != "planned" }.groupBy { lookup.currencyOf(it.accountId) }.mapValues { (_, l) -> l.sumOf { it.amount } }
                    buildJsonObject {
                        put("filters", "text='$text' from $from to $to"); put("matches", rows.size)
                        putJsonArray("spent") { spent.forEach { (cur, v) -> add(buildJsonObject { put("currency", cur); put("total", money(v, cur)) }) } }
                        putJsonArray("rows") {
                            rows.take(40).forEach { t -> add(buildJsonObject {
                                put("date", t.localDate); put("type", t.type); put("amount", Money.format(t.amount, lookup.currencyOf(t.accountId))); put("category", lookup.categoryName(t.categoryId))
                                put("account", lookup.accountName(t.accountId)); if (share) { put("payee", t.payee); put("note", t.note) }
                            }) }
                        }
                    }
                }
                "accounts" -> buildJsonArray {
                    lookup.activeAccounts.forEach { a -> add(buildJsonObject { put("name", a.name); put("type", a.type); put("balance", money(lookup.balances[a.id] ?: a.openingBalance, a.currency)) }) }
                }
                "loans" -> {
                    val moves = c.repo.loanMoves.first()
                    buildJsonArray {
                        c.repo.loans.first().forEach { l ->
                            val s = Loans.status(l, moves, lookup.balances[l.accountId] ?: 0, today)
                            add(buildJsonObject {
                                put("name", l.name); put("lender", l.lender); put("closed", l.closed); put("rate", l.annualRate ?: "0"); put("outstanding", money(s.outstanding, l.currency))
                                put("emi", money(s.emi, l.currency)); put("next_due", s.nextDue?.toString()); put("overdue_instalments", s.overdue); put("instalments_paid", s.paidInstalments)
                                put("instalments_left", s.remainingInstalments); put("interest_paid", money(s.interestPaid, l.currency)); put("interest_still_to_pay_estimate", money(s.projectedInterest, l.currency))
                            })
                        }
                    }
                }
                "deposits" -> buildJsonArray {
                    c.repo.deposits.first().forEach { d ->
                        val e = Deposits.estimate(d, today)
                        add(buildJsonObject {
                            put("name", d.name); put("status", d.status); put("rate", "${d.annualRate}%"); put("matures", d.maturityDate)
                            put("now_in_deposit", money(lookup.balances[d.accountId] ?: 0, d.currency)); put("at_maturity", money(e.maturity, d.currency))
                            put("basis", if (e.fromBank) "bank's figure" else "estimate")
                        })
                    }
                }
                "people_debts" -> {
                    val moves = c.repo.debtMoves.first()
                    buildJsonArray {
                        c.repo.debts.first().filter { !it.closed }.forEachIndexed { i, d ->
                            val b = Debts.balance(d, moves.filter { it.debtId == d.id })
                            if (b != 0L) add(buildJsonObject {
                                put("person", if (share) d.person else "Person ${i + 1}"); put(if ((d.direction == "lent") == (b > 0)) "owes_user" else "user_owes", money(kotlin.math.abs(b), d.currency)); put("due", d.dueDate)
                            })
                        }
                    }
                }
                "upcoming" -> {
                    val days = (args["days"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 365)
                    val until = today.plusDays(days.toLong())
                    val items = ArrayList<JsonObject>()
                    val moves = c.repo.loanMoves.first()
                    c.repo.loans.first().filter { !it.closed }.forEach { l ->
                        Loans.status(l, moves, lookup.balances[l.accountId] ?: 0, today).projection.rows.takeWhile { !it.date.isAfter(until) }
                            .forEach { r -> items += buildJsonObject { put("date", r.date.toString()); put("what", "${l.name} EMI"); put("amount", money(r.payment, l.currency)); put("overdue", r.date.isBefore(today)) } }
                    }
                    c.repo.deposits.first().filter { it.status == "active" && LocalDate.parse(it.maturityDate) <= until }
                        .forEach { d -> items += buildJsonObject { put("date", d.maturityDate); put("what", "${d.name} matures (money in)"); put("amount", money(Deposits.estimate(d, today).maturity, d.currency)) } }
                    c.recurring.upcoming(c.repo.recurringRules.first(), days = days.toLong()).forEach { u ->
                        items += buildJsonObject { put("date", u.date.toString()); put("what", u.rule.name ?: u.rule.payee ?: "Bill"); put("amount", money(u.rule.amount, lookup.currencyOf(u.rule.accountId))) }
                    }
                    c.repo.debts.first().filter { !it.closed && it.dueDate != null && LocalDate.parse(it.dueDate) <= until }
                        .forEach { d -> items += buildJsonObject { put("date", d.dueDate); put("what", if (d.direction == "lent") "${if (share) d.person else "Someone"} should repay you" else "Repay ${if (share) d.person else "someone"}") } }
                    buildJsonObject { put("from", today.toString()); put("to", until.toString()); put("note", "Expected, not yet paid."); put("items", JsonArray(items.sortedBy { it["date"]?.jsonPrimitive?.contentOrNull })) }
                }
                else -> error("Unknown tool $name")
            }
        }.getOrElse { e -> buildJsonObject { put("error", e.message ?: "failed") } }
    }

    private fun systemPrompt(base: String) = """
        You are the LekhaMint assistant on the user's phone. Today is ${Dates.today()}. The main currency is $base.
        Answer only from the user's own records, which you get by calling the tools. Never invent transactions, balances, rates or dates.
        Use the amounts the tools return; they are exact. Always say which period and records an answer is based on, and say what is missing when data is not there.
        Transfers, loan principal, money lent or borrowed and deposit movements are not income or spending; interest and fees are. Credit limits are not money the user has. Projections are estimates.
        Label suggestions as suggestions. You cannot change records. Be brief.
    """.trimIndent()

    private suspend fun complete(s: AiSettings, messages: JsonArray, withTools: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            if (s.model.isNotBlank()) put("model", s.model)
            put("messages", messages); put("temperature", 0.2)
            if (withTools) { put("tools", toolSpecs); put("tool_choice", "auto") }
        }
        val req = Request.Builder().url(s.baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { store.apiKey?.let { header("Authorization", "Bearer $it") } }
            .build()
        val res = try { http.newCall(req).execute() } catch (e: IOException) {
            throw AiException(if (s.host in setOf("localhost", "127.0.0.1")) "Couldn't reach the model on this phone. Check that the app serving it is running." else "Couldn't reach ${s.host}. Check the address and your connection.")
        }
        res.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.let { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: it.jsonPrimitive.contentOrNull } }.getOrNull() ?: "${r.code}"
                throw when {
                    r.code == 401 || r.code == 403 -> AiException("The provider refused the request ($msg). Check the API key.")
                    r.code == 404 -> AiException("Not found ($msg). Check the address and model name.")
                    withTools && r.code == 400 && Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> AiException(msg, toolsUnsupported = true)
                    else -> AiException("The model returned an error: $msg")
                }
            }
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: throw AiException("The model sent an empty answer")
        }
    }

    suspend fun ask(history: List<Turn>, question: String): Turn {
        val s = store.load()
        val base = c.lookup.value.baseCurrency
        val runs = ArrayList<ToolRun>()
        val msgs = ArrayList<JsonElement>()
        msgs += msg("system", systemPrompt(base))
        history.takeLast(6).forEach { msgs += msg("user", it.question); msgs += msg("assistant", it.answer) }
        suspend fun snapshotAnswer(): Turn {
            val parts = listOf("overview" to JsonObject(emptyMap()), "loans" to JsonObject(emptyMap()), "deposits" to JsonObject(emptyMap()), "people_debts" to JsonObject(emptyMap()),
                "upcoming" to buildJsonObject { put("days", 30) }).map { (n, a) -> "## $n\n" + runTool(n, a, s.shareDetails) }
            val m = msgs.toMutableList().apply { add(1, msg("system", "This model cannot look things up; answer only from this summary and say when a question needs more.\n\n" + parts.joinToString("\n\n"))); add(msg("user", question)) }
            val r = complete(s, JsonArray(m), false)
            return Turn(question, r["content"]?.jsonPrimitive?.contentOrNull.orEmpty(), runs, snapshot = true)
        }
        if (!s.tools) return snapshotAnswer()
        msgs += msg("user", question)
        repeat(6) {
            val m = try { complete(s, JsonArray(msgs), true) } catch (e: AiException) { if (e.toolsUnsupported) return snapshotAnswer() else throw e }
            val calls = m["tool_calls"] as? JsonArray
            if (calls.isNullOrEmpty()) return Turn(question, m["content"]?.jsonPrimitive?.contentOrNull.orEmpty(), runs, snapshot = false)
            msgs += m
            for (call in calls) {
                val fn = call.jsonObject["function"]!!.jsonObject
                val name = fn["name"]!!.jsonPrimitive.content
                val args = runCatching { json.parseToJsonElement(fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
                val result = runTool(name, args, s.shareDetails)
                runs += ToolRun(name, args, result)
                msgs += buildJsonObject { put("role", "tool"); put("tool_call_id", call.jsonObject["id"] ?: JsonPrimitive("")); put("name", name); put("content", result.toString().take(20000)) }
            }
        }
        return Turn(question, "I looked up a lot of records but could not finish. Try a narrower question.", runs, snapshot = false)
    }

    private fun msg(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }
}
