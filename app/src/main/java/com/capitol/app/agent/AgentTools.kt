package com.capitol.app.agent

import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.Signal
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.broker.PortfolioRepository
import com.capitol.app.data.mcp.IndMoneyMcp
import com.capitol.app.data.mcp.McpTool
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.MarketRepository
import com.capitol.app.data.watchlist.WatchlistStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The tools Capitol's AI agent can call. Each tool runs on-device against the
 * same analysis engine, watchlist and portfolio the UI uses.
 */
class AgentTools(
    private val market: MarketRepository,
    private val watchlist: WatchlistStore,
    private val portfolio: PortfolioRepository,
    private val indmoney: IndMoneyMcp,
    private val kiteMcp: com.capitol.app.data.mcp.KiteMcp? = null,
) {
    data class Spec(val name: String, val description: String, val properties: Map<String, Any>, val required: List<String>)

    private val indicatorEnum = IndicatorType.entries.map { it.name }
    private val rangeEnum = ChartRange.entries.map { it.name }

    val specs: List<Spec> = listOf(
        Spec("search_symbol",
            "Find Yahoo Finance / yfinance ticker symbols for a company name or partial ticker. Indian NSE stocks end in .NS, BSE in .BO; indices start with ^ (e.g. ^NSEI for NIFTY 50, ^BSESN for SENSEX).",
            mapOf("query" to mapOf("type" to "string", "description" to "Company name or ticker fragment")),
            listOf("query")),
        Spec("run_technical_analysis",
            "Run Capitol's technical analysis engine on one symbol. Returns verdict, score, every requested indicator's signal, metrics and interpretation, plus key support/resistance levels. Choose indicators that match the user's request; omit to use the user's default set.",
            mapOf(
                "symbol" to mapOf("type" to "string", "description" to "Ticker in yfinance format, e.g. RELIANCE.NS, AAPL"),
                "indicators" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to indicatorEnum),
                    "description" to "Indicators to compute"),
                "range" to mapOf("type" to "string", "enum" to rangeEnum, "description" to "Lookback window; Y1 (daily bars for 1 year) is a good default. Use D1/W1 for intraday."),
            ),
            listOf("symbol")),
        Spec("get_price_summary",
            "Get recent price statistics for a symbol: last price, change, period high/low, and returns over 1W/1M/3M where available.",
            mapOf("symbol" to mapOf("type" to "string"), "range" to mapOf("type" to "string", "enum" to rangeEnum)),
            listOf("symbol")),
        Spec("get_fundamentals",
            "Get key fundamentals for a symbol (market cap, P/E, P/B, dividend yield, beta, 52-week range, margins, ROE, debt/equity, sector, business summary). Use alongside technicals when the user asks about valuation or quality.",
            mapOf("symbol" to mapOf("type" to "string")), listOf("symbol")),
        Spec("get_news", "Get recent news headlines (title, publisher, time) for a symbol.",
            mapOf("symbol" to mapOf("type" to "string")), listOf("symbol")),
        Spec("get_calendar", "Upcoming earnings and dividend dates (next 90 days) for every symbol in the user's watchlists.", emptyMap(), emptyList()),
        Spec("plan_trade",
            "Build a rules-based trade plan for a symbol: entry zone, stop-loss (ATR or just beyond the nearest support/resistance), targets at the next levels with reward/risk multiples, and position size from the user's capital and risk %. Use when the user asks where to enter, where to put a stop, targets or how much to buy.",
            mapOf("symbol" to mapOf("type" to "string"),
                "direction" to mapOf("type" to "string", "enum" to listOf("LONG", "SHORT")),
                "capital" to mapOf("type" to "number", "description" to "Trading capital; defaults to the user's saved setting"),
                "risk_pct" to mapOf("type" to "number", "description" to "Percent of capital to risk; defaults to the user's saved setting")),
            listOf("symbol")),
        Spec("run_screener",
            "Scan a universe of stocks for those matching ALL given conditions. Universes: NIFTY50, BANKNIFTY, US_LARGE, WATCHLISTS. Set fresh_only to find setups that just triggered in the last 5 trading days.",
            mapOf("universe" to mapOf("type" to "string", "enum" to com.capitol.app.analysis.Universe.entries.map { it.name }),
                "conditions" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to com.capitol.app.analysis.ScreenCondition.entries.map { it.name })),
                "rsi_level" to mapOf("type" to "number", "description" to "Level for RSI_BELOW / RSI_ABOVE (default 30 / 70)"),
                "fresh_only" to mapOf("type" to "boolean")),
            listOf("universe", "conditions")),
        Spec("get_watchlist", "List all of the user's Capitol watchlists and their symbols, with price and indicator alerts.", emptyMap(), emptyList()),
        Spec("scan_watchlist",
            "Run technical analysis on every symbol across all watchlists and return a compact ranked table (verdict, score, price, change). Use for 'what looks best/worst on my watchlist'.",
            mapOf("indicators" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to indicatorEnum))),
            emptyList()),
        Spec("add_to_watchlist", "Add a symbol to the user's watchlist. Only do this when the user asks.",
            mapOf("symbol" to mapOf("type" to "string"), "name" to mapOf("type" to "string"),
                "list" to mapOf("type" to "string", "description" to "Optional watchlist name; defaults to the active list")), listOf("symbol")),
        Spec("remove_from_watchlist", "Remove a symbol from the watchlist. Only do this when the user asks.",
            mapOf("symbol" to mapOf("type" to "string")), listOf("symbol")),
        Spec("get_portfolio",
            "Get the user's holdings from connected brokers (Zerodha Kite, INDmoney) or imported CSV: symbol, qty, average price, LTP and P&L. Read-only.",
            emptyMap(), emptyList()),
        Spec("get_portfolio_analytics",
            "Portfolio-level analysis of the user's holdings: allocation by holding and sector, concentration, diversification score, value-weighted technical health, best/worst performers and risk warnings.",
            emptyMap(), emptyList()),
        Spec("list_indicators", "List every indicator Capitol supports, with a one-line description of each.", emptyMap(), emptyList()),
    )

    var defaultIndicators: Set<IndicatorType> = IndicatorType.DEFAULTS
    var params: com.capitol.app.analysis.IndicatorParams = com.capitol.app.analysis.IndicatorParams.DEFAULT
    /** Set by the app container: computes portfolio analytics for holdings. */
    var analyticsLoader: (suspend (List<com.capitol.app.data.broker.Holding>) -> List<com.capitol.app.analysis.CurrencyAnalytics>)? = null
    var tradeCapital: Double = 100_000.0
    var tradeRiskPct: Double = 1.0

    /** Executes a tool; returns text for the tool_result, or throws for an error result. */
    /** A tool exposed by a connected MCP server (INDmoney), renamed for Claude. */
    data class ExternalTool(val claudeName: String, val tool: McpTool)

    /** Claude tool name → (server, MCP tool). */
    @Volatile private var external: Map<String, Pair<String, McpTool>> = emptyMap()

    /** INDmoney MCP tools, when the user has connected INDmoney. Failures just hide the tools. */
    /** Tools from connected MCP servers: INDmoney (all its tools) and Kite MCP (read-only tools only). */
    suspend fun externalTools(): List<ExternalTool> {
        val ind = if (indmoney.isConnected) runCatching { indmoney.tools() }.getOrDefault(emptyList())
            .map { ExternalTool(claudeToolName(it.name), it) to "indmoney" } else emptyList()
        val kite = if (kiteMcp?.isConnected == true) runCatching { kiteMcp!!.readTools() }.getOrDefault(emptyList())
            .map { ExternalTool(claudeToolName(it.name, com.capitol.app.data.mcp.KiteMcp.PREFIX), it) to "kite" } else emptyList()
        val all = ind + kite
        external = all.associate { (t, server) -> t.claudeName to (server to t.tool) }
        return all.map { it.first }
    }

    suspend fun execute(name: String, input: JsonObject): String = external[name]?.let { (server, tool) ->
        if (server == "kite") kiteMcp!!.callRead(tool.name, input) else indmoney.call(tool.name, input)
    } ?: when (name) {
        "search_symbol" -> {
            val q = input.s("query") ?: error("query is required")
            val r = market.search(q)
            if (r.isEmpty()) "No matches for '$q'."
            else r.joinToString("\n") { "${it.symbol} — ${it.name} [${it.exchange}, ${it.type}]" }
        }
        "run_technical_analysis" -> {
            val sym = input.s("symbol") ?: error("symbol is required")
            val inds = input.list("indicators").mapNotNull { IndicatorType.fromName(it) }.toSet().ifEmpty { defaultIndicators }
            val range = input.s("range")?.let { r -> ChartRange.entries.firstOrNull { it.name == r } } ?: ChartRange.Y1
            TechnicalAnalyzer.analyze(market.history(sym, range), inds, params).toPlainText()
        }
        "get_price_summary" -> {
            val sym = input.s("symbol") ?: error("symbol is required")
            val range = input.s("range")?.let { r -> ChartRange.entries.firstOrNull { it.name == r } } ?: ChartRange.Y1
            val s = market.history(sym, range)
            val c = s.closes
            fun ret(bars: Int) = if (c.size > bars) "%+.2f%%".format((c.last() / c[c.size - 1 - bars] - 1) * 100) else "n/a"
            """
            ${s.name} (${s.symbol}) on ${s.exchange}
            Last: ${"%.2f".format(s.regularMarketPrice)} ${s.currency} (${"%+.2f".format(s.changePercent)}% today)
            Period high: ${"%.2f".format(s.candles.maxOf { it.high })}, low: ${"%.2f".format(s.candles.minOf { it.low })}
            Returns: 1W ${ret(5)}, 1M ${ret(21)}, 3M ${ret(63)}, period ${ret(c.size - 1)}
            Bars: ${c.size} (${range.interval})
            """.trimIndent()
        }
        "get_fundamentals" -> {
            val sym = input.s("symbol") ?: error("symbol is required")
            market.fundamentals(sym).toPlainText()
        }
        "get_news" -> {
            val sym = input.s("symbol") ?: error("symbol is required")
            val fmt = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.US)
            market.news(sym).take(8).joinToString("\n") { n ->
                "- ${n.title} (${n.publisher}${if (n.publishedAt > 0) ", " + fmt.format(java.util.Date(n.publishedAt * 1000)) else ""})"
            }.ifBlank { "No recent news found for $sym." }
        }
        "get_calendar" -> {
            val now = System.currentTimeMillis() / 1000
            val df = java.text.SimpleDateFormat("EEE dd MMM yyyy", java.util.Locale.US)
            com.capitol.app.data.market.EventsCalendar.load(market, watchlist.all().map { it.symbol to it.name }, now)
                .joinToString("\n") { "${df.format(java.util.Date(it.epochSeconds * 1000))}: ${it.symbol} ${it.type.label} (in ${it.daysFrom(now)} days)" }
                .ifBlank { "No upcoming earnings or dividend dates found for the watchlist." }
        }
        "plan_trade" -> {
            val sym = input.s("symbol") ?: error("symbol is required")
            val dir = if (input.s("direction") == "SHORT") com.capitol.app.analysis.Direction.SHORT else com.capitol.app.analysis.Direction.LONG
            val series = market.history(sym, ChartRange.Y1)
            val verdict = TechnicalAnalyzer.analyze(series, defaultIndicators, params).verdict
            val capital = (input["capital"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: tradeCapital
            val risk = (input["risk_pct"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: tradeRiskPct
            com.capitol.app.analysis.TradePlanner.plan(series, dir, capital, risk, params, verdict)
                ?.toPlainText(series.symbol, series.currency) ?: "Not enough price history to plan a trade for $sym."
        }
        "run_screener" -> {
            val u = com.capitol.app.analysis.Universe.entries.firstOrNull { it.name == input.s("universe") } ?: error("unknown universe")
            val level = (input["rsi_level"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val filters = input.list("conditions").mapNotNull { n -> com.capitol.app.analysis.ScreenCondition.entries.firstOrNull { it.name == n } }
                .map { com.capitol.app.analysis.ScreenFilter(it, if (it.needsValue) level ?: it.defaultValue else null) }
            if (filters.isEmpty()) error("at least one condition is required")
            val fresh = (input["fresh_only"] as? JsonPrimitive)?.content == "true"
            val symbols = if (u == com.capitol.app.analysis.Universe.WATCHLISTS) watchlist.all().map { it.symbol } else u.symbols
            val hits = com.capitol.app.analysis.Screener.run(market, symbols, filters, fresh, params, defaultIndicators)
            if (hits.isEmpty()) "No matches in ${u.label} (${symbols.size} scanned) for: ${filters.joinToString { it.description }}${if (fresh) " (just triggered)" else ""}."
            else "${hits.size} matches in ${u.label}:\n" + hits.take(25).joinToString("\n") {
                "${it.symbol}: ${it.verdict.label} (score ${"%+.2f".format(it.score)}), ${"%.2f".format(it.price)} ${it.currency} (${"%+.2f".format(it.changePct)}%) — ${it.reasons.joinToString("; ")}"
            }
        }
        "get_watchlist" -> {
            val d = watchlist.snapshot()
            d.lists.joinToString("\n\n") { l ->
                "List \"${l.name}\"${if (l == d.activeList) " (active)" else ""}:\n" + (if (l.items.isEmpty()) "  (empty)" else l.items.joinToString("\n") { w ->
                    val alerts = listOfNotNull(w.alertAbove?.let { "price ≥ $it" }, w.alertBelow?.let { "price ≤ $it" }) + w.indicatorAlerts.map { it.description }
                    "  ${w.symbol} — ${w.name}" + if (alerts.isNotEmpty()) " (alerts: ${alerts.joinToString("; ")})" else ""
                })
            }
        }
        "scan_watchlist" -> {
            val inds = input.list("indicators").mapNotNull { IndicatorType.fromName(it) }.toSet().ifEmpty { defaultIndicators }
            val rows = watchlist.all().mapNotNull { w ->
                runCatching { TechnicalAnalyzer.analyze(market.history(w.symbol, ChartRange.Y1), inds, params) }.getOrNull()
            }.sortedByDescending { it.compositeScore }
            if (rows.isEmpty()) "No watchlist data available."
            else "Indicators: ${inds.joinToString { it.shortName }}\n" + rows.joinToString("\n") {
                "${it.symbol}: ${it.verdict.label} (score ${"%+.2f".format(it.compositeScore)}, ${it.bullishCount}↑/${it.bearishCount}↓), " +
                    "price ${"%.2f".format(it.lastPrice)} ${it.currency} (${"%+.2f".format(it.changePercent)}%)"
            }
        }
        "add_to_watchlist" -> {
            val sym = input.s("symbol") ?: error("symbol is required")
            watchlist.add(sym, input.s("name") ?: sym, input.s("list")); "Added $sym to ${input.s("list")?.let { "the \"$it\" list" } ?: "the active watchlist"}."
        }
        "remove_from_watchlist" -> {
            val sym = input.s("symbol") ?: error("symbol is required")
            watchlist.remove(sym); "Removed $sym from the watchlist."
        }
        "get_portfolio" -> {
            if (!portfolio.anyConnected) "No broker connected. The user can connect Zerodha Kite or INDmoney in the Portfolio tab."
            else {
                portfolio.refresh()
                val st = portfolio.state.value
                (st.holdings.joinToString("\n") {
                    (if (it.isMutualFund) "MF ${it.displayName ?: it.tradingSymbol} (${it.tradingSymbol})" else it.yahooSymbol) + " [${it.broker.label}, ${it.currency}] qty ${it.quantity}, avg ${"%.2f".format(it.averagePrice)}, LTP ${"%.2f".format(it.lastPrice)}, P&L ${"%.2f".format(it.pnl)} (${"%+.2f".format(it.pnlPercent)}%)"
                } + st.totalsByCurrency.joinToString("") { t ->
                    "\nTotal (${t.currency}): invested ${"%.2f".format(t.invested)}, current ${"%.2f".format(t.current)}, P&L ${"%+.2f".format(t.pnlPercent)}%"
                } +
                    st.errors.joinToString("") { "\nError: $it" })
            }
        }
        "get_portfolio_analytics" -> {
            if (!portfolio.anyConnected) "No broker connected. The user can connect Zerodha Kite or INDmoney, or import a CSV, in the Portfolio tab."
            else {
                portfolio.refresh()
                val list = analyticsLoader?.invoke(portfolio.state.value.holdings).orEmpty()
                if (list.isEmpty()) "No holdings to analyse." else list.joinToString("\n\n") { a ->
                    buildString {
                        appendLine("${a.currency} portfolio: total ${"%,.0f".format(a.total)}")
                        appendLine("Allocation: " + a.byHolding.joinToString { "${it.label} ${"%.1f".format(it.pct)}%" })
                        appendLine("Sectors: " + a.bySector.joinToString { "${it.label} ${"%.0f".format(it.pct)}%" })
                        appendLine("Largest ${"%.0f".format(a.largestPct)}%, top-5 ${"%.0f".format(a.top5Pct)}%, effective holdings ${"%.1f".format(a.effectiveHoldings)}, diversification ${a.diversificationScore}/100")
                        a.technicalScore?.let { appendLine("Value-weighted technical score ${"%+.2f".format(it)} (${Signal.fromScore(it).label})") }
                        appendLine("Holdings health: " + a.health.joinToString { "${it.symbol} ${it.verdict.label} (${"%.0f".format(it.weightPct)}%)" })
                        a.oneYearChangePct?.let { appendLine("Current holdings over the past year: ${"%+.1f".format(it)}%") }
                        a.warnings.forEach { appendLine("Warning: $it") }
                    }
                }
            }
        }
        "list_indicators" -> IndicatorType.entries.joinToString("\n") { "${it.name} (${it.category.label}): ${it.about}" }
        else -> error("Unknown tool $name")
    }

    companion object {
        const val INDMONEY_PREFIX = "indmoney__"
        /** Claude tool names must match ^[a-zA-Z0-9_-]{1,64}$. */
        fun claudeToolName(mcpName: String, prefix: String = INDMONEY_PREFIX) = (prefix + mcpName.replace(Regex("[^a-zA-Z0-9_-]"), "_")).take(64)
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}
