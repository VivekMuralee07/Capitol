package com.capitol.app.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.analysis.AnalysisReport
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.SymbolMatch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AnalysisState(
    val symbol: String? = null,
    val range: ChartRange = ChartRange.Y1,
    val selected: Set<IndicatorType> = IndicatorType.DEFAULTS,
    val series: PriceSeries? = null,
    val report: AnalysisReport? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val suggestions: List<SymbolMatch> = emptyList(),
    val inWatchlist: Boolean = false,
    val params: com.capitol.app.analysis.IndicatorParams = com.capitol.app.analysis.IndicatorParams.DEFAULT,
    /** Multi-timeframe confirmation: verdicts on daily and weekly bars with the same indicators. */
    val timeframes: List<Pair<String, AnalysisReport>> = emptyList(),
    val fundamentals: com.capitol.app.data.market.Fundamentals? = null,
    val news: List<com.capitol.app.data.market.NewsItem> = emptyList(),
    val backtest: com.capitol.app.analysis.BacktestResult? = null,
    val backtestProgress: Float? = null,
    val backtestHorizon: Int = 20,
) {
    /** True when the daily and weekly verdicts point the same way. */
    val timeframesAligned: Boolean? get() = timeframes.takeIf { it.size >= 2 }?.map { it.second.verdict }?.let { v ->
        when { v.all { it.isBullish } || v.all { it.isBearish } -> true; v.any { it.isBullish } && v.any { it.isBearish } -> false; else -> null }
    }
}

object Presets {
    val TREND = setOf(IndicatorType.SMA, IndicatorType.EMA, IndicatorType.ADX, IndicatorType.SUPERTREND, IndicatorType.ICHIMOKU, IndicatorType.PSAR)
    val MOMENTUM = setOf(IndicatorType.RSI, IndicatorType.MACD, IndicatorType.STOCHASTIC, IndicatorType.CCI, IndicatorType.WILLIAMS_R, IndicatorType.ROC)
    val SWING = setOf(IndicatorType.SUPERTREND, IndicatorType.RSI, IndicatorType.MACD, IndicatorType.BOLLINGER, IndicatorType.VOLUME, IndicatorType.FIBONACCI)
    val VOLUME = setOf(IndicatorType.OBV, IndicatorType.VWAP, IndicatorType.MFI, IndicatorType.VOLUME)
    val ALL = IndicatorType.entries.toSet()
    val list = listOf("Swing trader" to SWING, "Trend" to TREND, "Momentum" to MOMENTUM, "Volume" to VOLUME, "Everything" to ALL)
}

@OptIn(FlowPreview::class)
class AnalysisViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()
    private val queries = MutableStateFlow("")
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val st = c.settings.settings.first()
            _state.value = _state.value.copy(selected = st.defaultIndicators, params = st.indicatorParams)
        }
        viewModelScope.launch {
            c.settings.settings.map { it.indicatorParams }.distinctUntilChanged().collect { p ->
                if (p != _state.value.params) { _state.value = _state.value.copy(params = p, backtest = null); recompute(); loadTimeframes() }
            }
        }
        viewModelScope.launch {
            queries.debounce(300).distinctUntilChanged().filter { it.length >= 2 }.collect { q ->
                val res = runCatching { c.market.search(q) }.getOrDefault(emptyList())
                if (_state.value.query == q) _state.value = _state.value.copy(suggestions = res)
            }
        }
        viewModelScope.launch {
            c.watchlist.items.collect { items ->
                val s = _state.value.symbol
                _state.value = _state.value.copy(inWatchlist = s != null && items.any { it.symbol.equals(s, true) })
            }
        }
    }

    fun onQuery(q: String) {
        _state.value = _state.value.copy(query = q, suggestions = if (q.length < 2) emptyList() else _state.value.suggestions)
        queries.value = q
    }

    fun open(symbol: String) {
        val s = symbol.trim().uppercase()
        if (s.isEmpty()) return
        _state.value = _state.value.copy(symbol = s, query = "", suggestions = emptyList(),
            fundamentals = null, news = emptyList(), backtest = null, timeframes = emptyList())
        load()
        loadExtras(s)
    }

    fun setRange(r: ChartRange) { _state.value = _state.value.copy(range = r); load() }

    fun toggle(t: IndicatorType) {
        val cur = _state.value.selected
        setIndicators(if (t in cur) cur - t else cur + t)
    }

    fun setIndicators(set: Set<IndicatorType>) {
        _state.value = _state.value.copy(selected = set, backtest = null)
        recompute()
        loadTimeframes()
    }

    private var backtestJob: Job? = null

    /** Walk-forward backtest of the current indicator set on 2 years of daily bars. */
    fun runBacktest(horizon: Int = _state.value.backtestHorizon) {
        val sym = _state.value.symbol ?: return
        backtestJob?.cancel()
        backtestJob = viewModelScope.launch {
            _state.value = _state.value.copy(backtestProgress = 0f, backtest = null, backtestHorizon = horizon)
            val result = runCatching {
                val series = c.market.history(sym, ChartRange.Y2)
                val st = _state.value
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.capitol.app.analysis.Backtester.run(series, st.selected, st.params, horizon = horizon, periodLabel = "the last 2 years") { p ->
                        _state.value = _state.value.copy(backtestProgress = p)
                    }
                }
            }
            _state.value = _state.value.copy(backtestProgress = null, backtest = result.getOrNull(),
                error = result.exceptionOrNull()?.let { "Backtest failed: ${it.message}" } ?: _state.value.error)
        }
    }

    private var tfJob: Job? = null

    /** Daily + weekly verdicts with the selected indicators (multi-timeframe confirmation). */
    private fun loadTimeframes() {
        val sym = _state.value.symbol ?: return
        tfJob?.cancel()
        tfJob = viewModelScope.launch {
            val st = _state.value
            val frames = listOf("Daily" to ChartRange.Y1, "Weekly" to ChartRange.Y5).mapNotNull { (label, r) ->
                runCatching { label to TechnicalAnalyzer.analyze(c.market.history(sym, r), st.selected, st.params) }.getOrNull()
            }
            _state.value = _state.value.copy(timeframes = frames)
        }
    }

    private fun loadExtras(sym: String) {
        viewModelScope.launch {
            runCatching { c.market.fundamentals(sym) }.onSuccess { if (_state.value.symbol == sym) _state.value = _state.value.copy(fundamentals = it) }
                .onFailure { com.capitol.app.diagnostics.Diagnostics.info("Analysis", "fundamentals($sym): ${it.message}") }
        }
        viewModelScope.launch {
            runCatching { c.market.news(sym) }.onSuccess { if (_state.value.symbol == sym) _state.value = _state.value.copy(news = it) }
        }
        loadTimeframes()
    }

    fun saveAsDefault() = viewModelScope.launch { c.settings.setDefaultIndicators(_state.value.selected) }

    fun toggleWatchlist() = viewModelScope.launch {
        val st = _state.value
        val s = st.symbol ?: return@launch
        if (st.inWatchlist) c.watchlist.remove(s) else c.watchlist.add(s, st.series?.name ?: s)
    }

    fun refresh() = load(force = true)

    /** Shows an already-loaded series (used by previews / screenshot tests). */
    @androidx.annotation.VisibleForTesting
    fun present(series: PriceSeries, selected: Set<IndicatorType> = _state.value.selected) {
        _state.value = _state.value.copy(symbol = series.symbol, series = series, selected = selected, loading = false)
        recompute()
    }

    private fun load(force: Boolean = false) {
        val sym = _state.value.symbol ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { c.market.history(sym, _state.value.range, force) }
                .onSuccess { series ->
                    _state.value = _state.value.copy(
                        series = series, loading = false,
                        inWatchlist = c.watchlist.contains(sym),
                    )
                    recompute()
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed to load $sym") }
        }
    }

    private fun recompute() {
        val s = _state.value.series ?: return
        _state.value = _state.value.copy(report = TechnicalAnalyzer.analyze(s, _state.value.selected, _state.value.params))
    }
}
