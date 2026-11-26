package com.capitol.app

import com.capitol.app.analysis.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {
    private val closes = listOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08,
        45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64)

    @Test fun smaMatchesManualAverage() {
        val s = Indicators.sma(closes, 5)
        assertNull(s[3])
        assertEquals(closes.subList(0, 5).average(), s[4]!!, 1e-9)
        assertEquals(closes.takeLast(5).average(), s.last()!!, 1e-9)
    }

    @Test fun emaSeedsWithSmaThenSmooths() {
        val e = Indicators.ema(closes, 10)
        assertEquals(closes.subList(0, 10).average(), e[9]!!, 1e-9)
        val k = 2.0 / 11
        assertEquals(closes[10] * k + e[9]!! * (1 - k), e[10]!!, 1e-9)
    }

    @Test fun rsiMatchesWilderReference() {
        // Classic Wilder example: first RSI(14) on this series ≈ 70.53
        val r = Indicators.rsi(closes, 14)
        assertEquals(70.53, r[14]!!, 0.1)
        assertTrue(r.filterNotNull().all { it in 0.0..100.0 })
    }

    @Test fun rsiAllGainsIs100() {
        val up = (1..30).map { it.toDouble() }
        assertEquals(100.0, Indicators.rsi(up).last()!!, 1e-9)
    }

    @Test fun bollingerBandsAreSymmetric() {
        val b = Indicators.bollinger(closes, 20)
        val mid = b.middle.last()!!
        assertEquals(b.upper.last()!! - mid, mid - b.lower.last()!!, 1e-9)
    }

    @Test fun pivotsClassicFormula() {
        val p = Indicators.pivots(110.0, 90.0, 100.0)
        assertEquals(100.0, p.p, 1e-9)
        assertEquals(110.0, p.r1, 1e-9)
        assertEquals(90.0, p.s1, 1e-9)
        assertEquals(120.0, p.r2, 1e-9)
    }

    @Test fun macdHistogramIsDifference() {
        val series = (0 until 80).map { 100 + 10 * kotlin.math.sin(it / 6.0) }
        val m = Indicators.macd(series)
        val i = series.size - 1
        assertEquals(m.macd[i]!! - m.signal[i]!!, m.hist[i]!!, 1e-9)
    }

    @Test fun supertrendUpInRisingMarket() {
        val c = (0 until 100).map { 100.0 + it }
        val st = Indicators.supertrend(c.map { it + 1 }, c.map { it - 1 }, c)
        assertEquals(true, st.uptrend.last())
    }
}
