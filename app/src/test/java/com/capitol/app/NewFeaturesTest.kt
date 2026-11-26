package com.capitol.app

import com.capitol.app.agent.CapitolAgent
import com.capitol.app.agent.ChatItem
import com.capitol.app.agent.ChatStore
import com.capitol.app.agent.SavedChat
import com.capitol.app.analysis.AlertEvaluator
import com.capitol.app.analysis.Backtester
import com.capitol.app.analysis.IndicatorParams
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.Candle
import com.capitol.app.data.market.MarketHours
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.executeWithRetry
import com.capitol.app.data.watchlist.AlertCondition
import com.capitol.app.data.watchlist.IndicatorAlert
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.sin

class NewFeaturesTest {
    private fun series(n: Int = 400, f: (Int) -> Double): PriceSeries {
        val candles = (0 until n).map { i ->
            val p = f(i)
            Candle(1_700_000_000L + i * 86_400L, p * 0.998, p * 1.01, p * 0.99, p, 1_000_000.0 + (i % 5) * 1e5)
        }
        return PriceSeries("T", "Test", "INR", "NSE", candles, candles.last().close, candles[n - 2].close)
    }

    // --- 2. retry / back-off
    @Test fun retriesRateLimitThenSucceeds() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("ok"))
            val resp = OkHttpClient().executeWithRetry(Request.Builder().url(server.url("/x")).build(), baseDelayMs = 1)
            assertEquals("ok", resp.body!!.string())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun retryGivesUpAndReturnsLastError() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
            val resp = OkHttpClient().executeWithRetry(Request.Builder().url(server.url("/x")).build(), baseDelayMs = 1)
            assertEquals(500, resp.code)
        }
    }

    // --- 3. market hours
    @Test fun marketHours() {
        val ist = ZoneId.of("Asia/Kolkata")
        assertTrue(MarketHours.indiaOpen(ZonedDateTime.of(2026, 11, 26, 10, 0, 0, 0, ist)))   // Thursday 10:00 IST
        assertFalse(MarketHours.indiaOpen(ZonedDateTime.of(2026, 11, 26, 16, 0, 0, 0, ist)))
        assertFalse(MarketHours.indiaOpen(ZonedDateTime.of(2026, 11, 28, 10, 0, 0, 0, ist)))  // Saturday
        val ny = ZoneId.of("America/New_York")
        assertTrue(MarketHours.usOpen(ZonedDateTime.of(2026, 11, 25, 9, 45, 0, 0, ny)))
        assertFalse(MarketHours.usOpen(ZonedDateTime.of(2026, 11, 25, 9, 15, 0, 0, ny)))
    }

    // --- 4. backtest
    @Test fun backtestOnSteadyUptrendFindsRightBuySignals() {
        val s = series { 100.0 * Math.pow(1.002, it.toDouble()) + 2 * sin(it / 7.0) }
        val r = Backtester.run(s, setOf(IndicatorType.SMA, IndicatorType.SUPERTREND), horizon = 10, step = 5)!!
        assertTrue(r.bullish.signals > 10)
        assertTrue("hit rate ${r.bullish.hitRatePct}", r.bullish.hitRatePct > 70)
        assertTrue(r.summary.contains("BUY"))
    }

    @Test fun backtestNeedsEnoughHistory() {
        assertNull(Backtester.run(series(70) { 100.0 + it }, setOf(IndicatorType.RSI), horizon = 20))
    }

    // --- 6. adjustable indicator settings
    @Test fun indicatorParamsChangeResultsAndTitles() {
        val s = series(260) { 100 + 10 * sin(it / 9.0) + it * 0.05 }
        val def = TechnicalAnalyzer.analyze(s, setOf(IndicatorType.RSI)).results.single()
        val fast = TechnicalAnalyzer.analyze(s, setOf(IndicatorType.RSI), IndicatorParams(rsiPeriod = 5)).results.single()
        assertEquals("Relative Strength Index (14)", def.title)
        assertEquals("Relative Strength Index (5)", fast.title)
        assertNotEquals(def.metrics.first().value, fast.metrics.first().value)
        val bad = IndicatorParams(smaShort = 300, smaMid = 10, smaLong = 5, rsiOverbought = 200, bbStdDev = 50.0).sanitized()
        assertTrue(bad.smaShort < bad.smaMid && bad.smaMid < bad.smaLong)
        assertEquals(95, bad.rsiOverbought); assertEquals(4.0, bad.bbStdDev, 0.0)
    }

    // --- 9. indicator alerts
    @Test fun alertsFireOnlyOnTransition() {
        assertEquals(setOf("a"), AlertEvaluator.fired(mapOf("a" to false, "b" to true), mapOf("a" to true, "b" to true)))
        assertTrue(AlertEvaluator.fired(emptyMap(), mapOf("a" to true)).isEmpty()) // new alert never fires immediately
    }

    @Test fun alertStatesReflectIndicators() {
        val falling = series(120) { 200.0 - it * 1.2 }
        val p = IndicatorParams.DEFAULT
        assertEquals(true, AlertEvaluator.state(IndicatorAlert(AlertCondition.RSI_BELOW, 30.0), falling, p))
        assertEquals(false, AlertEvaluator.state(IndicatorAlert(AlertCondition.RSI_ABOVE, 70.0), falling, p))
        assertEquals(true, AlertEvaluator.state(IndicatorAlert(AlertCondition.SUPERTREND_SELL), falling, p))
        assertEquals(true, AlertEvaluator.state(IndicatorAlert(AlertCondition.MACD_BEAR_CROSS), falling, p))
        assertNull(AlertEvaluator.state(IndicatorAlert(AlertCondition.GOLDEN_CROSS), falling, p)) // not enough bars for SMA200
    }

    // --- 13. cost estimate + saved chats
    @Test fun usageCost() {
        val u = CapitolAgent.Usage(inputTokens = 10_000, outputTokens = 1_000, cacheReadTokens = 50_000, cacheWriteTokens = 2_000)
        // 10k×$4 + 1k×$20 + 50k×$0.20 + 2k×$5 per million
        assertEquals((40_000 + 20_000 + 10_000 + 10_000) / 1_000_000.0, u.costUsd, 1e-9)
    }

    @Test fun chatStoreRoundTrip() = runBlocking {
        val dir = Files.createTempDirectory("chats").toFile()
        val store = ChatStore(dir)
        val chat = SavedChat("id1", "Analyse INFY", 1L, listOf(ChatItem.User("Analyse INFY"), ChatItem.Tool("Analysing INFY.NS", true),
            ChatItem.Assistant("**Buy**", 0.03, "3k in · 400 out")), CapitolAgent.Usage(1, 2), CapitolAgent.Session(listOf("{}"), listOf("q" to "a"), 42))
        store.save(chat)
        assertEquals(chat, store.list().single())
        store.delete("id1")
        assertTrue(store.list().isEmpty())
    }
}
