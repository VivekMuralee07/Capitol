package com.capitol.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capitol.app.analysis.AnalysisReport
import com.capitol.app.analysis.Signal
import com.capitol.app.data.market.QuoteSnapshot
import com.capitol.app.ui.components.AnimatedNumber
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.ChangeText
import com.capitol.app.ui.components.ErrorBanner
import com.capitol.app.ui.components.PriceChart
import com.capitol.app.ui.components.PriceChartMode
import com.capitol.app.ui.components.SectionTitle
import com.capitol.app.ui.components.SentimentGauge
import com.capitol.app.ui.components.SignalChip
import com.capitol.app.ui.components.Sparkline
import com.capitol.app.ui.components.changeColor
import com.capitol.app.ui.components.signalColor
import com.capitol.app.ui.theme.CapitolColors
import com.capitol.app.ui.theme.NumberStyle
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    onOpenAgent: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScreener: () -> Unit = {},
) {
    val st by vm.state.collectAsStateWithLifecycle()
    com.capitol.app.ui.components.AutoRefreshDuringMarketHours { vm.refresh(force = true) }
    HomeContent(st, isDark, onToggleTheme, { vm.refresh(force = true) }, vm::selectHero, onOpenSymbol, onOpenAgent, onOpenPortfolio, onOpenSettings, onOpenScreener)
}

/** Stateless home page, rendered from [HomeState] (also used by screenshot tests). */
@Composable
fun HomeContent(
    st: HomeState,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onRefresh: () -> Unit,
    onSelectHero: (MarketIndex) -> Unit,
    onOpenSymbol: (String) -> Unit,
    onOpenAgent: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScreener: () -> Unit = {},
) {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = st.loading && st.hero != null, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        AuroraBackground()
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Header(isDark, st.loading, onToggleTheme, onRefresh, onOpenSettings)
            }
            item { TickerTape(st.indices.map { it.second } + st.watch, onOpenSymbol) }
            st.error?.let { item { ErrorBanner(it) } }
            item { Staggered(0) { HeroCard(st, onSelectHero, onOpenSymbol) } }
            item { Staggered(1) { MoodCard(st) } }
            item { Staggered(2) { QuickActions(onOpenSymbol = { onOpenSymbol(st.heroIndex.symbol) }, onOpenAgent, onOpenPortfolio, onOpenScreener) } }
            item { SectionTitle("Global markets") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(st.indices) { i, (idx, q) ->
                        Staggered(i) { IndexCard(idx, q) { onOpenSymbol(q.symbol) } }
                    }
                    if (st.indices.isEmpty() && st.loading) items(4) { ShimmerBox(Modifier.size(160.dp, 120.dp)) }
                }
            }
            if (st.signals.isNotEmpty()) {
                item { SectionTitle("Watchlist signal heatmap") }
                item { SignalHeatmap(st.signals, onOpenSymbol) }
            }
            if (st.watch.isNotEmpty()) {
                val movers = st.watch.sortedByDescending { kotlin.math.abs(it.changePercent) }.take(6)
                item { SectionTitle("Top movers on your watchlist") }
                itemsIndexed(movers) { i, q -> Staggered(i) { MoverRow(q) { onOpenSymbol(q.symbol) } } }
            }
            item {
                Text(
                    "Data: Yahoo Finance via yfinance-compatible endpoints · Educational use only, not investment advice.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Slowly drifting colour blobs behind the home page. */
@Composable
private fun AuroraBackground() {
    val mc = CapitolColors.market
    val bg = MaterialTheme.colorScheme.background
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "phase")
    val a = if (mc.isDark) 0.28f else 0.35f
    Canvas(Modifier.fillMaxSize()) {
        drawRect(bg)
        val w = size.width; val h = size.height
        fun blob(cx: Float, cy: Float, r: Float, c: Color) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
        blob(w * (0.2f + 0.15f * cos(phase)), h * (0.12f + 0.05f * sin(phase)), w * 0.8f, mc.chart1)
        blob(w * (0.85f + 0.1f * sin(phase * 1.3f)), h * (0.25f + 0.06f * cos(phase)), w * 0.7f, mc.bull)
        blob(w * (0.5f + 0.2f * sin(phase * 0.7f)), h * (0.55f + 0.05f * cos(phase * 1.1f)), w * 0.9f, mc.chart3)
    }
}

@Composable
private fun Header(isDark: Boolean, loading: Boolean, onToggleTheme: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    val t = rememberInfiniteTransition(label = "logo")
    val pulse by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Row(Modifier.statusBarsPadding().padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(CapitolColors.market.chart1, CapitolColors.market.bull))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ShowChart, null, tint = Color.White, modifier = Modifier.scale(pulse))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(greet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Capitol", style = MaterialTheme.typography.headlineMedium)
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else IconButton(onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(onToggleTheme) { Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle theme") }
        IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TickerTape(quotes: List<QuoteSnapshot>, onOpen: (String) -> Unit) {
    if (quotes.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
            .padding(vertical = 8.dp)
            .basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp),
    ) {
        quotes.forEach { q ->
            Row(Modifier.padding(horizontal = 14.dp).clickable { onOpen(q.symbol) }, verticalAlignment = Alignment.CenterVertically) {
                Text(q.name.take(16), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Text("%,.2f".format(q.price), style = NumberStyle.copy(fontSize = 12.sp))
                Spacer(Modifier.width(4.dp))
                Text("%+.2f%%".format(q.changePercent), color = changeColor(q.change), style = NumberStyle.copy(fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun HeroCard(st: HomeState, onSelect: (MarketIndex) -> Unit, onOpen: (String) -> Unit) {
    val mc = CapitolColors.market
    val hero = st.hero
    val t = rememberInfiniteTransition(label = "live")
    val live by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "liveDot")
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(mc.heroStart, mc.heroEnd)))
            .clickable { hero?.let { onOpen(it.symbol) } }
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).alpha(live).clip(CircleShape).background(mc.bull))
                Spacer(Modifier.width(6.dp))
                Text(st.heroIndex.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(st.heroIndex.region, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            if (hero != null) {
                AnimatedNumber(hero.regularMarketPrice, fontSize = 34)
                ChangeText(hero.change, hero.changePercent, fontSize = 15)
                Spacer(Modifier.height(10.dp))
                PriceChart(hero.candles, mode = PriceChartMode.LINE, height = 190.dp)
            } else {
                ShimmerBox(Modifier.fillMaxWidth().height(240.dp))
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HomeViewModel.INDICES.take(5)) { idx ->
                    FilterChip(selected = idx == st.heroIndex, onClick = { onSelect(idx) }, label = { Text(idx.label) })
                }
            }
        }
    }
}

@Composable
private fun MoodCard(st: HomeState) {
    val mood = st.mood
    val sig = Signal.fromScore(mood)
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Market mood", style = MaterialTheme.typography.titleMedium)
                Text("Composite of your watchlist's technicals", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                SignalChip(sig)
                Spacer(Modifier.height(12.dp))
                BreadthBar(st.advancers, st.decliners)
            }
            SentimentGauge(mood, size = 150.dp)
        }
    }
}

@Composable
private fun BreadthBar(adv: Int, dec: Int) {
    val mc = CapitolColors.market
    val total = (adv + dec).coerceAtLeast(1)
    val frac by animateFloatAsState(adv.toFloat() / total, tween(1200), label = "breadth")
    Column {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(mc.bear.copy(alpha = 0.8f))) {
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).height(8.dp).background(mc.bull))
        }
        Spacer(Modifier.height(4.dp))
        Text("$adv advancing · $dec declining", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickActions(onOpenSymbol: () -> Unit, onOpenAgent: () -> Unit, onOpenPortfolio: () -> Unit, onOpenScreener: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction(Icons.AutoMirrored.Filled.ShowChart, "Analyze", CapitolColors.market.chart1, Modifier.weight(1f), onOpenSymbol)
        QuickAction(Icons.Default.FilterAlt, "Screener", CapitolColors.market.chart2, Modifier.weight(1f), onOpenScreener)
        QuickAction(Icons.Default.AutoAwesome, "Ask AI", CapitolColors.market.chart3, Modifier.weight(1f), onOpenAgent)
        QuickAction(Icons.Default.AccountBalanceWallet, "Portfolio", CapitolColors.market.bull, Modifier.weight(1f), onOpenPortfolio)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun IndexCard(idx: MarketIndex, q: QuoteSnapshot, onClick: () -> Unit) {
    CapitolCard(Modifier.width(168.dp).clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        Text(idx.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(idx.region, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        AnimatedNumber(q.price, fontSize = 17)
        Text("%+.2f%%".format(q.changePercent), color = changeColor(q.change), style = NumberStyle.copy(fontSize = 12.sp))
        Spacer(Modifier.height(6.dp))
        Sparkline(q.sparkline, Modifier.fillMaxWidth().height(36.dp))
    }
}

@Composable
private fun SignalHeatmap(reports: List<AnalysisReport>, onOpen: (String) -> Unit) {
    val rows = reports.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { r ->
                    val c = signalColor(r.verdict)
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.32f), c.copy(alpha = 0.12f))))
                            .clickable { onOpen(r.symbol) }.padding(12.dp)
                    ) {
                        Text(r.symbol.substringBefore('.'), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.verdict.label, color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("%+.2f".format(r.compositeScore), style = NumberStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MoverRow(q: QuoteSnapshot, onClick: () -> Unit) {
    CapitolCard(Modifier.fillMaxWidth().clickable(onClick = onClick), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(q.symbol, style = MaterialTheme.typography.titleSmall)
                Text(q.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Sparkline(q.sparkline, Modifier.width(70.dp).height(30.dp))
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("%,.2f".format(q.price), style = NumberStyle.copy(fontSize = 14.sp))
                Text("%+.2f%%".format(q.changePercent), color = changeColor(q.change), style = NumberStyle.copy(fontSize = 12.sp))
            }
        }
    }
}

/** Fade + slide entrance, staggered by [index]. */
@Composable
fun Staggered(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60L * index.coerceAtMost(8)); visible = true }
    AnimatedVisibility(visible, enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 4 }) { content() }
}

@Composable
fun ShimmerBox(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(
            Brush.linearGradient(
                listOf(base, base.copy(alpha = 0.4f), base),
                start = Offset(x * 600f, 0f), end = Offset(x * 600f + 400f, 400f),
            )
        )
    )
}
