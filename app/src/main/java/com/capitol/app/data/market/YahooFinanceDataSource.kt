package com.capitol.app.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
class YahooFinanceDataSource(
    baseHttp: OkHttpClient,
    private val base: String = BASE,
    private val cookieUrl: String = "https://fc.yahoo.com",
) : MarketDataSource {

    private val http = baseHttp.newBuilder().cookieJar(MemoryCookieJar()).build()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var crumb: String? = null

    override suspend fun history(symbol: String, range: ChartRange): PriceSeries = withContext(Dispatchers.IO) {
        val url = "$base/v8/finance/chart/${symbol.trim().uppercase()}".toHttpUrl().newBuilder()
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
        val url = "$base/v1/finance/search".toHttpUrl().newBuilder()
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

    /** Key statistics via `quoteSummary` (the endpoint behind yfinance's `Ticker.info`). */
    override suspend fun fundamentals(symbol: String): Fundamentals = withContext(Dispatchers.IO) {
        val sym = symbol.trim().uppercase()
        fun fetch(): JsonObject? {
            val c = crumb ?: obtainCrumb() ?: return null
            val url = "$base/v10/finance/quoteSummary/$sym".toHttpUrl().newBuilder()
                .addQueryParameter("modules", "price,summaryDetail,defaultKeyStatistics,assetProfile,financialData,calendarEvents")
                .addQueryParameter("crumb", c).build()
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
            http.executeWithRetry(req).use { resp ->
                if (resp.code == 401 || resp.code == 403) { crumb = null; return null }
                if (!resp.isSuccessful) return null
                return (json.parseToJsonElement(resp.body!!.string()).jsonObject["quoteSummary"] as? JsonObject)
                    ?.get("result")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
            }
        }
        val r = fetch() ?: fetch() ?: throw MarketDataException("Fundamentals unavailable for $sym")
        parseQuoteSummary(sym, r)
    }

    override suspend fun news(symbol: String): List<NewsItem> = withContext(Dispatchers.IO) {
        val url = "$base/v1/finance/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", symbol.trim()).addQueryParameter("quotesCount", "0")
            .addQueryParameter("newsCount", "10").build()
        val root = json.parseToJsonElement(get(url.toString())).jsonObject
        (root["news"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            NewsItem(
                title = o.str("title") ?: return@mapNotNull null,
                publisher = o.str("publisher") ?: "",
                link = o.str("link") ?: return@mapNotNull null,
                publishedAt = (o["providerPublishTime"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }

    private fun obtainCrumb(): String? = runCatching {
        // Same handshake as yfinance: get Yahoo's consent cookie, then exchange it for a crumb.
        http.newCall(Request.Builder().url(cookieUrl).header("User-Agent", USER_AGENT).build()).execute().close()
        http.newCall(Request.Builder().url("$base/v1/test/getcrumb").header("User-Agent", USER_AGENT).build()).execute().use { r ->
            r.body?.string()?.trim()?.takeIf { r.isSuccessful && it.isNotEmpty() && !it.contains('<') }
        }
    }.getOrNull()?.also { crumb = it }

    internal fun parseQuoteSummary(sym: String, r: JsonObject): Fundamentals {
        fun mod(n: String) = r[n] as? JsonObject
        fun raw(m: JsonObject?, k: String): Double? = when (val v = m?.get(k)) {
            is JsonObject -> (v["raw"] as? JsonPrimitive)?.doubleOrNull
            is JsonPrimitive -> v.doubleOrNull
            else -> null
        }
        val price = mod("price"); val sd = mod("summaryDetail"); val ks = mod("defaultKeyStatistics")
        val ap = mod("assetProfile"); val fd = mod("financialData"); val cal = mod("calendarEvents")
        val now = System.currentTimeMillis() / 1000
        val earnings = ((cal?.get("earnings") as? JsonObject)?.get("earningsDate") as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("raw") as? JsonPrimitive)?.longOrNull }
            ?.filter { it >= now - 86_400 }?.minOrNull()
        return Fundamentals(
            symbol = sym,
            name = price?.str("longName") ?: price?.str("shortName"),
            sector = ap?.str("sector"), industry = ap?.str("industry"),
            currency = price?.str("currency"),
            marketCap = raw(price, "marketCap") ?: raw(sd, "marketCap"),
            trailingPE = raw(sd, "trailingPE"), forwardPE = raw(sd, "forwardPE") ?: raw(ks, "forwardPE"),
            priceToBook = raw(ks, "priceToBook"), dividendYield = raw(sd, "dividendYield"),
            beta = raw(sd, "beta") ?: raw(ks, "beta"),
            fiftyTwoWeekHigh = raw(sd, "fiftyTwoWeekHigh"), fiftyTwoWeekLow = raw(sd, "fiftyTwoWeekLow"),
            averageVolume = raw(sd, "averageVolume"),
            profitMargins = raw(fd, "profitMargins") ?: raw(ks, "profitMargins"),
            returnOnEquity = raw(fd, "returnOnEquity"), debtToEquity = raw(fd, "debtToEquity"),
            summary = ap?.str("longBusinessSummary"),
            nextEarningsDate = earnings,
            exDividendDate = raw(cal, "exDividendDate")?.toLong() ?: raw(sd, "exDividendDate")?.toLong(),
            dividendDate = raw(cal, "dividendDate")?.toLong(),
        )
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
            http.executeWithRetry(req).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 429) throw MarketDataException("Yahoo Finance is rate-limiting requests — try again in a minute")
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
