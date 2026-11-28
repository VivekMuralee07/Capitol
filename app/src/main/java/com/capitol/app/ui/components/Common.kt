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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
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
fun CapitolCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(18.dp), tint: androidx.compose.ui.graphics.Brush? = null,
                content: @Composable ColumnScope.() -> Unit) {
    val dark = CapitolColors.market.isDark
    Card(
        modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // Hairline edge: barely there in dark mode, a soft rule on light backgrounds.
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (dark) 0.55f else 0.9f)),
        elevation = CardDefaults.cardElevation(0.dp),
    ) { Column((if (tint != null) Modifier.background(tint) else Modifier).padding(padding), content = content) }
}

/** Section heading: quiet title with an optional trailing action ("See all", chips…). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    val mc = CapitolColors.market
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mc.vibrant) {
            Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(mc.heroGradient)))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * Large-title screen header used by every screen: optional back button, title, subtitle and trailing actions.
 * Handles the status bar inset itself.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            HeaderIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (onBack == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Round, softly filled icon button used in headers. */
@Composable
fun HeaderIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit,
                     tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(20.dp), tint = tint) }
}

/** Tinted pill with an arrow and percentage — the standard way Capitol shows a day change. */
@Composable
fun ChangePill(change: Double, pct: Double, modifier: Modifier = Modifier, showAbsolute: Boolean = false, fontSize: Int = 13) {
    val c = changeColor(change)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (change >= 0) "▲ " else "▼ ") + (if (showAbsolute) "%,.2f · ".format(kotlin.math.abs(change)) else "") + "%.2f%%".format(kotlin.math.abs(pct)),
            color = c, style = NumberStyle.copy(fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Small uppercase label above a value (e.g. "INVESTED"). */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.error
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.copy(alpha = 0.10f)).padding(14.dp),
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

/**
 * Compact pill tabs (chart ranges, index pickers): the selected pill is filled with the text colour,
 * the rest are plain labels — a quiet, premium segmented control.
 */
@Composable
fun <T> PillTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(options.size) { i ->
            val o = options[i]
            val on = o == selected
            Text(
                label(o),
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A thin rule between rows inside a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) =
    androidx.compose.material3.HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
