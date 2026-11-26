package com.capitol.app.ui.calendar

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.data.market.CalendarEvent
import com.capitol.app.data.market.EventType
import com.capitol.app.data.market.EventsCalendar
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.theme.CapitolColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CalendarState(val loading: Boolean = true, val events: List<CalendarEvent> = emptyList(), val checked: Int = 0)

class CalendarViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(CalendarState())
    init { refresh() }
    fun refresh() = viewModelScope.launch {
        state.value = state.value.copy(loading = true)
        val items = c.watchlist.all()
        state.value = CalendarState(false, EventsCalendar.load(c.market, items.map { it.symbol to it.name }), items.size)
    }
}

@Composable
fun CalendarScreen(vm: CalendarViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis() / 1000
    val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("Earnings & dividends", style = MaterialTheme.typography.headlineSmall)
                    Text("Next 90 days for all your watchlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!st.loading && st.events.isEmpty()) item {
            CapitolCard { Text("No upcoming earnings or dividend dates found for the ${st.checked} symbols in your watchlists. Indices and some international listings don't publish these dates.") }
        }
        items(st.events) { e ->
            val days = e.daysFrom(now)
            val color = when (e.type) { EventType.EARNINGS -> CapitolColors.market.chart1; EventType.EX_DIVIDEND -> CapitolColors.market.bull; EventType.DIVIDEND -> CapitolColors.market.neutral }
            CapitolCard(Modifier.fillMaxWidth().clickable { onOpen(e.symbol) }, padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.symbol} · ${e.type.label}", style = MaterialTheme.typography.titleSmall)
                        Text(e.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt.format(Date(e.epochSeconds * 1000)), style = MaterialTheme.typography.bodySmall)
                        Text(when { days < 0 -> "yesterday"; days == 0L -> "today"; days == 1L -> "tomorrow"; else -> "in $days days" },
                            style = MaterialTheme.typography.labelMedium, color = if (days <= 7) color else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Text("Dates come from Yahoo Finance and can change; companies confirm them closer to the event.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    }
}
