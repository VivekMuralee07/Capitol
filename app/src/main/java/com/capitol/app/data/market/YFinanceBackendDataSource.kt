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
            .apply { range.days?.let { addQueryParameter("days", it.toString()) } ?: addQueryParameter("period", range.period) }
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

    @Serializable
    private data class NewsDto(val news: List<NewsItem> = emptyList())

    override suspend fun fundamentals(symbol: String): Fundamentals = withContext(Dispatchers.IO) {
        val url = endpoint("info").addQueryParameter("symbol", symbol.trim().uppercase()).build()
        json.decodeFromString<Fundamentals>(get(url.toString())).copy(symbol = symbol.trim().uppercase())
    }

    override suspend fun news(symbol: String): List<NewsItem> = withContext(Dispatchers.IO) {
        val url = endpoint("news").addQueryParameter("symbol", symbol.trim().uppercase()).build()
        json.decodeFromString<NewsDto>(get(url.toString())).news
    }

    @Serializable
    private data class QuoteDto(val symbol: String, val price: Double, val change: Double = 0.0, val change_percent: Double = 0.0,
                                val day_high: Double? = null, val day_low: Double? = null, val volume: Double? = null, val name: String? = null, val currency: String? = null)
    @Serializable
    private data class QuotesDto(val quotes: List<QuoteDto> = emptyList())

    override suspend fun quotes(symbols: List<String>): List<com.capitol.app.data.live.LiveQuote> = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) return@withContext emptyList()
        val url = endpoint("quotes").addQueryParameter("symbols", symbols.distinct().joinToString(",")).build()
        json.decodeFromString<QuotesDto>(get(url.toString())).quotes.map {
            com.capitol.app.data.live.LiveQuote(it.symbol, it.price, it.change, it.change_percent, it.day_high, it.day_low, it.volume?.toLong(),
                System.currentTimeMillis(), it.name, it.currency)
        }
    }

    private fun endpoint(path: String) = "${baseUrl().trimEnd('/')}/$path".toHttpUrl().newBuilder()

    private fun get(url: String): String = try {
        http.executeWithRetry(Request.Builder().url(url).build()).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw MarketDataException("yfinance service HTTP ${resp.code}: $body")
            body
        }
    } catch (e: IOException) {
        throw MarketDataException("Cannot reach yfinance service at ${baseUrl()}: ${e.message}", e)
    }
}
