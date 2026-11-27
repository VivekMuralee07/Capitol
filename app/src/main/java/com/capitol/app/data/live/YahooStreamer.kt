package com.capitol.app.data.live

import com.capitol.app.diagnostics.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Yahoo Finance's streaming price feed (the one behind yfinance's `WebSocket`).
 * Subscribes to symbols, re-sends the subscription every 15 s as a heartbeat, and
 * reconnects with back-off. Emits decoded [LiveQuote]s.
 */
class YahooStreamer(
    baseHttp: OkHttpClient,
    private val url: String = URL,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val http = baseHttp.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val _quotes = MutableSharedFlow<LiveQuote>(extraBufferCapacity = 256)
    val quotes: SharedFlow<LiveQuote> = _quotes.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var symbols: Set<String> = emptySet()
    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile var lastMessageAt = 0L
        private set

    /** Sets the full set of subscribed symbols; connects when non-empty, disconnects when empty. */
    @Synchronized
    fun setSymbols(newSymbols: Set<String>) {
        val added = newSymbols - symbols; val removed = symbols - newSymbols
        symbols = newSymbols
        if (newSymbols.isEmpty()) { stop(); return }
        if (loop?.isActive != true) start()
        else {
            if (removed.isNotEmpty()) send(mapOf("unsubscribe" to removed))
            if (added.isNotEmpty()) send(mapOf("subscribe" to newSymbols))
        }
    }

    private fun start() {
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive && symbols.isNotEmpty()) {
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                socket = http.newWebSocket(Request.Builder().url(url).header("User-Agent", UA).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        _connected.value = true; backoff = 1_000L
                        send(mapOf("subscribe" to symbols))
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Diagnostics.info("Live", "stream failure: ${t.message}"); closed.complete(Unit)
                    }
                })
                // Heartbeat: Yahoo expects the subscription to be repeated periodically.
                val beat = launch { while (isActive) { delay(HEARTBEAT_MS); send(mapOf("subscribe" to symbols)) } }
                closed.await()
                beat.cancel(); _connected.value = false; socket = null
                if (symbols.isEmpty()) break
                delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    private fun stop() {
        loop?.cancel(); loop = null
        socket?.close(1000, "done"); socket = null
        _connected.value = false
    }

    internal fun handle(text: String) {
        val b64 = runCatching { (json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.content }.getOrNull() ?: text
        PricingDecoder.decodeBase64(b64)?.let { lastMessageAt = System.currentTimeMillis(); _quotes.tryEmit(it) }
    }

    private fun send(msg: Map<String, Collection<String>>) {
        val (k, v) = msg.entries.first()
        socket?.send(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(mapOf(k to JsonArray(v.map { JsonPrimitive(it) })))))
    }

    companion object {
        const val URL = "wss://streamer.finance.yahoo.com/?version=2"
        const val HEARTBEAT_MS = 15_000L
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
