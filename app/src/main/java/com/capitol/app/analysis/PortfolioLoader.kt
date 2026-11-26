package com.capitol.app.analysis

import com.capitol.app.AppContainer
import com.capitol.app.data.broker.Holding
import com.capitol.app.data.market.ChartRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Fetches price history and sectors for holdings, then computes [PortfolioAnalytics]. */
object PortfolioLoader {
    suspend fun load(c: AppContainer, holdings: List<Holding>): List<CurrencyAnalytics> = coroutineScope {
        val settings = c.settings.settings.first()
        val gate = Semaphore(5)
        val symbols = holdings.map { it.yahooSymbol }.distinct()
        val series = symbols.map { s -> async { gate.withPermit { s to runCatching { c.market.history(s, ChartRange.Y1) }.getOrNull() } } }
        val sectors = symbols.map { s -> async { gate.withPermit { s to runCatching { c.market.fundamentals(s).sector }.getOrNull() } } }
        val seriesMap = series.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        val sectorMap = sectors.awaitAll().toMap()
        withContext(Dispatchers.Default) {
            PortfolioAnalytics.compute(holdings, seriesMap, sectorMap, settings.indicatorParams, settings.defaultIndicators)
        }
    }
}
