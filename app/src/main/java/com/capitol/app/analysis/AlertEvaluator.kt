package com.capitol.app.analysis

import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.watchlist.AlertCondition
import com.capitol.app.data.watchlist.IndicatorAlert

/**
 * Evaluates watchlist indicator alerts. Each condition is a *state* (e.g. "MACD above
 * signal"); the autopilot notifies when a state flips from false to true, which turns
 * states into events (a cross, a Supertrend flip, RSI dipping below a level).
 */
object AlertEvaluator {
    fun state(alert: IndicatorAlert, s: PriceSeries, params: IndicatorParams, verdict: Signal? = null): Boolean? {
        val c = s.closes
        val p = params.sanitized()
        if (c.size < 3) return null
        return when (alert.condition) {
            AlertCondition.RSI_BELOW -> Indicators.rsi(c, p.rsiPeriod).last()?.let { it < (alert.value ?: p.rsiOversold.toDouble()) }
            AlertCondition.RSI_ABOVE -> Indicators.rsi(c, p.rsiPeriod).last()?.let { it > (alert.value ?: p.rsiOverbought.toDouble()) }
            AlertCondition.SUPERTREND_BUY, AlertCondition.SUPERTREND_SELL -> {
                val up = Indicators.supertrend(s.candles.map { it.high }, s.candles.map { it.low }, c, p.supertrendPeriod, p.supertrendMultiplier).uptrend.last()
                up?.let { if (alert.condition == AlertCondition.SUPERTREND_BUY) it else !it }
            }
            AlertCondition.MACD_BULL_CROSS, AlertCondition.MACD_BEAR_CROSS -> {
                val m = Indicators.macd(c, p.macdFast, p.macdSlow, p.macdSignal)
                val a = m.macd.last(); val b = m.signal.last()
                if (a == null || b == null) null else if (alert.condition == AlertCondition.MACD_BULL_CROSS) a > b else a < b
            }
            AlertCondition.GOLDEN_CROSS, AlertCondition.DEATH_CROSS -> {
                val mid = Indicators.sma(c, p.smaMid).last(); val long = Indicators.sma(c, p.smaLong).last()
                if (mid == null || long == null) null else if (alert.condition == AlertCondition.GOLDEN_CROSS) mid > long else mid < long
            }
            AlertCondition.ABOVE_LONG_SMA, AlertCondition.BELOW_LONG_SMA -> Indicators.sma(c, p.smaLong).last()?.let {
                if (alert.condition == AlertCondition.ABOVE_LONG_SMA) c.last() > it else c.last() < it
            }
            AlertCondition.VERDICT_BUY -> verdict?.isBullish
            AlertCondition.VERDICT_SELL -> verdict?.isBearish
        }
    }

    /** Keys whose state went from false to true. Unknown previous state never fires (avoids alerts on creation). */
    fun fired(previous: Map<String, Boolean>, now: Map<String, Boolean>): Set<String> =
        now.filter { (k, v) -> v && previous[k] == false }.keys
}
