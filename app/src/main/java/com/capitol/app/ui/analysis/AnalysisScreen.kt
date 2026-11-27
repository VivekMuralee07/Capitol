package com.capitol.app.ui.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capitol.app.analysis.AnalysisReport
import com.capitol.app.analysis.IndicatorCategory
import com.capitol.app.analysis.IndicatorResult
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.Pane
import com.capitol.app.analysis.Signal
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.isIntraday
import com.capitol.app.ui.components.AnimatedNumber
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.ChangeText
import com.capitol.app.ui.components.ErrorBanner
import com.capitol.app.ui.components.IndicatorPane
import com.capitol.app.ui.components.PriceChart
import com.capitol.app.ui.components.PriceChartMode
import com.capitol.app.ui.components.SectionTitle
import com.capitol.app.ui.components.SentimentGauge
import com.capitol.app.ui.components.SignalChip
import com.capitol.app.ui.components.signalColor
import com.capitol.app.ui.components.roleColor
import com.capitol.app.ui.home.ShimmerBox
import com.capitol.app.ui.home.Staggered
import com.capitol.app.ui.theme.CapitolColors
import com.capitol.app.ui.theme.NumberStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(vm: AnalysisViewModel, initialSymbol: String?, onCompare: (String) -> Unit = {}, onAskAi: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) vm.setActive(true)
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.setActive(false)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.setActive(false) }
    }
    LaunchedEffect(initialSymbol) { if (initialSymbol != null && initialSymbol != st.symbol) vm.open(initialSymbol) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var chartMode by rememberSaveable { mutableStateOf(PriceChartMode.CANDLES) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SearchBar(st, vm)
        if (st.symbol != null) com.capitol.app.ui.components.AutoRefreshDuringMarketHours { vm.refresh() }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = st.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), 
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            st.error?.let { item { ErrorBanner(it) } }
            st.series?.cachedAt?.let { at -> item { OfflineBanner(at) } }
            if (st.symbol == null) {
                item { EmptyState(vm::open) }
                return@LazyColumn
            }
            val series = st.series
            item {
                if (series == null) ShimmerBox(Modifier.fillMaxWidth().height(120.dp))
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(series.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${series.symbol} · ${series.exchange} · ${series.currency}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        val q = st.liveQuote
                        com.capitol.app.ui.components.FlashingPrice(q?.price ?: series.regularMarketPrice, fontSize = 28)
                        if (q != null) ChangeText(q.change, q.changePercent) else ChangeText(series.change, series.changePercent)
                        com.capitol.app.ui.components.LiveBadge(st.streaming, st.lastTick,
                            if (series.symbol.endsWith(".NS") || series.symbol.endsWith(".BO") || series.symbol.startsWith("^NSE") || series.symbol == "^BSESN")
                                com.capitol.app.data.market.MarketHours.indiaOpen()
                            else if (series.symbol.endsWith("-USD")) true else com.capitol.app.data.market.MarketHours.usOpen(),
                            Modifier.padding(top = 2.dp))
                    }
                    ShareMenu(st)
                    IconButton(vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton({ vm.toggleWatchlist() }) {
                        Icon(if (st.inWatchlist) Icons.Default.Star else Icons.Default.StarBorder, "Watchlist",
                            tint = if (st.inWatchlist) CapitolColors.market.neutral else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ChartRange.PICKER) { r -> FilterChip(r == st.range, { vm.setRange(r) }, { Text(r.label) }) }
                    item {
                        IconButton({ chartMode = if (chartMode == PriceChartMode.CANDLES) PriceChartMode.LINE else PriceChartMode.CANDLES }) {
                            Icon(if (chartMode == PriceChartMode.CANDLES) Icons.AutoMirrored.Filled.ShowChart else Icons.Default.CandlestickChart, "Chart type")
                        }
                    }
                }
            }
            val report = st.report
            if (series != null) {
                item {
                    val overlays = report?.results?.filter { it.type.pane == Pane.OVERLAY && it.available && it.type != IndicatorType.PIVOTS && it.type != IndicatorType.FIBONACCI }
                        ?.flatMap { it.lines }.orEmpty()
                    val levels = report?.results?.filter { it.type == IndicatorType.PIVOTS || it.type == IndicatorType.FIBONACCI }
                        ?.flatMap { it.levels }.orEmpty()
                    CapitolCard(padding = PaddingValues(8.dp)) {
                        PriceChart(series.candles, overlays = overlays, levels = levels, mode = chartMode, intraday = st.range.isIntraday, height = 300.dp)
                        if (overlays.isNotEmpty()) Legend(overlays.map { it.name to it.role })
                        // Oscillators / volume indicators get their own stacked panes under the price chart.
                        report?.results?.filter { it.available && it.type.pane == Pane.SEPARATE && it.lines.isNotEmpty() }?.forEach { r ->
                            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.signal.label, style = MaterialTheme.typography.labelMedium, color = signalColor(r.signal))
                            }
                            IndicatorPane(r.lines, levels = r.levels, height = 90.dp)
                            Legend(r.lines.map { it.name to it.role })
                        }
                        if (overlays.isEmpty() && report?.results?.none { it.available && it.type.pane == Pane.SEPARATE } == true) {
                            Text("Select indicators with the Filter button to plot them here.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${st.selected.size} indicators selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Button({ showFilter = true }) {
                        Icon(Icons.Default.FilterList, null); Spacer(Modifier.width(6.dp)); Text("Filter")
                    }
                }
                Spacer(Modifier.height(6.dp))
                SelectedChips(st.selected, vm::toggle)
            }
            if (report != null) {
                item { VerdictCard(report) }
                if (st.timeframes.isNotEmpty()) item { TimeframeCard(st) }
                item { SummaryCard(report, onAskAi = {
                    onAskAi("Explain the technical picture for ${report.symbol} using ${st.selected.joinToString { it.shortName }}. What are the key levels and what would change the view?")
                }) }
                if (report.keyLevels.isNotEmpty()) item { LevelsCard(report) }
                item { TradePlannerCard(st, vm::setTradeDirection, vm::setTradeDefaults) }
                item { BacktestCard(st, vm::runBacktest) }
                st.fundamentals?.let { f -> item { FundamentalsCard(f, st.series?.currency) } }
                if (st.news.isNotEmpty()) item { NewsCard(st.news) }
                item {
                    OutlinedButton({ onCompare(report.symbol) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CompareArrows, null); Spacer(Modifier.width(6.dp)); Text("Compare with other stocks")
                    }
                }
                item { SectionTitle("Indicator breakdown") }
                report.results.forEachIndexed { i, r ->
                    item(key = r.type.name) { Staggered(i) { IndicatorCard(r) } }
                }
            }
        }
        }
    }

    if (showFilter) {
        ModalBottomSheet(onDismissRequest = { showFilter = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            IndicatorFilterSheet(st.selected, vm::toggle, vm::setIndicators, onSave = { vm.saveAsDefault() }, onDone = { showFilter = false })
        }
    }
}

@Composable
private fun SearchBar(st: AnalysisState, vm: AnalysisViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = st.query, onValueChange = vm::onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search stock, index, ETF… e.g. Infosys, TSLA") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true, shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.open(st.suggestions.firstOrNull()?.symbol ?: st.query) }),
        )
        AnimatedVisibility(st.suggestions.isNotEmpty()) {
            CapitolCard(Modifier.fillMaxWidth().padding(top = 6.dp), padding = PaddingValues(4.dp)) {
                st.suggestions.take(7).forEach { m ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.open(m.symbol) }.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(m.symbol, style = MaterialTheme.typography.titleSmall)
                            Text(m.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text("${m.exchange} · ${m.type}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onOpen: (String) -> Unit) {
    CapitolCard {
        Icon(Icons.Default.BarChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("Analyse any stock", style = MaterialTheme.typography.titleLarge)
        Text("Search above, then pick the indicators you care about. Capitol explains what each one says right now and combines them into one verdict.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Popular", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("RELIANCE.NS", "INFY.NS", "TATAMOTORS.NS", "^NSEI", "AAPL", "NVDA", "MSFT", "BTC-USD").forEach {
                AssistChip({ onOpen(it) }, { Text(it) })
            }
        }
    }
}

@Composable
private fun Legend(items: List<Pair<String, com.capitol.app.analysis.LineRole>>) {
    val mc = CapitolColors.market
    val primary = MaterialTheme.colorScheme.primary
    LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { (name, role) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp, 3.dp).background(mc.roleColor(role, primary)))
                Spacer(Modifier.width(4.dp))
                Text(name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedChips(selected: Set<IndicatorType>, onToggle: (IndicatorType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IndicatorType.entries.filter { it in selected }.forEach { t ->
            FilterChip(true, { onToggle(t) }, { Text(t.shortName) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorFilterSheet(
    selected: Set<IndicatorType>,
    onToggle: (IndicatorType) -> Unit,
    onSet: (Set<IndicatorType>) -> Unit,
    onSave: () -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item {
            Text("Choose indicators", style = MaterialTheme.typography.titleLarge)
            Text("Select as many as you like. The verdict and explanation adapt to exactly this set.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Presets.list.forEach { (name, set) -> AssistChip({ onSet(set) }, { Text(name) }) }
                AssistChip({ onSet(setOf()) }, { Text("Clear") })
            }
        }
        IndicatorType.byCategory().forEach { (cat, types) ->
            item {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cat.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val allOn = types.all { it in selected }
                    TextButton({ onSet(if (allOn) selected - types.toSet() else selected + types) }) { Text(if (allOn) "None" else "All") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { t -> FilterChip(t in selected, { onToggle(t) }, { Text(t.title.substringBefore(" (")) }) }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ onSave() }, Modifier.weight(1f)) { Text("Save as default") }
                Button(onDone, Modifier.weight(1f)) { Text("Apply (${selected.size})") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VerdictCard(r: AnalysisReport) {
    val c = signalColor(r.verdict)
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Capitol verdict", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.verdict.label, style = MaterialTheme.typography.headlineMedium, color = c)
                Text("Score ${"%+.2f".format(r.compositeScore)} · Agreement ${r.confidence}%", style = NumberStyle.copy(fontSize = 12.sp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Counter("Bullish", r.bullishCount, CapitolColors.market.bull)
                    Counter("Neutral", r.neutralCount, CapitolColors.market.neutral)
                    Counter("Bearish", r.bearishCount, CapitolColors.market.bear)
                }
            }
            SentimentGauge(r.compositeScore, size = 140.dp)
        }
        if (r.categoryScores.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            r.categoryScores.forEach { (cat, s) -> CategoryBar(cat, s) }
        }
    }
}

@Composable
private fun Counter(label: String, n: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text("$n", style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryBar(cat: IndicatorCategory, score: Double) {
    val sig = Signal.fromScore(score)
    val c = signalColor(sig)
    val frac by animateFloatAsState(((score + 2) / 4).toFloat().coerceIn(0.03f, 1f), tween(1000), label = "cat")
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(cat.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(sig.label, style = MaterialTheme.typography.labelMedium, color = c)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

@Composable
private fun SummaryCard(r: AnalysisReport, onAskAi: () -> Unit) {
    CapitolCard {
        Text("What it all means", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(r.summary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onAskAi) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Ask the AI agent to go deeper") }
    }
}

@Composable
private fun LevelsCard(r: AnalysisReport) {
    val mc = CapitolColors.market
    CapitolCard {
        Text("Key levels", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        (r.keyLevels.filter { !it.isSupport }.reversed() + r.keyLevels.filter { it.isSupport }).forEach { l ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (l.isSupport) mc.bull else mc.bear))
                Spacer(Modifier.width(8.dp))
                Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("%,.2f".format(l.price), style = NumberStyle.copy(fontSize = 13.sp))
                Spacer(Modifier.width(8.dp))
                Text(if (l.isSupport) "support" else "resistance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorCard(r: IndicatorResult) {
    var expanded by remember { mutableStateOf(false) }
    CapitolCard(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall)
                Text(r.type.category.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.available) SignalChip(r.signal)
        }
        Spacer(Modifier.height(6.dp))
        Text(r.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (r.metrics.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.metrics.forEach { m ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(m.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(m.value, style = NumberStyle.copy(fontSize = 13.sp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("What it says now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(r.interpretation, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "Hide explainer" else "How does ${r.type.shortName} work?", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("What it measures", style = MaterialTheme.typography.labelLarge)
            Text(r.type.about, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text("How to read it", style = MaterialTheme.typography.labelLarge)
            Text(r.type.howToRead, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OfflineBanner(at: Long) {
    val time = remember(at) { java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CapitolColors.market.neutral.copy(alpha = 0.15f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CloudOff, null, tint = CapitolColors.market.neutral)
        Spacer(Modifier.width(8.dp))
        Text("Couldn't reach the data source — showing saved data from $time. Pull to refresh to try again.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TimeframeCard(st: AnalysisState) {
    CapitolCard {
        Text("Timeframe confirmation", style = MaterialTheme.typography.titleMedium)
        Text("Same indicators on daily and weekly bars", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        st.timeframes.forEach { (label, r) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("%+.2f".format(r.compositeScore), style = NumberStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                SignalChip(r.verdict)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(when (st.timeframesAligned) {
            true -> "✓ Aligned — daily and weekly agree, which makes the signal more reliable."
            false -> "⚠ Conflicting — the short-term and long-term pictures disagree. Wait for them to line up, or size positions smaller."
            null -> "Mixed — at least one timeframe is neutral."
        }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BacktestCard(st: AnalysisState, onRun: (Int) -> Unit) {
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Backtest these indicators", style = MaterialTheme.typography.titleMedium)
        }
        Text("How did this indicator set's verdict perform on this stock over the last 2 years?",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Look ahead:", style = MaterialTheme.typography.labelLarge)
            listOf(5, 10, 20, 60).forEach { h -> FilterChip(st.backtestHorizon == h, { onRun(h) }, { Text("${h}d") }) }
        }
        val progress = st.backtestProgress
        val bt = st.backtest
        when {
            progress != null -> { Spacer(Modifier.height(8.dp)); LinearProgressIndicator({ progress }, Modifier.fillMaxWidth()) }
            bt != null -> {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(bt.bullish to CapitolColors.market.bull, bt.bearish to CapitolColors.market.bear).forEach { (b, col) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.12f)).padding(10.dp)) {
                            Text(b.label, style = MaterialTheme.typography.labelMedium, color = col)
                            Text(if (b.signals == 0) "—" else "%.0f%% right".format(b.hitRatePct), style = MaterialTheme.typography.titleMedium)
                            Text("${b.signals} signals · avg %+.2f%%".format(b.avgReturnPct), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(bt.summary, style = MaterialTheme.typography.bodySmall)
            }
            else -> { Spacer(Modifier.height(8.dp)); Button({ onRun(st.backtestHorizon) }) { Text("Run backtest") } }
        }
    }
}

@Composable
private fun FundamentalsCard(f: com.capitol.app.data.market.Fundamentals, currency: String?) {
    var expanded by remember { mutableStateOf(false) }
    CapitolCard(Modifier.animateContentSize()) {
        Text("Fundamentals", style = MaterialTheme.typography.titleMedium)
        listOfNotNull(f.sector, f.industry).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        val items = listOfNotNull(
            f.marketCap?.let { "Market cap" to "${currency ?: f.currency ?: ""} ${com.capitol.app.data.market.Fundamentals.compact(it)}".trim() },
            f.trailingPE?.let { "P/E" to "%.1f".format(it) }, f.forwardPE?.let { "Fwd P/E" to "%.1f".format(it) },
            f.priceToBook?.let { "P/B" to "%.2f".format(it) }, f.dividendYield?.let { "Div yield" to "%.2f%%".format(it * 100) },
            f.beta?.let { "Beta" to "%.2f".format(it) }, f.fiftyTwoWeekHigh?.let { "52w high" to "%,.2f".format(it) },
            f.fiftyTwoWeekLow?.let { "52w low" to "%,.2f".format(it) }, f.returnOnEquity?.let { "ROE" to "%.1f%%".format(it * 100) },
            f.profitMargins?.let { "Margin" to "%.1f%%".format(it * 100) }, f.debtToEquity?.let { "Debt/Eq" to "%.1f".format(it) },
            f.nextEarningsDate?.let { "Next earnings" to java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(it * 1000)) },
            f.exDividendDate?.takeIf { it * 1000 > System.currentTimeMillis() - 86_400_000 }?.let { "Ex-dividend" to java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(it * 1000)) },
        )
        if (items.isEmpty()) Text("No fundamental data for this symbol.", style = MaterialTheme.typography.bodySmall)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { (k, v) ->
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = NumberStyle.copy(fontSize = 13.sp))
                }
            }
        }
        f.summary?.takeIf { it.isNotBlank() }?.let { s ->
            Spacer(Modifier.height(8.dp))
            Text(if (expanded) s else s.take(180) + if (s.length > 180) "…" else "", style = MaterialTheme.typography.bodySmall)
            if (s.length > 180) TextButton({ expanded = !expanded }) { Text(if (expanded) "Less" else "More") }
        }
    }
}

@Composable
private fun NewsCard(news: List<com.capitol.app.data.market.NewsItem>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fmt = remember { java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()) }
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Newspaper, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Latest news", style = MaterialTheme.typography.titleMedium)
        }
        news.take(6).forEach { n ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(n.link))) }
            }.padding(vertical = 8.dp)) {
                Text(n.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(n.publisher.ifBlank { null }, n.publishedAt.takeIf { it > 0 }?.let { fmt.format(java.util.Date(it * 1000)) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TradePlannerCard(
    st: AnalysisState,
    onDirection: (com.capitol.app.analysis.Direction) -> Unit,
    onDefaults: (Double, Double) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val plan = st.tradePlan
    val mc = CapitolColors.market
    val ccy = st.series?.currency.orEmpty()
    CapitolCard {
        Text("Trade planner", style = MaterialTheme.typography.titleMedium)
        Text("Entry, stop-loss, targets and position size from the chart's levels", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            com.capitol.app.analysis.Direction.entries.forEachIndexed { i, d ->
                SegmentedButton(st.tradeDirection == d, { onDirection(d) },
                    androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2)) { Text(d.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Capital ${"%,.0f".format(st.tradeCapital)} $ccy · risk ${"%.1f".format(st.tradeRiskPct)}% per trade",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton({ editing = true }) { Text("Change") }
        }
        if (plan == null) { Text("Not enough data to plan a trade.", style = MaterialTheme.typography.bodySmall); return@CapitolCard }
        val rows = buildList {
            add(Triple("Entry zone", "${"%,.2f".format(plan.entryZoneLow)} – ${"%,.2f".format(plan.entryZoneHigh)}", MaterialTheme.colorScheme.onSurface))
            add(Triple("Stop-loss", "%,.2f".format(plan.stop), mc.bear))
            plan.targets.forEach { add(Triple("Target · ${it.label}", "${"%,.2f".format(it.price)}  (${"%.1f".format(it.rMultiple)}R)", mc.bull)) }
            add(Triple("Quantity", "${plan.quantity} shares ≈ ${"%,.0f".format(plan.positionValue)}", MaterialTheme.colorScheme.onSurface))
            add(Triple("Money at risk", "%,.0f".format(plan.riskAmount), mc.neutral))
        }
        rows.forEach { (k, v, col) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(v, style = NumberStyle.copy(fontSize = 13.sp), color = col)
            }
        }
        Text("Stop placed ${plan.stopReason}. ATR ${"%,.2f".format(plan.atr)}.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = mc.neutral, modifier = Modifier.padding(top = 4.dp)) }
        Text("Educational plan based on technical levels — not investment advice.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
    if (editing) {
        var cap by remember { mutableStateOf("%.0f".format(st.tradeCapital)) }
        var risk by remember { mutableStateOf(st.tradeRiskPct.toString()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Position sizing") },
            text = {
                Column {
                    OutlinedTextField(cap, { cap = it }, label = { Text("Trading capital") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    OutlinedTextField(risk, { risk = it }, label = { Text("Risk per trade (%)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    Text("Many traders risk 0.5–2% of capital per trade.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ onDefaults(cap.toDoubleOrNull() ?: st.tradeCapital, risk.toDoubleOrNull() ?: st.tradeRiskPct); editing = false }) { Text("Save") } },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShareMenu(st: AnalysisState) {
    val report = st.report ?: return
    val series = st.series ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val content = com.capitol.app.export.ReportContent(report, series, st.tradePlan, st.fundamentals)
    val exporter = remember { com.capitol.app.export.ReportExporter(ctx.applicationContext) }
    fun export(make: (com.capitol.app.export.ReportExporter) -> java.io.File, mime: String) = scope.launch {
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { make(exporter) } }
            .onSuccess { exporter.share(it, mime) }
            .onFailure { com.capitol.app.diagnostics.Diagnostics.error("Export", "export failed: ${it.message}", it) }
    }
    androidx.compose.foundation.layout.Box {
        IconButton({ open = true }) { Icon(Icons.Default.Share, "Share") }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            androidx.compose.material3.DropdownMenuItem({ Text("Share as image") }, { open = false; export({ it.toPng(content) }, "image/png") })
            androidx.compose.material3.DropdownMenuItem({ Text("Share as PDF") }, { open = false; export({ it.toPdf(content) }, "application/pdf") })
            androidx.compose.material3.DropdownMenuItem({ Text("Share as text") }, { open = false; exporter.shareText(exporter.toText(content)) })
        }
    }
}
