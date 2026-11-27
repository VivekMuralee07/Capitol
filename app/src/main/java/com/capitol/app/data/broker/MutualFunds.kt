package com.capitol.app.data.broker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parses Zerodha Coin mutual-fund holdings — the Kite Connect `/mf/holdings` shape
 * (`tradingsymbol` (ISIN), `fund`, `folio`, `quantity`, `average_price`, `last_price`, `pnl`),
 * either as the API's `{"data": [...]}` or as the JSON text Kite MCP's `get_mf_holdings` returns.
 */
object MutualFunds {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<Holding> {
        val el = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()
            ?: extractJson(text)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?: return emptyList()
        return parse(el)
    }

    fun parse(el: JsonElement): List<Holding> {
        val arr: JsonArray = findRecords(el, "tradingsymbol", "isin") ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val isin = o.str("tradingsymbol") ?: o.str("isin") ?: return@mapNotNull null
            val qty = o.num("quantity") ?: return@mapNotNull null
            if (qty <= 0) return@mapNotNull null
            Holding(
                broker = Broker.COIN, tradingSymbol = isin, exchange = "MF", quantity = qty,
                averagePrice = o.num("average_price") ?: 0.0, lastPrice = o.num("last_price") ?: o.num("average_price") ?: 0.0,
                dayChangePercent = null, kind = AssetKind.MUTUAL_FUND, displayName = o.str("fund") ?: o.str("scheme_name"), folio = o.str("folio"),
            )
        }
    }

    /**
     * Finds the first array of objects having any of [keys], searching nested objects
     * (`{"data": [...]}`, `{"data": {"holdings": [...]}}`, a bare array…).
     */
    fun findRecords(el: JsonElement, vararg keys: String, depth: Int = 0): JsonArray? {
        if (depth > 5) return null
        return when (el) {
            is JsonArray -> if (el.any { o -> o is JsonObject && keys.any { it in o } }) el
                else el.firstNotNullOfOrNull { findRecords(it, *keys, depth = depth + 1) }
            is JsonObject -> el.values.firstNotNullOfOrNull { findRecords(it, *keys, depth = depth + 1) }
            else -> null
        }
    }

    /** MCP tools may wrap JSON in prose; pull out the first top-level array or object. */
    internal fun extractJson(text: String): String? {
        val start = text.indexOfFirst { it == '[' || it == '{' }.takeIf { it >= 0 } ?: return null
        val open = text[start]; val close = if (open == '[') ']' else '}'
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) { '"' -> inStr = true; open -> depth++; close -> if (--depth == 0) return text.substring(start, i + 1) }
        }
        return null
    }
}
