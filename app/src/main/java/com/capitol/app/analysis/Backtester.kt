package com.capitol.app.analysis

import com.capitol.app.data.market.PriceSeries

data class BacktestBucket(val label: String, val signals: Int, val avgReturnPct: Double, val hitRatePct: Double)

data class BacktestResult(
    val horizonBars: Int,
    val samples: Int,
    val periodLabel: String,
    val bullish: BacktestBucket,
    val bearish: BacktestBucket,
    val neutral: BacktestBucket,
    val baselineAvgPct: Double,
    val baselineUpPct: Double,
) {
    val summary: String get() = buildString {
        append("Tested $samples past dates over $periodLabel, looking $horizonBars bars ahead each time. ")
        if (bullish.signals > 0) append("When this indicator set said BUY (${bullish.signals}×), price was higher ${"%.0f".format(bullish.hitRatePct)}% of the time, averaging ${"%+.2f".format(bullish.avgReturnPct)}%. ")
        else append("It never gave a BUY signal in this period. ")
        if (bearish.signals > 0) append("SELL signals (${bearish.signals}×) were followed by a fall ${"%.0f".format(bearish.hitRatePct)}% of the time (average move ${"%+.2f".format(bearish.avgReturnPct)}%). ")
        append("For comparison, any random day averaged ${"%+.2f".format(baselineAvgPct)}% and was up ${"%.0f".format(baselineUpPct)}% of the time. ")
        val edge = bullish.avgReturnPct - baselineAvgPct
        if (bullish.signals >= 5) append(
            when {
                edge > 1.0 && bullish.hitRatePct > baselineUpPct + 5 -> "On this stock, BUY signals have had a clear edge. "
                edge < -0.5 -> "On this stock, BUY signals have done worse than random — treat them with caution. "
                else -> "On this stock, BUY signals have had little edge over random days. "
            }
        ) else append("Too few BUY signals to judge reliably. ")
        append("Past behaviour doesn't guarantee future results.")
    }
}

/**
 * Walk-forward backtest of Capitol's verdict: at each past date, analyse only the data
 * available up to that date, then record the actual price move [horizon] bars later.
 */
object Backtester {
    fun run(
        series: PriceSeries,
        selected: Set<IndicatorType>,
        params: IndicatorParams = IndicatorParams.DEFAULT,
        horizon: Int = 20,
        warmup: Int = 60,
        step: Int = 2,
        periodLabel: String = "the loaded history",
        onProgress: (Float) -> Unit = {},
    ): BacktestResult? {
        val c = series.candles
        val last = c.size - 1 - horizon
        if (last <= warmup) return null
        data class Obs(val score: Double, val fwd: Double)
        val obs = ArrayList<Obs>()
        val total = ((last - warmup) / step + 1).coerceAtLeast(1)
        var done = 0
        for (i in warmup..last step step) {
            val sub = series.copy(candles = c.subList(0, i + 1), regularMarketPrice = c[i].close, previousClose = c[i - 1].close)
            val score = TechnicalAnalyzer.analyze(sub, selected, params).compositeScore
            obs += Obs(score, (c[i + horizon].close / c[i].close - 1) * 100)
            done++
            onProgress(done.toFloat() / total)
        }
        fun bucket(label: String, xs: List<Obs>, rightWhen: (Double) -> Boolean) = BacktestBucket(
            label, xs.size,
            if (xs.isEmpty()) 0.0 else xs.map { it.fwd }.average(),
            if (xs.isEmpty()) 0.0 else xs.count { rightWhen(it.fwd) } * 100.0 / xs.size,
        )
        return BacktestResult(
            horizonBars = horizon, samples = obs.size, periodLabel = periodLabel,
            bullish = bucket("Buy signals", obs.filter { Signal.fromScore(it.score).isBullish }) { it > 0 },
            bearish = bucket("Sell signals", obs.filter { Signal.fromScore(it.score).isBearish }) { it < 0 },
            neutral = bucket("Neutral", obs.filter { Signal.fromScore(it.score) == Signal.NEUTRAL }) { it > 0 },
            baselineAvgPct = obs.map { it.fwd }.average(),
            baselineUpPct = obs.count { it.fwd > 0 } * 100.0 / obs.size,
        )
    }
}
