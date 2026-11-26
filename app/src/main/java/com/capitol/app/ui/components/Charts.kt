package com.capitol.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capitol.app.analysis.ChartLine
import com.capitol.app.analysis.LineRole
import com.capitol.app.data.market.Candle
import com.capitol.app.ui.theme.CapitolColors
import com.capitol.app.ui.theme.MarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

fun MarketColors.roleColor(role: LineRole, primary: Color): Color = when (role) {
    LineRole.PRIMARY -> primary
    LineRole.SECONDARY -> chart2
    LineRole.TERTIARY -> chart3
    LineRole.BULL -> bull
    LineRole.BEAR -> bear
    LineRole.MUTED -> chart4.copy(alpha = 0.7f)
    LineRole.ACCENT -> neutral
}

/** Build a smooth path through the non-null points; gaps break the line. */
private fun DrawScope.linePath(values: List<Double?>, x: (Int) -> Float, y: (Double) -> Float, progress: Float = 1f): Path {
    val path = Path()
    val limit = (values.size * progress).roundToInt().coerceIn(0, values.size)
    var started = false
    for (i in 0 until limit) {
        val v = values[i]
        if (v == null) { started = false; continue }
        if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
    }
    return path
}

/** Small trend line with a gradient fill, animated on first draw. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color? = null, strokeWidth: Dp = 2.dp, fill: Boolean = true) {
    val mc = CapitolColors.market
    val c = color ?: if ((values.lastOrNull() ?: 0.0) >= (values.firstOrNull() ?: 0.0)) mc.bull else mc.bear
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val x = { i: Int -> i * size.width / (values.size - 1) }
        val y = { v: Double -> (size.height - ((v - lo) / span * size.height * 0.9 + size.height * 0.05)).toFloat() }
        val p = linePath(values, x, y, progress.value)
        if (fill) {
            val area = Path().apply {
                addPath(p)
                val lastIdx = ((values.size * progress.value).roundToInt() - 1).coerceAtLeast(0)
                lineTo(x(lastIdx), size.height); lineTo(0f, size.height); close()
            }
            drawPath(area, Brush.verticalGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent)))
        }
        drawPath(p, c, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round))
    }
}

enum class PriceChartMode { CANDLES, LINE }

/**
 * Interactive price chart: candles or area line, indicator overlays, horizontal
 * levels, price axis and a drag-to-inspect crosshair.
 */
@Composable
fun PriceChart(
    candles: List<Candle>,
    modifier: Modifier = Modifier,
    overlays: List<ChartLine> = emptyList(),
    levels: List<Pair<String, Double>> = emptyList(),
    mode: PriceChartMode = PriceChartMode.CANDLES,
    intraday: Boolean = false,
    height: Dp = 280.dp,
) {
    val mc = CapitolColors.market
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val measurer = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    val reveal = remember(candles) { Animatable(0f) }
    LaunchedEffect(candles) { reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val fmt = remember(intraday) { SimpleDateFormat(if (intraday) "dd MMM HH:mm" else "dd MMM yyyy", Locale.getDefault()) }

    Canvas(
        modifier.fillMaxWidth().height(height)
            .pointerInput(candles) {
                detectDragGestures(onDragEnd = { touchX = null }, onDragCancel = { touchX = null }) { change, _ -> touchX = change.position.x }
            }
            .pointerInput(candles) { detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null }) }
    ) {
        if (candles.size < 2) return@Canvas
        val axisW = 56.dp.toPx()
        val chartW = size.width - axisW
        val top = 8.dp.toPx(); val bottom = size.height - 18.dp.toPx()
        val h = bottom - top

        var lo = candles.minOf { it.low }; var hi = candles.maxOf { it.high }
        overlays.forEach { l -> l.values.forEach { v -> if (v != null) { lo = min(lo, v); hi = max(hi, v) } } }
        val pad = (hi - lo) * 0.06; lo -= pad; hi += pad
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val n = candles.size
        val step = chartW / n
        val x = { i: Int -> (i + 0.5f) * step }
        val y = { v: Double -> (top + (hi - v) / span * h).toFloat() }

        // grid + axis labels
        val labelStyle = TextStyle(color = onSurface, fontSize = 10.sp)
        for (g in 0..4) {
            val v = lo + span * g / 4
            val yy = y(v)
            drawLine(mc.grid, Offset(0f, yy), Offset(chartW, yy), 1f)
            val t = measurer.measure("%.2f".format(v), labelStyle)
            drawText(t, topLeft = Offset(chartW + 6.dp.toPx(), yy - t.size.height / 2))
        }
        listOf(0, n / 2, n - 1).forEach { i ->
            val t = measurer.measure(fmt.format(Date(candles[i].time * 1000)), labelStyle)
            val tx = (x(i) - t.size.width / 2).coerceIn(0f, chartW - t.size.width)
            drawText(t, topLeft = Offset(tx, bottom + 3.dp.toPx()))
        }

        clipRect(0f, 0f, chartW * reveal.value, size.height) {
            // horizontal levels
            levels.forEach { (name, v) ->
                if (v in lo..hi) {
                    val yy = y(v)
                    drawLine(mc.neutral.copy(alpha = 0.6f), Offset(0f, yy), Offset(chartW, yy), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    drawText(measurer.measure(name, TextStyle(color = mc.neutral, fontSize = 9.sp)), topLeft = Offset(4f, yy - 13.dp.toPx()))
                }
            }

            if (mode == PriceChartMode.CANDLES && n <= 180) {
                val bodyW = (step * 0.62f).coerceAtLeast(1.5f)
                candles.forEachIndexed { i, c ->
                    val col = if (c.close >= c.open) mc.bull else mc.bear
                    drawLine(col, Offset(x(i), y(c.high)), Offset(x(i), y(c.low)), max(1f, step * 0.12f))
                    val t = y(max(c.open, c.close)); val b = y(min(c.open, c.close))
                    drawRoundRect(col, Offset(x(i) - bodyW / 2, t), Size(bodyW, max(1.5f, b - t)), CornerRadius(1.5f))
                }
            } else {
                val closes = candles.map { it.close }
                val up = closes.last() >= closes.first()
                val col = if (up) mc.bull else mc.bear
                val path = linePath(closes, x, y)
                val area = Path().apply { addPath(path); lineTo(x(n - 1), bottom); lineTo(x(0), bottom); close() }
                drawPath(area, Brush.verticalGradient(listOf(col.copy(alpha = 0.30f), Color.Transparent), startY = top, endY = bottom))
                drawPath(path, col, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
            }

            overlays.forEach { l ->
                val col = mc.roleColor(l.role, primary)
                drawPath(linePath(l.values, x, y), col, style = Stroke(
                    width = 1.6.dp.toPx(),
                    pathEffect = if (l.dotted) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
                ))
            }
        }

        // crosshair
        touchX?.let { tx ->
            val i = (tx / step).toInt().coerceIn(0, n - 1)
            val c = candles[i]
            drawLine(onSurface.copy(alpha = 0.5f), Offset(x(i), top), Offset(x(i), bottom), 1f)
            drawLine(onSurface.copy(alpha = 0.5f), Offset(0f, y(c.close)), Offset(chartW, y(c.close)), 1f)
            drawCircle(primary, 5.dp.toPx(), Offset(x(i), y(c.close)))
            val label = buildString {
                append(fmt.format(Date(c.time * 1000))).append('\n')
                append("O ${"%.2f".format(c.open)}  H ${"%.2f".format(c.high)}\n")
                append("L ${"%.2f".format(c.low)}  C ${"%.2f".format(c.close)}")
                overlays.forEach { o -> o.values.getOrNull(i)?.let { append("\n${o.name} ${"%.2f".format(it)}") } }
            }
            val t = measurer.measure(label, TextStyle(color = MaterialThemeOnSurface(onSurface), fontSize = 11.sp))
            val bx = if (x(i) > chartW / 2) x(i) - t.size.width - 16.dp.toPx() else x(i) + 10.dp.toPx()
            drawRoundRect(surface.copy(alpha = 0.95f), Offset(bx - 6, top), Size(t.size.width + 12f, t.size.height + 12f), CornerRadius(12f))
            drawText(t, topLeft = Offset(bx, top + 6))
        }
    }
}

private fun MaterialThemeOnSurface(c: Color) = c.copy(alpha = 1f)

/** Indicator sub-pane: lines, histogram bars and guide levels. */
@Composable
fun IndicatorPane(
    lines: List<ChartLine>,
    modifier: Modifier = Modifier,
    levels: List<Pair<String, Double>> = emptyList(),
    height: Dp = 120.dp,
) {
    val mc = CapitolColors.market
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val reveal = remember(lines) { Animatable(0f) }
    LaunchedEffect(lines) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = lines.maxOfOrNull { it.values.size } ?: 0
        if (n < 2) return@Canvas
        val axisW = 56.dp.toPx(); val chartW = size.width - axisW
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        lines.forEach { l -> l.values.forEach { v -> if (v != null) { lo = min(lo, v); hi = max(hi, v) } } }
        levels.forEach { (_, v) -> lo = min(lo, v); hi = max(hi, v) }
        if (lo == Double.MAX_VALUE) return@Canvas
        if (lines.any { it.asHistogram }) { lo = min(lo, 0.0); hi = max(hi, 0.0) }
        val pad = (hi - lo) * 0.08; lo -= pad; hi += pad
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val step = chartW / n
        val x = { i: Int -> (i + 0.5f) * step }
        val y = { v: Double -> ((hi - v) / span * size.height).toFloat() }
        val style = TextStyle(color = onSurface, fontSize = 9.sp)
        levels.forEach { (name, v) ->
            drawLine(mc.grid.copy(alpha = 0.5f), Offset(0f, y(v)), Offset(chartW, y(v)), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            val t = measurer.measure(name, style)
            drawText(t, topLeft = Offset(chartW + 6.dp.toPx(), y(v) - t.size.height / 2))
        }
        val hiT = measurer.measure("%.1f".format(hi - pad), style)
        if (levels.isEmpty()) drawText(hiT, topLeft = Offset(chartW + 6.dp.toPx(), 0f))
        clipRect(0f, 0f, chartW * reveal.value, size.height) {
            lines.forEach { l ->
                if (l.asHistogram) {
                    val zero = y(0.0)
                    l.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        val col = if (l.role == LineRole.MUTED) (if (v >= 0) mc.bull else mc.bear).copy(alpha = 0.55f) else mc.roleColor(l.role, primary)
                        val yy = y(v)
                        drawRect(col, Offset(x(i) - step * 0.35f, min(yy, zero)), Size(step * 0.7f, kotlin.math.abs(zero - yy).coerceAtLeast(1f)))
                    }
                } else {
                    drawPath(linePath(l.values, x, y), mc.roleColor(l.role, primary), style = Stroke(1.6.dp.toPx(),
                        pathEffect = if (l.dotted) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null))
                }
            }
        }
    }
}

/**
 * Semicircular sentiment gauge from Strong Sell (−2) to Strong Buy (+2) with a
 * spring-animated needle.
 */
@Composable
fun SentimentGauge(score: Double, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val mc = CapitolColors.market
    val onSurface = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.surfaceVariant
    val target = ((score.coerceIn(-2.0, 2.0) + 2) / 4).toFloat()
    val anim by animateFloatAsState(target, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow), label = "gauge")
    Box(modifier.size(size, size * 0.6f)) {
        Canvas(Modifier.fillMaxWidth().height(size * 0.6f)) {
            val stroke = 18.dp.toPx()
            val d = min(this.size.width, this.size.height * 2) - stroke
            val tl = Offset((this.size.width - d) / 2, stroke / 2)
            drawArc(track, 180f, 180f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(mc.bear, mc.neutral, mc.bull, mc.bear), center = Offset(this.size.width / 2, tl.y + d / 2)),
                180f, 180f * anim, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val c = Offset(this.size.width / 2, tl.y + d / 2)
            val ang = PI + PI * anim
            val r = d / 2 - stroke * 1.2f
            drawLine(onSurface, c, Offset(c.x + (r * cos(ang)).toFloat(), c.y + (r * sin(ang)).toFloat()), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(onSurface, 7.dp.toPx(), c)
        }
    }
}

/** Animated donut chart for allocations. */
@Composable
fun DonutChart(values: List<Pair<Double, Color>>, modifier: Modifier = Modifier, thickness: Dp = 22.dp) {
    val sweep = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { sweep.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val total = values.sumOf { it.first }.takeIf { it > 0 } ?: return@Canvas
        val stroke = thickness.toPx()
        val d = min(size.width, size.height) - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
        var start = -90f
        values.forEach { (v, col) ->
            val a = (v / total * 360f).toFloat() * sweep.value
            drawArc(col, start, (a - 1.5f).coerceAtLeast(0.5f), false, tl, Size(d, d), style = Stroke(stroke))
            start += a
        }
    }
}
