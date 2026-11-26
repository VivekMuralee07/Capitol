package com.capitol.app.analysis

enum class IndicatorCategory(val label: String) {
    TREND("Trend"),
    MOMENTUM("Momentum"),
    VOLATILITY("Volatility"),
    VOLUME("Volume"),
    LEVELS("Support & Resistance"),
    PATTERNS("Price Action"),
}

/** Where the indicator is drawn: on top of price, or in its own pane below. */
enum class Pane { OVERLAY, SEPARATE, NONE }

enum class IndicatorType(
    val title: String,
    val shortName: String,
    val category: IndicatorCategory,
    val pane: Pane,
    /** What the indicator measures. */
    val about: String,
    /** How traders typically read it. */
    val howToRead: String,
) {
    SMA(
        "Simple Moving Averages (20/50/200)", "SMA", IndicatorCategory.TREND, Pane.OVERLAY,
        "The average closing price over the last 20, 50 and 200 bars. Smooths out noise to reveal the underlying trend.",
        "Price above a rising average = uptrend. The 50 crossing above the 200 is a 'Golden Cross' (bullish); crossing below is a 'Death Cross' (bearish). Long averages often act as support/resistance.",
    ),
    EMA(
        "Exponential Moving Averages (12/26)", "EMA", IndicatorCategory.TREND, Pane.OVERLAY,
        "Moving averages that weight recent prices more heavily, so they react faster than SMAs.",
        "Fast EMA above slow EMA signals bullish short-term momentum. Crossovers are early (but noisier) trend-change signals.",
    ),
    MACD(
        "MACD (12, 26, 9)", "MACD", IndicatorCategory.MOMENTUM, Pane.SEPARATE,
        "Moving Average Convergence Divergence: the gap between the 12 and 26 EMAs, with a 9-period signal line and histogram.",
        "MACD crossing above its signal line is bullish, below is bearish. A growing histogram means momentum is accelerating; MACD above zero means the short-term trend is above the long-term trend.",
    ),
    RSI(
        "Relative Strength Index (14)", "RSI", IndicatorCategory.MOMENTUM, Pane.SEPARATE,
        "A 0–100 oscillator comparing the size of recent gains to recent losses.",
        "Above 70 = overbought (pullback risk), below 30 = oversold (bounce potential). Readings holding above 50 confirm an uptrend; below 50 a downtrend. Divergence from price warns of reversals.",
    ),
    STOCHASTIC(
        "Stochastic Oscillator (14, 3, 3)", "STOCH", IndicatorCategory.MOMENTUM, Pane.SEPARATE,
        "Where the close sits within the recent 14-bar high–low range, from 0 to 100.",
        "Above 80 = overbought, below 20 = oversold. %K crossing above %D in the oversold zone is a buy trigger; crossing below in the overbought zone is a sell trigger.",
    ),
    BOLLINGER(
        "Bollinger Bands (20, 2σ)", "BB", IndicatorCategory.VOLATILITY, Pane.OVERLAY,
        "A 20-period SMA with bands two standard deviations above and below, adapting to volatility.",
        "Touching the upper band = strong/extended, lower band = weak/oversold. A 'squeeze' (narrow bands) often precedes a big move. %B shows where price sits inside the bands (0 = lower, 1 = upper).",
    ),
    ATR(
        "Average True Range (14)", "ATR", IndicatorCategory.VOLATILITY, Pane.SEPARATE,
        "The average size of each bar's full range including gaps — a pure volatility gauge with no direction.",
        "Rising ATR = larger swings and risk; falling ATR = calmer market. Commonly used to size stop-losses (e.g. 2×ATR below entry).",
    ),
    ADX(
        "Average Directional Index (14)", "ADX", IndicatorCategory.TREND, Pane.SEPARATE,
        "Measures trend strength (ADX) and direction (+DI vs −DI).",
        "ADX above 25 = trending market, below 20 = range-bound. +DI above −DI = bulls in control; the reverse = bears in control.",
    ),
    OBV(
        "On-Balance Volume", "OBV", IndicatorCategory.VOLUME, Pane.SEPARATE,
        "Running total that adds volume on up days and subtracts it on down days.",
        "OBV rising with price confirms the trend. OBV rising while price is flat/falling suggests accumulation (bullish divergence); the opposite suggests distribution.",
    ),
    VWAP(
        "Volume-Weighted Average Price", "VWAP", IndicatorCategory.VOLUME, Pane.OVERLAY,
        "The average price weighted by volume (anchored per session on intraday charts, rolling 20 bars on daily charts).",
        "Price above VWAP = buyers in control and institutions paying up; below = sellers in control. Often acts as intraday support/resistance.",
    ),
    CCI(
        "Commodity Channel Index (20)", "CCI", IndicatorCategory.MOMENTUM, Pane.SEPARATE,
        "How far the typical price is from its average, scaled by mean deviation.",
        "Above +100 = strong upward momentum (or overbought), below −100 = strong downward momentum (or oversold). Zero-line crosses mark momentum shifts.",
    ),
    WILLIAMS_R(
        "Williams %R (14)", "%R", IndicatorCategory.MOMENTUM, Pane.SEPARATE,
        "Like the stochastic, but on a 0 to −100 scale showing the close relative to the 14-bar high.",
        "Above −20 = overbought, below −80 = oversold. Moves out of these zones are often used as entry/exit triggers.",
    ),
    MFI(
        "Money Flow Index (14)", "MFI", IndicatorCategory.VOLUME, Pane.SEPARATE,
        "A volume-weighted RSI that tracks money flowing in and out.",
        "Above 80 = overbought, below 20 = oversold. Divergences between MFI and price can signal reversals before they appear in price.",
    ),
    ICHIMOKU(
        "Ichimoku Cloud", "ICHI", IndicatorCategory.TREND, Pane.OVERLAY,
        "A complete trend system: Tenkan (9), Kijun (26) and a 'cloud' (Senkou spans) projected forward.",
        "Price above the cloud = bullish, below = bearish, inside = undecided. Tenkan above Kijun adds bullish confirmation. A green future cloud (Span A > Span B) is supportive.",
    ),
    PSAR(
        "Parabolic SAR", "SAR", IndicatorCategory.TREND, Pane.OVERLAY,
        "Trailing 'stop and reverse' dots that follow price and accelerate as a trend extends.",
        "Dots below price = uptrend, above = downtrend. A flip is a trend-reversal signal, and the dot level doubles as a trailing stop.",
    ),
    ROC(
        "Rate of Change (12)", "ROC", IndicatorCategory.MOMENTUM, Pane.SEPARATE,
        "Percentage change in price over the last 12 bars.",
        "Positive and rising = accelerating upward momentum; negative = downward momentum. Extreme readings can mark exhaustion.",
    ),
    SUPERTREND(
        "Supertrend (10, 3)", "ST", IndicatorCategory.TREND, Pane.OVERLAY,
        "An ATR-based trailing band that flips between support (uptrend) and resistance (downtrend).",
        "Price above the green line = buy/hold; below the red line = sell/avoid. Very popular on Indian markets for swing trades.",
    ),
    PIVOTS(
        "Pivot Points (Classic)", "PIVOT", IndicatorCategory.LEVELS, Pane.OVERLAY,
        "Support (S1–S3) and resistance (R1–R3) levels derived from the previous bar's high, low and close.",
        "Trading above the pivot (P) is bullish, below is bearish. R1/S1 are the first targets; breaks through them often extend to R2/S2.",
    ),
    FIBONACCI(
        "Fibonacci Retracement", "FIB", IndicatorCategory.LEVELS, Pane.OVERLAY,
        "Key ratios (23.6%, 38.2%, 50%, 61.8%, 78.6%) between the recent swing high and low.",
        "In an uptrend, pullbacks often find support at 38.2%–61.8%. Holding the 61.8% 'golden ratio' keeps the trend intact; losing it suggests a deeper reversal.",
    ),
    VOLUME(
        "Volume Analysis", "VOL", IndicatorCategory.VOLUME, Pane.SEPARATE,
        "Today's volume compared to its 20-bar average, and whether volume is heavier on up or down days.",
        "Price moves on above-average volume are more reliable. Rising prices on falling volume may be losing conviction.",
    ),
    PATTERNS(
        "Candlestick Patterns", "CNDL", IndicatorCategory.PATTERNS, Pane.NONE,
        "Detects classic reversal/continuation candles: engulfing, hammer, shooting star, doji, morning/evening star.",
        "Patterns are most meaningful at support/resistance and when confirmed by the next candle and volume.",
    );

    companion object {
        val DEFAULTS = setOf(SMA, RSI, MACD, BOLLINGER, SUPERTREND, VOLUME)
        fun byCategory() = entries.groupBy { it.category }
        fun fromName(s: String): IndicatorType? =
            entries.firstOrNull { it.name.equals(s, true) || it.shortName.equals(s, true) }
    }
}
