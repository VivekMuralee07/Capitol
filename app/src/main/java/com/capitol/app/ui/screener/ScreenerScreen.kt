package com.capitol.app.ui.screener

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.analysis.ScreenCondition
import com.capitol.app.analysis.ScreenFilter
import com.capitol.app.analysis.ScreenHit
import com.capitol.app.analysis.Screener
import com.capitol.app.analysis.Universe
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.SignalChip
import com.capitol.app.ui.components.changeColor
import com.capitol.app.ui.theme.NumberStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ScreenerState(
    val universe: Universe = Universe.NIFTY50,
    val filters: List<ScreenFilter> = Screener.PRESETS.first().filters,
    val freshOnly: Boolean = true,
    val running: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val hits: List<ScreenHit>? = null,
)

class ScreenerViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(ScreenerState())
    private var job: Job? = null

    fun setUniverse(u: Universe) { state.value = state.value.copy(universe = u, hits = null) }
    fun setFresh(on: Boolean) { state.value = state.value.copy(freshOnly = on, hits = null) }
    fun addFilter(c: ScreenCondition) { state.value = state.value.copy(filters = (state.value.filters + ScreenFilter(c)).distinctBy { it.condition }, hits = null) }
    fun removeFilter(f: ScreenFilter) { state.value = state.value.copy(filters = state.value.filters - f, hits = null) }
    fun setValue(f: ScreenFilter, v: Double) { state.value = state.value.copy(filters = state.value.filters.map { if (it == f) it.copy(value = v) else it }, hits = null) }
    fun preset(i: Int) { val p = Screener.PRESETS[i]; state.value = state.value.copy(filters = p.filters, freshOnly = p.freshOnly, hits = null) }

    fun run() {
        job?.cancel()
        job = viewModelScope.launch {
            val st = state.value
            val settings = c.settings.settings.first()
            val symbols = if (st.universe == Universe.WATCHLISTS) c.watchlist.all().map { it.symbol } else st.universe.symbols
            state.value = st.copy(running = true, progress = 0 to symbols.size, hits = null)
            val hits = Screener.run(c.market, symbols, st.filters, st.freshOnly, settings.indicatorParams, settings.defaultIndicators) { d, t ->
                state.value = state.value.copy(progress = d to t)
            }
            state.value = state.value.copy(running = false, progress = null, hits = hits)
        }
    }
    fun stop() { job?.cancel(); state.value = state.value.copy(running = false, progress = null) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenerScreen(vm: ScreenerViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    var addMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            com.capitol.app.ui.components.ScreenHeader("Screener", subtitle = "Find stocks matching your setup", onBack = onBack)
        }
        item {
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Screener.PRESETS.forEachIndexed { i, p -> AssistChip({ vm.preset(i) }, { Text(p.label) }) } }
        }
        item {
            Text("Universe", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Universe.entries.forEach { u -> FilterChip(st.universe == u, { vm.setUniverse(u) },
                    { Text(if (u.symbols.isEmpty()) u.label else "${u.label} (${u.symbols.size})") }) }
            }
        }
        item {
            CapitolCard {
                Text("Conditions (all must match)", style = MaterialTheme.typography.titleSmall)
                st.filters.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• ${f.condition.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (f.condition.needsValue) {
                            listOf(20.0, 30.0, 40.0, 60.0, 70.0, 80.0).filter { if (f.condition == ScreenCondition.RSI_BELOW) it <= 40 else it >= 60 }.forEach { v ->
                                FilterChip(f.value == v, { vm.setValue(f, v) }, { Text("%.0f".format(v)) }, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        IconButton({ vm.removeFilter(f) }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton({ addMenu = true }) { Text("+ Add condition") }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            ScreenCondition.entries.filter { c -> st.filters.none { it.condition == c } }.forEach { c ->
                                DropdownMenuItem({ Text(c.label) }, { vm.addFilter(c); addMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Just triggered", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Switch(st.freshOnly, vm::setFresh)
                }
                if (st.freshOnly) Text("Only stocks where a condition turned true in the last ${Screener.FRESH_BARS} trading days.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            val p = st.progress
            if (st.running && p != null) {
                Text("Scanning ${p.first} of ${p.second}…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator({ if (p.second == 0) 0f else p.first.toFloat() / p.second }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedButton(vm::stop) { Text("Stop") }
            } else Button(vm::run, enabled = st.filters.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Run screen") }
        }
        st.hits?.let { hits ->
            item { Text("${hits.size} match${if (hits.size == 1) "" else "es"}", style = MaterialTheme.typography.titleMedium) }
            if (hits.isEmpty()) item { Text("Nothing matches right now. Try fewer conditions, turn off \"Just triggered\", or another universe.", style = MaterialTheme.typography.bodySmall) }
            items(hits, key = { it.symbol }) { h ->
                CapitolCard(Modifier.fillMaxWidth().clickable { onOpen(h.symbol) }, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.symbol, style = MaterialTheme.typography.titleSmall)
                            Text(h.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("%,.2f".format(h.price), style = NumberStyle.copy(fontSize = 14.sp))
                            Text("%+.2f%%".format(h.changePct), color = changeColor(h.changePct), style = NumberStyle.copy(fontSize = 12.sp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalChip(h.verdict)
                        Spacer(Modifier.width(8.dp))
                        Text(h.reasons.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Text("Index lists are built in and may lag recent rebalancing. Educational use only.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
