package com.capitol.app.data.market

import kotlinx.serialization.Serializable

/** One OHLCV bar. [time] is epoch seconds (UTC). */
@Serializable
data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

@Serializable
data class PriceSeries(
    val symbol: String,
    val name: String,
    val currency: String,
    val exchange: String,
    val candles: List<Candle>,
    val regularMarketPrice: Double,
    val previousClose: Double,
    /** Set when this series was served from the offline cache because a fresh fetch failed. */
    val cachedAt: Long? = null,
) {
    val change: Double get() = regularMarketPrice - previousClose
    val changePercent: Double get() = if (previousClose == 0.0) 0.0 else change / previousClose * 100.0
    val closes: List<Double> get() = candles.map { it.close }
}

@Serializable
data class SymbolMatch(
    val symbol: String,
    val name: String,
    val exchange: String,
    val type: String,
)

/** Lightweight quote used on the home page and watchlist. */
data class QuoteSnapshot(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String,
    val sparkline: List<Double>,
)

/**
 * Period / interval pairs mirror the values accepted by yfinance's
 * `Ticker.history(period=..., interval=...)`.
 */
enum class ChartRange(val label: String, val period: String, val interval: String) {
    D1("1D", "1d", "5m"),
    W1("1W", "5d", "30m"),
    M1("1M", "1mo", "1d"),
    M3("3M", "3mo", "1d"),
    M6("6M", "6mo", "1d"),
    Y1("1Y", "1y", "1d"),
    Y2("2Y", "2y", "1d"),
    Y5("5Y", "5y", "1wk"),
}

fun PriceSeries.toSnapshot() = QuoteSnapshot(
    symbol = symbol,
    name = name,
    price = regularMarketPrice,
    change = change,
    changePercent = changePercent,
    currency = currency,
    sparkline = closes.takeLast(60),
)

/**
 * Previous session close. Daily+ bars: the bar before the last one. Intraday bars:
 * the last bar of the previous calendar day (falls back to [fallback]).
 */
fun inferPreviousClose(candles: List<Candle>, intraday: Boolean, fallback: Double?): Double? {
    if (candles.size < 2) return fallback
    if (!intraday) return candles[candles.size - 2].close
    val lastDay = candles.last().time / 86_400
    return candles.lastOrNull { it.time / 86_400 != lastDay }?.close ?: fallback
}

val ChartRange.isIntraday: Boolean get() = interval.endsWith("m") || interval.endsWith("h")

@Serializable
data class Fundamentals(
    val symbol: String,
    val name: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val currency: String? = null,
    val marketCap: Double? = null,
    val trailingPE: Double? = null,
    val forwardPE: Double? = null,
    val priceToBook: Double? = null,
    val dividendYield: Double? = null,
    val beta: Double? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val averageVolume: Double? = null,
    val profitMargins: Double? = null,
    val returnOnEquity: Double? = null,
    val debtToEquity: Double? = null,
    val summary: String? = null,
) {
    fun toPlainText(): String = buildList {
        add("$symbol${name?.let { " — $it" } ?: ""}")
        listOfNotNull(sector, industry).takeIf { it.isNotEmpty() }?.let { add("Sector: ${it.joinToString(" / ")}") }
        fun n(label: String, v: Double?, f: (Double) -> String) { if (v != null) add("$label: ${f(v)}") }
        n("Market cap", marketCap) { compact(it) }
        n("P/E (TTM)", trailingPE) { "%.1f".format(it) }
        n("Forward P/E", forwardPE) { "%.1f".format(it) }
        n("Price/Book", priceToBook) { "%.2f".format(it) }
        n("Dividend yield", dividendYield) { "%.2f%%".format(it * 100) }
        n("Beta", beta) { "%.2f".format(it) }
        n("52-week high", fiftyTwoWeekHigh) { "%.2f".format(it) }
        n("52-week low", fiftyTwoWeekLow) { "%.2f".format(it) }
        n("Profit margin", profitMargins) { "%.1f%%".format(it * 100) }
        n("Return on equity", returnOnEquity) { "%.1f%%".format(it * 100) }
        n("Debt/Equity", debtToEquity) { "%.1f".format(it) }
    }.joinToString("\n")

    companion object {
        fun compact(v: Double) = when {
            v >= 1e12 -> "%.2fT".format(v / 1e12); v >= 1e9 -> "%.2fB".format(v / 1e9)
            v >= 1e7 -> "%.2fCr".format(v / 1e7).let { if (v < 1e9) "%.1fM".format(v / 1e6) else it }
            v >= 1e6 -> "%.1fM".format(v / 1e6); else -> "%.0f".format(v)
        }
    }
}

@Serializable
data class NewsItem(val title: String, val publisher: String, val link: String, val publishedAt: Long)
