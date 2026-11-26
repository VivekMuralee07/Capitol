package com.capitol.app.data.market

interface MarketDataSource {
    suspend fun history(symbol: String, range: ChartRange): PriceSeries
    suspend fun search(query: String): List<SymbolMatch>
    suspend fun fundamentals(symbol: String): Fundamentals
    suspend fun news(symbol: String): List<NewsItem>
}

class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
