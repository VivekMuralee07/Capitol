package com.capitol.app

import com.capitol.app.data.live.LiveCandles
import com.capitol.app.data.live.LiveQuote
import com.capitol.app.data.live.LiveQuotes
import com.capitol.app.data.live.PricingDecoder
import com.capitol.app.data.live.YahooStreamer
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.Fundamentals
import com.capitol.app.data.market.MarketDataSource
import com.capitol.app.data.market.NewsItem
import com.capitol.app.data.market.QuoteSnapshot
import com.capitol.app.data.market.SymbolMatch
import com.capitol.app.data.market.YahooFinanceDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LiveTest {
    // Generated with yfinance's own pricing_pb2 (see PR description), i.e. the real wire format.
    private val realMessage = """{"type": "pricing", "message": "CgtSRUxJQU5DRS5OUxUAaDhFGICQsM7YZiIDSU5SKgNOU0kwCDgBRQAAoD9IjtqWAVUAADlFXQBENUVlAAASQmoTUkVMSUFOQ0UgSU5EVVNUUklFU30AMDZFhQEAIDZF2AEEiQIAAEDlnDCyQg=="}"""
    private val negativeB64 = "CgdJTkZZLk5TFQCAu0QYkN6wzthmRQAAIMBIxgFlCtcZwg=="

    @Test fun decodesRealYahooPricingMessage() {
        val b64 = realMessage.substringAfter("\"message\": \"").substringBefore('"')
        val q = PricingDecoder.decodeBase64(b64)!!
        assertEquals("RELIANCE.NS", q.symbol)
        assertEquals(2950.5, q.price, 1e-3)
        assertEquals(1_764_240_000_000L, q.time)
        assertEquals("INR", q.currency)
        assertEquals(1, q.marketHours)
        assertEquals(1.25, q.changePercent, 1e-6)
        assertEquals(1_234_567L, q.dayVolume)
        assertEquals(2960.0, q.dayHigh!!, 1e-3); assertEquals(2900.25, q.dayLow!!, 1e-3)
        assertEquals(36.5, q.change, 1e-3)
        assertEquals("RELIANCE INDUSTRIES", q.name)
        assertEquals(2914.0, q.previousClose, 1e-3)
    }

    @Test fun decodesNegativeChangeAndRejectsGarbage() {
        val q = PricingDecoder.decodeBase64(negativeB64)!!
        assertEquals("INFY.NS", q.symbol); assertEquals(-2.5, q.changePercent, 1e-6); assertEquals(99L, q.dayVolume)
        assertNull(PricingDecoder.decodeBase64("not base64!!"))
        assertNull(PricingDecoder.decode(byteArrayOf(0x15, 0x00))) // truncated
    }

    @Test fun streamerSubscribesAndEmits() = runBlocking {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {}
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    if (text.contains("\"subscribe\"")) webSocket.send(realMessage)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val streamer = YahooStreamer(OkHttpClient(), server.url("/").toString().replace("http", "ws"), scope)
            streamer.setSymbols(setOf("RELIANCE.NS", "^NSEI"))
            val q = withTimeout(5_000) { streamer.quotes.first() }
            assertEquals("RELIANCE.NS", q.symbol)
            val sub = received.poll(2, TimeUnit.SECONDS)!!
            assertTrue(sub.contains("\"subscribe\"") && sub.contains("RELIANCE.NS") && sub.contains("^NSEI"))
            assertTrue(streamer.connected.value)
            streamer.setSymbols(emptySet())
            withTimeout(5_000) { streamer.connected.first { !it } }
            scope.cancel()
            Thread.sleep(300) // let the close handshake finish before the server shuts down
        }
    }

    @Test fun chartRangesOfferedAndCustomWindowsUseDates() {
        assertEquals(listOf("1D", "5D", "1W", "1M", "3M", "6M", "1Y", "3Y", "5Y"), ChartRange.PICKER.map { it.label })
        MockWebServer().use { server ->
            val chart = """{"chart":{"result":[{"meta":{"symbol":"TCS.NS","currency":"INR","regularMarketPrice":12.0},
                "timestamp":[1700000000,1700086400],"indicators":{"quote":[{"open":[10,11],"high":[10,11],"low":[10,11],"close":[10,12],"volume":[1,1]}]}}],"error":null}}"""
            repeat(3) { server.enqueue(MockResponse().setBody(chart)) }
            val yahoo = YahooFinanceDataSource(OkHttpClient(), server.url("").toString().trimEnd('/'))
            runBlocking {
                yahoo.history("TCS.NS", ChartRange.W1)
                yahoo.history("TCS.NS", ChartRange.Y3)
                yahoo.history("TCS.NS", ChartRange.D5)
            }
            val w = server.takeRequest().requestUrl!!
            assertNull(w.queryParameter("range"))
            val span = w.queryParameter("period2")!!.toLong() - w.queryParameter("period1")!!.toLong()
            assertEquals(7 * 86_400L, span); assertEquals("30m", w.queryParameter("interval"))
            val y = server.takeRequest().requestUrl!!
            assertEquals(3 * 365 * 86_400L, y.queryParameter("period2")!!.toLong() - y.queryParameter("period1")!!.toLong())
            val d = server.takeRequest().requestUrl!!
            assertEquals("5d", d.queryParameter("range")); assertNull(d.queryParameter("period1"))
        }
    }

    @Test fun snapshotsAndMovers() {
        val snap = QuoteSnapshot("A", "A", 10.0, 0.5, 5.0, "INR", listOf(9.0, 9.5, 10.0))
        val upd = LiveCandles.apply(snap, LiveQuote("A", 11.0, 1.5, 15.0))
        assertEquals(11.0, upd.price, 0.0); assertEquals(15.0, upd.changePercent, 0.0); assertEquals(listOf(9.0, 9.5, 11.0), upd.sparkline)
        val quotes = (1..8).associate { i -> "S$i" to LiveQuote("S$i", 100.0, 0.0, (i - 4).toDouble() * 1.5) }
        val (g, l) = LiveCandles.movers(quotes, quotes.keys + "MISSING", n = 3)
        assertEquals(listOf("S8", "S7", "S6"), g.map { it.symbol })
        assertEquals(listOf("S1", "S2", "S3"), l.map { it.symbol })
        assertTrue(g.none { it.changePercent <= 0 } && l.none { it.changePercent >= 0 })
    }

    @Test fun parsesBatchQuotes() {
        val body = """{"quoteResponse":{"result":[{"symbol":"TCS.NS","regularMarketPrice":4012.5,"regularMarketChange":-20.1,
            "regularMarketChangePercent":-0.5,"regularMarketDayHigh":4050,"regularMarketDayLow":4001,"regularMarketVolume":1500000,
            "regularMarketTime":1764240000,"shortName":"TCS","currency":"INR","marketState":"REGULAR"},{"symbol":"BAD"}],"error":null}}"""
        val q = YahooFinanceDataSource(OkHttpClient()).parseQuotes(body).single()
        assertEquals("TCS.NS", q.symbol); assertEquals(-0.5, q.changePercent, 0.0); assertEquals(1, q.marketHours)
        assertEquals(1_764_240_000_000L, q.time); assertEquals(1_500_000L, q.dayVolume)
    }

    private class FakeSource : MarketDataSource {
        var calls = 0
        var price = 100.0
        override suspend fun history(symbol: String, range: ChartRange) = error("unused")
        override suspend fun search(query: String) = emptyList<SymbolMatch>()
        override suspend fun fundamentals(symbol: String) = Fundamentals(symbol)
        override suspend fun news(symbol: String) = emptyList<NewsItem>()
        override suspend fun quotes(symbols: List<String>): List<LiveQuote> { calls++; return symbols.map { LiveQuote(it, price, 1.0, 1.0, time = 1_000L) } }
    }

    @Test fun hubSnapshotsPollsAndPrefersNewerStreamTicks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val src = FakeSource()
        // Streamer points at a dead endpoint: the hub must fall back to polling.
        val streamer = YahooStreamer(OkHttpClient(), "ws://127.0.0.1:9/", scope)
        val hub = LiveQuotes(streamer, { src }, scope, pollMs = 200, staleStreamMs = 0)
        hub.watch("home", listOf("aapl", "MSFT"))
        withTimeout(3_000) { hub.quotes.first { it.size == 2 } }
        src.price = 105.0
        withTimeout(3_000) { hub.quotes.first { it["AAPL"]?.price == 105.0 } }
        assertTrue(src.calls >= 2)
        // A streamed tick newer than snapshots isn't overwritten by an older snapshot.
        hub.merge(LiveQuote("AAPL", 200.0, 5.0, 2.5, time = 9_999_999_999_999L, source = LiveQuote.Source.STREAM))
        hub.merge(LiveQuote("AAPL", 101.0, 1.0, 1.0, time = 5_000L))
        assertEquals(200.0, hub.quotes.value.getValue("AAPL").price, 0.0)
        hub.release("home")
        val before = src.calls
        Thread.sleep(600)
        assertEquals(before, src.calls) // polling stops when nothing is watched
        scope.cancel()
    }
}
