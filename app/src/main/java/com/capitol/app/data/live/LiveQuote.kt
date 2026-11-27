package com.capitol.app.data.live

/** A real-time (or near-real-time) quote. */
data class LiveQuote(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val dayVolume: Long? = null,
    /** Epoch milliseconds of the last trade. */
    val time: Long = System.currentTimeMillis(),
    val name: String? = null,
    val currency: String? = null,
    /** Yahoo market-hours code: 0 pre-market, 1 regular, 2 post-market, 3 extended. */
    val marketHours: Int? = null,
    val source: Source = Source.SNAPSHOT,
) {
    enum class Source { STREAM, SNAPSHOT }
    val previousClose: Double get() = price - change
}
