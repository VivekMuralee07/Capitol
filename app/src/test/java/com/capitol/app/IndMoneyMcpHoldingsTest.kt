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
        assertEquals(listOf("get_us_stocks_details", "us_stocks", "networth_holdings"), IndMoneyMcp.pickHoldingTools(names))
        assertEquals(listOf("get_holdings"), IndMoneyMcp.pickHoldingTools(listOf("get_holdings", "cancel_order", "sell_stock")))
        assertTrue(IndMoneyMcp.pickHoldingTools(listOf("networth_snapshot", "search_stocks", "buy_us_stock")).isEmpty())
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
}
