package com.capitol.app.data.mcp

import com.capitol.app.data.broker.AssetKind
import com.capitol.app.data.broker.Broker
import com.capitol.app.data.broker.BrokerException
import com.capitol.app.data.broker.Holding
import com.capitol.app.data.broker.MutualFunds
import com.capitol.app.data.prefs.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/**
 * Connection to INDmoney's official MCP server (https://mcp.indmoney.com/mcp) via standard MCP OAuth.
 * Uses the same stored keys as earlier versions, so existing sign-ins keep working.
 */
class IndMoneyMcp(http: OkHttpClient, secrets: SecretStore, serverUrl: String = SERVER_URL) {
    private val conn = OAuthMcp(http, secrets, serverUrl, keyPrefix = "MCP_INDMONEY",
        redirectUris = listOf(REDIRECT_URI, LOOPBACK_REDIRECT_URI), providerName = "INDmoney")

    val isConnected get() = conn.isConnected
    suspend fun beginLogin(): String = conn.beginLogin()
    suspend fun completeLogin(redirectUrl: String) = conn.completeLogin(redirectUrl)
    fun disconnect() = conn.disconnect()
    suspend fun tools(): List<McpTool> = conn.tools()
    suspend fun call(name: String, args: JsonObject): String = conn.call(name, args)

    /**
     * Stock holdings (Indian and US) for the Portfolio tab. INDmoney doesn't publish its tool names,
     * so the holdings tools are picked from the server's own list, called read-only, and their
     * JSON parsed tolerantly. When nothing usable comes back, the error lists the tools offered
     * and Diagnostics records a sample of each reply.
     */
    suspend fun holdings(): List<Holding> {
        val tools = conn.tools()
        val picks = pickHoldingTools(tools)
        com.capitol.app.diagnostics.Diagnostics.info("INDmoneyMCP", "tools offered: ${tools.joinToString { it.name }}; using: ${picks.joinToString()}")
        val offered = tools.joinToString { it.name }.ifBlank { "none" }
        if (picks.isEmpty()) throw BrokerException("INDmoney's MCP server has no holdings tool Capitol recognises. Tools it offers: $offered")
        val out = mutableListOf<Holding>()
        val errors = mutableListOf<String>()
        for (name in picks) {
            val tool = tools.first { it.name == name }
            com.capitol.app.diagnostics.Diagnostics.info("INDmoneyMCP", "$name schema: ${tool.inputSchema.toString().take(600)}")
            // A tool with an asset-type option (e.g. asset_class: us_stocks / indian_stocks / …) is called once per stock type.
            for ((args, usArg) in callPlans(tool)) {
                runCatching { call(name, args) }
                    .onSuccess { text ->
                        val parsed = parseHoldings(text, usHint = isUsName(name) || usArg)
                        com.capitol.app.diagnostics.Diagnostics.info("INDmoneyMCP", "$name $args → ${parsed.size} holdings; reply: ${text.take(1500)}")
                        out += parsed
                    }
                    .onFailure { errors += "$name $args: ${it.message}" }
            }
        }
        if (out.isEmpty()) {
            errors.forEach { com.capitol.app.diagnostics.Diagnostics.info("INDmoneyMCP", it) }
            throw BrokerException("INDmoney returned no stock holdings Capitol could read (tried: ${picks.joinToString()}" +
                (if (errors.isNotEmpty()) "; ${errors.size} failed" else "") + "). Tools it offers: $offered. Settings → Diagnostics has the details.")
        }
        // The same stock can appear in an overall and a market-specific tool: keep one per symbol + market.
        return out.distinctBy { it.tradingSymbol.uppercase() to it.isUs }
    }
    companion object {
        const val SERVER_URL = "https://mcp.indmoney.com/mcp"
        const val REDIRECT_URI = "capitol://indmoney-callback"
        const val LOOPBACK_REDIRECT_URI = "http://127.0.0.1:43217/indmoney-callback"
        fun isRedirect(url: String) = url.startsWith(REDIRECT_URI) || url.startsWith(LOOPBACK_REDIRECT_URI)

        private fun tokens(n: String) = n.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        internal fun isUsName(n: String): Boolean { val t = tokens(n); return "us" in t || "usstocks" in t || "usstock" in t || "global" in t || "international" in t }

        private val STOCK_VALUE = Regex("stock|equit|share")

        /**
         * Argument sets to call [tool] with: normally one (holdings mode if any). If an enum argument lists
         * stock asset types ("us_stocks", "indian_stocks", "EQUITY"…), one call per stock value, flagged US or not.
         */
        internal fun callPlans(tool: McpTool): List<Pair<JsonObject, Boolean>> {
            val base = KiteMcp.holdingsArgs(tool)
            val props = tool.inputSchema["properties"] as? JsonObject ?: return listOf(base to false)
            for ((k, v) in props) {
                if (k in base) continue
                val values = ((v as? JsonObject)?.get("enum") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: continue
                val stockValues = values.filter { STOCK_VALUE.containsMatchIn(it.lowercase()) && "mutual" !in it.lowercase() && KiteMcp.isReadValue(it) }
                if (stockValues.isNotEmpty()) return stockValues.map { JsonObject(base + (k to JsonPrimitive(it))) to isUsName(it) }
            }
            return listOf(base to false)
        }

        /**
         * Read-only tools that look like stock holdings, US-specific ones first, at most 4.
         * Mutual funds, orders, transactions, SIPs and net-worth summaries are skipped.
         */
        /** Tool-level pick: name heuristics plus "every required argument can be filled" (so no market-data tools needing symbols). */
        internal fun pickHoldingTools(tools: List<McpTool>): List<String> {
            val byName = tools.associateBy { it.name }
            return pickHoldingToolNames(tools.map { it.name }).filter { n -> byName[n]?.let(::canCallWithoutInput) ?: true }
        }

        /** True when every required argument is supplied by [KiteMcp.holdingsArgs] (e.g. a mode), i.e. no symbols/ids needed. */
        internal fun canCallWithoutInput(tool: McpTool): Boolean {
            val required = (tool.inputSchema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val filled = KiteMcp.holdingsArgs(tool).keys
            return required.all { it in filled }
        }

        internal fun pickHoldingToolNames(names: List<String>): List<String> {
            // Market-data tools (OHLC, quotes, details for given symbols) and non-stock products are never holdings.
            val skip = listOf("order", "transaction", "sip", "mutual", "_mf", "mf_", "fund", "watchlist", "alert", "news",
                "snapshot", "allocation", "summary", "search", "lookup", "quote", "chart", "history", "insurance", "loan", "credit", "bank", "deposit",
                "ohlc", "candle", "price", "detail", "info", "fundamental", "ltp", "depth", "option", "future", "ipo", "screener",
                "mover", "gainer", "loser", "greek", "member", "analysis", "category")
            fun score(n: String): Int {
                val l = n.lowercase(); val t = tokens(n)
                // Read tools only; a bare "us_stocks"-style name counts if it has no write verb.
                if (!KiteMcp.isReadOnly(l) && !("stock" in l && KiteMcp.isReadValue(l))) return -1
                if (skip.any { it in l }) return -1
                val holdingish = "holding" in l || "portfolio" in l || "position" in l
                val stockish = "stock" in l || "equit" in l || "share" in l
                // Names made only of market + stock words ("us_stocks", "indian_equities") count as holdings tools.
                val bareStocks = t.isNotEmpty() && t.all { it in setOf("us", "usstocks", "indian", "india", "global", "my", "stock", "stocks", "equity", "equities", "shares") }
                if (!holdingish && !bareStocks && !(stockish && ("get" in t || "list" in t))) return -1
                return (if (isUsName(n)) 4 else 0) + (if (holdingish) 2 else 0) + (if (stockish) 1 else 0)
            }
            return names.map { it to score(it) }.filter { it.second >= 0 }.sortedByDescending { it.second }.take(4).map { it.first }
        }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val SYMBOL = listOf("ticker", "tradingsymbol", "trading_symbol", "symbol", "stock_symbol", "nse_symbol", "bse_symbol", "scrip", "stock_code")
        private val QTY = listOf("quantity", "qty", "total_quantity", "shares", "units", "no_of_shares", "holding_quantity", "net_quantity")
        private val AVG = listOf("average_price", "avg_price", "avg_buy_price", "average_buy_price", "buy_price", "avg_cost", "average_cost", "cost_price", "buy_avg")
        private val LTP = listOf("last_price", "ltp", "current_price", "market_price", "cmp", "close_price", "price")
        private val EXCHANGE = listOf("exchange", "market", "exchange_code", "segment")
        private val NAME = listOf("name", "stock_name", "instrument_name", "asset_name", "company_name", "security_name", "display_name", "scheme_name", "title")
        /** Per-record asset type: a US stock row in a mixed net-worth list says so here. */
        private val ASSET_TYPE = listOf("asset_type", "asset_class", "assettype", "type", "category", "instrument_type", "asset", "segment", "market")

        /** Turns an MCP reply (JSON, maybe wrapped in prose) into holdings; [usHint] marks a US-only tool. */
        internal fun parseHoldings(text: String, usHint: Boolean): List<Holding> {
            val el = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()
                ?: MutualFunds.extractJson(text)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                ?: return parseTable(text, usHint)
            return records(el).mapNotNull { (o, sectionUs) -> toHolding(o, usHint || sectionUs) }.ifEmpty { parseTable(text, usHint) }
        }

        /** Markdown/pipe tables ("| Symbol | Qty | Avg | LTP |"), turned into records and parsed like JSON rows. */
        internal fun parseTable(text: String, usHint: Boolean): List<Holding> {
            val rows = text.lines().map { it.trim() }.filter { it.startsWith("|") && it.count { c -> c == '|' } >= 3 }
                .filterNot { Regex("^\\|[\\s:|-]+\\|?$").matches(it) }
                .map { r -> r.trim('|').split('|').map { it.trim() } }
            if (rows.size < 2) return emptyList()
            val header = rows.first().map { it.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }
            var sectionUs = false
            return rows.drop(1).mapNotNull { cells ->
                if (cells.size != header.size) { sectionUs = isUsName(cells.joinToString(" ")); return@mapNotNull null }
                toHolding(JsonObject(header.zip(cells).associate { (h, c) -> h to JsonPrimitive(c) }), usHint || sectionUs)
            }
        }

        /** All objects that look like a holding, remembering whether they sat under a US-named key. */
        private fun records(el: JsonElement, us: Boolean = false, depth: Int = 0): List<Pair<JsonObject, Boolean>> {
            if (depth > 6) return emptyList()
            return when (el) {
                is JsonArray -> el.flatMap { records(it, us, depth + 1) }
                is JsonObject -> {
                    val keys = el.keys.map { it.lowercase() }
                    if (keys.any { it in SYMBOL || it in NAME } && keys.any { k -> QTY.any { k == it || k.startsWith(it + "_") } }) listOf(el to us)
                    else el.entries.flatMap { (k, v) -> records(v, us || isUsName(k), depth + 1) }
                }
                else -> emptyList()
            }
        }

        private fun num(v: JsonElement?): Double? = (v as? JsonPrimitive)?.content?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()

        private fun toHolding(o: JsonObject, usHint: Boolean): Holding? {
            val lower = o.entries.associate { it.key.lowercase() to it.value }
            fun first(keys: List<String>, preferUsd: Boolean = false): JsonElement? {
                if (preferUsd) keys.firstNotNullOfOrNull { k -> lower["${k}_usd"] ?: lower["usd_$k"] }?.let { return it }
                return keys.firstNotNullOfOrNull { lower[it] }
            }
            val name = (first(NAME) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
            val sym = (first(SYMBOL) as? JsonPrimitive)?.content?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: name?.uppercase() ?: return null
            val exchangeField = (first(EXCHANGE) as? JsonPrimitive)?.content?.trim().orEmpty()
            val currency = (lower["currency"] as? JsonPrimitive)?.content?.uppercase()
            val assetType = ASSET_TYPE.mapNotNull { (lower[it] as? JsonPrimitive)?.content?.lowercase() }.joinToString(" ")
            // Skip non-stock rows of a mixed net-worth list (mutual funds, gold, FDs, EPF…).
            if (assetType.isNotBlank() && listOf("mutual", "fund", "gold", "deposit", "epf", "nps", "bond", "insurance", "crypto", "cash", "real")
                    .any { it in assetType } && !STOCK_VALUE.containsMatchIn(assetType)) return null
            val us = Holding.isUsExchange(exchangeField) || currency == "USD" || isUsName(assetType) || (usHint && currency != "INR")
            val qty = num(first(QTY)) ?: return null
            if (qty <= 0) return null
            var avg = num(first(AVG, us))
            var ltp = num(first(LTP, us))
            // Fall back to values ÷ quantity (USD-named values first for US stocks).
            if (avg == null) num(first(listOf("invested_value", "invested_amount", "invested", "investment", "cost_value", "buy_value"), us))?.let { avg = it / qty }
            if (ltp == null) num(first(listOf("current_value", "market_value", "value", "present_value"), us))?.let { ltp = it / qty }
            val exchange = when {
                us -> "US"
                exchangeField.equals("BSE", true) -> "BSE"
                else -> "NSE"
            }
            return Holding(Broker.INDMONEY, sym.removeSuffix(".NS").removeSuffix(".BO"), exchange, qty, avg ?: 0.0, ltp ?: avg ?: 0.0,
                num(lower["day_change_percentage"] ?: lower["day_change_percent"] ?: lower["change_percent"]), AssetKind.EQUITY,
                displayName = name)
        }
    }
}
