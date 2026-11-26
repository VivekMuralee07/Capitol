package com.capitol.app.analysis

import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.MarketRepository
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.watchlist.AlertCondition
import com.capitol.app.data.watchlist.IndicatorAlert
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Built-in symbol universes (index constituents change over time; missing symbols are skipped). */
enum class Universe(val label: String, val symbols: List<String>) {
    NIFTY50("NIFTY 50", listOf(
        "RELIANCE", "HDFCBANK", "ICICIBANK", "BHARTIARTL", "TCS", "INFY", "SBIN", "ITC", "LT", "HINDUNILVR",
        "BAJFINANCE", "KOTAKBANK", "AXISBANK", "MARUTI", "M&M", "SUNPHARMA", "HCLTECH", "ULTRACEMCO", "NTPC", "TITAN",
        "ONGC", "POWERGRID", "ADANIPORTS", "BAJAJFINSV", "ASIANPAINT", "WIPRO", "NESTLEIND", "COALINDIA", "JSWSTEEL", "TATASTEEL",
        "BEL", "TRENT", "ETERNAL", "GRASIM", "HINDALCO", "TECHM", "SBILIFE", "HDFCLIFE", "CIPLA", "DRREDDY",
        "EICHERMOT", "APOLLOHOSP", "BAJAJ-AUTO", "SHRIRAMFIN", "TATACONSUM", "JIOFIN", "INDUSINDBK", "ADANIENT", "HEROMOTOCO", "TATAMOTORS",
    ).map { "$it.NS" }),
    BANKNIFTY("Bank NIFTY", listOf(
        "HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "INDUSINDBK", "BANKBARODA", "PNB", "AUBANK", "FEDERALBNK", "IDFCFIRSTB", "CANBK",
    ).map { "$it.NS" }),
    US_LARGE("US large caps", listOf(
        "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "BRK-B", "JPM", "LLY", "V", "UNH", "XOM", "MA",
        "COST", "HD", "PG", "JNJ", "NFLX", "ABBV", "BAC", "CRM", "ORCL", "AMD", "KO", "PEP", "ADBE", "WMT", "CVX",
    )),
    WATCHLISTS("My watchlists", emptyList()),
}

enum class ScreenCondition(val label: String, val needsValue: Boolean = false, val defaultValue: Double? = null, val canBeFresh: Boolean = true) {
    VERDICT_BUY("Capitol verdict is Buy or better"),
    VERDICT_SELL("Capitol verdict is Sell or worse"),
    SUPERTREND_BUY("Supertrend is green"),
    SUPERTREND_SELL("Supertrend is red"),
    MACD_BULL("MACD above signal"),
    MACD_BEAR("MACD below signal"),
    RSI_BELOW("RSI below", true, 30.0),
    RSI_ABOVE("RSI above", true, 70.0),
    GOLDEN_CROSS("Medium SMA above long SMA"),
    ABOVE_LONG_SMA("Price above long SMA"),
    BELOW_LONG_SMA("Price below long SMA"),
    BB_SQUEEZE("Bollinger squeeze", canBeFresh = false),
    VOLUME_SPIKE("Volume ≥ 2× average", canBeFresh = false),
    NEAR_HIGH("Within 3% of 52-week high", canBeFresh = false),
    NEAR_LOW("Within 3% of 52-week low", canBeFresh = false),
}

data class ScreenFilter(val condition: ScreenCondition, val value: Double? = condition.defaultValue) {
    val description get() = condition.label + (value?.let { " ${"%.0f".format(it)}" } ?: "")
}

data class ScreenHit(val symbol: String, val name: String, val price: Double, val changePct: Double, val currency: String,
                     val verdict: Signal, val score: Double, val reasons: List<String>)

data class ScreenPreset(val label: String, val filters: List<ScreenFilter>, val freshOnly: Boolean)

/** Scans a universe for stocks matching every filter (optionally only setups that just triggered). */
object Screener {
    const val FRESH_BARS = 5

    val PRESETS = listOf(
        ScreenPreset("Fresh Supertrend buy", listOf(ScreenFilter(ScreenCondition.SUPERTREND_BUY)), true),
        ScreenPreset("Oversold bounce", listOf(ScreenFilter(ScreenCondition.RSI_BELOW, 35.0), ScreenFilter(ScreenCondition.MACD_BULL)), false),
        ScreenPreset("Momentum leaders", listOf(ScreenFilter(ScreenCondition.VERDICT_BUY), ScreenFilter(ScreenCondition.ABOVE_LONG_SMA), ScreenFilter(ScreenCondition.NEAR_HIGH)), false),
        ScreenPreset("Bollinger squeeze", listOf(ScreenFilter(ScreenCondition.BB_SQUEEZE)), false),
        ScreenPreset("Fresh MACD cross", listOf(ScreenFilter(ScreenCondition.MACD_BULL), ScreenFilter(ScreenCondition.ABOVE_LONG_SMA)), true),
        ScreenPreset("Breakdown risk", listOf(ScreenFilter(ScreenCondition.SUPERTREND_SELL), ScreenFilter(ScreenCondition.BELOW_LONG_SMA)), true),
    )

    /** Whether [s] satisfies the filter right now (null = not enough data). */
    fun state(f: ScreenFilter, s: PriceSeries, p: IndicatorParams, inds: Set<IndicatorType>): Boolean? {
        fun alert(c: AlertCondition) = AlertEvaluator.state(IndicatorAlert(c, f.value), s, p)
        val c = s.closes
        return when (f.condition) {
            ScreenCondition.VERDICT_BUY -> TechnicalAnalyzer.analyze(s, inds, p).verdict.isBullish
            ScreenCondition.VERDICT_SELL -> TechnicalAnalyzer.analyze(s, inds, p).verdict.isBearish
            ScreenCondition.SUPERTREND_BUY -> alert(AlertCondition.SUPERTREND_BUY)
            ScreenCondition.SUPERTREND_SELL -> alert(AlertCondition.SUPERTREND_SELL)
            ScreenCondition.MACD_BULL -> alert(AlertCondition.MACD_BULL_CROSS)
            ScreenCondition.MACD_BEAR -> alert(AlertCondition.MACD_BEAR_CROSS)
            ScreenCondition.RSI_BELOW -> alert(AlertCondition.RSI_BELOW)
            ScreenCondition.RSI_ABOVE -> alert(AlertCondition.RSI_ABOVE)
            ScreenCondition.GOLDEN_CROSS -> alert(AlertCondition.GOLDEN_CROSS)
            ScreenCondition.ABOVE_LONG_SMA -> alert(AlertCondition.ABOVE_LONG_SMA)
            ScreenCondition.BELOW_LONG_SMA -> alert(AlertCondition.BELOW_LONG_SMA)
            ScreenCondition.BB_SQUEEZE -> {
                val b = Indicators.bollinger(c, p.bbPeriod, p.bbStdDev)
                val bw = c.indices.mapNotNull { i -> val u = b.upper[i]; val l = b.lower[i]; val m = b.middle[i]; if (u != null && l != null && m != null && m != 0.0) (u - l) / m else null }.takeLast(120)
                if (bw.size < 40) null else bw.last() <= bw.sorted()[(bw.size * 0.15).toInt()]
            }
            ScreenCondition.VOLUME_SPIKE -> {
                val v = s.candles.map { it.volume }
                if (v.size < 21 || v.takeLast(20).all { it == 0.0 }) null else v.last() >= 2 * v.subList(v.size - 21, v.size - 1).average()
            }
            ScreenCondition.NEAR_HIGH -> s.candles.takeLast(252).maxOf { it.high }.let { c.last() >= it * 0.97 }
            ScreenCondition.NEAR_LOW -> s.candles.takeLast(252).minOf { it.low }.let { c.last() <= it * 1.03 }
        }
    }

    /** Matches all filters; with [freshOnly], at least one filter must have turned true within [FRESH_BARS]. */
    fun matches(s: PriceSeries, filters: List<ScreenFilter>, freshOnly: Boolean, p: IndicatorParams, inds: Set<IndicatorType>): List<String>? {
        if (filters.isEmpty() || s.candles.size < 30) return null
        val reasons = mutableListOf<String>()
        var anyFresh = false
        val earlier = if (freshOnly && s.candles.size > FRESH_BARS + 30) s.copy(candles = s.candles.dropLast(FRESH_BARS)) else null
        for (f in filters) {
            if (state(f, s, p, inds) != true) return null
            val fresh = earlier != null && f.condition.canBeFresh && state(f, earlier, p, inds) == false
            if (fresh) anyFresh = true
            reasons += f.description + if (fresh) " (new)" else ""
        }
        if (freshOnly && !anyFresh) return null
        return reasons
    }

    suspend fun run(
        repo: MarketRepository, symbols: List<String>, filters: List<ScreenFilter>, freshOnly: Boolean,
        p: IndicatorParams, inds: Set<IndicatorType>, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<ScreenHit> = coroutineScope {
        val gate = Semaphore(5)
        val done = AtomicInteger()
        symbols.distinct().map { sym ->
            async {
                gate.withPermit {
                    val hit = runCatching {
                        val s = repo.history(sym, ChartRange.Y1)
                        matches(s, filters, freshOnly, p, inds)?.let { reasons ->
                            val r = TechnicalAnalyzer.analyze(s, inds, p)
                            ScreenHit(s.symbol, s.name, s.regularMarketPrice, s.changePercent, s.currency, r.verdict, r.compositeScore, reasons)
                        }
                    }.getOrNull()
                    onProgress(done.incrementAndGet(), symbols.size)
                    hit
                }
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.score }
    }
}
