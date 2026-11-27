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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    /** Hero chart window (charts aren't modified by live ticks; only prices are). */
    val heroRange: ChartRange = ChartRange.D1,
    /** Live price for the hero symbol, shown in the hero header. */
    val heroQuote: com.capitol.app.data.live.LiveQuote? = null,
    val moversUniverse: com.capitol.app.analysis.Universe = com.capitol.app.analysis.Universe.NIFTY50,
    val gainers: List<com.capitol.app.data.live.LiveQuote> = emptyList(),
    /** True when movers come from the whole market (Yahoo screener); false while falling back to the index list. */
    val moversWholeMarket: Boolean = false,
    val losers: List<com.capitol.app.data.live.LiveQuote> = emptyList(),
    val streaming: Boolean = false,
    /** Epoch ms of the most recent live tick or snapshot shown. */
    val lastTick: Long? = null,
) {
    /** Average composite score of the watchlist — Capitol's "market mood". */
    val mood: Double get() = if (signals.isEmpty()) 0.0 else signals.map { it.compositeScore }.average()
    val advancers get() = watch.count { it.change >= 0 }
    val decliners get() = watch.count { it.change < 0 }
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var active = false
    /** Whole-market mover candidates per tab (today's top ~25 gainers + ~25 losers), refreshed every few minutes. */
    private val marketMovers = java.util.concurrent.ConcurrentHashMap<com.capitol.app.analysis.Universe, List<String>>()
    private var moversJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            c.live.quotes.collect { quotes -> applyLive(quotes) }
        }
        viewModelScope.launch { c.live.streaming.collect { _state.value = _state.value.copy(streaming = it) } }
        refresh()
        // Keep the home page in sync with the watchlist and default indicators.
        viewModelScope.launch {
            combine(
                c.watchlist.items.map { items -> items.map { it.symbol } },
                c.settings.settings.map { it.defaultIndicators to it.indicatorParams },
            ) { symbols, inds -> symbols to inds }
                .distinctUntilChanged()
                .drop(1) // the initial value is already handled by refresh()
                .collect { (symbols, inds) -> refreshWatch(symbols, inds) }
        }
    }

    /** Reloads only the watchlist-driven sections (movers, heatmap, mood, breadth). */
    private suspend fun refreshWatch(symbols: List<String>, ip: Pair<Set<com.capitol.app.analysis.IndicatorType>, com.capitol.app.analysis.IndicatorParams>) {
        val (inds, params) = ip
        val series = coroutineScope {
            symbols.map { s -> async { runCatching { c.market.history(s, ChartRange.Y1) }.getOrNull() } }.awaitAll().filterNotNull()
        }
        _state.value = _state.value.copy(
            watch = series.map { s -> QuoteSnapshot(s.symbol, s.name, s.regularMarketPrice, s.change, s.changePercent, s.currency, s.closes.takeLast(40)) },
            signals = series.map { TechnicalAnalyzer.analyze(it, inds, params) }.sortedByDescending { it.compositeScore },
        )
    }

    fun selectHero(index: MarketIndex) {
        _state.value = _state.value.copy(heroIndex = index)
        loadHero()
        updateWatch()
    }

    fun setHeroRange(r: ChartRange) { _state.value = _state.value.copy(heroRange = r); loadHero() }

    fun setMoversUniverse(u: com.capitol.app.analysis.Universe) {
        _state.value = _state.value.copy(moversUniverse = u, moversWholeMarket = marketMovers.containsKey(u))
        updateWatch()
        applyLive(c.live.quotes.value)
        if (active) startMovers()
    }

    /** While visible: fetch whole-market movers now and every [MOVERS_REFRESH_MS]; live ticks re-rank them in between. */
    private fun startMovers() {
        moversJob?.cancel()
        moversJob = viewModelScope.launch {
            while (true) {
                loadMarketMovers(_state.value.moversUniverse)
                kotlinx.coroutines.delay(MOVERS_REFRESH_MS)
            }
        }
    }

    private suspend fun loadMarketMovers(u: com.capitol.app.analysis.Universe) {
        val market = marketFor(u)
        val result = runCatching { c.market.dayMovers(market) }
            .onFailure { com.capitol.app.diagnostics.Diagnostics.info("Movers", "market screener failed (${market.label}): ${it.message}") }
            .getOrNull()
        val (g, l) = result ?: (emptyList<com.capitol.app.data.live.LiveQuote>() to emptyList())
        if (g.isEmpty() && l.isEmpty()) {
            // Keep any earlier whole-market list; otherwise fall back to the index constituents.
            if (_state.value.moversUniverse == u) _state.value = _state.value.copy(moversWholeMarket = marketMovers.containsKey(u))
            return
        }
        c.live.seed(g + l)
        marketMovers[u] = (g + l).map { it.symbol.uppercase() }.distinct()
        if (_state.value.moversUniverse == u) {
            _state.value = _state.value.copy(moversWholeMarket = true)
            updateWatch()
            applyLive(c.live.quotes.value)
        }
    }

    private fun loadHero(force: Boolean = false) = viewModelScope.launch {
        val st = _state.value
        runCatching { c.market.history(st.heroIndex.symbol, st.heroRange, force) }.onSuccess { s ->
            _state.value = _state.value.copy(hero = s, heroQuote = c.live.quotes.value[s.symbol])
        }
    }

    /** Called by the screen when it becomes visible / hidden: live updates only run while shown. */
    fun setActive(on: Boolean) {
        active = on
        if (on) { updateWatch(); startMovers() } else { moversJob?.cancel(); c.live.release(OWNER) }
    }

    private fun moverSymbols(): List<String> = _state.value.moversUniverse.let { marketMovers[it] ?: it.symbols }

    private fun updateWatch() {
        if (!active) return
        val st = _state.value
        c.live.watch(OWNER, INDICES.map { it.symbol } + st.heroIndex.symbol + st.watch.map { it.symbol } + moverSymbols())
    }

    private fun applyLive(quotes: Map<String, com.capitol.app.data.live.LiveQuote>) {
        if (quotes.isEmpty()) return
        val st = _state.value
        val (g, l) = com.capitol.app.data.live.LiveCandles.movers(quotes, moverSymbols())
        val relevant = (INDICES.map { it.symbol } + st.heroIndex.symbol + st.watch.map { it.symbol } + moverSymbols()).mapNotNull { quotes[it] }
        _state.value = st.copy(
            heroQuote = quotes[st.heroIndex.symbol] ?: st.heroQuote,
            indices = st.indices.map { (i, snap) -> i to (quotes[snap.symbol]?.let { com.capitol.app.data.live.LiveCandles.apply(snap, it) } ?: snap) },
            watch = st.watch.map { snap -> quotes[snap.symbol]?.let { com.capitol.app.data.live.LiveCandles.apply(snap, it) } ?: snap },
            gainers = g, losers = l,
            lastTick = relevant.maxOfOrNull { it.time }?.takeIf { it > 0 } ?: st.lastTick,
        )
    }

    override fun onCleared() { c.live.release(OWNER) }

    fun refresh(force: Boolean = false) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val settings = c.settings.settings.first()
        val heroJob = async { runCatching { c.market.history(_state.value.heroIndex.symbol, _state.value.heroRange, force) }.getOrNull() }
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
        val signals = watchSeries.map { TechnicalAnalyzer.analyze(it, settings.defaultIndicators, settings.indicatorParams) }
        _state.value = _state.value.copy(
            loading = false,
            hero = hero ?: _state.value.hero,
            indices = idx.map { (i, s) -> i to com.capitol.app.data.market.QuoteSnapshot(s.symbol, i.label, s.regularMarketPrice, s.change, s.changePercent, s.currency, s.closes) },
            watch = watchSeries.map { s -> QuoteSnapshot(s.symbol, s.name, s.regularMarketPrice, s.change, s.changePercent, s.currency, s.closes.takeLast(40)) },
            signals = signals.sortedByDescending { it.compositeScore },
            error = if (hero == null && idx.isEmpty()) "Couldn't reach the market data source. Check your connection or the data source in Settings." else null,
        )
        applyLive(c.live.quotes.value)
        updateWatch()
        if (force) c.live.snapshot((INDICES.map { it.symbol } + moverSymbols() + watchSymbols).distinct())
    }

    companion object {
        private const val OWNER = "home"
        private const val MOVERS_REFRESH_MS = 5 * 60_000L
        fun marketFor(u: com.capitol.app.analysis.Universe) =
            if (u == com.capitol.app.analysis.Universe.US_LARGE) com.capitol.app.data.market.MoversMarket.US
            else com.capitol.app.data.market.MoversMarket.INDIA
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
