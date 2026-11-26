package com.capitol.app.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Talks to the Capitol Python service (see `/backend`), which wraps
 * [yfinance](https://github.com/ranaroussi/yfinance) directly:
 * `yf.Ticker(symbol).history(period, interval)` and `yf.Search(query)`.
 */
class YFinanceBackendDataSource(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
) : MarketDataSource {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class HistoryDto(
        val symbol: String,
        val name: String = "",
        val currency: String = "",
        val exchange: String = "",
        val price: Double? = null,
        val previous_close: Double? = null,
        val candles: List<Candle> = emptyList(),
    )

    @Serializable
    private data class SearchDto(val results: List<SymbolMatch> = emptyList())

    override suspend fun history(symbol: String, range: ChartRange): PriceSeries = withContext(Dispatchers.IO) {
        val url = endpoint("history")
            .addQueryParameter("symbol", symbol.trim().uppercase())
            .addQueryParameter("period", range.period)
            .addQueryParameter("interval", range.interval)
            .build()
        val dto = json.decodeFromString<HistoryDto>(get(url.toString()))
        if (dto.candles.isEmpty()) throw MarketDataException("No data found for $symbol")
        val price = dto.price ?: dto.candles.last().close
        PriceSeries(
            symbol = dto.symbol,
            name = dto.name.ifBlank { dto.symbol },
            currency = dto.currency,
            exchange = dto.exchange,
            candles = dto.candles,
            regularMarketPrice = price,
            previousClose = dto.previous_close ?: inferPreviousClose(dto.candles, range.isIntraday, null) ?: price,
        )
    }

    override suspend fun search(query: String): List<SymbolMatch> = withContext(Dispatchers.IO) {
        val url = endpoint("search").addQueryParameter("q", query).build()
        json.decodeFromString<SearchDto>(get(url.toString())).results
    }

    private fun endpoint(path: String) = "${baseUrl().trimEnd('/')}/$path".toHttpUrl().newBuilder()

    private fun get(url: String): String = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw MarketDataException("yfinance service HTTP ${resp.code}: $body")
            body
        }
    } catch (e: IOException) {
        throw MarketDataException("Cannot reach yfinance service at ${baseUrl()}: ${e.message}", e)
    }
}
