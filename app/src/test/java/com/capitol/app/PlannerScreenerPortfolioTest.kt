package com.capitol.app

import com.capitol.app.analysis.Direction
import com.capitol.app.analysis.PortfolioAnalytics
import com.capitol.app.analysis.ScreenCondition
import com.capitol.app.analysis.ScreenFilter
import com.capitol.app.analysis.Screener
import com.capitol.app.analysis.IndicatorParams
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.Signal
import com.capitol.app.analysis.TradePlanner
import com.capitol.app.data.broker.Broker
import com.capitol.app.data.broker.Holding
import com.capitol.app.data.market.Candle
import com.capitol.app.data.market.EventType
import com.capitol.app.data.market.EventsCalendar
import com.capitol.app.data.market.Fundamentals
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.ui.lock.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.sin

class PlannerScreenerPortfolioTest {
    /** Daily series of [n] bars ending on the same day regardless of length (like real data). */
    private fun series(n: Int = 300, sym: String = "T", f: (Int) -> Double): PriceSeries {
        val end = 1_700_000_000L + 400 * 86_400L
        val c = (0 until n).map { i -> val p = f(i); Candle(end - (n - 1 - i) * 86_400L, p, p * 1.01, p * 0.99, p, 1e6) }
        return PriceSeries(sym, sym, "INR", "NSE", c, c.last().close, c[n - 2].close)
    }
    private val uptrend = series { 100.0 + it * 0.4 + 3 * sin(it / 6.0) }

    // --- 11. trade planner
    @Test fun longPlanIsConsistent() {
        val p = TradePlanner.plan(uptrend, Direction.LONG, capital = 100_000.0, riskPct = 1.0)!!
        assertTrue(p.stop < p.entry)
        assertTrue(p.targets.isNotEmpty() && p.targets.all { it.price > p.entry })
        assertEquals(p.targets.map { it.rMultiple }.sorted(), p.targets.map { it.rMultiple })
        val budget = 1_000.0
        assertEquals(minOf(floor(budget / p.riskPerShare), floor(100_000 / p.entry)).toInt(), p.quantity)
        assertTrue(p.riskAmount <= budget + 1e-6)
        assertTrue(p.entryZoneLow <= p.entryZoneHigh)
    }

    @Test fun shortPlanMirrorsAndWarnsAgainstTrend() {
        val p = TradePlanner.plan(uptrend, Direction.SHORT, 50_000.0, 2.0, verdict = Signal.STRONG_BUY)!!
        assertTrue(p.stop > p.entry)
        assertTrue(p.targets.all { it.price < p.entry })
        assertTrue(p.warnings.any { it.contains("other way") })
        assertNull(TradePlanner.plan(series(20) { 100.0 }, Direction.LONG, 1e5, 1.0))
    }

    // --- 9. screener
    @Test fun screenerMatchesAndFreshness() {
        val p = IndicatorParams.DEFAULT; val inds = IndicatorType.DEFAULTS
        val st = listOf(ScreenFilter(ScreenCondition.SUPERTREND_BUY))
        assertNotNull(Screener.matches(uptrend, st, freshOnly = false, p, inds))
        assertNull(Screener.matches(uptrend, st, freshOnly = true, p, inds)) // long-running uptrend isn't "new"
        // Down for 250 bars, then a sharp turn up in the last 3 → Supertrend just turned green.
        val turned = series(260) { if (it < 257) 300.0 - it * 0.5 else 172.0 + (it - 256) * 25.0 }
        val r = Screener.matches(turned, st, freshOnly = true, p, inds)
        assertNotNull(r); assertTrue(r!!.single().endsWith("(new)"))
        assertNull(Screener.matches(uptrend, listOf(ScreenFilter(ScreenCondition.RSI_BELOW, 30.0)), false, p, inds))
        assertNotNull(Screener.matches(uptrend, listOf(ScreenFilter(ScreenCondition.NEAR_HIGH), ScreenFilter(ScreenCondition.ABOVE_LONG_SMA)), false, p, inds))
    }

    // --- 10. portfolio analytics
    @Test fun portfolioAnalytics() {
        fun h(sym: String, ex: String, q: Double, avg: Double, ltp: Double, b: Broker = Broker.KITE) = Holding(b, sym, ex, q, avg, ltp, null)
        val holdings = listOf(
            h("INFY", "NSE", 10.0, 1000.0, 1500.0), h("INFY", "NSE", 10.0, 1200.0, 1500.0, Broker.INDMONEY), // same stock, two brokers
            h("TCS", "NSE", 5.0, 3000.0, 4000.0), h("HDFCBANK", "NSE", 10.0, 1600.0, 1000.0),
            h("AAPL", "US", 2.0, 150.0, 200.0),
        )
        val series = mapOf("INFY.NS" to series(300, "INFY.NS") { 1000.0 + it * 2 }, "TCS.NS" to series(250, "TCS.NS") { 3000.0 + it * 4 })
        val out = PortfolioAnalytics.compute(holdings, series, mapOf("INFY.NS" to "Technology", "TCS.NS" to "Technology", "HDFCBANK.NS" to "Financial Services"))
        assertEquals(listOf("INR", "USD"), out.map { it.currency }.sorted())
        val inr = out.first { it.currency == "INR" }
        assertEquals(30_000.0 + 20_000 + 10_000, inr.total, 1e-6)
        assertEquals(50.0, inr.byHolding.first { it.label == "INFY" }.pct, 1e-6) // merged across brokers
        assertEquals(50.0, inr.largestPct, 1e-6)
        assertEquals(83.33, inr.bySector.first().pct, 0.01); assertEquals("Technology", inr.bySector.first().label)
        assertEquals(1 / (0.25 + 0.1111 + 0.0278), inr.effectiveHoldings, 0.01)
        assertTrue(inr.warnings.any { it.contains("largest position") })
        assertTrue(inr.warnings.any { it.contains("Technology") })
        assertEquals(listOf("HDFCBANK"), inr.losers.map { it.tradingSymbol })
        // Value history starts where both priced holdings have data (TCS has fewer bars) and values INFY at 20 shares.
        val vh = inr.valueHistory
        assertEquals(250, vh.size)
        assertEquals(20 * (1000.0 + 50 * 2) + 5 * 3000.0, vh.first().second, 1e-6)
    }

    // --- 13. calendar
    @Test fun calendarEventsWithinHorizon() {
        val now = 1_800_000_000L
        val f = Fundamentals("INFY.NS", nextEarningsDate = now + 3 * 86_400, exDividendDate = now - 30 * 86_400, dividendDate = now + 200 * 86_400)
        val ev = EventsCalendar.eventsFor(f, "Infosys", now)
        assertEquals(listOf(EventType.EARNINGS), ev.map { it.type })
        assertEquals(3, ev.single().daysFrom(now))
    }

    // --- 5. app lock
    @Test fun appLockTiming() {
        assertFalse(AppLock.shouldRelock(10_000))
        assertTrue(AppLock.shouldRelock(AppLock.RELOCK_AFTER_MS))
        AppLock.onForeground(enabled = true, coldStart = true); assertTrue(AppLock.locked)
        AppLock.onForeground(enabled = false, coldStart = false); assertFalse(AppLock.locked)
    }
}
