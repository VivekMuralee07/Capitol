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
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
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
    val lists = c.watchlist.data
    val autopilotOn = c.settings.settings.map { it.agentAutoScan }

    fun selectList(i: Int) = viewModelScope.launch { c.watchlist.setActive(i) }
    fun createList(name: String) = viewModelScope.launch { c.watchlist.createList(name) }
    fun renameList(i: Int, name: String) = viewModelScope.launch { c.watchlist.renameList(i, name) }
    fun deleteList(i: Int) = viewModelScope.launch { c.watchlist.deleteList(i) }
    fun setIndicatorAlerts(s: String, alerts: List<com.capitol.app.data.watchlist.IndicatorAlert>) =
        viewModelScope.launch { c.watchlist.setIndicatorAlerts(s, alerts) }
    fun enableAutopilot() = viewModelScope.launch { c.settings.setAgentAutoScan(true) }

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
fun WatchlistScreen(vm: WatchlistViewModel, onOpen: (String) -> Unit, onCalendar: () -> Unit = {}) {
    val st by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var alertFor by remember { mutableStateOf<WatchItem?>(null) }
    val data by vm.lists.collectAsStateWithLifecycle(null)
    val autopilotOn by vm.autopilotOn.collectAsStateWithLifecycle(false)
    var listDialog by remember { mutableStateOf<Pair<Int?, String>?>(null) } // (index or null=new, name)
    var listMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        com.capitol.app.ui.components.ScreenHeader("Watchlist", Modifier.padding(horizontal = 16.dp),
            subtitle = "${st.rows.size} symbols · swipe left to remove") {
            com.capitol.app.ui.components.HeaderIconButton(Icons.Default.Event, "Earnings & dividends calendar", onCalendar)
            com.capitol.app.ui.components.HeaderIconButton(Icons.Default.Refresh, "Refresh", { vm.refresh() })
            com.capitol.app.ui.components.HeaderIconButton(Icons.Default.Add, "Add", { adding = true }, tint = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(vm::toggleSort) { Text(if (st.sortBySignal) "Sorted by signal" else "Sort by signal") }
        }
        data?.let { d ->
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(d.lists.size) { i ->
                        androidx.compose.material3.FilterChip(i == d.active, { vm.selectList(i) },
                            { Text("${d.lists[i].name} (${d.lists[i].items.size})") })
                    }
                    item { androidx.compose.material3.AssistChip({ listDialog = (null to "") }, { Text("+ New list") }) }
                }
                Box {
                    IconButton({ listMenu = true }) { Icon(Icons.Default.MoreVert, "List options") }
                    androidx.compose.material3.DropdownMenu(listMenu, { listMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem({ Text("Rename \"${d.activeList.name}\"") }, { listMenu = false; listDialog = d.active to d.activeList.name })
                        if (d.lists.size > 1) androidx.compose.material3.DropdownMenuItem({ Text("Delete \"${d.activeList.name}\"") }, { listMenu = false; vm.deleteList(d.active) })
                    }
                }
            }
        }
        com.capitol.app.ui.components.AutoRefreshDuringMarketHours { vm.refresh() }
        @OptIn(ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = st.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    }

    if (adding) AddDialog(vm, onDismiss = { adding = false })
    alertFor?.let { item ->
        AlertDialogFor(item, autopilotOn, onEnableAutopilot = vm::enableAutopilot, onDismiss = { alertFor = null }) { a, b, ind ->
            vm.setAlerts(item.symbol, a, b); vm.setIndicatorAlerts(item.symbol, ind); alertFor = null
        }
    }
    listDialog?.let { (idx, initial) ->
        var name by remember(idx) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { listDialog = null },
            title = { Text(if (idx == null) "New watchlist" else "Rename watchlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("e.g. US stocks, Swing trades") }) },
            confirmButton = { TextButton({ if (idx == null) vm.createList(name) else vm.renameList(idx, name); listDialog = null }) { Text("Save") } },
            dismissButton = { TextButton({ listDialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WatchCard(row: WatchRow, onClick: () -> Unit, onAlert: () -> Unit) {
    val s = row.series
    CapitolCard(Modifier.fillMaxWidth().clickable(onClick = onClick), padding = PaddingValues(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.item.symbol.substringBefore('.'), style = MaterialTheme.typography.titleMedium)
                Text(s?.name ?: row.item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s != null) Sparkline(s.closes.takeLast(60), Modifier.width(80.dp).height(34.dp))
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(s?.let { "%,.2f".format(it.regularMarketPrice) } ?: "—", style = NumberStyle.copy(fontSize = 15.sp))
                s?.let { Spacer(Modifier.height(3.dp)); com.capitol.app.ui.components.ChangePill(it.change, it.changePercent, fontSize = 11) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            row.report?.let { SignalChip(it.verdict) }
            Spacer(Modifier.weight(1f))
            val alerts = listOfNotNull(row.item.alertAbove?.let { "≥ %.2f".format(it) }, row.item.alertBelow?.let { "≤ %.2f".format(it) },
                row.item.indicatorAlerts.size.takeIf { it > 0 }?.let { "$it indicator alert${if (it > 1) "s" else ""}" })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertDialogFor(
    item: WatchItem,
    autopilotOn: Boolean,
    onEnableAutopilot: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double?, Double?, List<com.capitol.app.data.watchlist.IndicatorAlert>) -> Unit,
) {
    var above by remember { mutableStateOf(item.alertAbove?.toString() ?: "") }
    var below by remember { mutableStateOf(item.alertBelow?.toString() ?: "") }
    val alerts = remember { androidx.compose.runtime.mutableStateListOf(*item.indicatorAlerts.toTypedArray()) }
    var cond by remember { mutableStateOf(com.capitol.app.data.watchlist.AlertCondition.RSI_BELOW) }
    var value by remember { mutableStateOf("30") }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alerts · ${item.symbol}") },
        text = {
            LazyColumn {
                item {
                    if (!autopilotOn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Alerts need Autopilot running in the background.", style = MaterialTheme.typography.bodySmall,
                                color = CapitolColors.market.neutral, modifier = Modifier.weight(1f))
                            TextButton(onEnableAutopilot) { Text("Turn on") }
                        }
                    } else Text("Autopilot checks these in the background and notifies you once when each becomes true.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Price", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(above, { above = it }, label = { Text("Alert when price ≥") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(below, { below = it }, label = { Text("Alert when price ≤") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.height(12.dp))
                    Text("Indicators", style = MaterialTheme.typography.labelLarge)
                }
                items(alerts.toList()) { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• ${a.description}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton({ alerts.remove(a) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
                item {
                    androidx.compose.material3.ExposedDropdownMenuBox(menu, { menu = it }) {
                        OutlinedTextField(cond.label, {}, readOnly = true, label = { Text("Add condition") },
                            modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(menu) })
                        ExposedDropdownMenu(menu, { menu = false }) {
                            com.capitol.app.data.watchlist.AlertCondition.entries.forEach { c ->
                                androidx.compose.material3.DropdownMenuItem({ Text(c.label) }, {
                                    cond = c; menu = false
                                    value = when (c) { com.capitol.app.data.watchlist.AlertCondition.RSI_BELOW -> "30"; com.capitol.app.data.watchlist.AlertCondition.RSI_ABOVE -> "70"; else -> "" }
                                })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (cond.needsValue) OutlinedTextField(value, { value = it }, Modifier.weight(1f), label = { Text("Level") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        else Spacer(Modifier.weight(1f))
                        TextButton({
                            val v = if (cond.needsValue) value.toDoubleOrNull() ?: return@TextButton else null
                            val a = com.capitol.app.data.watchlist.IndicatorAlert(cond, v)
                            if (alerts.none { it.key == a.key }) alerts.add(a)
                        }) { Text("Add") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSave(above.toDoubleOrNull(), below.toDoubleOrNull(), alerts.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
