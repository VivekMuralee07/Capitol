package com.capitol.app.data.live

import com.capitol.app.data.market.Candle
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.QuoteSnapshot

/** Applies live ticks to charts and snapshots. */
object LiveCandles {
    /**
     * Merges [q] into [s]: updates the candle whose [bucketSec] window contains the tick, or
     * appends a new one. Ticks older than the last candle are ignored.
     */
    fun merge(s: PriceSeries, q: LiveQuote, bucketSec: Long): PriceSeries {
        if (!q.symbol.equals(s.symbol, true) || s.candles.isEmpty()) return s
        val t = q.time / 1000
        val bucket = t - Math.floorMod(t, bucketSec)
        val last = s.candles.last()
        val lastBucket = last.time - Math.floorMod(last.time, bucketSec)
        val candles = when {
            bucket < lastBucket -> return s
            bucket == lastBucket -> s.candles.dropLast(1) + last.copy(
                high = maxOf(last.high, q.price), low = minOf(last.low, q.price), close = q.price)
            else -> s.candles + Candle(bucket, last.close, maxOf(last.close, q.price), minOf(last.close, q.price), q.price, 0.0)
        }
        val prev = if (q.change != 0.0) q.price - q.change else s.previousClose
        return s.copy(candles = candles, regularMarketPrice = q.price, previousClose = prev)
    }

    fun apply(snap: QuoteSnapshot, q: LiveQuote): QuoteSnapshot = snap.copy(
        price = q.price,
        change = if (q.change != 0.0 || q.changePercent != 0.0) q.change else snap.change,
        changePercent = if (q.change != 0.0 || q.changePercent != 0.0) q.changePercent else snap.changePercent,
        sparkline = if (snap.sparkline.isEmpty()) snap.sparkline else snap.sparkline.dropLast(1) + q.price,
    )

    /** Top [n] gainers and losers by day change among [symbols] that have quotes. */
    fun movers(quotes: Map<String, LiveQuote>, symbols: Collection<String>, n: Int = 5): Pair<List<LiveQuote>, List<LiveQuote>> {
        val qs = symbols.mapNotNull { quotes[it.uppercase()] }
        return qs.filter { it.changePercent > 0 }.sortedByDescending { it.changePercent }.take(n) to
            qs.filter { it.changePercent < 0 }.sortedBy { it.changePercent }.take(n)
    }
}
