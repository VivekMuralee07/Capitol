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
