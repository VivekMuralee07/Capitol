package com.capitol.app.analysis

import com.capitol.app.data.broker.Holding
import com.capitol.app.data.market.PriceSeries
import kotlin.math.min

data class AllocationSlice(val label: String, val value: Double, val pct: Double)

data class HoldingHealth(val symbol: String, val verdict: Signal, val score: Double, val weightPct: Double)

data class CurrencyAnalytics(
    val currency: String,
    val total: Double,
    val byHolding: List<AllocationSlice>,
    val bySector: List<AllocationSlice>,
    val largestPct: Double,
    val top5Pct: Double,
    /** 1 / Σw² — how many equally sized positions the portfolio behaves like. */
    val effectiveHoldings: Double,
    /** 0–100, from effective holdings (10+ equal-weight positions ≈ 100). */
    val diversificationScore: Int,
    /** Value-weighted average of each holding's Capitol composite score (−2…+2). */
    val technicalScore: Double?,
    val health: List<HoldingHealth>,
    val winners: List<Holding>,
    val losers: List<Holding>,
    /** Current quantities valued at past closing prices (not your actual trade history). */
    val valueHistory: List<Pair<Long, Double>>,
) {
    val oneYearChangePct: Double? get() = valueHistory.takeIf { it.size > 1 }?.let { (it.last().second / it.first().second - 1) * 100 }
    val warnings: List<String> get() = buildList {
        if (largestPct > 25) add("Your largest position is ${"%.0f".format(largestPct)}% of the portfolio — a single stock dominates.")
        bySector.firstOrNull()?.takeIf { it.pct > 40 && it.label != "Unknown" }?.let { add("${"%.0f".format(it.pct)}% is in ${it.label} — sector concentration risk.") }
        if (effectiveHoldings < 5 && byHolding.size >= 3) add("The portfolio behaves like only ${"%.1f".format(effectiveHoldings)} equal positions.")
        val bearish = health.filter { it.verdict.isBearish }.sumOf { it.weightPct }
        if (bearish > 30) add("${"%.0f".format(bearish)}% of the portfolio is in holdings with bearish technicals.")
    }
}

object PortfolioAnalytics {
    fun compute(
        holdings: List<Holding>,
        series: Map<String, PriceSeries>,
        sectors: Map<String, String?>,
        params: IndicatorParams = IndicatorParams.DEFAULT,
        indicators: Set<IndicatorType> = IndicatorType.DEFAULTS,
    ): List<CurrencyAnalytics> = holdings.filter { it.current > 0 }.groupBy { it.currency }.map { (ccy, hs) ->
        // Merge the same symbol held at several brokers.
        val grouped = hs.groupBy { it.yahooSymbol }
        val merged = grouped.map { (_, list) -> list.first() to list.sumOf { it.current } }
        val qty = grouped.map { (sym, list) -> sym to list.sumOf { it.quantity } }
        val total = merged.sumOf { it.second }
        val weights = merged.map { it.first.yahooSymbol to it.second / total }
        val sorted = merged.sortedByDescending { it.second }
        val byHolding = sorted.take(6).map { AllocationSlice(it.first.tradingSymbol, it.second, it.second / total * 100) } +
            sorted.drop(6).takeIf { it.isNotEmpty() }?.let { rest -> listOf(AllocationSlice("Others (${rest.size})", rest.sumOf { it.second }, rest.sumOf { it.second } / total * 100)) }.orEmpty()
        val bySector = merged.groupBy { sectors[it.first.yahooSymbol] ?: "Unknown" }
            .map { (sec, list) -> AllocationSlice(sec, list.sumOf { it.second }, list.sumOf { it.second } / total * 100) }
            .sortedByDescending { it.value }
        val eff = 1.0 / weights.sumOf { it.second * it.second }
        val health = merged.mapNotNull { (h, v) ->
            series[h.yahooSymbol]?.let { s -> TechnicalAnalyzer.analyze(s, indicators, params).let { r -> HoldingHealth(h.yahooSymbol, r.verdict, r.compositeScore, v / total * 100) } }
        }.sortedByDescending { it.weightPct }
        val covered = health.sumOf { it.weightPct }
        CurrencyAnalytics(
            currency = ccy, total = total, byHolding = byHolding, bySector = bySector,
            largestPct = sorted.first().second / total * 100,
            top5Pct = sorted.take(5).sumOf { it.second } / total * 100,
            effectiveHoldings = eff,
            diversificationScore = min(100.0, eff / 10.0 * 100).toInt(),
            technicalScore = if (covered > 0) health.sumOf { it.score * it.weightPct } / covered else null,
            health = health,
            winners = hs.sortedByDescending { it.pnlPercent }.take(3).filter { it.pnl > 0 },
            losers = hs.sortedBy { it.pnlPercent }.take(3).filter { it.pnl < 0 },
            valueHistory = valueHistory(qty, series),
        )
    }

    /** Σ quantity × close for each day all held symbols have data (forward-filled across holidays). */
    internal fun valueHistory(qty: List<Pair<String, Double>>, series: Map<String, PriceSeries>): List<Pair<Long, Double>> {
        val withData = qty.filter { series[it.first]?.candles?.isNotEmpty() == true }
        if (withData.isEmpty()) return emptyList()
        val start = withData.maxOf { series.getValue(it.first).candles.first().time / 86_400 }
        val days = withData.flatMap { q -> series.getValue(q.first).candles.map { it.time / 86_400 } }.filter { it >= start }.toSortedSet()
        val idx = withData.associate { it.first to 0 }.toMutableMap()
        val last = mutableMapOf<String, Double>()
        return days.map { d ->
            var v = 0.0
            withData.forEach { (sym, q) ->
                val cs = series.getValue(sym).candles
                var i = idx.getValue(sym)
                while (i < cs.size && cs[i].time / 86_400 <= d) { last[sym] = cs[i].close; i++ }
                idx[sym] = i
                v += q * (last[sym] ?: 0.0)
            }
            d * 86_400 to v
        }
    }
}
