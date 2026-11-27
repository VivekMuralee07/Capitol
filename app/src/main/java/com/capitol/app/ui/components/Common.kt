package com.capitol.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import com.capitol.app.analysis.Signal
import com.capitol.app.ui.theme.CapitolColors
import com.capitol.app.ui.theme.NumberStyle

@Composable
fun signalColor(s: Signal): Color {
    val mc = CapitolColors.market
    return when { s.isBullish -> mc.bull; s.isBearish -> mc.bear; else -> mc.neutral }
}

@Composable
fun changeColor(change: Double): Color = if (change >= 0) CapitolColors.market.bull else CapitolColors.market.bear

@Composable
fun SignalChip(signal: Signal, modifier: Modifier = Modifier) {
    val c = signalColor(signal)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.15f))
            .border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when { signal.isBullish -> Icons.AutoMirrored.Filled.TrendingUp; signal.isBearish -> Icons.AutoMirrored.Filled.TrendingDown; else -> Icons.AutoMirrored.Filled.TrendingFlat },
            null, tint = c, modifier = Modifier.padding(end = 4.dp).width(16.dp),
        )
        Text(signal.label, color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** A number that smoothly counts to its new value. */
@Composable
fun AnimatedNumber(value: Double, modifier: Modifier = Modifier, format: (Double) -> String = { "%,.2f".format(it) },
                   color: Color = MaterialTheme.colorScheme.onSurface, fontSize: Int = 16) {
    val anim by animateFloatAsState(value.toFloat(), tween(900), label = "num")
    Text(format(anim.toDouble()), modifier, color = color, style = NumberStyle.copy(fontSize = fontSize.sp))
}

@Composable
fun ChangeText(change: Double, pct: Double, modifier: Modifier = Modifier, fontSize: Int = 13) {
    val c = changeColor(change)
    Text(
        "${if (change >= 0) "▲" else "▼"} ${"%,.2f".format(kotlin.math.abs(change))} (${"%+.2f".format(pct)}%)",
        modifier, color = c, style = NumberStyle.copy(fontSize = fontSize.sp),
    )
}

@Composable
fun CapitolCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) { Column(Modifier.padding(padding), content = content) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.error
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.12f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = c)
        Spacer(Modifier.width(8.dp))
        Text(message, color = c, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Minimal markdown: headings, bullets, **bold** and `code`. Enough for agent replies. */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.padding(2.dp))
                line.startsWith("#") -> Text(inline(line.trimStart('#').trim()), style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") || line.trimStart().startsWith("• ") -> Row {
                    val indent = (line.length - line.trimStart().length) * 4
                    Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = indent.dp, end = 8.dp))
                    Text(inline(line.trimStart().drop(2)), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                line == "---" -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MaterialTheme.colorScheme.outline).padding(0.5.dp))
                else -> Text(inline(line), color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 }
                else { append(s.substring(i)); i = s.length }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > 0) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, background = Color(0x22888888))) { append(s.substring(i + 1, end)) }; i = end + 1 }
                else { append(s[i]); i++ }
            }
            s[i] == '_' && s.indexOf('_', i + 1) > 0 && (i == 0 || s[i - 1] == ' ') -> {
                val end = s.indexOf('_', i + 1)
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}

/** Calls [onTick] every [intervalMs] while the screen is visible and a market is open. */
@Composable
fun AutoRefreshDuringMarketHours(intervalMs: Long = 60_000, onTick: () -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val tick = androidx.compose.runtime.rememberUpdatedState(onTick)
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(intervalMs)
                if (com.capitol.app.data.market.MarketHours.anyOpen()) tick.value()
            }
        }
    }
}

/**
 * A price that briefly flashes green (up) or red (down) when it changes — the classic
 * live-ticker effect.
 */
@Composable
fun FlashingPrice(value: Double, modifier: Modifier = Modifier, fontSize: Int = 14, format: (Double) -> String = { "%,.2f".format(it) }) {
    val mc = com.capitol.app.ui.theme.CapitolColors.market
    var previous by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value) }
    val flash = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    var up by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(value) {
        if (value != previous) {
            up = value > previous; previous = value
            flash.snapTo(1f); flash.animateTo(0f, androidx.compose.animation.core.tween(900))
        }
    }
    val bg = (if (up) mc.bull else mc.bear).copy(alpha = 0.35f * flash.value)
    Text(format(value), modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 4.dp, vertical = 1.dp),
        style = NumberStyle.copy(fontSize = fontSize.sp))
}

/** "● LIVE · 14:32:05" badge; pulses while streaming. */
@Composable
fun LiveBadge(streaming: Boolean, lastTick: Long?, marketOpen: Boolean, modifier: Modifier = Modifier) {
    val mc = com.capitol.app.ui.theme.CapitolColors.market
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    val fmt = androidx.compose.runtime.remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    val (label, color) = when {
        !marketOpen -> "MARKET CLOSED" to MaterialTheme.colorScheme.onSurfaceVariant
        streaming -> "LIVE" to mc.bear
        else -> "DELAYED" to mc.neutral
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 5.dp).size(7.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(color.copy(alpha = if (streaming && marketOpen) a else 0.8f)))
        Text(label + (lastTick?.let { " · ${fmt.format(java.util.Date(it))}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
