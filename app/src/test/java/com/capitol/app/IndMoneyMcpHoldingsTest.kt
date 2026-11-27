package com.capitol.app

import com.capitol.app.data.broker.Broker
import com.capitol.app.data.mcp.IndMoneyMcp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** INDmoney MCP holdings for the Portfolio tab (Indian + US stocks). */
class IndMoneyMcpHoldingsTest {
    @Test fun picksHoldingToolsUsFirstAndSkipsOthers() {
        val names = listOf("networth_snapshot", "networth_allocation_breakdown", "networth_holdings", "get_us_stocks_details",
            "lookup_ind_keys", "place_order", "get_mf_holdings", "get_transactions", "us_stocks")
        assertEquals(listOf("us_stocks", "networth_holdings"), IndMoneyMcp.pickHoldingToolNames(names))
        // Market-data tools seen on INDmoney's server are never treated as holdings.
        assertTrue(IndMoneyMcp.pickHoldingToolNames(listOf("get_us_stocks_details", "get_us_stocks_ohlc", "get_us_stock_price")).isEmpty())
        assertEquals(listOf("get_holdings"), IndMoneyMcp.pickHoldingToolNames(listOf("get_holdings", "cancel_order", "sell_stock")))
        assertTrue(IndMoneyMcp.pickHoldingToolNames(listOf("networth_snapshot", "search_stocks", "buy_us_stock")).isEmpty())
    }

    /** A tool that needs symbols (INDmoney's market-data tools) is skipped even if its name looks like holdings. */
    @Test fun skipsToolsNeedingInput() {
        fun tool(name: String, schema: String) = com.capitol.app.data.mcp.McpTool(name, "", kotlinx.serialization.json.Json.parseToJsonElement(schema) as kotlinx.serialization.json.JsonObject)
        val tools = listOf(
            tool("get_us_stocks", """{"type":"object","properties":{"symbols":{"type":"array"}},"required":["symbols"]}"""),
            tool("us_holdings", """{"type":"object","properties":{"mode":{"type":"string","enum":["holdings","orders"]}},"required":["mode"]}"""),
            tool("networth_holdings", """{"type":"object","properties":{}}"""),
        )
        assertEquals(listOf("us_holdings", "networth_holdings"), IndMoneyMcp.pickHoldingTools(tools))
    }

    @Test fun parsesUsToolPreferringUsdFields() {
        val reply = """Here are your US stocks: {"data":{"holdings":[
            {"ticker":"MRVL","name":"Marvell Technology","quantity":"3.5","avg_price_usd":"70.10","current_price_usd":"$88.20","current_price":"7400"},
            {"ticker":"AAPL","quantity":0,"avg_price_usd":100}]}}"""
        val h = IndMoneyMcp.parseHoldings(reply, usHint = true).single()
        assertEquals("MRVL", h.tradingSymbol); assertTrue(h.isUs); assertEquals("USD", h.currency)
        assertEquals(3.5, h.quantity, 0.0); assertEquals(70.10, h.averagePrice, 1e-9); assertEquals(88.20, h.lastPrice, 1e-9)
        assertEquals(Broker.INDMONEY, h.broker); assertEquals("MRVL", h.yahooSymbol); assertEquals("Marvell Technology", h.displayName)
    }

    @Test fun parsesMixedNetWorthReply() {
        val reply = """{"stocks":[{"symbol":"INFY","exchange":"NSE","qty":10,"avg_price":1400,"ltp":1500}],
            "us_stocks":[{"symbol":"AAPL","quantity":2,"invested_value":300,"current_value":400}],
            "mutual_funds":[{"isin":"INF879O01027","units":12.5}]}"""
        val hs = IndMoneyMcp.parseHoldings(reply, usHint = false).associateBy { it.tradingSymbol }
        assertEquals(setOf("INFY", "AAPL"), hs.keys)
        assertEquals("INFY.NS", hs.getValue("INFY").yahooSymbol); assertEquals("INR", hs.getValue("INFY").currency)
        val aapl = hs.getValue("AAPL")
        assertTrue(aapl.isUs); assertEquals(150.0, aapl.averagePrice, 1e-9); assertEquals(200.0, aapl.lastPrice, 1e-9)
    }

    @Test fun currencyFieldOverridesHintAndJunkIsIgnored() {
        val inr = IndMoneyMcp.parseHoldings("""[{"symbol":"TCS","quantity":1,"avg_price":3000,"currency":"INR"}]""", usHint = true).single()
        assertEquals("NSE", inr.exchange)
        assertTrue(IndMoneyMcp.parseHoldings("You have no holdings.", usHint = true).isEmpty())
        assertTrue(IndMoneyMcp.parseHoldings("""{"total_value":12345}""", usHint = false).isEmpty())
    }

    /** The tool list INDmoney's live server returned (from a user's screenshot). */
    @Test fun picksFromInDmoneysRealToolList() {
        val live = listOf("indian_stocks_sips", "networth_snapshot", "networth_allocation_breakdown", "networth_holdings",
            "get_indian_stocks_ohlc", "get_indian_stocks_details", "get_indian_stocks_movers", "get_indian_stocks_option_chain",
            "get_indian_stocks_greeks_history", "lookup_ind_keys", "user_watchlist", "get_mf_funds_details", "get_mf_by_category",
            "mf_sips", "get_us_stocks_details", "get_us_stocks_movers", "get_us_stocks_ohlc", "us_stocks_sips", "get_family_members",
            "get_family_portfolio", "get_family_asset_holdings", "save_analysis")
        val picked = IndMoneyMcp.pickHoldingToolNames(live)
        assertEquals("networth_holdings", picked.first())
        assertTrue(picked.toString(), picked.none { "movers" in it || "sips" in it || "ohlc" in it || "details" in it || it == "save_analysis" })
    }

    @Test fun callsOncePerStockAssetType() {
        val tool = com.capitol.app.data.mcp.McpTool("networth_holdings", "", kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"object","properties":{"asset_class":{"type":"string","enum":["indian_stocks","us_stocks","mutual_funds","gold"]}}}""") as kotlinx.serialization.json.JsonObject)
        val plans = IndMoneyMcp.callPlans(tool)
        assertEquals(listOf("""{"asset_class":"indian_stocks"}""" to false, """{"asset_class":"us_stocks"}""" to true),
            plans.map { it.first.toString() to it.second })
    }

    @Test fun parsesMixedRowsTablesAndNames() {
        // Mixed net-worth rows: only stocks kept; US flagged from the row's asset type.
        val mixed = """{"holdings":[
            {"asset_type":"US_STOCKS","symbol":"NVDA","quantity":1.2,"avg_price":120,"current_price":180},
            {"asset_type":"MUTUAL_FUND","name":"Parag Parikh Flexi Cap","units":50,"nav":80},
            {"asset_type":"INDIAN_STOCKS","name":"Infosys Ltd","quantity":4,"avg_price":1400,"ltp":1500}]}"""
        val hs = IndMoneyMcp.parseHoldings(mixed, usHint = false)
        assertEquals(listOf("NVDA", "INFOSYS LTD"), hs.map { it.tradingSymbol })
        assertTrue(hs[0].isUs); assertEquals("INR", hs[1].currency); assertEquals("Infosys Ltd", hs[1].displayName)
        // Markdown table reply.
        val table = """Your US stocks:
            | Symbol | Quantity | Avg Price | Current Price |
            |---|---|---|---|
            | MRVL | 3 | $70.10 | $88.20 |
            | AAPL | 0.5 | $150 | $210 |"""
        val t = IndMoneyMcp.parseHoldings(table, usHint = true)
        assertEquals(listOf("MRVL", "AAPL"), t.map { it.tradingSymbol })
        assertEquals(88.20, t[0].lastPrice, 1e-9); assertTrue(t.all { it.isUs })
    }
}
