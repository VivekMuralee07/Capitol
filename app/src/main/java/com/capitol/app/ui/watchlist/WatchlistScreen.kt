package com.capitol.app.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.analysis.AnalysisReport
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.SymbolMatch
import com.capitol.app.data.watchlist.WatchItem
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.SignalChip
import com.capitol.app.ui.components.Sparkline
import com.capitol.app.ui.components.changeColor
import com.capitol.app.ui.theme.CapitolColors
import com.capitol.app.ui.theme.NumberStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class WatchRow(val item: WatchItem, val series: PriceSeries?, val report: AnalysisReport?)

data class WatchState(val rows: List<WatchRow> = emptyList(), val loading: Boolean = false, val sortBySignal: Boolean = false)

class WatchlistViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()
    private val cache = mutableMapOf<String, Pair<PriceSeries?, AnalysisReport?>>()
    val searchResults = MutableStateFlow<List<SymbolMatch>>(emptyList())

    init {
        viewModelScope.launch {
            c.watchlist.items.collect { items ->
                publish(items)
                val missing = items.filter { it.symbol !in cache }
                if (missing.isNotEmpty()) load(missing)
            }
        }
        viewModelScope.launch {
            c.settings.settings.map { it.defaultIndicators to it.indicatorParams }.distinctUntilChanged().drop(1).collect { (inds, params) ->
                cache.replaceAll { _, (s, _) -> s to s?.let { TechnicalAnalyzer.analyze(it, inds, params) } }
                publish(c.watchlist.current())
            }
        }
    }

    private fun publish(items: List<WatchItem>) {
        var rows = items.map { WatchRow(it, cache[it.symbol]?.first, cache[it.symbol]?.second) }
        if (_state.value.sortBySignal) rows = rows.sortedByDescending { it.report?.compositeScore ?: -9.0 }
        _state.value = _state.value.copy(rows = rows)
    }

    private suspend fun load(items: List<WatchItem>, force: Boolean = false) {
        _state.value = _state.value.copy(loading = true)
        val st = c.settings.settings.first()
        val inds = st.defaultIndicators
        items.map { w ->
            viewModelScope.async {
                val s = runCatching { c.market.history(w.symbol, ChartRange.Y1, force) }.getOrNull()
                cache[w.symbol] = s to s?.let { TechnicalAnalyzer.analyze(it, inds, st.indicatorParams) }
            }
        }.awaitAll()
        _state.value = _state.value.copy(loading = false)
        publish(c.watchlist.current())
    }

    fun refresh() = viewModelScope.launch { load(c.watchlist.current(), force = true) }
    fun toggleSort() { _state.value = _state.value.copy(sortBySignal = !_state.value.sortBySignal); viewModelScope.launch { publish(c.watchlist.current()) } }
    fun remove(s: String) = viewModelScope.launch { c.watchlist.remove(s); cache.remove(s) }
    fun add(m: SymbolMatch) = viewModelScope.launch { c.watchlist.add(m.symbol, m.name); searchResults.value = emptyList() }
    fun setAlerts(s: String, above: Double?, below: Double?) = viewModelScope.launch { c.watchlist.setAlerts(s, above, below) }
    fun search(q: String) = viewModelScope.launch {
        searchResults.value = if (q.length < 2) emptyList() else runCatching { c.market.search(q) }.getOrDefault(emptyList())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(vm: WatchlistViewModel, onOpen: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var alertFor by remember { mutableStateOf<WatchItem?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Watchlist", style = MaterialTheme.typography.headlineMedium)
                Text("${st.rows.size} symbols · swipe left to remove", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(vm::toggleSort) { Text(if (st.sortBySignal) "Sorted: signal" else "Sort by signal") }
            IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton({ adding = true }) { Icon(Icons.Default.Add, "Add") }
        }
        if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.rows.isEmpty()) item {
                CapitolCard { Text("Your watchlist is empty. Tap + to add symbols.") }
            }
            items(st.rows, key = { it.item.symbol }) { row ->
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
                    if (it == SwipeToDismissBoxValue.EndToStart) { vm.remove(row.item.symbol); true } else false
                })
                SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, backgroundContent = {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(CapitolColors.market.bear.copy(alpha = 0.3f)).padding(20.dp),
                        contentAlignment = Alignment.CenterEnd) { Icon(Icons.Default.Delete, "Delete") }
                }) {
                    WatchCard(row, { onOpen(row.item.symbol) }, { alertFor = row.item })
                }
            }
        }
    }

    if (adding) AddDialog(vm, onDismiss = { adding = false })
    alertFor?.let { item -> AlertDialogFor(item, onDismiss = { alertFor = null }) { a, b -> vm.setAlerts(item.symbol, a, b); alertFor = null } }
}

@Composable
private fun WatchCard(row: WatchRow, onClick: () -> Unit, onAlert: () -> Unit) {
    val s = row.series
    CapitolCard(Modifier.fillMaxWidth().clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.item.symbol, style = MaterialTheme.typography.titleSmall)
                Text(s?.name ?: row.item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s != null) Sparkline(s.closes.takeLast(60), Modifier.width(80.dp).height(34.dp))
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(s?.let { "%,.2f".format(it.regularMarketPrice) } ?: "—", style = NumberStyle.copy(fontSize = 14.sp))
                s?.let { Text("%+.2f%%".format(it.changePercent), color = changeColor(it.change), style = NumberStyle.copy(fontSize = 12.sp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            row.report?.let { SignalChip(it.verdict) }
            Spacer(Modifier.weight(1f))
            val alerts = listOfNotNull(row.item.alertAbove?.let { "≥ %.2f".format(it) }, row.item.alertBelow?.let { "≤ %.2f".format(it) })
            if (alerts.isNotEmpty()) Text(alerts.joinToString("  "), style = MaterialTheme.typography.labelSmall, color = CapitolColors.market.neutral)
            IconButton(onAlert) { Icon(Icons.Default.NotificationsActive, "Price alert") }
        }
    }
}

@Composable
private fun AddDialog(vm: WatchlistViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by vm.searchResults.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to watchlist") },
        text = {
            Column {
                OutlinedTextField(q, { q = it; vm.search(it) }, singleLine = true, placeholder = { Text("Search symbol or company") })
                results.take(6).forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { vm.add(m); onDismiss() }.padding(vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(m.symbol, style = MaterialTheme.typography.titleSmall)
                            Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Text(m.exchange, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ if (q.isNotBlank()) { vm.add(SymbolMatch(q.trim().uppercase(), q.trim().uppercase(), "", "")); onDismiss() } }) { Text("Add as typed") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlertDialogFor(item: WatchItem, onDismiss: () -> Unit, onSave: (Double?, Double?) -> Unit) {
    var above by remember { mutableStateOf(item.alertAbove?.toString() ?: "") }
    var below by remember { mutableStateOf(item.alertBelow?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price alerts · ${item.symbol}") },
        text = {
            Column {
                Text("The AI autopilot checks these in the background (enable it in Settings).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(above, { above = it }, label = { Text("Alert when price ≥") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(below, { below = it }, label = { Text("Alert when price ≤") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton({ onSave(above.toDoubleOrNull(), below.toDoubleOrNull()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
