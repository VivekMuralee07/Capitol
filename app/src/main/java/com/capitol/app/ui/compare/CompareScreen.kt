package com.capitol.app.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.analysis.AnalysisReport
import com.capitol.app.analysis.ChartLine
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.LineRole
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.SymbolMatch
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.IndicatorPane
import com.capitol.app.ui.components.SignalChip
import com.capitol.app.ui.components.changeColor
import com.capitol.app.ui.components.roleColor
import com.capitol.app.ui.components.signalColor
import com.capitol.app.ui.theme.CapitolColors
import com.capitol.app.ui.theme.NumberStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class CompareRow(val series: PriceSeries, val report: AnalysisReport)

data class CompareState(
    val symbols: List<String> = emptyList(),
    val range: ChartRange = ChartRange.Y1,
    val rows: List<CompareRow> = emptyList(),
    val indicators: Set<IndicatorType> = IndicatorType.DEFAULTS,
    val loading: Boolean = false,
    val failed: List<String> = emptyList(),
)

class CompareViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CompareState())
    val state: StateFlow<CompareState> = _state.asStateFlow()
    val suggestions = MutableStateFlow<List<SymbolMatch>>(emptyList())

    fun init(initial: List<String>) {
        if (_state.value.symbols.isNotEmpty() || initial.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(symbols = initial.distinct().take(MAX), indicators = c.settings.settings.first().defaultIndicators)
            load()
        }
    }

    fun add(symbol: String) {
        val s = symbol.trim().uppercase()
        if (s.isEmpty() || s in _state.value.symbols || _state.value.symbols.size >= MAX) return
        _state.value = _state.value.copy(symbols = _state.value.symbols + s); suggestions.value = emptyList(); load()
    }
    fun remove(symbol: String) { _state.value = _state.value.copy(symbols = _state.value.symbols - symbol, rows = _state.value.rows.filter { it.series.symbol != symbol }) }
    fun setRange(r: ChartRange) { _state.value = _state.value.copy(range = r); load() }
    fun toggle(t: IndicatorType) {
        val cur = _state.value.indicators
        _state.value = _state.value.copy(indicators = if (t in cur && cur.size > 1) cur - t else cur + t); load()
    }
    fun search(q: String) = viewModelScope.launch {
        suggestions.value = if (q.length < 2) emptyList() else runCatching { c.market.search(q) }.getOrDefault(emptyList()).take(5)
    }

    private fun load() = viewModelScope.launch {
        val st = _state.value
        _state.value = st.copy(loading = true)
        val params = c.settings.settings.first().indicatorParams
        val results = st.symbols.map { s -> async { s to runCatching { c.market.history(s, st.range) }.getOrNull() } }.awaitAll()
        _state.value = _state.value.copy(
            loading = false,
            rows = results.mapNotNull { (_, ser) -> ser?.let { CompareRow(it, TechnicalAnalyzer.analyze(it, st.indicators, params)) } },
            failed = results.filter { it.second == null }.map { it.first },
        )
    }

    companion object { const val MAX = 4 }
}

private val ROLES = listOf(LineRole.PRIMARY, LineRole.SECONDARY, LineRole.TERTIARY, LineRole.ACCENT)

@Composable
fun CompareScreen(vm: CompareViewModel, initial: List<String>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val sugg by vm.suggestions.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    LaunchedEffect(initial) { vm.init(initial) }
    val mc = CapitolColors.market
    val primary = MaterialTheme.colorScheme.primary

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            com.capitol.app.ui.components.ScreenHeader("Compare", subtitle = "Up to 4 stocks side by side", onBack = onBack)
            if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(st.symbols) { s ->
                    val idx = st.symbols.indexOf(s)
                    InputChip(true, { vm.remove(s) }, { Text(s) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(mc.roleColor(ROLES[idx % ROLES.size], primary))) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) })
                }
            }
            if (st.symbols.size < CompareViewModel.MAX) {
                OutlinedTextField(q, { q = it; vm.search(it) }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Add a stock (up to ${CompareViewModel.MAX})") }, shape = RoundedCornerShape(16.dp))
                sugg.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { vm.add(m.symbol); q = "" }.padding(10.dp)) {
                        Text(m.symbol, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(120.dp))
                        Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (st.failed.isNotEmpty()) Text("Couldn't load: ${st.failed.joinToString()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(ChartRange.M1, ChartRange.M3, ChartRange.M6, ChartRange.Y1, ChartRange.Y2, ChartRange.Y5)) { r ->
                    FilterChip(r == st.range, { vm.setRange(r) }, { Text(r.label) })
                }
            }
        }
        if (st.rows.size >= 1) {
            item {
                // Performance rebased to 100, aligned on the most recent bars.
                val len = st.rows.minOf { it.series.candles.size }
                val lines = st.rows.mapIndexed { i, r ->
                    val closes = r.series.closes.takeLast(len)
                    ChartLine(r.series.symbol, closes.map { it / closes.first() * 100 }, ROLES[st.symbols.indexOf(r.series.symbol).coerceAtLeast(i) % ROLES.size])
                }
                CapitolCard {
                    Text("Performance (rebased to 100)", style = MaterialTheme.typography.titleMedium)
                    IndicatorPane(lines, levels = listOf("100" to 100.0), height = 200.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        lines.forEach { l ->
                            val ret = (l.values.lastOrNull() ?: 100.0)!! - 100
                            Text("${l.name} ${"%+.1f".format(ret)}%", style = NumberStyle.copy(fontSize = 12.sp), color = mc.roleColor(l.role, primary))
                        }
                    }
                }
            }
            item {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IndicatorType.entries.forEach { t -> FilterChip(t in st.indicators, { vm.toggle(t) }, { Text(t.shortName) }) }
                }
            }
            item { CompareTable(st, onOpen) }
        }
    }
}

@Composable
private fun CompareTable(st: CompareState, onOpen: (String) -> Unit) {
    val colW = 104.dp
    CapitolCard(padding = PaddingValues(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Cell("", 92.dp, header = true)
                Cell("Verdict", 92.dp); Cell("Score", 92.dp); Cell("Price", 92.dp); Cell("Change", 92.dp)
                st.indicators.sortedBy { it.ordinal }.forEach { Cell(it.shortName, 92.dp) }
            }
            st.rows.forEach { row ->
                val r = row.report
                Column(Modifier.clickable { onOpen(row.series.symbol) }) {
                    Cell(row.series.symbol.substringBefore('.'), colW, header = true)
                    Box(Modifier.width(colW).height(40.dp), contentAlignment = Alignment.CenterStart) { SignalChip(r.verdict) }
                    Cell("%+.2f".format(r.compositeScore), colW)
                    Cell("%,.2f".format(r.lastPrice), colW)
                    Box(Modifier.width(colW).height(40.dp), contentAlignment = Alignment.CenterStart) {
                        Text("%+.2f%%".format(r.changePercent), color = changeColor(r.changePercent), style = NumberStyle.copy(fontSize = 13.sp))
                    }
                    st.indicators.sortedBy { it.ordinal }.forEach { t ->
                        val res = r.results.firstOrNull { it.type == t }
                        Box(Modifier.width(colW).height(40.dp), contentAlignment = Alignment.CenterStart) {
                            if (res == null || !res.available) Text("—", style = MaterialTheme.typography.bodySmall)
                            else Text(res.signal.label, color = signalColor(res.signal), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, w: androidx.compose.ui.unit.Dp, header: Boolean = false) {
    Box(Modifier.width(w).height(40.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            color = if (header) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
