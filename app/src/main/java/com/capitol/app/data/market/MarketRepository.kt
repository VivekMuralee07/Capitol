package com.capitol.app.data.market

import com.capitol.app.data.prefs.DataSourceMode
import com.capitol.app.data.prefs.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/** Single entry point for price data, with a short-lived in-memory cache. */
class MarketRepository(
    private val yahoo: YahooFinanceDataSource,
    private val backend: YFinanceBackendDataSource,
    private val settings: SettingsStore,
) {
    private data class Entry(val at: Long, val series: PriceSeries)
    private val cache = ConcurrentHashMap<String, Entry>()

    private suspend fun source(): MarketDataSource =
        when (settings.settings.first().dataSource) {
            DataSourceMode.DIRECT -> yahoo
            DataSourceMode.YFINANCE_SERVICE -> backend
        }

    suspend fun history(symbol: String, range: ChartRange = ChartRange.Y1, force: Boolean = false): PriceSeries {
        val key = "${symbol.uppercase()}|${range.name}"
        val now = System.currentTimeMillis()
        if (!force) cache[key]?.takeIf { now - it.at < CACHE_MS }?.let { return it.series }
        val series = source().history(symbol, range)
        cache[key] = Entry(now, series)
        return series
    }

    suspend fun search(query: String): List<SymbolMatch> = source().search(query)

    /** Fetch many symbols in parallel; failures are dropped. */
    suspend fun snapshots(symbols: List<String>, range: ChartRange = ChartRange.M3): List<QuoteSnapshot> = coroutineScope {
        symbols.map { s -> async { runCatching { history(s, range).toSnapshot() }.getOrNull() } }
            .awaitAll().filterNotNull()
    }

    companion object { private const val CACHE_MS = 60_000L }
}
