package com.capitol.app.analysis

import com.capitol.app.data.market.PriceSeries
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class Direction(val label: String) { LONG("Buy (long)"), SHORT("Sell (short)") }

data class TradeTarget(val label: String, val price: Double, val rMultiple: Double)

data class TradePlan(
    val direction: Direction,
    val entry: Double,
    val entryZoneLow: Double,
    val entryZoneHigh: Double,
    val stop: Double,
    val stopReason: String,
    val atr: Double,
    val riskPerShare: Double,
    val quantity: Int,
    val positionValue: Double,
    val riskAmount: Double,
    val targets: List<TradeTarget>,
    val warnings: List<String>,
) {
    fun toPlainText(symbol: String, ccy: String): String = buildString {
        appendLine("${direction.label} plan for $symbol ($ccy)")
        appendLine("Entry zone: ${"%.2f".format(entryZoneLow)} – ${"%.2f".format(entryZoneHigh)} (last ${"%.2f".format(entry)})")
        appendLine("Stop-loss: ${"%.2f".format(stop)} — $stopReason (risk ${"%.2f".format(riskPerShare)}/share)")
        targets.forEach { appendLine("Target ${it.label}: ${"%.2f".format(it.price)} (${"%.1f".format(it.rMultiple)}R)") }
        appendLine("Position: $quantity shares ≈ ${"%,.0f".format(positionValue)}, risking ≈ ${"%,.0f".format(riskAmount)}")
        warnings.forEach { appendLine("⚠ $it") }
        append("Educational plan, not investment advice.")
    }
}

/**
 * Builds a rules-based trade plan from technical levels: ATR- or support-based stop,
 * targets at the next resistance levels (or R-multiples), and position size from risk.
 */
object TradePlanner {
    fun plan(
        series: PriceSeries,
        direction: Direction,
        capital: Double,
        riskPct: Double,
        params: IndicatorParams = IndicatorParams.DEFAULT,
        verdict: Signal? = null,
        atrMult: Double = 2.0,
    ): TradePlan? {
        val c = series.candles
        if (c.size < 30) return null
        val p = params.sanitized()
        val h = c.map { it.high }; val l = c.map { it.low }; val cl = c.map { it.close }
        val entry = cl.last()
        val atr = Indicators.atr(h, l, cl, p.atrPeriod).last() ?: return null
        if (atr <= 0) return null
        val long = direction == Direction.LONG
        val sign = if (long) 1.0 else -1.0

        // Candidate levels
        val levels = mutableListOf<Pair<String, Double>>()
        val prev = c[c.size - 2]
        val pv = Indicators.pivots(prev.high, prev.low, prev.close)
        levels += listOf("Pivot" to pv.p, "R1" to pv.r1, "R2" to pv.r2, "R3" to pv.r3, "S1" to pv.s1, "S2" to pv.s2, "S3" to pv.s3)
        levels += "20-bar low" to l.takeLast(20).min()
        levels += "20-bar high" to h.takeLast(20).max()
        val look = min(c.size, 120)
        levels += "$look-bar high" to h.takeLast(look).max()
        levels += "$look-bar low" to l.takeLast(look).min()
        Indicators.sma(cl, p.smaMid).last()?.let { levels += "SMA${p.smaMid}" to it }
        Indicators.sma(cl, p.smaLong).last()?.let { levels += "SMA${p.smaLong}" to it }

        // Protective side (supports for a long, resistances for a short), nearest first.
        val protective = levels.filter { (it.second - entry) * sign < 0 }.sortedBy { abs(it.second - entry) }
        val profit = levels.filter { (it.second - entry) * sign > 0 }.sortedBy { abs(it.second - entry) }

        val atrStop = entry - sign * atrMult * atr
        val nearest = protective.firstOrNull { abs(entry - it.second) in (0.5 * atr)..(3 * atr) }
        val (stop, stopReason) = if (nearest != null) {
            (nearest.second - sign * 0.25 * atr) to "just ${if (long) "below" else "above"} ${nearest.first} (${"%.2f".format(nearest.second)})"
        } else atrStop to "${"%.1f".format(atrMult)}× ATR from entry"
        val risk = abs(entry - stop)

        val zoneEdge = nearest?.second?.let { if (long) max(it, entry - 0.5 * atr) else min(it, entry + 0.5 * atr) } ?: (entry - sign * 0.5 * atr)
        val (zLow, zHigh) = if (long) zoneEdge to entry else entry to zoneEdge

        val targets = profit.filter { abs(it.second - entry) >= 0.5 * risk }
            .distinctBy { "%.2f".format(it.second) }.take(3)
            .map { TradeTarget(it.first, it.second, abs(it.second - entry) / risk) }
            .toMutableList()
        listOf(2.0, 3.0).forEach { r ->
            if (targets.size < 2 && targets.none { abs(it.rMultiple - r) < 0.3 }) targets += TradeTarget("${r.toInt()}R", entry + sign * r * risk, r)
        }
        targets.sortBy { it.rMultiple }

        val riskBudget = capital * riskPct / 100
        val qty = if (risk > 0) min(floor(riskBudget / risk), floor(capital / entry)).toInt().coerceAtLeast(0) else 0

        val warnings = buildList {
            if (verdict != null && ((long && verdict.isBearish) || (!long && verdict.isBullish)))
                add("The Capitol verdict (${verdict.label}) points the other way — this trade goes against the current signals.")
            targets.firstOrNull()?.let { if (it.rMultiple < 1.5) add("First target is only ${"%.1f".format(it.rMultiple)}× the risk — reward/risk is thin.") }
            if (atr / entry > 0.04) add("Volatility is high (ATR ${"%.1f".format(atr / entry * 100)}% of price) — consider a smaller position.")
            if (qty == 0) add("Your capital and risk settings are too small for one share at this stop distance.")
            if (qty > 0 && qty * entry >= capital * 0.999) add("Position size is capped by capital, so you'd be risking less than ${"%.1f".format(riskPct)}%.")
        }
        return TradePlan(direction, entry, zLow, zHigh, stop, stopReason, atr, risk, qty, qty * entry, qty * risk, targets, warnings)
    }
}
