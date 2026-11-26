package com.capitol.app.data.broker

import com.capitol.app.data.prefs.SecretStore
import com.capitol.app.data.prefs.SecretStore.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Read-only client for INDmoney's INDstocks trading API (https://api-docs.indstocks.com).
 * Generate an access token at indstocks.com → API → "Generate Token" and paste it in
 * Capitol's Portfolio screen. The token is sent as the raw `Authorization` header.
 *
 * The response parser is deliberately lenient about field names so that minor API
 * revisions don't break the import. A CSV import fallback is offered in the UI for
 * users without API access.
 */
class IndMoneyClient(private val http: OkHttpClient, private val secrets: SecretStore) {

    private val json = Json { ignoreUnknownKeys = true }

    val isConnected get() = secrets.has(Key.INDMONEY_ACCESS_TOKEN)
    private val base get() = secrets.get(Key.INDMONEY_BASE_URL) ?: DEFAULT_BASE

    fun disconnect() = secrets.put(Key.INDMONEY_ACCESS_TOKEN, null)

    suspend fun holdings(): List<Holding> = withContext(Dispatchers.IO) {
        val token = secrets.get(Key.INDMONEY_ACCESS_TOKEN) ?: throw BrokerException("Add your INDstocks access token first")
        val req = Request.Builder().url("${base.trimEnd('/')}/portfolio/holdings")
            .header("Authorization", token)
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) throw BrokerException("INDmoney rejected the token (HTTP ${resp.code}). Tokens expire daily — generate a new one. If you set a static IP for the token, requests must come from it.")
            if (!resp.isSuccessful) throw BrokerException("INDmoney HTTP ${resp.code}")
            parseHoldings(json.parseToJsonElement(body))
        }
    }

    internal fun parseHoldings(root: JsonElement): List<Holding> {
        val arr: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray)
                ?: ((root["data"] as? JsonObject)?.values?.firstOrNull { it is JsonArray } as? JsonArray)
                ?: (root.values.firstOrNull { it is JsonArray } as? JsonArray)
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val sym = o.pick("trading_symbol", "tradingsymbol", "symbol", "security_name", "name") ?: return@mapNotNull null
            Holding(
                broker = Broker.INDMONEY,
                tradingSymbol = sym.substringBefore("-EQ").uppercase(),
                exchange = o.pick("exchange", "exchange_segment", "segment")?.let { if (it.contains("BSE", true)) "BSE" else "NSE" } ?: "NSE",
                quantity = o.pickNum("quantity", "qty", "holding_quantity", "total_quantity") ?: 0.0,
                averagePrice = o.pickNum("average_price", "avg_price", "average_buy_price", "buy_avg") ?: 0.0,
                lastPrice = o.pickNum("last_price", "ltp", "last_traded_price", "close_price", "market_price") ?: 0.0,
                dayChangePercent = o.pickNum("day_change_percentage", "day_change_percent", "change_percent"),
            )
        }
    }

    private fun JsonObject.pick(vararg keys: String) = keys.firstNotNullOfOrNull { str(it) }
    private fun JsonObject.pickNum(vararg keys: String) = keys.firstNotNullOfOrNull { num(it) }

    companion object {
        const val DEFAULT_BASE = "https://api.indstocks.com"

        /**
         * Parses a holdings CSV exported from INDmoney (or any broker). Expected columns
         * (case-insensitive, any order): symbol, quantity, avg price[, ltp][, exchange].
         */
        fun parseCsv(text: String): List<Holding> {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2) return emptyList()
            val header = lines.first().split(',').map { it.trim().lowercase().replace("\"", "") }
            fun idx(vararg names: String) = header.indexOfFirst { h -> names.any { h.contains(it) } }
            val iSym = idx("symbol", "instrument", "stock", "name")
            val iQty = idx("qty", "quantity")
            val iAvg = idx("avg", "average", "buy price")
            val iLtp = idx("ltp", "last", "current price", "market price")
            val iEx = idx("exchange")
            if (iSym < 0 || iQty < 0) return emptyList()
            return lines.drop(1).mapNotNull { line ->
                val c = line.split(',').map { it.trim().replace("\"", "") }
                val sym = c.getOrNull(iSym)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val qty = c.getOrNull(iQty)?.toDoubleOrNull() ?: return@mapNotNull null
                val avg = c.getOrNull(iAvg)?.toDoubleOrNull() ?: 0.0
                Holding(Broker.INDMONEY, sym.uppercase(), c.getOrNull(iEx)?.uppercase() ?: "NSE", qty, avg,
                    c.getOrNull(iLtp)?.toDoubleOrNull() ?: avg, null)
            }
        }
    }
}
