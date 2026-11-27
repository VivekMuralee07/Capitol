package com.capitol.app.data.live

import com.capitol.app.data.market.QuoteSnapshot

/** Applies live prices to quote snapshots and ranks movers. (Charts are not modified by live ticks.) */
object LiveCandles {
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
