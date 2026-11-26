package com.capitol.app.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure indicator math. Every function returns a list aligned 1:1 with its input;
 * positions without enough history are `null`.
 */
object Indicators {

    fun sma(values: List<Double?>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        var sum = 0.0
        var count = 0
        val window = ArrayDeque<Double>()
        for (i in values.indices) {
            val v = values[i]
            if (v == null) { window.clear(); sum = 0.0; count = 0; continue }
            window.addLast(v); sum += v; count++
            if (window.size > period) { sum -= window.removeFirst(); count-- }
            if (window.size == period) out[i] = sum / period
        }
        return out
    }

    fun ema(values: List<Double?>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        val k = 2.0 / (period + 1)
        var prev: Double? = null
        val seed = ArrayList<Double>()
        for (i in values.indices) {
            val v = values[i] ?: continue
            if (prev == null) {
                seed += v
                if (seed.size == period) { prev = seed.average(); out[i] = prev }
            } else {
                prev = v * k + prev * (1 - k)
                out[i] = prev
            }
        }
        return out
    }

    /** Wilder's smoothing (RMA), used by RSI, ATR and ADX. */
    fun rma(values: List<Double?>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        var prev: Double? = null
        val seed = ArrayList<Double>()
        for (i in values.indices) {
            val v = values[i] ?: continue
            if (prev == null) {
                seed += v
                if (seed.size == period) { prev = seed.average(); out[i] = prev }
            } else {
                prev = (prev * (period - 1) + v) / period
                out[i] = prev
            }
        }
        return out
    }

    fun stdev(values: List<Double>, period: Int): List<Double?> {
        val means = sma(values, period)
        return values.indices.map { i ->
            val m = means[i] ?: return@map null
            var s = 0.0
            for (j in i - period + 1..i) { val d = values[j] - m; s += d * d }
            sqrt(s / period)
        }
    }

    fun rsi(close: List<Double>, period: Int = 14): List<Double?> {
        if (close.size < 2) return List(close.size) { null }
        val gains = ArrayList<Double?>(close.size).apply { add(null) }
        val losses = ArrayList<Double?>(close.size).apply { add(null) }
        for (i in 1 until close.size) {
            val d = close[i] - close[i - 1]
            gains += max(d, 0.0); losses += max(-d, 0.0)
        }
        val ag = rma(gains, period); val al = rma(losses, period)
        return close.indices.map { i ->
            val g = ag[i] ?: return@map null
            val l = al[i] ?: return@map null
            if (l == 0.0) 100.0 else 100.0 - 100.0 / (1 + g / l)
        }
    }

    data class Macd(val macd: List<Double?>, val signal: List<Double?>, val hist: List<Double?>)

    fun macd(close: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd {
        val f = ema(close, fast); val s = ema(close, slow)
        val line = close.indices.map { i -> val a = f[i]; val b = s[i]; if (a != null && b != null) a - b else null }
        val sig = ema(line, signal)
        val hist = close.indices.map { i -> val a = line[i]; val b = sig[i]; if (a != null && b != null) a - b else null }
        return Macd(line, sig, hist)
    }

    data class Bands(val upper: List<Double?>, val middle: List<Double?>, val lower: List<Double?>)

    fun bollinger(close: List<Double>, period: Int = 20, mult: Double = 2.0): Bands {
        val mid = sma(close, period); val sd = stdev(close, period)
        return Bands(
            upper = close.indices.map { i -> mid[i]?.let { m -> sd[i]?.let { m + mult * it } } },
            middle = mid,
            lower = close.indices.map { i -> mid[i]?.let { m -> sd[i]?.let { m - mult * it } } },
        )
    }

    fun highest(values: List<Double>, period: Int, i: Int): Double? =
        if (i + 1 < period) null else (i - period + 1..i).maxOf { values[it] }

    fun lowest(values: List<Double>, period: Int, i: Int): Double? =
        if (i + 1 < period) null else (i - period + 1..i).minOf { values[it] }

    data class Stoch(val k: List<Double?>, val d: List<Double?>)

    /** Slow stochastic (14, 3, 3). */
    fun stochastic(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 14, smoothK: Int = 3, smoothD: Int = 3): Stoch {
        val raw = c.indices.map { i ->
            val hh = highest(h, period, i) ?: return@map null
            val ll = lowest(l, period, i) ?: return@map null
            if (hh == ll) 50.0 else (c[i] - ll) / (hh - ll) * 100
        }
        val k = sma(raw, smoothK)
        return Stoch(k, sma(k, smoothD))
    }

    fun trueRange(h: List<Double>, l: List<Double>, c: List<Double>): List<Double?> = c.indices.map { i ->
        if (i == 0) h[0] - l[0]
        else maxOf(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
    }

    fun atr(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 14) = rma(trueRange(h, l, c), period)

    data class Adx(val adx: List<Double?>, val plusDi: List<Double?>, val minusDi: List<Double?>)

    fun adx(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 14): Adx {
        val n = c.size
        val plusDm = MutableList<Double?>(n) { null }
        val minusDm = MutableList<Double?>(n) { null }
        val tr = trueRange(h, l, c).toMutableList().also { if (n > 0) it[0] = null }
        for (i in 1 until n) {
            val up = h[i] - h[i - 1]; val down = l[i - 1] - l[i]
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
        }
        val sTr = rma(tr, period); val sP = rma(plusDm, period); val sM = rma(minusDm, period)
        val pDi = (0 until n).map { i -> val t = sTr[i]; val p = sP[i]; if (t != null && p != null && t != 0.0) 100 * p / t else null }
        val mDi = (0 until n).map { i -> val t = sTr[i]; val m = sM[i]; if (t != null && m != null && t != 0.0) 100 * m / t else null }
        val dx = (0 until n).map { i ->
            val p = pDi[i]; val m = mDi[i]
            if (p != null && m != null && p + m != 0.0) 100 * abs(p - m) / (p + m) else null
        }
        return Adx(rma(dx, period), pDi, mDi)
    }

    fun obv(c: List<Double>, v: List<Double>): List<Double?> {
        var acc = 0.0
        return c.indices.map { i ->
            if (i > 0) acc += when { c[i] > c[i - 1] -> v[i]; c[i] < c[i - 1] -> -v[i]; else -> 0.0 }
            acc
        }
    }

    /**
     * VWAP. For intraday bars it is anchored to each trading day; for daily+
     * bars a rolling [period] VWAP is returned.
     */
    fun vwap(h: List<Double>, l: List<Double>, c: List<Double>, v: List<Double>, times: List<Long>, intraday: Boolean, period: Int = 20): List<Double?> {
        val tp = c.indices.map { (h[it] + l[it] + c[it]) / 3 }
        if (intraday) {
            var pv = 0.0; var vol = 0.0; var day = -1L
            return c.indices.map { i ->
                val d = times[i] / 86_400
                if (d != day) { day = d; pv = 0.0; vol = 0.0 }
                pv += tp[i] * v[i]; vol += v[i]
                if (vol == 0.0) tp[i] else pv / vol
            }
        }
        return c.indices.map { i ->
            if (i + 1 < period) return@map null
            var pv = 0.0; var vol = 0.0
            for (j in i - period + 1..i) { pv += tp[j] * v[j]; vol += v[j] }
            if (vol == 0.0) null else pv / vol
        }
    }

    fun cci(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 20): List<Double?> {
        val tp = c.indices.map { (h[it] + l[it] + c[it]) / 3 }
        val m = sma(tp, period)
        return c.indices.map { i ->
            val mean = m[i] ?: return@map null
            var md = 0.0
            for (j in i - period + 1..i) md += abs(tp[j] - mean)
            md /= period
            if (md == 0.0) 0.0 else (tp[i] - mean) / (0.015 * md)
        }
    }

    fun williamsR(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 14): List<Double?> = c.indices.map { i ->
        val hh = highest(h, period, i) ?: return@map null
        val ll = lowest(l, period, i) ?: return@map null
        if (hh == ll) -50.0 else (hh - c[i]) / (hh - ll) * -100
    }

    fun mfi(h: List<Double>, l: List<Double>, c: List<Double>, v: List<Double>, period: Int = 14): List<Double?> {
        val tp = c.indices.map { (h[it] + l[it] + c[it]) / 3 }
        return c.indices.map { i ->
            if (i < period) return@map null
            var pos = 0.0; var neg = 0.0
            for (j in i - period + 1..i) {
                val flow = tp[j] * v[j]
                if (tp[j] > tp[j - 1]) pos += flow else if (tp[j] < tp[j - 1]) neg += flow
            }
            if (neg == 0.0) 100.0 else 100 - 100 / (1 + pos / neg)
        }
    }

    data class Ichimoku(
        val tenkan: List<Double?>, val kijun: List<Double?>,
        /** Senkou spans aligned to the bar they apply to (already displaced 26 forward). */
        val spanA: List<Double?>, val spanB: List<Double?>,
        /** Projected spans 26 bars ahead of the last bar (future cloud). */
        val futureSpanA: Double?, val futureSpanB: Double?,
    )

    fun ichimoku(h: List<Double>, l: List<Double>, c: List<Double>, conv: Int = 9, base: Int = 26, spanBPeriod: Int = 52, shift: Int = 26): Ichimoku {
        fun mid(p: Int, i: Int): Double? {
            val hh = highest(h, p, i) ?: return null
            val ll = lowest(l, p, i) ?: return null
            return (hh + ll) / 2
        }
        val tenkan = c.indices.map { mid(conv, it) }
        val kijun = c.indices.map { mid(base, it) }
        val rawA = c.indices.map { i -> val t = tenkan[i]; val k = kijun[i]; if (t != null && k != null) (t + k) / 2 else null }
        val rawB = c.indices.map { mid(spanBPeriod, it) }
        val spanA = c.indices.map { i -> if (i - shift >= 0) rawA[i - shift] else null }
        val spanB = c.indices.map { i -> if (i - shift >= 0) rawB[i - shift] else null }
        return Ichimoku(tenkan, kijun, spanA, spanB, rawA.lastOrNull(), rawB.lastOrNull())
    }

    data class Sar(val sar: List<Double?>, val uptrend: List<Boolean?>)

    fun parabolicSar(h: List<Double>, l: List<Double>, step: Double = 0.02, maxStep: Double = 0.2): Sar {
        val n = h.size
        val sar = MutableList<Double?>(n) { null }
        val up = MutableList<Boolean?>(n) { null }
        if (n < 2) return Sar(sar, up)
        var isUp = h[1] + l[1] >= h[0] + l[0]
        var af = step
        var ep = if (isUp) h[0] else l[0]
        var s = if (isUp) l[0] else h[0]
        for (i in 1 until n) {
            s += af * (ep - s)
            if (isUp) {
                s = min(s, min(l[i - 1], if (i > 1) l[i - 2] else l[i - 1]))
                if (l[i] < s) { isUp = false; s = ep; ep = l[i]; af = step }
                else if (h[i] > ep) { ep = h[i]; af = min(af + step, maxStep) }
            } else {
                s = max(s, max(h[i - 1], if (i > 1) h[i - 2] else h[i - 1]))
                if (h[i] > s) { isUp = true; s = ep; ep = h[i]; af = step }
                else if (l[i] < ep) { ep = l[i]; af = min(af + step, maxStep) }
            }
            sar[i] = s; up[i] = isUp
        }
        return Sar(sar, up)
    }

    fun roc(c: List<Double>, period: Int = 12): List<Double?> = c.indices.map { i ->
        if (i < period || c[i - period] == 0.0) null else (c[i] - c[i - period]) / c[i - period] * 100
    }

    data class SuperTrend(val line: List<Double?>, val uptrend: List<Boolean?>)

    fun supertrend(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 10, mult: Double = 3.0): SuperTrend {
        val a = atr(h, l, c, period)
        val n = c.size
        val line = MutableList<Double?>(n) { null }
        val dir = MutableList<Boolean?>(n) { null }
        var finalUpper = 0.0; var finalLower = 0.0; var isUp = true; var started = false
        for (i in 0 until n) {
            val atrV = a[i] ?: continue
            val mid = (h[i] + l[i]) / 2
            val bu = mid + mult * atrV; val bl = mid - mult * atrV
            if (!started) {
                finalUpper = bu; finalLower = bl; isUp = c[i] >= mid; started = true
            } else {
                finalUpper = if (bu < finalUpper || c[i - 1] > finalUpper) bu else finalUpper
                finalLower = if (bl > finalLower || c[i - 1] < finalLower) bl else finalLower
                isUp = if (isUp) c[i] >= finalLower else c[i] > finalUpper
            }
            line[i] = if (isUp) finalLower else finalUpper
            dir[i] = isUp
        }
        return SuperTrend(line, dir)
    }

    data class Pivots(val p: Double, val r1: Double, val r2: Double, val r3: Double, val s1: Double, val s2: Double, val s3: Double)

    /** Classic floor pivots computed from a completed bar. */
    fun pivots(high: Double, low: Double, close: Double): Pivots {
        val p = (high + low + close) / 3
        return Pivots(
            p = p,
            r1 = 2 * p - low, s1 = 2 * p - high,
            r2 = p + (high - low), s2 = p - (high - low),
            r3 = high + 2 * (p - low), s3 = low - 2 * (high - p),
        )
    }

    val FIB_RATIOS = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)

    /** Linear-regression slope of the last [n] points, normalised by their mean (per bar, %). */
    fun slopePct(values: List<Double?>, n: Int): Double? {
        val pts = values.takeLast(n).filterNotNull()
        if (pts.size < 3) return null
        val xs = pts.indices.map { it.toDouble() }
        val mx = xs.average(); val my = pts.average()
        var num = 0.0; var den = 0.0
        for (i in pts.indices) { num += (xs[i] - mx) * (pts[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        if (den == 0.0 || my == 0.0) return 0.0
        return num / den / abs(my) * 100
    }
}
