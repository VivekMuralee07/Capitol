package com.capitol.app.data.live

import com.capitol.app.data.market.MarketDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Live quote hub. Screens register the symbols they show ([watch]); the hub takes a
 * snapshot, streams updates from Yahoo's websocket, and falls back to polling every
 * [pollMs] when the stream is silent (blocked network, outside market hours, format change).
 */
class LiveQuotes(
    private val streamer: YahooStreamer,
    private val source: suspend () -> MarketDataSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollMs: Long = 15_000,
    private val staleStreamMs: Long = 30_000,
) {
    private val _quotes = MutableStateFlow<Map<String, LiveQuote>>(emptyMap())
    val quotes: StateFlow<Map<String, LiveQuote>> = _quotes.asStateFlow()
    val streaming: StateFlow<Boolean> get() = streamer.connected

    private val owners = ConcurrentHashMap<String, Set<String>>()
    private var pollJob: Job? = null

    init {
        scope.launch { streamer.quotes.collect { q -> merge(q) } }
    }

    /** Registers [symbols] under [owner] (e.g. a screen); replaces that owner's previous set. */
    fun watch(owner: String, symbols: Collection<String>) {
        val before = all()
        if (symbols.isEmpty()) owners.remove(owner) else owners[owner] = symbols.map { it.uppercase() }.toSet()
        val now = all()
        streamer.setSymbols(now)
        val added = now - before
        if (added.isNotEmpty()) scope.launch { snapshot(added.toList()) }
        if (now.isEmpty()) { pollJob?.cancel(); pollJob = null } else if (pollJob?.isActive != true) startPolling()
    }

    fun release(owner: String) = watch(owner, emptyList())

    private fun all(): Set<String> = owners.values.flatten().toSet()

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                delay(pollMs)
                val streamQuiet = System.currentTimeMillis() - streamer.lastMessageAt > staleStreamMs
                if (streamQuiet) snapshot(all().toList())
            }
        }
    }

    /** Fetches a fresh snapshot for [symbols] (also used for pull-to-refresh). */
    suspend fun snapshot(symbols: List<String>) {
        if (symbols.isEmpty()) return
        runCatching { source().quotes(symbols) }.getOrNull()?.forEach { merge(it) }
    }

    /** Adds quotes fetched elsewhere (e.g. the market screener) so they show at once and keep updating live. */
    fun seed(quotes: Collection<LiveQuote>) = quotes.forEach { merge(it.copy(symbol = it.symbol.uppercase())) }

    internal fun merge(q: LiveQuote) {
        _quotes.update { m ->
            val old = m[q.symbol]
            // Don't let an older snapshot overwrite a newer streamed tick.
            if (old != null && old.source == LiveQuote.Source.STREAM && q.source == LiveQuote.Source.SNAPSHOT && q.time in 1 until old.time) m
            else m + (q.symbol to q.copy(name = q.name ?: old?.name, currency = q.currency ?: old?.currency))
        }
    }
}
