package com.capitol.app.data.market

interface MarketDataSource {
    suspend fun history(symbol: String, range: ChartRange): PriceSeries
    suspend fun search(query: String): List<SymbolMatch>
}

class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
