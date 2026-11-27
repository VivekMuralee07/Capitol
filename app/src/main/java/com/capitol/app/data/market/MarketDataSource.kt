package com.capitol.app.data.market

interface MarketDataSource {
    suspend fun history(symbol: String, range: ChartRange): PriceSeries
    suspend fun search(query: String): List<SymbolMatch>
    suspend fun fundamentals(symbol: String): Fundamentals
    suspend fun news(symbol: String): List<NewsItem>
    /** Latest price + day change for many symbols in one go. */
    suspend fun quotes(symbols: List<String>): List<com.capitol.app.data.live.LiveQuote>
}

class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A whole market for the day's top movers (Yahoo screener region + exchanges). */
enum class MoversMarket(val label: String, val region: String, val exchanges: List<String>, val minPrice: Double) {
    INDIA("India (NSE)", "in", listOf("NSI"), 5.0),
    US("US", "us", emptyList(), 5.0),
}
