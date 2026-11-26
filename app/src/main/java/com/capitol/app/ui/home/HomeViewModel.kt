package com.capitol.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.analysis.AnalysisReport
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.QuoteSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MarketIndex(val symbol: String, val label: String, val region: String)

data class HomeState(
    val loading: Boolean = true,
    val hero: PriceSeries? = null,
    val heroIndex: MarketIndex = HomeViewModel.INDICES.first(),
    val indices: List<Pair<MarketIndex, QuoteSnapshot>> = emptyList(),
    val watch: List<QuoteSnapshot> = emptyList(),
    val signals: List<AnalysisReport> = emptyList(),
    val error: String? = null,
) {
    /** Average composite score of the watchlist — Capitol's "market mood". */
    val mood: Double get() = if (signals.isEmpty()) 0.0 else signals.map { it.compositeScore }.average()
    val advancers get() = watch.count { it.change >= 0 }
    val decliners get() = watch.count { it.change < 0 }
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init { refresh() }

    fun selectHero(index: MarketIndex) {
        _state.value = _state.value.copy(heroIndex = index)
        viewModelScope.launch {
            runCatching { c.market.history(index.symbol, ChartRange.M3) }.onSuccess {
                _state.value = _state.value.copy(hero = it)
            }
        }
    }

    fun refresh(force: Boolean = false) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val settings = c.settings.settings.first()
        val heroJob = async { runCatching { c.market.history(_state.value.heroIndex.symbol, ChartRange.M3, force) }.getOrNull() }
        val idxJob = async {
            INDICES.map { idx -> async { runCatching { c.market.history(idx.symbol, ChartRange.M1, force) }.getOrNull()?.let { idx to it } } }
                .awaitAll().filterNotNull()
        }
        val watchSymbols = c.watchlist.current().map { it.symbol }
        val watchJob = async {
            watchSymbols.map { s -> async { runCatching { c.market.history(s, ChartRange.Y1, force) }.getOrNull() } }.awaitAll().filterNotNull()
        }
        val hero = heroJob.await()
        val idx = idxJob.await()
        val watchSeries = watchJob.await()
        val signals = watchSeries.map { TechnicalAnalyzer.analyze(it, settings.defaultIndicators) }
        _state.value = _state.value.copy(
            loading = false,
            hero = hero ?: _state.value.hero,
            indices = idx.map { (i, s) -> i to com.capitol.app.data.market.QuoteSnapshot(s.symbol, i.label, s.regularMarketPrice, s.change, s.changePercent, s.currency, s.closes) },
            watch = watchSeries.map { s -> QuoteSnapshot(s.symbol, s.name, s.regularMarketPrice, s.change, s.changePercent, s.currency, s.closes.takeLast(40)) },
            signals = signals.sortedByDescending { it.compositeScore },
            error = if (hero == null && idx.isEmpty()) "Couldn't reach the market data source. Check your connection or the data source in Settings." else null,
        )
    }

    companion object {
        val INDICES = listOf(
            MarketIndex("^NSEI", "NIFTY 50", "India"),
            MarketIndex("^BSESN", "SENSEX", "India"),
            MarketIndex("^NSEBANK", "BANK NIFTY", "India"),
            MarketIndex("^GSPC", "S&P 500", "US"),
            MarketIndex("^IXIC", "NASDAQ", "US"),
            MarketIndex("GC=F", "Gold", "Commodity"),
            MarketIndex("INR=X", "USD/INR", "FX"),
            MarketIndex("BTC-USD", "Bitcoin", "Crypto"),
        )
    }
}
