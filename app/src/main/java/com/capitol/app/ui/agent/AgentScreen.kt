package com.capitol.app.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.capitol.app.agent.ChatItem
import com.capitol.app.agent.SavedChat
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.MarkdownText
import com.capitol.app.ui.theme.CapitolColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentState(
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    val streaming: String = "",
    val chatId: String = java.util.UUID.randomUUID().toString(),
    val usage: CapitolAgent.Usage = CapitolAgent.Usage(),
    val saved: List<SavedChat> = emptyList(),
)

class AgentViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    val configured get() = c.agent.isConfigured
    val secretsVersion = c.secrets.version
    private val store = c.chats

    init { viewModelScope.launch { _state.value = _state.value.copy(saved = store.list()) } }

    fun send(text: String) {
        if (text.isBlank() || _state.value.busy) return
        _state.value = _state.value.copy(items = _state.value.items + ChatItem.User(text.trim()), busy = true, streaming = "")
        viewModelScope.launch {
            try {
                val answer = c.agent.ask(text.trim()) { step ->
                    val st = _state.value
                    when (step) {
                        is CapitolAgent.Step.TextDelta -> _state.value = st.copy(streaming = st.streaming + step.text)
                        is CapitolAgent.Step.ToolCall -> _state.value = st.copy(items = st.items + ChatItem.Tool(step.summary), streaming = "")
                        is CapitolAgent.Step.ToolDone -> {
                            val items = st.items.toMutableList()
                            val idx = items.indexOfLast { it is ChatItem.Tool && !it.done }
                            if (idx >= 0) items[idx] = (items[idx] as ChatItem.Tool).copy(done = true, ok = step.ok)
                            _state.value = st.copy(items = items)
                        }
                    }
                }
                val u = answer.usage
                val note = if (answer.condensed) listOf(ChatItem.Note("Earlier messages were condensed into a short recap to keep costs down.")) else emptyList()
                _state.value = _state.value.copy(
                    items = _state.value.items + note + ChatItem.Assistant(answer.text, u.costUsd,
                        "${compact(u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens)} in · ${compact(u.outputTokens)} out"),
                    busy = false, streaming = "", usage = _state.value.usage + u,
                )
            } catch (e: Exception) {
                com.capitol.app.diagnostics.Diagnostics.error("Agent", "ask failed: ${e.message}", e)
                _state.value = _state.value.copy(items = _state.value.items + ChatItem.Error(e.message ?: "Something went wrong"), busy = false, streaming = "")
            }
            persist()
        }
    }

    private suspend fun persist() {
        val st = _state.value
        val title = st.items.firstOrNull { it is ChatItem.User }?.let { (it as ChatItem.User).text.take(60) } ?: return
        store.save(SavedChat(st.chatId, title, System.currentTimeMillis(), st.items, st.usage, c.agent.exportSession()))
        _state.value = _state.value.copy(saved = store.list())
    }

    fun newChat() { c.agent.reset(); _state.value = AgentState(saved = _state.value.saved) }

    fun open(chat: SavedChat) {
        if (_state.value.busy) return
        c.agent.restore(chat.session)
        _state.value = AgentState(items = chat.items, chatId = chat.id, usage = chat.usage, saved = _state.value.saved)
    }

    fun delete(chat: SavedChat) = viewModelScope.launch {
        store.delete(chat.id)
        if (chat.id == _state.value.chatId) newChat()
        _state.value = _state.value.copy(saved = store.list())
    }

    private fun compact(n: Long) = if (n >= 1000) "%.1fk".format(n / 1000.0) else "$n"
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
    var showHistory by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(initialPrompt) { if (!initialPrompt.isNullOrBlank()) input = initialPrompt }
    LaunchedEffect(st.items.size, st.streaming.length / 200) { if (st.items.isNotEmpty()) list.animateScrollToItem(st.items.size) }
    val configured = remember(secretsVersion) { vm.configured }

    if (showHistory) HistorySheet(st.saved, st.chatId, onOpen = { vm.open(it); showHistory = false }, onDelete = { vm.delete(it) }, onDismiss = { showHistory = false })
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        com.capitol.app.ui.components.ScreenHeader("AI Agent", Modifier.padding(horizontal = 16.dp),
            subtitle = if (st.usage.costUsd > 0) "This chat ≈ ${usd(st.usage.costUsd)} · powered by Claude" else "Powered by Claude · runs Capitol's tools for you") {
            com.capitol.app.ui.components.HeaderIconButton(Icons.Default.History, "Saved chats", { showHistory = true })
            com.capitol.app.ui.components.HeaderIconButton(Icons.Default.Add, "New chat", vm::newChat, tint = MaterialTheme.colorScheme.primary)
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
            if (st.streaming.isNotBlank()) item { Bubble(ChatItem.Assistant(st.streaming)) }
            if (st.busy && st.streaming.isBlank()) item { Thinking() }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask about any stock…") },
                shape = RoundedCornerShape(26.dp), maxLines = 4, enabled = configured,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline, focusedBorderColor = MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(8.dp))
            FilledIconButton({ vm.send(input); input = "" }, enabled = configured && !st.busy && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

private fun usd(v: Double) = if (v < 0.01) "$%.3f".format(v) else "$%.2f".format(v)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(saved: List<SavedChat>, currentId: String, onOpen: (SavedChat) -> Unit, onDelete: (SavedChat) -> Unit, onDismiss: () -> Unit) {
    val fmt = remember { java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()) }
    androidx.compose.material3.ModalBottomSheet(onDismiss) {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item { Text("Saved chats", style = MaterialTheme.typography.titleLarge) }
            if (saved.isEmpty()) item { Text("No saved chats yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp)) }
            items(saved, key = { it.id }) { chat ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (chat.id == currentId) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpen(chat) }) {
                        Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${fmt.format(java.util.Date(chat.updatedAt))} · ≈ ${usd(chat.usage.costUsd)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ onDelete(chat) }) { Icon(Icons.Default.DeleteSweep, "Delete") }
                }
            }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                .background(MaterialTheme.colorScheme.primary).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(item.text, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is ChatItem.Assistant -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)).padding(16.dp)) {
            MarkdownText(item.text)
            item.costUsd?.let { cost ->
                Text("≈ ${usd(cost)}${item.tokens?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        is ChatItem.Note -> Text(item.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
