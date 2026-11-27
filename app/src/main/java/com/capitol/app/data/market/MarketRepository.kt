package com.capitol.app.data.market

import com.capitol.app.data.prefs.DataSourceMode
import com.capitol.app.data.prefs.SettingsStore
import com.capitol.app.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for price data: a 60-second memory cache, plus an on-disk copy of
 * the last good response for every symbol/range so the app still works offline or while
 * Yahoo is rate-limiting (the returned series then has [PriceSeries.cachedAt] set).
 */
class MarketRepository(
    private val yahoo: YahooFinanceDataSource,
    private val backend: YFinanceBackendDataSource,
    private val settings: SettingsStore,
    private val diskDir: File? = null,
) {
    private data class Entry(val at: Long, val series: PriceSeries)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun source(): MarketDataSource =
        when (settings.settings.first().dataSource) {
            DataSourceMode.DIRECT -> yahoo
            DataSourceMode.YFINANCE_SERVICE -> backend
        }

    suspend fun history(symbol: String, range: ChartRange = ChartRange.Y1, force: Boolean = false): PriceSeries {
        val key = "${symbol.uppercase()}|${range.name}"
        val now = System.currentTimeMillis()
        if (!force) cache[key]?.takeIf { now - it.at < CACHE_MS }?.let { return it.series }
        return try {
            val series = source().history(symbol, range)
            cache[key] = Entry(now, series)
            saveToDisk(key, series)
            series
        } catch (e: Exception) {
            Diagnostics.error("Market", "history($symbol, ${range.name}) failed: ${e.message}")
            loadFromDisk(key)?.also { cache[key] = Entry(now - CACHE_MS + 15_000, it) } ?: throw e
        }
    }

    suspend fun search(query: String): List<SymbolMatch> = source().search(query)

    private val fundCache = ConcurrentHashMap<String, Pair<Long, Fundamentals>>()

    /** Fundamentals change slowly: cached for 12 hours in memory and on disk. */
    suspend fun fundamentals(symbol: String, force: Boolean = false): Fundamentals {
        val key = "FUND|${symbol.uppercase()}"
        val now = System.currentTimeMillis()
        if (!force) {
            fundCache[key]?.takeIf { now - it.first < FUND_MS }?.let { return it.second }
            withContext(Dispatchers.IO) {
                fileFor(key)?.takeIf { it.exists() && now - it.lastModified() < FUND_MS }
                    ?.let { f -> runCatching { json.decodeFromString<Fundamentals>(f.readText()) }.getOrNull() }
            }?.let { fundCache[key] = now to it; return it }
        }
        val f = source().fundamentals(symbol)
        fundCache[key] = now to f
        withContext(Dispatchers.IO) { runCatching { fileFor(key)?.apply { parentFile?.mkdirs(); writeText(json.encodeToString(f)) } } }
        return f
    }
    suspend fun news(symbol: String): List<NewsItem> = source().news(symbol)

    /** Fetch many symbols in parallel; failures are dropped. */
    suspend fun snapshots(symbols: List<String>, range: ChartRange = ChartRange.M3): List<QuoteSnapshot> = coroutineScope {
        symbols.map { s -> async { runCatching { history(s, range).toSnapshot() }.getOrNull() } }
            .awaitAll().filterNotNull()
    }

    private fun fileFor(key: String) = diskDir?.let { File(it, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json") }

    private suspend fun saveToDisk(key: String, s: PriceSeries) = withContext(Dispatchers.IO) {
        runCatching { fileFor(key)?.apply { parentFile?.mkdirs(); writeText(json.encodeToString(s)) } }
    }

    private suspend fun loadFromDisk(key: String): PriceSeries? = withContext(Dispatchers.IO) {
        val f = fileFor(key)?.takeIf { it.exists() } ?: return@withContext null
        runCatching { json.decodeFromString<PriceSeries>(f.readText()).copy(cachedAt = f.lastModified()) }.getOrNull()
    }

    /** Whole-market top gainers/losers for today (Yahoo screener, on-device whichever data source is set). */
    suspend fun dayMovers(market: MoversMarket) = yahoo.dayMovers(market)

    companion object { private const val CACHE_MS = 60_000L; private const val FUND_MS = 12 * 3_600_000L }
}
