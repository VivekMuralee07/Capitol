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
