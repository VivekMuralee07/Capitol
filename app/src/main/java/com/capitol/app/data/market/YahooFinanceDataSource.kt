package com.capitol.app.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Native Kotlin port of the Yahoo Finance endpoints that
 * [yfinance](https://github.com/ranaroussi/yfinance) uses internally
 * (`/v8/finance/chart` for `Ticker.history()` and `/v1/finance/search` for
 * `yf.Search`). This lets Capitol work fully on-device, without a server.
 *
 * For the full yfinance feature set use [YFinanceBackendDataSource], which talks
 * to the bundled Python service in `/backend` that calls yfinance directly.
 */
class YahooFinanceDataSource(private val http: OkHttpClient) : MarketDataSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun history(symbol: String, range: ChartRange): PriceSeries = withContext(Dispatchers.IO) {
        val url = "$BASE/v8/finance/chart/${symbol.trim().uppercase()}".toHttpUrl().newBuilder()
            .addQueryParameter("range", range.period)
            .addQueryParameter("interval", range.interval)
            .addQueryParameter("includePrePost", "false")
            .addQueryParameter("events", "div,splits")
            .build()
        val body = get(url.toString())
        val root = json.parseToJsonElement(body).jsonObject
        val chart = root["chart"]?.jsonObject ?: throw MarketDataException("Malformed response")
        val error = chart["error"]
        if (error != null && error !is JsonNull) {
            val desc = error.jsonObject["description"]?.jsonPrimitive?.content ?: "Unknown error"
            throw MarketDataException("$symbol: $desc")
        }
        val result = (chart["result"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw MarketDataException("No data found for $symbol")
        parseChart(result, range)
    }

    override suspend fun search(query: String): List<SymbolMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "$BASE/v1/finance/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("quotesCount", "12")
            .addQueryParameter("newsCount", "0")
            .build()
        val root = json.parseToJsonElement(get(url.toString())).jsonObject
        val quotes = root["quotes"] as? JsonArray ?: return@withContext emptyList()
        quotes.mapNotNull { el ->
            val o = el.jsonObject
            val sym = o.str("symbol") ?: return@mapNotNull null
            SymbolMatch(
                symbol = sym,
                name = o.str("longname") ?: o.str("shortname") ?: sym,
                exchange = o.str("exchDisp") ?: o.str("exchange") ?: "",
                type = o.str("quoteType") ?: "",
            )
        }
    }

    private fun parseChart(result: JsonObject, range: ChartRange): PriceSeries {
        val meta = result["meta"]!!.jsonObject
        val timestamps = (result["timestamp"] as? JsonArray)?.map { it.jsonPrimitive.longOrNull } ?: emptyList()
        val quote = result["indicators"]?.jsonObject?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject
        fun col(name: String) = (quote?.get(name) as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull } ?: emptyList()
        val o = col("open"); val h = col("high"); val l = col("low"); val c = col("close"); val v = col("volume")

        val candles = timestamps.indices.mapNotNull { i ->
            val t = timestamps[i] ?: return@mapNotNull null
            val close = c.getOrNull(i) ?: return@mapNotNull null
            Candle(
                time = t,
                open = o.getOrNull(i) ?: close,
                high = h.getOrNull(i) ?: close,
                low = l.getOrNull(i) ?: close,
                close = close,
                volume = v.getOrNull(i) ?: 0.0,
            )
        }
        val symbol = meta.str("symbol") ?: ""
        val price = meta.dbl("regularMarketPrice") ?: candles.lastOrNull()?.close ?: 0.0
        // meta.chartPreviousClose is the close *before the range starts*, so only trust it for 1-day charts.
        val prev = meta.dbl("previousClose")
            ?: inferPreviousClose(candles, range.isIntraday, if (range == ChartRange.D1) meta.dbl("chartPreviousClose") else null)
            ?: price
        return PriceSeries(
            symbol = symbol,
            name = meta.str("longName") ?: meta.str("shortName") ?: symbol,
            currency = meta.str("currency") ?: "",
            exchange = meta.str("fullExchangeName") ?: meta.str("exchangeName") ?: "",
            candles = candles,
            regularMarketPrice = price,
            previousClose = prev,
        )
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful && body.isBlank()) {
                    throw MarketDataException("Yahoo Finance returned HTTP ${resp.code}")
                }
                return body
            }
        } catch (e: IOException) {
            throw MarketDataException("Network error: ${e.message}", e)
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull

    companion object {
        private const val BASE = "https://query2.finance.yahoo.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
