package com.capitol.app.analysis

import kotlinx.serialization.Serializable

/** User-adjustable indicator settings (Settings → Indicator settings). Defaults are the classic values. */
@Serializable
data class IndicatorParams(
    val smaShort: Int = 20, val smaMid: Int = 50, val smaLong: Int = 200,
    val emaFast: Int = 12, val emaSlow: Int = 26,
    val macdFast: Int = 12, val macdSlow: Int = 26, val macdSignal: Int = 9,
    val rsiPeriod: Int = 14, val rsiOverbought: Int = 70, val rsiOversold: Int = 30,
    val stochPeriod: Int = 14,
    val bbPeriod: Int = 20, val bbStdDev: Double = 2.0,
    val atrPeriod: Int = 14, val adxPeriod: Int = 14,
    val cciPeriod: Int = 20, val williamsPeriod: Int = 14, val mfiPeriod: Int = 14, val rocPeriod: Int = 12,
    val supertrendPeriod: Int = 10, val supertrendMultiplier: Double = 3.0,
) {
    /** Clamp values to sane ranges so a bad entry can't break the analysis. */
    fun sanitized(): IndicatorParams {
        fun p(v: Int, lo: Int = 2, hi: Int = 400) = v.coerceIn(lo, hi)
        val s = p(smaShort); val m = p(smaMid).coerceAtLeast(s + 1); val l = p(smaLong).coerceAtLeast(m + 1)
        val ef = p(emaFast); val es = p(emaSlow).coerceAtLeast(ef + 1)
        val mf = p(macdFast); val ms = p(macdSlow).coerceAtLeast(mf + 1)
        val ob = rsiOverbought.coerceIn(55, 95); val os = rsiOversold.coerceIn(5, 45)
        return copy(smaShort = s, smaMid = m, smaLong = l, emaFast = ef, emaSlow = es, macdFast = mf, macdSlow = ms,
            macdSignal = p(macdSignal), rsiPeriod = p(rsiPeriod), rsiOverbought = ob, rsiOversold = os,
            stochPeriod = p(stochPeriod), bbPeriod = p(bbPeriod), bbStdDev = bbStdDev.coerceIn(0.5, 4.0),
            atrPeriod = p(atrPeriod), adxPeriod = p(adxPeriod), cciPeriod = p(cciPeriod), williamsPeriod = p(williamsPeriod),
            mfiPeriod = p(mfiPeriod), rocPeriod = p(rocPeriod), supertrendPeriod = p(supertrendPeriod),
            supertrendMultiplier = supertrendMultiplier.coerceIn(0.5, 10.0))
    }

    fun g(d: Double) = if (d % 1.0 == 0.0) "%.0f".format(d) else "%.1f".format(d)

    /** Indicator title including the active settings, e.g. "Relative Strength Index (9)". */
    fun titleFor(t: IndicatorType): String = when (t) {
        IndicatorType.SMA -> "Simple Moving Averages ($smaShort/$smaMid/$smaLong)"
        IndicatorType.EMA -> "Exponential Moving Averages ($emaFast/$emaSlow)"
        IndicatorType.MACD -> "MACD ($macdFast, $macdSlow, $macdSignal)"
        IndicatorType.RSI -> "Relative Strength Index ($rsiPeriod)"
        IndicatorType.STOCHASTIC -> "Stochastic Oscillator ($stochPeriod, 3, 3)"
        IndicatorType.BOLLINGER -> "Bollinger Bands ($bbPeriod, ${g(bbStdDev)}σ)"
        IndicatorType.ATR -> "Average True Range ($atrPeriod)"
        IndicatorType.ADX -> "Average Directional Index ($adxPeriod)"
        IndicatorType.CCI -> "Commodity Channel Index ($cciPeriod)"
        IndicatorType.WILLIAMS_R -> "Williams %R ($williamsPeriod)"
        IndicatorType.MFI -> "Money Flow Index ($mfiPeriod)"
        IndicatorType.ROC -> "Rate of Change ($rocPeriod)"
        IndicatorType.SUPERTREND -> "Supertrend ($supertrendPeriod, ${g(supertrendMultiplier)})"
        else -> t.title
    }

    companion object { val DEFAULT = IndicatorParams() }
}
