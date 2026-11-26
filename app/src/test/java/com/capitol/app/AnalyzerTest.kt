package com.capitol.app

import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.Signal
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.broker.IndMoneyClient
import com.capitol.app.data.broker.KiteConnectClient
import com.capitol.app.data.market.Candle
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class AnalyzerTest {
    private fun series(trend: Double, n: Int = 260): PriceSeries {
        val candles = (0 until n).map { i ->
            val base = 100 + trend * i + 3 * sin(i / 5.0)
            Candle(1_700_000_000L + i * 86_400L, base - 0.5, base + 1.5, base - 1.5, base, 1_000_000.0 + (i % 7) * 50_000)
        }
        return PriceSeries("TEST.NS", "Test Co", "INR", "NSE", candles, candles.last().close, candles[n - 2].close)
    }

    @Test fun everyIndicatorComputesOnAYearOfData() {
        val r = TechnicalAnalyzer.analyze(series(0.3), IndicatorType.entries.toSet())
        assertEquals(IndicatorType.entries.size, r.results.size)
        val missing = r.results.filter { !it.available }.map { it.type }
        assertTrue("Unavailable: $missing", missing.isEmpty())
        r.results.forEach { assertTrue(it.interpretation.isNotBlank()) }
    }

    @Test fun uptrendIsBullishDowntrendIsBearish() {
        val trend = setOf(IndicatorType.SMA, IndicatorType.EMA, IndicatorType.SUPERTREND, IndicatorType.ADX)
        assertTrue(TechnicalAnalyzer.analyze(series(0.5), trend).verdict.isBullish)
        val down = series(-0.25)
        assertTrue(TechnicalAnalyzer.analyze(down, trend).verdict.isBearish)
    }

    @Test fun filterLimitsResultsToSelection() {
        val sel = setOf(IndicatorType.RSI, IndicatorType.MACD)
        val r = TechnicalAnalyzer.analyze(series(0.1), sel)
        assertEquals(sel, r.results.map { it.type }.toSet())
        assertTrue(r.summary.contains("2 indicators"))
    }

    @Test fun shortHistoryReportsUnavailableInsteadOfCrashing() {
        val r = TechnicalAnalyzer.analyze(series(0.1, n = 15), IndicatorType.entries.toSet())
        assertTrue(r.results.any { !it.available })
    }

    @Test fun signalThresholds() {
        assertEquals(Signal.STRONG_BUY, Signal.fromScore(1.5))
        assertEquals(Signal.NEUTRAL, Signal.fromScore(0.1))
        assertEquals(Signal.SELL, Signal.fromScore(-0.6))
    }

    @Test fun kiteRequestTokenParsing() {
        assertEquals("abc123", KiteConnectClient.requestTokenFrom("capitol://kite-callback?action=login&request_token=abc123&status=success"))
        assertEquals(null, KiteConnectClient.requestTokenFrom("https://kite.zerodha.com/connect/login?v=3"))
    }

    @Test fun csvImport() {
        val csv = "Symbol,Quantity,Avg Price,LTP,Exchange\nINFY,10,1500,1600,NSE\n\"TCS\",2,3500,3400,BSE"
        val h = IndMoneyClient.parseCsv(csv)
        assertEquals(2, h.size)
        assertEquals("INFY.NS", h[0].yahooSymbol)
        assertEquals("TCS.BO", h[1].yahooSymbol)
        assertEquals(1000.0, h[0].pnl, 1e-9)
    }

    @Test fun versionComparison() {
        assertTrue(UpdateManager.isNewer("1.2.10", "1.2.9"))
        assertTrue(UpdateManager.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateManager.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateManager.isNewer("1.0.0", "1.0.1"))
    }
}
