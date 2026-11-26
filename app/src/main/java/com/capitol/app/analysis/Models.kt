package com.capitol.app.analysis

enum class Signal(val label: String, val score: Int) {
    STRONG_BUY("Strong Buy", 2),
    BUY("Buy", 1),
    NEUTRAL("Neutral", 0),
    SELL("Sell", -1),
    STRONG_SELL("Strong Sell", -2);

    val isBullish get() = score > 0
    val isBearish get() = score < 0

    companion object {
        fun fromScore(s: Double): Signal = when {
            s >= 1.1 -> STRONG_BUY
            s >= 0.35 -> BUY
            s > -0.35 -> NEUTRAL
            s > -1.1 -> SELL
            else -> STRONG_SELL
        }
    }
}

/** Colour role for a plotted line; mapped to theme colours in the UI. */
enum class LineRole { PRIMARY, SECONDARY, TERTIARY, BULL, BEAR, MUTED, ACCENT }

data class ChartLine(
    val name: String,
    val values: List<Double?>,
    val role: LineRole,
    val dotted: Boolean = false,
    val asHistogram: Boolean = false,
)

data class Metric(val label: String, val value: String)

data class IndicatorResult(
    val type: IndicatorType,
    val signal: Signal,
    /** −2..+2 fine-grained score used for the composite. */
    val score: Double,
    val headline: String,
    val metrics: List<Metric>,
    /** Data-driven explanation of what the indicator says right now. */
    val interpretation: String,
    val lines: List<ChartLine> = emptyList(),
    /** Horizontal guide levels (e.g. 30/70 for RSI, pivot levels). */
    val levels: List<Pair<String, Double>> = emptyList(),
    val available: Boolean = true,
)

data class KeyLevel(val label: String, val price: Double, val isSupport: Boolean)

data class AnalysisReport(
    val symbol: String,
    val name: String,
    val currency: String,
    val lastPrice: Double,
    val changePercent: Double,
    val results: List<IndicatorResult>,
    val compositeScore: Double,
    val verdict: Signal,
    val bullishCount: Int,
    val bearishCount: Int,
    val neutralCount: Int,
    /** Plain-English synthesis of all the selected indicators together. */
    val summary: String,
    val categoryScores: Map<IndicatorCategory, Double>,
    val keyLevels: List<KeyLevel>,
    val generatedAt: Long = System.currentTimeMillis(),
) {
    /** Confidence 0..100 based on agreement between indicators. */
    val confidence: Int get() {
        val total = bullishCount + bearishCount + neutralCount
        if (total == 0) return 0
        val agree = maxOf(bullishCount, bearishCount, neutralCount).toDouble() / total
        return (agree * 100).toInt()
    }

    /** Compact text form used by the AI agent and notifications. */
    fun toPlainText(): String = buildString {
        appendLine("$name ($symbol) — last ${"%.2f".format(lastPrice)} $currency (${"%+.2f".format(changePercent)}%)")
        appendLine("Composite verdict: ${verdict.label} (score ${"%+.2f".format(compositeScore)}, confidence $confidence%)")
        appendLine("Bullish $bullishCount · Bearish $bearishCount · Neutral $neutralCount")
        results.filter { it.available }.forEach { r ->
            appendLine("- ${r.type.title}: ${r.signal.label}. ${r.headline}. " +
                r.metrics.joinToString(", ") { "${it.label}=${it.value}" })
            appendLine("  ${r.interpretation}")
        }
        if (keyLevels.isNotEmpty()) {
            appendLine("Key levels: " + keyLevels.joinToString("; ") {
                "${it.label} ${"%.2f".format(it.price)} (${if (it.isSupport) "support" else "resistance"})"
            })
        }
        appendLine(summary)
    }
}
