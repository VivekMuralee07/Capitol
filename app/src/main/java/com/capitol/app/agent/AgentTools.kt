package com.capitol.app.agent

import com.capitol.app.analysis.IndicatorType
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
        Spec("list_indicators", "List every indicator Capitol supports, with a one-line description of each.", emptyMap(), emptyList()),
    )

    var defaultIndicators: Set<IndicatorType> = IndicatorType.DEFAULTS
    var params: com.capitol.app.analysis.IndicatorParams = com.capitol.app.analysis.IndicatorParams.DEFAULT

    /** Executes a tool; returns text for the tool_result, or throws for an error result. */
    /** A tool exposed by a connected MCP server (INDmoney), renamed for Claude. */
    data class ExternalTool(val claudeName: String, val tool: McpTool)

    @Volatile private var external: Map<String, McpTool> = emptyMap()

    /** INDmoney MCP tools, when the user has connected INDmoney. Failures just hide the tools. */
    suspend fun externalTools(): List<ExternalTool> {
        if (!indmoney.isConnected) { external = emptyMap(); return emptyList() }
        val list = runCatching { indmoney.tools() }.getOrDefault(emptyList()).map { ExternalTool(claudeToolName(it.name), it) }
        external = list.associate { it.claudeName to it.tool }
        return list
    }

    suspend fun execute(name: String, input: JsonObject): String = external[name]?.let { indmoney.call(it.name, input) } ?: when (name) {
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
                    "${it.yahooSymbol} [${it.broker.label}, ${it.currency}] qty ${it.quantity}, avg ${"%.2f".format(it.averagePrice)}, LTP ${"%.2f".format(it.lastPrice)}, P&L ${"%.2f".format(it.pnl)} (${"%+.2f".format(it.pnlPercent)}%)"
                } + st.totalsByCurrency.joinToString("") { t ->
                    "\nTotal (${t.currency}): invested ${"%.2f".format(t.invested)}, current ${"%.2f".format(t.current)}, P&L ${"%+.2f".format(t.pnlPercent)}%"
                } +
                    st.errors.joinToString("") { "\nError: $it" })
            }
        }
        "list_indicators" -> IndicatorType.entries.joinToString("\n") { "${it.name} (${it.category.label}): ${it.about}" }
        else -> error("Unknown tool $name")
    }

    companion object {
        const val INDMONEY_PREFIX = "indmoney__"
        /** Claude tool names must match ^[a-zA-Z0-9_-]{1,64}$. */
        fun claudeToolName(mcpName: String) = (INDMONEY_PREFIX + mcpName.replace(Regex("[^a-zA-Z0-9_-]"), "_")).take(64)
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}
