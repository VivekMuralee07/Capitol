package com.capitol.app.analysis

import com.capitol.app.data.market.Candle
import com.capitol.app.data.market.PriceSeries
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a [PriceSeries] into an [AnalysisReport] for any subset of indicators.
 * Each indicator yields a signal, metrics and a data-driven plain-English
 * interpretation; the report then synthesises them into one verdict.
 */
object TechnicalAnalyzer {

    fun analyze(series: PriceSeries, selected: Set<IndicatorType>, params: IndicatorParams = IndicatorParams.DEFAULT): AnalysisReport {
        val ctx = Ctx(series, params.sanitized())
        val types = if (selected.isEmpty()) IndicatorType.DEFAULTS else selected
        val results = IndicatorType.entries.filter { it in types }.map { t ->
            runCatching { compute(t, ctx) }.getOrElse { unavailable(t, "Could not compute: ${it.message}") }.copy(title = ctx.p.titleFor(t))
        }
        val usable = results.filter { it.available }
        val composite = if (usable.isEmpty()) 0.0 else usable.sumOf { it.score * weight(it.type) } / usable.sumOf { weight(it.type) }
        val verdict = Signal.fromScore(composite)
        val bull = usable.count { it.score >= 0.35 }
        val bear = usable.count { it.score <= -0.35 }
        val neutral = usable.size - bull - bear
        val catScores = usable.groupBy { it.type.category }.mapValues { (_, rs) -> rs.map { it.score }.average() }
        val levels = keyLevels(ctx)
        return AnalysisReport(
            symbol = series.symbol, name = series.name, currency = series.currency,
            lastPrice = series.regularMarketPrice, changePercent = series.changePercent,
            results = results, compositeScore = composite, verdict = verdict,
            bullishCount = bull, bearishCount = bear, neutralCount = neutral,
            summary = summarize(ctx, usable, composite, verdict, catScores, levels),
            categoryScores = catScores, keyLevels = levels,
        )
    }

    private fun weight(t: IndicatorType) = when (t.category) {
        IndicatorCategory.TREND -> 1.3
        IndicatorCategory.MOMENTUM -> 1.0
        IndicatorCategory.VOLUME -> 0.9
        IndicatorCategory.VOLATILITY -> 0.7
        IndicatorCategory.LEVELS -> 0.6
        IndicatorCategory.PATTERNS -> 0.6
    }

    /** Precomputed columns shared by all indicators. */
    private class Ctx(val series: PriceSeries, val p: IndicatorParams) {
        val candles: List<Candle> = series.candles
        val c = candles.map { it.close }
        val h = candles.map { it.high }
        val l = candles.map { it.low }
        val o = candles.map { it.open }
        val v = candles.map { it.volume }
        val t = candles.map { it.time }
        val n = candles.size
        val last get() = c.last()
        val intraday = n > 2 && (t[n - 1] - t[n - 2]) < 86_000
        val ccy = series.currency
    }

    private fun f(x: Double?) = if (x == null) "—" else when {
        abs(x) >= 1e9 -> "%.2fB".format(x / 1e9)
        abs(x) >= 1e6 -> "%.2fM".format(x / 1e6)
        abs(x) >= 1e4 -> "%.0f".format(x)
        else -> "%.2f".format(x)
    }
    private fun pct(x: Double?) = if (x == null) "—" else "%+.2f%%".format(x)

    private fun unavailable(t: IndicatorType, why: String) = IndicatorResult(
        type = t, signal = Signal.NEUTRAL, score = 0.0, headline = "Not enough data",
        metrics = emptyList(), interpretation = why, available = false,
    )

    private fun need(ctx: Ctx, bars: Int, t: IndicatorType): IndicatorResult? =
        if (ctx.n < bars) unavailable(t, "${t.shortName} needs at least $bars bars of history; only ${ctx.n} are loaded. Pick a longer range.") else null

    /** Bars since the sign of (a-b) last changed; null if never within lookback. */
    private fun barsSinceCross(a: List<Double?>, b: List<Double?>, lookback: Int = 30): Int? {
        val n = a.size
        var prevSign: Int? = null
        for (back in 0 until min(lookback, n)) {
            val i = n - 1 - back
            val x = a[i] ?: return null; val y = b[i] ?: return null
            val s = if (x >= y) 1 else -1
            if (prevSign != null && s != prevSign) return back - 1
            prevSign = s
        }
        return null
    }

    private fun clamp(x: Double) = x.coerceIn(-2.0, 2.0)

    private fun compute(t: IndicatorType, x: Ctx): IndicatorResult = when (t) {
        IndicatorType.SMA -> sma(x)
        IndicatorType.EMA -> ema(x)
        IndicatorType.MACD -> macd(x)
        IndicatorType.RSI -> rsi(x)
        IndicatorType.STOCHASTIC -> stoch(x)
        IndicatorType.BOLLINGER -> bollinger(x)
        IndicatorType.ATR -> atr(x)
        IndicatorType.ADX -> adx(x)
        IndicatorType.OBV -> obv(x)
        IndicatorType.VWAP -> vwap(x)
        IndicatorType.CCI -> cci(x)
        IndicatorType.WILLIAMS_R -> williams(x)
        IndicatorType.MFI -> mfi(x)
        IndicatorType.ICHIMOKU -> ichimoku(x)
        IndicatorType.PSAR -> psar(x)
        IndicatorType.ROC -> roc(x)
        IndicatorType.SUPERTREND -> supertrend(x)
        IndicatorType.PIVOTS -> pivots(x)
        IndicatorType.FIBONACCI -> fibonacci(x)
        IndicatorType.VOLUME -> volume(x)
        IndicatorType.PATTERNS -> patterns(x)
    }

    // ---------------------------------------------------------------- Trend

    private fun sma(x: Ctx): IndicatorResult {
        val P = x.p
        need(x, P.smaShort, IndicatorType.SMA)?.let { return it }
        val s20 = Indicators.sma(x.c, P.smaShort); val s50 = Indicators.sma(x.c, P.smaMid); val s200 = Indicators.sma(x.c, P.smaLong)
        val p = x.last
        val a20 = s20.last(); val a50 = s50.last(); val a200 = s200.last()
        var score = 0.0
        val parts = mutableListOf<String>()
        listOf(Triple("${P.smaShort}", a20, 0.5), Triple("${P.smaMid}", a50, 0.6), Triple("${P.smaLong}", a200, 0.7)).forEach { (name, avg, w) ->
            if (avg != null) {
                val d = (p - avg) / avg * 100
                score += if (p > avg) w else -w
                parts += "price is ${"%.1f".format(abs(d))}% ${if (p > avg) "above" else "below"} the $name-SMA (${f(avg)})"
            }
        }
        val slope50 = Indicators.slopePct(s50, 10)
        var cross = ""
        if (a50 != null && a200 != null) {
            val since = barsSinceCross(s50, s200, 40)
            cross = if (a50 > a200) {
                score += 0.2
                if (since != null) " A Golden Cross (${P.smaMid} above ${P.smaLong}) happened $since bars ago — a classic long-term bullish signal." else " The 50-SMA is above the 200-SMA, a long-term bullish structure."
            } else {
                score -= 0.2
                if (since != null) " A Death Cross (${P.smaMid} below ${P.smaLong}) happened $since bars ago — a classic long-term bearish signal." else " The 50-SMA is below the 200-SMA, a long-term bearish structure."
            }
        }
        val headline = when {
            score >= 1.2 -> "Price above all key averages — strong uptrend"
            score >= 0.3 -> "Mostly above averages — uptrend"
            score <= -1.2 -> "Price below all key averages — strong downtrend"
            score <= -0.3 -> "Mostly below averages — downtrend"
            else -> "Mixed — price is chopping around its averages"
        }
        val slopeTxt = slope50?.let { " The ${P.smaMid}-SMA is ${if (it > 0.02) "rising" else if (it < -0.02) "falling" else "flat"} (${"%.2f".format(it)}%/bar)." } ?: ""
        return IndicatorResult(
            IndicatorType.SMA, Signal.fromScore(score), clamp(score), headline,
            listOf(Metric("SMA${P.smaShort}", f(a20)), Metric("SMA${P.smaMid}", f(a50)), Metric("SMA${P.smaLong}", f(a200))),
            "Right now ${parts.joinToString("; ")}.$cross$slopeTxt" +
                (if (a200 == null) " (Load more history to see the ${P.smaLong}-SMA.)" else ""),
            lines = listOf(
                ChartLine("SMA${P.smaShort}", s20, LineRole.SECONDARY),
                ChartLine("SMA${P.smaMid}", s50, LineRole.TERTIARY),
                ChartLine("SMA${P.smaLong}", s200, LineRole.ACCENT),
            ),
        )
    }

    private fun ema(x: Ctx): IndicatorResult {
        val P = x.p
        need(x, P.emaSlow, IndicatorType.EMA)?.let { return it }
        val e12 = Indicators.ema(x.c, P.emaFast); val e26 = Indicators.ema(x.c, P.emaSlow)
        val a = e12.last()!!; val b = e26.last()!!; val p = x.last
        val gap = (a - b) / b * 100
        val since = barsSinceCross(e12, e26, 30)
        var score = (if (a > b) 0.8 else -0.8) + (if (p > a) 0.4 else -0.4)
        if (since != null && since <= 5) score += if (a > b) 0.5 else -0.5
        val crossTxt = since?.let { " The last crossover was a ${if (a > b) "bullish" else "bearish"} cross $it bars ago${if (it <= 5) " — a fresh signal" else ""}." } ?: ""
        return IndicatorResult(
            IndicatorType.EMA, Signal.fromScore(score), clamp(score),
            if (a > b) "Fast EMA above slow EMA — bullish momentum" else "Fast EMA below slow EMA — bearish momentum",
            listOf(Metric("EMA${P.emaFast}", f(a)), Metric("EMA${P.emaSlow}", f(b)), Metric("Spread", pct(gap))),
            "The ${P.emaFast}-EMA (${f(a)}) is ${"%.2f".format(abs(gap))}% ${if (a > b) "above" else "below"} the ${P.emaSlow}-EMA (${f(b)}), and price is ${if (p > a) "above" else "below"} the fast EMA.$crossTxt",
            lines = listOf(ChartLine("EMA${P.emaFast}", e12, LineRole.BULL), ChartLine("EMA${P.emaSlow}", e26, LineRole.BEAR)),
        )
    }

    private fun adx(x: Ctx): IndicatorResult {
        need(x, x.p.adxPeriod * 2 + 2, IndicatorType.ADX)?.let { return it }
        val r = Indicators.adx(x.h, x.l, x.c, x.p.adxPeriod)
        val adx = r.adx.last() ?: return unavailable(IndicatorType.ADX, "Not enough data for ADX.")
        val p = r.plusDi.last()!!; val m = r.minusDi.last()!!
        val strength = when { adx >= 40 -> "very strong"; adx >= 25 -> "strong"; adx >= 20 -> "emerging"; else -> "weak / no" }
        val dir = if (p > m) 1.0 else -1.0
        val mag = when { adx >= 40 -> 1.6; adx >= 25 -> 1.1; adx >= 20 -> 0.6; else -> 0.15 }
        val rising = (Indicators.slopePct(r.adx, 5) ?: 0.0) > 0
        val score = dir * mag
        return IndicatorResult(
            IndicatorType.ADX, Signal.fromScore(score), clamp(score),
            "ADX ${"%.0f".format(adx)} — $strength trend, ${if (p > m) "bulls" else "bears"} in control",
            listOf(Metric("ADX", f(adx)), Metric("+DI", f(p)), Metric("−DI", f(m))),
            "ADX at ${"%.1f".format(adx)} indicates a $strength trend and is ${if (rising) "rising (trend strengthening)" else "falling (trend losing steam)"}. " +
                "+DI (${"%.1f".format(p)}) is ${if (p > m) "above" else "below"} −DI (${"%.1f".format(m)}), so ${if (p > m) "buyers" else "sellers"} dominate. " +
                if (adx < 20) "In a weak-trend regime, oscillators (RSI, Stochastic) are usually more reliable than trend-following signals." else "In a trending regime, trend-following signals (moving averages, Supertrend) deserve more weight than overbought/oversold readings.",
            lines = listOf(
                ChartLine("ADX", r.adx, LineRole.PRIMARY),
                ChartLine("+DI", r.plusDi, LineRole.BULL),
                ChartLine("−DI", r.minusDi, LineRole.BEAR),
            ),
            levels = listOf("25" to 25.0, "20" to 20.0),
        )
    }

    private fun ichimoku(x: Ctx): IndicatorResult {
        need(x, 78, IndicatorType.ICHIMOKU)?.let { return it }
        val r = Indicators.ichimoku(x.h, x.l, x.c)
        val p = x.last
        val ten = r.tenkan.last()!!; val kij = r.kijun.last()!!
        val a = r.spanA.last(); val b = r.spanB.last()
        if (a == null || b == null) return unavailable(IndicatorType.ICHIMOKU, "Not enough data for the Ichimoku cloud.")
        val top = max(a, b); val bot = min(a, b)
        var score = 0.0
        val pos = when { p > top -> { score += 1.0; "above the cloud (bullish)" }; p < bot -> { score -= 1.0; "below the cloud (bearish)" }; else -> "inside the cloud (indecision)" }
        score += if (ten > kij) 0.5 else -0.5
        val futureGreen = (r.futureSpanA ?: 0.0) > (r.futureSpanB ?: 0.0)
        score += if (futureGreen) 0.3 else -0.3
        return IndicatorResult(
            IndicatorType.ICHIMOKU, Signal.fromScore(score), clamp(score),
            "Price $pos",
            listOf(Metric("Tenkan", f(ten)), Metric("Kijun", f(kij)), Metric("Cloud", "${f(bot)}–${f(top)}")),
            "Price (${f(p)}) is $pos, whose current range is ${f(bot)}–${f(top)}. Tenkan-sen (${f(ten)}) is ${if (ten > kij) "above" else "below"} Kijun-sen (${f(kij)}), a ${if (ten > kij) "bullish" else "bearish"} TK alignment. " +
                "The projected cloud 26 bars ahead is ${if (futureGreen) "bullish (Span A above Span B)" else "bearish (Span A below Span B)"}. " +
                "Kijun-sen at ${f(kij)} is the level to watch as dynamic support/resistance.",
            lines = listOf(
                ChartLine("Tenkan", r.tenkan, LineRole.SECONDARY),
                ChartLine("Kijun", r.kijun, LineRole.TERTIARY),
                ChartLine("Span A", r.spanA, LineRole.BULL, dotted = true),
                ChartLine("Span B", r.spanB, LineRole.BEAR, dotted = true),
            ),
        )
    }

    private fun psar(x: Ctx): IndicatorResult {
        need(x, 10, IndicatorType.PSAR)?.let { return it }
        val r = Indicators.parabolicSar(x.h, x.l)
        val s = r.sar.last()!!; val up = r.uptrend.last()!!
        var flip = 0
        for (i in x.n - 1 downTo 1) { if (r.uptrend[i] != r.uptrend[i - 1]) break; flip++ }
        val score = (if (up) 1.0 else -1.0) + if (flip <= 3) (if (up) 0.4 else -0.4) else 0.0
        val dist = abs(x.last - s) / x.last * 100
        return IndicatorResult(
            IndicatorType.PSAR, Signal.fromScore(score), clamp(score),
            if (up) "SAR below price — uptrend" else "SAR above price — downtrend",
            listOf(Metric("SAR", f(s)), Metric("Trend age", "$flip bars"), Metric("Distance", "%.2f%%".format(dist))),
            "The SAR dot is at ${f(s)}, ${"%.1f".format(dist)}% ${if (up) "below" else "above"} price. The ${if (up) "up" else "down"}trend began ${flip + 1} bars ago${if (flip <= 3) " — a fresh reversal" else ""}. " +
                "A ${if (up) "close below" else "close above"} ${f(s)} would flip the signal; traders often use it as a trailing stop.",
            lines = listOf(ChartLine("SAR", r.sar, LineRole.ACCENT, dotted = true)),
        )
    }

    private fun supertrend(x: Ctx): IndicatorResult {
        need(x, x.p.supertrendPeriod + 5, IndicatorType.SUPERTREND)?.let { return it }
        val r = Indicators.supertrend(x.h, x.l, x.c, x.p.supertrendPeriod, x.p.supertrendMultiplier)
        val line = r.line.last()!!; val up = r.uptrend.last()!!
        var age = 0
        for (i in x.n - 1 downTo 1) { if (r.uptrend[i] != r.uptrend[i - 1] || r.uptrend[i - 1] == null) break; age++ }
        val score = (if (up) 1.2 else -1.2) + if (age <= 3) (if (up) 0.4 else -0.4) else 0.0
        val bull = r.line.indices.map { if (r.uptrend[it] == true) r.line[it] else null }
        val bear = r.line.indices.map { if (r.uptrend[it] == false) r.line[it] else null }
        return IndicatorResult(
            IndicatorType.SUPERTREND, Signal.fromScore(score), clamp(score),
            if (up) "Supertrend is green — BUY mode" else "Supertrend is red — SELL mode",
            listOf(Metric("Line", f(line)), Metric("Trend age", "${age + 1} bars")),
            "Supertrend has been in ${if (up) "buy" else "sell"} mode for ${age + 1} bars${if (age <= 3) " (fresh flip!)" else ""}. " +
                "The line at ${f(line)} acts as ${if (up) "trailing support — a close below it would flip to sell" else "overhead resistance — a close above it would flip to buy"}.",
            lines = listOf(ChartLine("ST up", bull, LineRole.BULL), ChartLine("ST down", bear, LineRole.BEAR)),
        )
    }

    // ------------------------------------------------------------- Momentum

    private fun macd(x: Ctx): IndicatorResult {
        need(x, x.p.macdSlow + x.p.macdSignal, IndicatorType.MACD)?.let { return it }
        val r = Indicators.macd(x.c, x.p.macdFast, x.p.macdSlow, x.p.macdSignal)
        val m = r.macd.last()!!; val s = r.signal.last()!!; val h = r.hist.last()!!
        val hPrev = r.hist[x.n - 2] ?: h
        val since = barsSinceCross(r.macd, r.signal, 30)
        var score = (if (m > s) 0.8 else -0.8) + (if (m > 0) 0.4 else -0.4) + (if (h > hPrev) 0.3 else -0.3)
        if (since != null && since <= 3) score += if (m > s) 0.4 else -0.4
        val accel = if (h > hPrev) "expanding" else "contracting"
        return IndicatorResult(
            IndicatorType.MACD, Signal.fromScore(score), clamp(score),
            if (m > s) "MACD above signal — bullish momentum" else "MACD below signal — bearish momentum",
            listOf(Metric("MACD", "%.3f".format(m)), Metric("Signal", "%.3f".format(s)), Metric("Histogram", "%.3f".format(h))),
            "MACD (${"%.3f".format(m)}) is ${if (m > s) "above" else "below"} its signal line (${"%.3f".format(s)}) and ${if (m > 0) "above" else "below"} zero, " +
                "meaning the short-term trend is ${if (m > 0) "stronger" else "weaker"} than the long-term trend. The histogram is $accel, so momentum is ${if (h > hPrev) "building" else "fading"}" +
                (since?.let { ". Last signal-line cross: $it bars ago${if (it <= 3) " — a fresh ${if (m > s) "buy" else "sell"} trigger" else ""}." } ?: "."),
            lines = listOf(
                ChartLine("Histogram", r.hist, LineRole.MUTED, asHistogram = true),
                ChartLine("MACD", r.macd, LineRole.PRIMARY),
                ChartLine("Signal", r.signal, LineRole.ACCENT),
            ),
            levels = listOf("0" to 0.0),
        )
    }

    private fun rsi(x: Ctx): IndicatorResult {
        need(x, x.p.rsiPeriod + 6, IndicatorType.RSI)?.let { return it }
        val r = Indicators.rsi(x.c, x.p.rsiPeriod)
        val ob = x.p.rsiOverbought.toDouble(); val os = x.p.rsiOversold.toDouble()
        val v = r.last()!!
        val prev = r[x.n - 6] ?: v
        val score = when {
            v >= ob + 10 -> -1.4; v >= ob -> -0.9
            v <= os - 10 -> 1.4; v <= os -> 0.9
            v >= 55 -> 0.6; v <= 45 -> -0.6
            else -> 0.0
        } + if (v in os..ob) (if (v > prev) 0.2 else -0.2) else 0.0
        val div = divergence(x.c, r)
        val zone = when { v >= ob -> "overbought"; v <= os -> "oversold"; v >= 50 -> "bullish zone"; else -> "bearish zone" }
        return IndicatorResult(
            IndicatorType.RSI, Signal.fromScore(score + div.first), clamp(score + div.first),
            "RSI ${"%.1f".format(v)} — $zone",
            listOf(Metric("RSI", "%.1f".format(v)), Metric("5 bars ago", "%.1f".format(prev)), Metric("Zone", zone)),
            "RSI is ${"%.1f".format(v)} (${if (v > prev) "up" else "down"} from ${"%.1f".format(prev)} five bars ago). " + when {
                v >= ob -> "That is overbought: buyers have pushed hard and a pause or pullback is common, although strong trends can stay overbought for weeks."
                v <= os -> "That is oversold: selling may be exhausted and a relief bounce is common, but wait for RSI to turn back above ${x.p.rsiOversold} for confirmation."
                v >= 50 -> "Holding above 50 means gains are outweighing losses — momentum favours the bulls with room before overbought."
                else -> "Below 50 means losses are outweighing gains — momentum favours the bears, though not yet oversold."
            } + div.second,
            lines = listOf(ChartLine("RSI", r, LineRole.PRIMARY)),
            levels = listOf("${x.p.rsiOverbought}" to ob, "50" to 50.0, "${x.p.rsiOversold}" to os),
        )
    }

    /** Simple divergence check over the last 30 bars. Returns (score adj, text). */
    private fun divergence(price: List<Double>, osc: List<Double?>): Pair<Double, String> {
        val n = price.size
        if (n < 30) return 0.0 to ""
        val half = 15
        val p1 = price.subList(n - 30, n - half); val p2 = price.subList(n - half, n)
        val o1 = osc.subList(n - 30, n - half).filterNotNull(); val o2 = osc.subList(n - half, n).filterNotNull()
        if (o1.isEmpty() || o2.isEmpty()) return 0.0 to ""
        return when {
            p2.max() > p1.max() && o2.max() < o1.max() - 2 ->
                -0.5 to " ⚠ Bearish divergence: price made a higher high but the oscillator made a lower high — upward momentum is weakening."
            p2.min() < p1.min() && o2.min() > o1.min() + 2 ->
                0.5 to " ✦ Bullish divergence: price made a lower low but the oscillator made a higher low — selling pressure is fading."
            else -> 0.0 to ""
        }
    }

    private fun stoch(x: Ctx): IndicatorResult {
        need(x, x.p.stochPeriod + 6, IndicatorType.STOCHASTIC)?.let { return it }
        val r = Indicators.stochastic(x.h, x.l, x.c, x.p.stochPeriod)
        val k = r.k.last()!!; val d = r.d.last() ?: k
        val since = barsSinceCross(r.k, r.d, 10)
        var score = when { k >= 80 -> -0.9; k <= 20 -> 0.9; k > 50 -> 0.3; else -> -0.3 }
        if (since != null && since <= 2) {
            if (k > d && k < 40) score += 0.8
            if (k < d && k > 60) score -= 0.8
        } else score += if (k > d) 0.2 else -0.2
        return IndicatorResult(
            IndicatorType.STOCHASTIC, Signal.fromScore(score), clamp(score),
            "%K ${"%.0f".format(k)} / %D ${"%.0f".format(d)}" + when { k >= 80 -> " — overbought"; k <= 20 -> " — oversold"; else -> "" },
            listOf(Metric("%K", "%.1f".format(k)), Metric("%D", "%.1f".format(d))),
            "The close sits at the ${"%.0f".format(k)}th percentile of the ${x.p.stochPeriod}-bar range. %K is ${if (k > d) "above" else "below"} %D" +
                (since?.let { ", after crossing $it bars ago" } ?: "") + ". " + when {
                k >= 80 && k < d -> "A bearish cross inside the overbought zone is a classic sell trigger."
                k <= 20 && k > d -> "A bullish cross inside the oversold zone is a classic buy trigger."
                k >= 80 -> "Overbought — watch for %K to cross below %D as a sell trigger."
                k <= 20 -> "Oversold — watch for %K to cross above %D as a buy trigger."
                else -> "Mid-range readings carry little edge on their own; use them with the trend."
            },
            lines = listOf(ChartLine("%K", r.k, LineRole.PRIMARY), ChartLine("%D", r.d, LineRole.ACCENT)),
            levels = listOf("80" to 80.0, "20" to 20.0),
        )
    }

    private fun cci(x: Ctx): IndicatorResult {
        need(x, x.p.cciPeriod + 5, IndicatorType.CCI)?.let { return it }
        val r = Indicators.cci(x.h, x.l, x.c, x.p.cciPeriod)
        val v = r.last()!!; val prev = r[x.n - 2] ?: v
        val score = when { v > 200 -> -0.6; v > 100 -> 0.9; v > 0 -> 0.4; v < -200 -> 0.6; v < -100 -> -0.9; else -> -0.4 }
        return IndicatorResult(
            IndicatorType.CCI, Signal.fromScore(score), clamp(score),
            "CCI ${"%.0f".format(v)}",
            listOf(Metric("CCI", "%.1f".format(v)), Metric("Prev", "%.1f".format(prev))),
            "CCI is ${"%.0f".format(v)}. " + when {
                v > 200 -> "Extremely stretched above the mean — momentum is strong but a mean-reversion pullback is likely."
                v > 100 -> "Above +100 signals a strong new upward move is under way."
                v > 0 -> "Positive: price is above its statistical mean, mildly bullish."
                v < -200 -> "Extremely stretched below the mean — a snap-back rally is likely."
                v < -100 -> "Below −100 signals a strong downward move is under way."
                else -> "Negative: price is below its statistical mean, mildly bearish."
            } + if ((prev < 0) != (v < 0)) " It just crossed the zero line — a momentum shift." else "",
            lines = listOf(ChartLine("CCI", r, LineRole.PRIMARY)),
            levels = listOf("+100" to 100.0, "0" to 0.0, "−100" to -100.0),
        )
    }

    private fun williams(x: Ctx): IndicatorResult {
        need(x, x.p.williamsPeriod + 1, IndicatorType.WILLIAMS_R)?.let { return it }
        val r = Indicators.williamsR(x.h, x.l, x.c, x.p.williamsPeriod)
        val v = r.last()!!
        val score = when { v > -10 -> -1.0; v > -20 -> -0.7; v < -90 -> 1.0; v < -80 -> 0.7; v > -50 -> 0.3; else -> -0.3 }
        return IndicatorResult(
            IndicatorType.WILLIAMS_R, Signal.fromScore(score), clamp(score),
            "%R ${"%.0f".format(v)}" + when { v > -20 -> " — overbought"; v < -80 -> " — oversold"; else -> "" },
            listOf(Metric("%R", "%.1f".format(v))),
            "Williams %R is ${"%.1f".format(v)}: the close is ${"%.0f".format(-v)}% below the ${x.p.williamsPeriod}-bar high. " + when {
                v > -20 -> "Overbought — the stock is closing near its highs; good for trend followers, risky for new buyers."
                v < -80 -> "Oversold — the stock is closing near its lows; a move back above −80 would be a buy trigger."
                else -> "Mid-range — no extreme reading."
            },
            lines = listOf(ChartLine("%R", r, LineRole.PRIMARY)),
            levels = listOf("−20" to -20.0, "−80" to -80.0),
        )
    }

    private fun roc(x: Ctx): IndicatorResult {
        need(x, x.p.rocPeriod + 3, IndicatorType.ROC)?.let { return it }
        val r = Indicators.roc(x.c, x.p.rocPeriod)
        val v = r.last()!!; val prev = r[x.n - 4] ?: v
        val score = (when { v > 8 -> 1.2; v > 2 -> 0.8; v > 0 -> 0.3; v < -8 -> -1.2; v < -2 -> -0.8; else -> -0.3 }) + if (v > prev) 0.2 else -0.2
        return IndicatorResult(
            IndicatorType.ROC, Signal.fromScore(score), clamp(score),
            "Price ${pct(v)} over ${x.p.rocPeriod} bars",
            listOf(Metric("ROC", pct(v)), Metric("3 bars ago", pct(prev))),
            "Price has moved ${pct(v)} over the last ${x.p.rocPeriod} bars, and the rate of change is ${if (v > prev) "accelerating" else "decelerating"} (was ${pct(prev)}). " +
                if (abs(v) > 15) "Such a large move can indicate exhaustion — expect consolidation." else "",
            lines = listOf(ChartLine("ROC", r, LineRole.PRIMARY)),
            levels = listOf("0" to 0.0),
        )
    }

    // ----------------------------------------------------------- Volatility

    private fun bollinger(x: Ctx): IndicatorResult {
        need(x, x.p.bbPeriod, IndicatorType.BOLLINGER)?.let { return it }
        val b = Indicators.bollinger(x.c, x.p.bbPeriod, x.p.bbStdDev)
        val u = b.upper.last()!!; val m = b.middle.last()!!; val lo = b.lower.last()!!
        val p = x.last
        val pctB = if (u == lo) 0.5 else (p - lo) / (u - lo)
        val bw = (u - lo) / m * 100
        val bwHist = x.c.indices.mapNotNull { i -> val uu = b.upper[i]; val ll = b.lower[i]; val mm = b.middle[i]; if (uu != null && ll != null && mm != null) (uu - ll) / mm * 100 else null }.takeLast(120)
        val squeeze = bwHist.size > 20 && bw <= bwHist.sorted()[(bwHist.size * 0.15).toInt()]
        val score = when {
            pctB > 1.0 -> -0.6   // stretched above band
            pctB < 0.0 -> 0.6    // stretched below band
            pctB > 0.8 -> 0.5
            pctB < 0.2 -> -0.5
            p > m -> 0.3
            else -> -0.3
        }
        return IndicatorResult(
            IndicatorType.BOLLINGER, Signal.fromScore(score), clamp(score),
            "%B ${"%.2f".format(pctB)}${if (squeeze) " · squeeze!" else ""}",
            listOf(Metric("Upper", f(u)), Metric("Middle", f(m)), Metric("Lower", f(lo)), Metric("Bandwidth", "%.1f%%".format(bw))),
            "Price (${f(p)}) sits at %B = ${"%.2f".format(pctB)} inside bands of ${f(lo)}–${f(u)}. " + when {
                pctB > 1 -> "It has closed ABOVE the upper band — very strong, but statistically stretched; pullbacks toward the middle band (${f(m)}) are common."
                pctB < 0 -> "It has closed BELOW the lower band — very weak, but statistically stretched; a bounce toward the middle band (${f(m)}) is common."
                pctB > 0.8 -> "It is riding the upper band, typical of a healthy uptrend."
                pctB < 0.2 -> "It is hugging the lower band, typical of a downtrend."
                else -> "It is mid-band; the 20-SMA (${f(m)}) is the pivot between bullish and bearish."
            } + " Bandwidth is ${"%.1f".format(bw)}%" + if (squeeze) " — a volatility SQUEEZE (bottom 15% of the last 120 bars). Squeezes often precede sharp breakouts; watch which band breaks first." else ".",
            lines = listOf(
                ChartLine("Upper", b.upper, LineRole.MUTED, dotted = true),
                ChartLine("Middle", b.middle, LineRole.SECONDARY),
                ChartLine("Lower", b.lower, LineRole.MUTED, dotted = true),
            ),
        )
    }

    private fun atr(x: Ctx): IndicatorResult {
        need(x, x.p.atrPeriod + 6, IndicatorType.ATR)?.let { return it }
        val r = Indicators.atr(x.h, x.l, x.c, x.p.atrPeriod)
        val a = r.last()!!; val p = x.last
        val atrPct = a / p * 100
        val avg = r.takeLast(50).filterNotNull().average()
        val rising = a > avg
        return IndicatorResult(
            IndicatorType.ATR, Signal.NEUTRAL, 0.0,
            "ATR ${f(a)} (${"%.2f".format(atrPct)}% of price)",
            listOf(Metric("ATR", f(a)), Metric("ATR %", "%.2f%%".format(atrPct)), Metric("50-bar avg", f(avg)),
                Metric("2×ATR stop", f(p - 2 * a))),
            "The stock typically moves ${f(a)} ${x.ccy} (${"%.2f".format(atrPct)}%) per bar. Volatility is ${if (rising) "above" else "below"} its 50-bar average (${f(avg)}), so ${if (rising) "expect wider swings — reduce position size" else "the market is relatively calm"}. " +
                "A common long-trade stop is 2×ATR below price: ${f(p - 2 * a)}; a short-trade stop 2×ATR above: ${f(p + 2 * a)}. ATR has no direction, so it does not vote in the verdict.",
            lines = listOf(ChartLine("ATR", r, LineRole.PRIMARY)),
        )
    }

    // --------------------------------------------------------------- Volume

    private fun obv(x: Ctx): IndicatorResult {
        need(x, 25, IndicatorType.OBV)?.let { return it }
        if (x.v.takeLast(20).all { it == 0.0 }) return unavailable(IndicatorType.OBV, "No volume data for this symbol (common for indices and FX).")
        val r = Indicators.obv(x.c, x.v)
        val oSlope = Indicators.slopePct(r.map { it?.let { v -> v + 1e12 } }, 20) ?: 0.0 // offset avoids /0 around zero
        val obvTrendUp = (r.last()!! - (r[x.n - 20] ?: 0.0)) > 0
        val priceUp = x.last > x.c[x.n - 20]
        val score = when {
            obvTrendUp && priceUp -> 1.0
            !obvTrendUp && !priceUp -> -1.0
            obvTrendUp && !priceUp -> 0.7
            else -> -0.7
        }
        return IndicatorResult(
            IndicatorType.OBV, Signal.fromScore(score), score,
            when {
                obvTrendUp && priceUp -> "Volume confirms the rise"
                !obvTrendUp && !priceUp -> "Volume confirms the decline"
                obvTrendUp -> "Accumulation — OBV up while price is down"
                else -> "Distribution — OBV down while price is up"
            },
            listOf(Metric("OBV", f(r.last())), Metric("20-bar OBV", if (obvTrendUp) "Rising" else "Falling"), Metric("20-bar price", if (priceUp) "Rising" else "Falling")),
            "Over the last 20 bars, OBV has been ${if (obvTrendUp) "rising" else "falling"} while price has been ${if (priceUp) "rising" else "falling"}. " + when {
                obvTrendUp && priceUp -> "Volume is flowing in on up days, confirming the uptrend is backed by real buying."
                !obvTrendUp && !priceUp -> "Volume is heavier on down days, confirming the selling."
                obvTrendUp -> "That bullish divergence suggests quiet accumulation — smart money may be buying the dip."
                else -> "That bearish divergence suggests distribution — the rally lacks volume support."
            } + if (oSlope == 0.0) "" else "",
            lines = listOf(ChartLine("OBV", r, LineRole.PRIMARY)),
        )
    }

    private fun vwap(x: Ctx): IndicatorResult {
        need(x, 20, IndicatorType.VWAP)?.let { return it }
        if (x.v.takeLast(20).all { it == 0.0 }) return unavailable(IndicatorType.VWAP, "No volume data for this symbol.")
        val r = Indicators.vwap(x.h, x.l, x.c, x.v, x.t, x.intraday)
        val w = r.last()!!; val p = x.last
        val d = (p - w) / w * 100
        val score = when { d > 3 -> 0.6; d > 0 -> 0.9; d < -3 -> -0.6; else -> -0.9 }
        return IndicatorResult(
            IndicatorType.VWAP, Signal.fromScore(score), score,
            "Price ${pct(d)} vs VWAP",
            listOf(Metric("VWAP", f(w)), Metric("Distance", pct(d)), Metric("Mode", if (x.intraday) "Session" else "Rolling 20")),
            "${if (x.intraday) "Session-anchored" else "Rolling 20-bar"} VWAP is ${f(w)}, and price is ${"%.2f".format(abs(d))}% ${if (d > 0) "above" else "below"} it. " +
                (if (d > 0) "Buyers are paying above the average volume-weighted price — a bullish sign of demand." else "Price is below the average volume-weighted price — sellers are in control.") +
                if (abs(d) > 3) " The gap is large, so a reversion toward VWAP is possible." else "",
            lines = listOf(ChartLine("VWAP", r, LineRole.ACCENT)),
        )
    }

    private fun mfi(x: Ctx): IndicatorResult {
        need(x, x.p.mfiPeriod + 6, IndicatorType.MFI)?.let { return it }
        if (x.v.takeLast(20).all { it == 0.0 }) return unavailable(IndicatorType.MFI, "No volume data for this symbol.")
        val r = Indicators.mfi(x.h, x.l, x.c, x.v, x.p.mfiPeriod)
        val v = r.last()!!
        val div = divergence(x.c, r)
        val score = when { v >= 80 -> -1.0; v <= 20 -> 1.0; v > 55 -> 0.5; v < 45 -> -0.5; else -> 0.0 } + div.first
        return IndicatorResult(
            IndicatorType.MFI, Signal.fromScore(score), clamp(score),
            "MFI ${"%.0f".format(v)}" + when { v >= 80 -> " — overbought"; v <= 20 -> " — oversold"; else -> "" },
            listOf(Metric("MFI", "%.1f".format(v))),
            "Money Flow Index is ${"%.1f".format(v)}. " + when {
                v >= 80 -> "Heavy money inflows have pushed it into overbought territory — a pullback is likely as buying exhausts."
                v <= 20 -> "Heavy outflows have pushed it into oversold territory — selling may be exhausted."
                v > 55 -> "More money is flowing in than out — buyers are accumulating."
                v < 45 -> "More money is flowing out than in — sellers are distributing."
                else -> "Money flow is balanced."
            } + div.second,
            lines = listOf(ChartLine("MFI", r, LineRole.PRIMARY)),
            levels = listOf("80" to 80.0, "20" to 20.0),
        )
    }

    private fun volume(x: Ctx): IndicatorResult {
        need(x, 21, IndicatorType.VOLUME)?.let { return it }
        if (x.v.takeLast(20).all { it == 0.0 }) return unavailable(IndicatorType.VOLUME, "No volume data for this symbol.")
        val avg20 = x.v.subList(x.n - 21, x.n - 1).average()
        val today = x.v.last()
        val ratio = if (avg20 == 0.0) 1.0 else today / avg20
        val dayUp = x.last >= x.c[x.n - 2]
        var upVol = 0.0; var downVol = 0.0
        for (i in x.n - 20 until x.n) if (x.c[i] >= x.c[i - 1]) upVol += x.v[i] else downVol += x.v[i]
        val upShare = upVol / (upVol + downVol).coerceAtLeast(1.0)
        var score = (upShare - 0.5) * 3
        if (ratio > 1.5) score += if (dayUp) 0.6 else -0.6
        val volMA = Indicators.sma(x.v, 20)
        return IndicatorResult(
            IndicatorType.VOLUME, Signal.fromScore(score), clamp(score),
            "Volume ${"%.1f".format(ratio)}× average on a ${if (dayUp) "green" else "red"} bar",
            listOf(Metric("Last", f(today)), Metric("20-bar avg", f(avg20)), Metric("Up-volume share", "%.0f%%".format(upShare * 100))),
            "The last bar traded ${"%.1f".format(ratio)}× its 20-bar average volume on a ${if (dayUp) "rising" else "falling"} close. " +
                (if (ratio > 1.5) "That is a volume spike — ${if (dayUp) "strong buying conviction" else "strong selling pressure"}. " else if (ratio < 0.6) "Volume is light, so the move carries little conviction. " else "") +
                "${"%.0f".format(upShare * 100)}% of the last 20 bars' volume came on up bars, so ${if (upShare > 0.55) "buyers" else if (upShare < 0.45) "sellers" else "neither side"} ${if (upShare in 0.45..0.55) "dominates" else "dominate"}.",
            lines = listOf(ChartLine("Volume", x.v, LineRole.MUTED, asHistogram = true), ChartLine("Vol MA20", volMA, LineRole.ACCENT)),
        )
    }

    // ------------------------------------------------------------- Levels

    private fun pivots(x: Ctx): IndicatorResult {
        need(x, 3, IndicatorType.PIVOTS)?.let { return it }
        val prev = x.candles[x.n - 2]
        val pv = Indicators.pivots(prev.high, prev.low, prev.close)
        val p = x.last
        val ladder = listOf("S3" to pv.s3, "S2" to pv.s2, "S1" to pv.s1, "P" to pv.p, "R1" to pv.r1, "R2" to pv.r2, "R3" to pv.r3)
        val below = ladder.lastOrNull { it.second <= p }; val above = ladder.firstOrNull { it.second > p }
        val score = when { p > pv.r1 -> 1.0; p > pv.p -> 0.6; p < pv.s1 -> -1.0; else -> -0.6 }
        return IndicatorResult(
            IndicatorType.PIVOTS, Signal.fromScore(score), score,
            "Trading ${if (p > pv.p) "above" else "below"} pivot ${f(pv.p)}",
            ladder.reversed().map { Metric(it.first, f(it.second)) },
            "From the previous bar (H ${f(prev.high)}, L ${f(prev.low)}, C ${f(prev.close)}) the pivot is ${f(pv.p)}. Price is ${if (p > pv.p) "above it — a bullish bias" else "below it — a bearish bias"}. " +
                "Nearest support: ${below?.let { "${it.first} ${f(it.second)}" } ?: "none below"}; nearest resistance: ${above?.let { "${it.first} ${f(it.second)}" } ?: "none above"}.",
            levels = ladder,
        )
    }

    private fun fibonacci(x: Ctx): IndicatorResult {
        need(x, 30, IndicatorType.FIBONACCI)?.let { return it }
        val look = min(x.n, 120)
        val hs = x.h.takeLast(look); val ls = x.l.takeLast(look)
        val hi = hs.max(); val lo = ls.min()
        val hiIdx = hs.indexOf(hi); val loIdx = ls.indexOf(lo)
        val upLeg = loIdx < hiIdx  // swing from low to high: retracements measured down from the high
        val levels = Indicators.FIB_RATIOS.map { r ->
            val price = if (upLeg) hi - (hi - lo) * r else lo + (hi - lo) * r
            "%.1f%%".format(r * 100) to price
        }
        val p = x.last
        val retr = if (hi == lo) 0.0 else if (upLeg) (hi - p) / (hi - lo) else (p - lo) / (hi - lo)
        val nearest = levels.minBy { abs(it.second - p) }
        val score = if (upLeg) when { retr < 0.382 -> 0.9; retr < 0.618 -> 0.3; else -> -0.8 }
        else when { retr < 0.382 -> -0.9; retr < 0.618 -> -0.3; else -> 0.8 }
        return IndicatorResult(
            IndicatorType.FIBONACCI, Signal.fromScore(score), score,
            "${"%.1f".format(retr * 100)}% retracement of the ${if (upLeg) "up" else "down"}-swing",
            levels.map { Metric(it.first, f(it.second)) },
            "Over the last $look bars the swing ran ${if (upLeg) "up from ${f(lo)} to ${f(hi)}" else "down from ${f(hi)} to ${f(lo)}"}. Price has retraced ${"%.1f".format(retr * 100)}% of that move and is closest to the ${nearest.first} level (${f(nearest.second)}). " +
                if (upLeg) when {
                    retr < 0.382 -> "A shallow pullback — the uptrend is healthy."
                    retr < 0.618 -> "A normal pullback into the 38.2–61.8% zone, where uptrends often resume. Watch for a bounce."
                    else -> "Price has broken below the 61.8% 'golden ratio', which often means the uptrend has failed."
                } else when {
                    retr < 0.382 -> "Only a weak bounce in a downtrend — sellers still dominate."
                    retr < 0.618 -> "A relief rally into the 38.2–61.8% zone, where downtrends often resume."
                    else -> "Price has reclaimed more than 61.8% of the decline — a sign the downtrend may be reversing."
                },
            levels = levels,
        )
    }

    // ------------------------------------------------------------ Patterns

    private fun patterns(x: Ctx): IndicatorResult {
        need(x, 5, IndicatorType.PATTERNS)?.let { return it }
        val found = mutableListOf<Pair<String, Double>>()
        val cs = x.candles
        val n = x.n
        fun body(c: Candle) = abs(c.close - c.open)
        fun range(c: Candle) = (c.high - c.low).coerceAtLeast(1e-9)
        fun bull(c: Candle) = c.close > c.open
        val a = cs[n - 3]; val b = cs[n - 2]; val c = cs[n - 1]
        val trendDown = x.c[n - 1] < x.c[max(0, n - 6)]
        if (!bull(b) && bull(c) && c.close >= b.open && c.open <= b.close && body(c) > body(b)) found += "Bullish Engulfing" to 1.0
        if (bull(b) && !bull(c) && c.open >= b.close && c.close <= b.open && body(c) > body(b)) found += "Bearish Engulfing" to -1.0
        val lowerWick = min(c.open, c.close) - c.low; val upperWick = c.high - max(c.open, c.close)
        if (lowerWick > 2 * body(c) && upperWick < body(c) && trendDown) found += "Hammer" to 0.8
        if (upperWick > 2 * body(c) && lowerWick < body(c) && !trendDown) found += "Shooting Star" to -0.8
        if (body(c) / range(c) < 0.1) found += "Doji (indecision)" to 0.0
        if (!bull(a) && body(b) / range(b) < 0.3 && bull(c) && c.close > (a.open + a.close) / 2) found += "Morning Star" to 1.2
        if (bull(a) && body(b) / range(b) < 0.3 && !bull(c) && c.close < (a.open + a.close) / 2) found += "Evening Star" to -1.2
        if ((n - 3 until n).all { bull(cs[it]) && cs[it].close > cs[it - 1].close }) found += "Three White Soldiers" to 1.0
        if ((n - 3 until n).all { !bull(cs[it]) && cs[it].close < cs[it - 1].close }) found += "Three Black Crows" to -1.0
        val score = if (found.isEmpty()) 0.0 else found.sumOf { it.second } / found.size
        return IndicatorResult(
            IndicatorType.PATTERNS, Signal.fromScore(score), clamp(score),
            if (found.isEmpty()) "No significant pattern on the latest candles" else found.joinToString(", ") { it.first },
            found.map { Metric(it.first, if (it.second > 0) "Bullish" else if (it.second < 0) "Bearish" else "Neutral") },
            if (found.isEmpty()) "The last three candles do not form a recognised reversal or continuation pattern."
            else found.joinToString(" ") { (name, s) ->
                when (name) {
                    "Bullish Engulfing" -> "Bullish Engulfing: today's green body completely swallowed yesterday's red body — buyers seized control."
                    "Bearish Engulfing" -> "Bearish Engulfing: today's red body completely swallowed yesterday's green body — sellers seized control."
                    "Hammer" -> "Hammer: after a decline, sellers pushed price down but buyers drove it back up — a potential bottom."
                    "Shooting Star" -> "Shooting Star: after a rise, buyers pushed price up but sellers slammed it back down — a potential top."
                    "Morning Star" -> "Morning Star: a three-candle bottom-reversal pattern."
                    "Evening Star" -> "Evening Star: a three-candle top-reversal pattern."
                    "Three White Soldiers" -> "Three White Soldiers: three strong consecutive up-closes — sustained buying."
                    "Three Black Crows" -> "Three Black Crows: three strong consecutive down-closes — sustained selling."
                    else -> "Doji: open and close are almost equal — the market is undecided; the next candle often sets direction."
                }.let { if (s == 0.0) it else it }
            } + " Confirm with the next candle and volume.",
        )
    }

    // --------------------------------------------------------- Key levels

    private fun keyLevels(x: Ctx): List<KeyLevel> {
        if (x.n < 20) return emptyList()
        val p = x.last
        val cands = mutableListOf<Pair<String, Double>>()
        val look = min(x.n, 60)
        cands += "${look}-bar high" to x.h.takeLast(look).max()
        cands += "${look}-bar low" to x.l.takeLast(look).min()
        Indicators.sma(x.c, x.p.smaMid).last()?.let { cands += "SMA${x.p.smaMid}" to it }
        Indicators.sma(x.c, x.p.smaLong).last()?.let { cands += "SMA${x.p.smaLong}" to it }
        val prev = x.candles[x.n - 2]
        val pv = Indicators.pivots(prev.high, prev.low, prev.close)
        cands += listOf("Pivot" to pv.p, "R1" to pv.r1, "S1" to pv.s1)
        val sup = cands.filter { it.second < p }.sortedByDescending { it.second }.take(3)
        val res = cands.filter { it.second > p }.sortedBy { it.second }.take(3)
        return sup.map { KeyLevel(it.first, it.second, true) } + res.map { KeyLevel(it.first, it.second, false) }
    }

    // ------------------------------------------------------------ Summary

    private fun summarize(
        x: Ctx, rs: List<IndicatorResult>, composite: Double, verdict: Signal,
        cats: Map<IndicatorCategory, Double>, levels: List<KeyLevel>,
    ): String {
        if (rs.isEmpty()) return "Not enough price history to analyse the selected indicators. Try a longer time range."
        val bulls = rs.filter { it.score >= 0.35 }.map { it.type.shortName }
        val bears = rs.filter { it.score <= -0.35 }.map { it.type.shortName }
        fun mood(s: Double) = when { s >= 0.6 -> "clearly bullish"; s >= 0.2 -> "leaning bullish"; s <= -0.6 -> "clearly bearish"; s <= -0.2 -> "leaning bearish"; else -> "neutral" }
        val sb = StringBuilder()
        sb.append("Across the ${rs.size} indicator${if (rs.size > 1) "s" else ""} you selected, the overall reading is ${verdict.label.uppercase()} (score ${"%+.2f".format(composite)} on a −2…+2 scale). ")
        if (bulls.isNotEmpty()) sb.append("Bullish: ${bulls.joinToString()}. ")
        if (bears.isNotEmpty()) sb.append("Bearish: ${bears.joinToString()}. ")
        val catTxt = cats.entries.joinToString("; ") { "${it.key.label} is ${mood(it.value)}" }
        if (catTxt.isNotEmpty()) sb.append("By category — $catTxt. ")
        val trend = cats[IndicatorCategory.TREND]; val mom = cats[IndicatorCategory.MOMENTUM]
        if (trend != null && mom != null) {
            when {
                trend > 0.3 && mom < -0.3 -> sb.append("The trend is up but momentum is cooling — this often means a pullback or consolidation inside an uptrend; dips toward support may be buying opportunities. ")
                trend < -0.3 && mom > 0.3 -> sb.append("The trend is down but momentum is improving — a relief rally may be under way; wait for trend indicators to confirm before treating it as a reversal. ")
                trend > 0.3 && mom > 0.3 -> sb.append("Trend and momentum agree to the upside — the strongest kind of bullish confluence. ")
                trend < -0.3 && mom < -0.3 -> sb.append("Trend and momentum agree to the downside — avoid catching the falling knife until signals improve. ")
            }
        }
        val sup = levels.filter { it.isSupport }.firstOrNull(); val res = levels.firstOrNull { !it.isSupport }
        if (sup != null || res != null) {
            sb.append("Watch ")
            sup?.let { sb.append("support near ${"%.2f".format(it.price)} (${it.label})") }
            if (sup != null && res != null) sb.append(" and ")
            res?.let { sb.append("resistance near ${"%.2f".format(it.price)} (${it.label})") }
            sb.append(". ")
        }
        if (rs.size < 3) sb.append("Tip: combine indicators from different categories (trend + momentum + volume) for a more reliable picture. ")
        sb.append("This is educational technical analysis, not investment advice.")
        return sb.toString()
    }
}
