package com.capitol.app.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.agent.CapitolAgent
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.MarkdownText
import com.capitol.app.ui.theme.CapitolColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String) : ChatItem
    data class Tool(val text: String, val done: Boolean = false, val ok: Boolean = true) : ChatItem
    data class Error(val text: String) : ChatItem
}

data class AgentState(val items: List<ChatItem> = emptyList(), val busy: Boolean = false)

class AgentViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    val configured get() = c.agent.isConfigured
    val secretsVersion = c.secrets.version

    fun send(text: String) {
        if (text.isBlank() || _state.value.busy) return
        _state.value = AgentState(_state.value.items + ChatItem.User(text.trim()), busy = true)
        viewModelScope.launch {
            try {
                val answer = c.agent.ask(text.trim()) { step ->
                    val items = _state.value.items.toMutableList()
                    when (step) {
                        is CapitolAgent.Step.ToolCall -> items += ChatItem.Tool(step.summary)
                        is CapitolAgent.Step.ToolDone -> {
                            val idx = items.indexOfLast { it is ChatItem.Tool && !it.done }
                            if (idx >= 0) items[idx] = (items[idx] as ChatItem.Tool).copy(done = true, ok = step.ok)
                        }
                    }
                    _state.value = _state.value.copy(items = items)
                }
                _state.value = AgentState(_state.value.items + ChatItem.Assistant(answer), busy = false)
            } catch (e: Exception) {
                _state.value = AgentState(_state.value.items + ChatItem.Error(e.message ?: "Something went wrong"), busy = false)
            }
        }
    }

    fun clear() { c.agent.reset(); _state.value = AgentState() }
}

private val SUGGESTIONS = listOf(
    "Scan my watchlist and rank the strongest setups",
    "Analyse RELIANCE.NS with RSI, MACD and Supertrend",
    "Is NIFTY 50 overbought right now?",
    "Review my portfolio holdings technically",
    "Compare TCS and Infosys on trend and momentum",
    "Explain Bollinger squeeze on HDFC Bank",
)

@Composable
fun AgentScreen(vm: AgentViewModel, initialPrompt: String?, onOpenSettings: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val secretsVersion by vm.secretsVersion.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(initialPrompt) { if (!initialPrompt.isNullOrBlank()) input = initialPrompt }
    LaunchedEffect(st.items.size) { if (st.items.isNotEmpty()) list.animateScrollToItem(st.items.size) }
    val configured = remember(secretsVersion) { vm.configured }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(CapitolColors.market.chart3, CapitolColors.market.chart1))),
                contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = androidx.compose.ui.graphics.Color.White) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Capitol AI Agent", style = MaterialTheme.typography.titleLarge)
                Text("Powered by Claude · runs Capitol's tools for you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(vm::clear) { Icon(Icons.Default.DeleteSweep, "New chat") }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!configured) item {
                CapitolCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                        Text("Connect Claude", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Add your Anthropic API key in Settings to let the agent analyse stocks, scan your watchlist and review your portfolio automatically.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    Button(onOpenSettings) { Text("Open settings") }
                }
            }
            if (st.items.isEmpty()) item {
                Text("Try asking", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(6.dp))
                SUGGESTIONS.forEach { s -> AssistChip({ vm.send(s) }, { Text(s) }, enabled = configured) }
            }
            items(st.items) { item -> Bubble(item) }
            if (st.busy) item { Thinking() }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask about any stock…") },
                shape = RoundedCornerShape(20.dp), maxLines = 4, enabled = configured)
            Spacer(Modifier.width(8.dp))
            FilledIconButton({ vm.send(input); input = "" }, enabled = configured && !st.busy && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                .background(MaterialTheme.colorScheme.primary).padding(12.dp)) {
                Text(item.text, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        is ChatItem.Assistant -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) { MarkdownText(item.text) }
        is ChatItem.Tool -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Icon(Icons.Default.Build, null, Modifier.size(14.dp), tint = if (!item.ok) MaterialTheme.colorScheme.error else CapitolColors.market.chart4)
            Spacer(Modifier.width(6.dp))
            Text(item.text + if (item.done) (if (item.ok) " ✓" else " ✗") else "…", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ChatItem.Error -> Text(item.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Thinking() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(8.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.padding(3.dp).size(8.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}
