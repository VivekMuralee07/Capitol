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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.capitol.app.data.market.isIntraday
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
    // Live quotes run only while Home is on screen.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) vm.setActive(true)
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.setActive(false)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.setActive(false) }
    }
    HomeContent(st, isDark, onToggleTheme, { vm.refresh(force = true) }, vm::selectHero, onOpenSymbol, onOpenAgent, onOpenPortfolio, onOpenSettings, onOpenScreener,
        onHeroRange = vm::setHeroRange, onMoversUniverse = vm::setMoversUniverse)
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
    onHeroRange: (com.capitol.app.data.market.ChartRange) -> Unit = {},
    onMoversUniverse: (com.capitol.app.analysis.Universe) -> Unit = {},
) {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = st.loading && st.hero != null, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        TopGlow()
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Header(isDark, st.loading, onToggleTheme, onRefresh, onOpenSettings)
            }
            item { TickerTape(st.indices.map { it.second } + st.watch, onOpenSymbol) }
            st.error?.let { item { ErrorBanner(it) } }
            item { Staggered(0) { HeroCard(st, onSelectHero, onOpenSymbol, onHeroRange) } }
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
            item { MoversSection(st, onMoversUniverse, onOpenSymbol) }
            if (st.signals.isNotEmpty()) {
                item { SectionTitle("Watchlist signal heatmap") }
                item { SignalHeatmap(st.signals, onOpenSymbol) }
            }
            if (st.watch.isNotEmpty()) {
                val movers = st.watch.sortedByDescending { kotlin.math.abs(it.changePercent) }.take(6)
                item { SectionTitle("Top movers on your watchlist") }
                item {
                    CapitolCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        movers.forEachIndexed { i, q ->
                            if (i > 0) com.capitol.app.ui.components.RowDivider()
                            MoverRow(q) { onOpenSymbol(q.symbol) }
                        }
                    }
                }
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

/** A calm, static accent glow behind the top of the home page. */
@Composable
private fun TopGlow() {
    val mc = CapitolColors.market
    val bg = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        drawRect(bg)
        val w = size.width
        val r = w * 1.1f
        drawCircle(Brush.radialGradient(listOf(mc.glow.copy(alpha = if (mc.isDark) 0.16f else 0.10f), Color.Transparent),
            center = Offset(w * 0.15f, -w * 0.15f), radius = r), r, Offset(w * 0.15f, -w * 0.15f))
    }
}

@Composable
private fun Header(isDark: Boolean, loading: Boolean, onToggleTheme: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    Row(Modifier.statusBarsPadding().padding(top = 14.dp, bottom = 2.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ShowChart, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(greet, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Capitol", style = MaterialTheme.typography.titleLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            else com.capitol.app.ui.components.HeaderIconButton(Icons.Default.Refresh, "Refresh", onRefresh)
            com.capitol.app.ui.components.HeaderIconButton(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle theme", onToggleTheme)
            com.capitol.app.ui.components.HeaderIconButton(Icons.Default.Settings, "Settings", onSettings)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TickerTape(quotes: List<QuoteSnapshot>, onOpen: (String) -> Unit) {
    if (quotes.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .basicMarquee(iterations = Int.MAX_VALUE, velocity = 36.dp),
    ) {
        quotes.forEach { q ->
            Row(Modifier.padding(horizontal = 14.dp).clickable { onOpen(q.symbol) }, verticalAlignment = Alignment.CenterVertically) {
                Text(q.name.take(16), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("%,.2f".format(q.price), style = NumberStyle.copy(fontSize = 12.sp))
                Spacer(Modifier.width(4.dp))
                Text("%+.2f%%".format(q.changePercent), color = changeColor(q.change), style = NumberStyle.copy(fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun HeroCard(st: HomeState, onSelect: (MarketIndex) -> Unit, onOpen: (String) -> Unit, onRange: (com.capitol.app.data.market.ChartRange) -> Unit) {
    val mc = CapitolColors.market
    val hero = st.hero
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(mc.heroStart, mc.heroEnd)))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (mc.isDark) 0.55f else 0.9f), RoundedCornerShape(28.dp))
            .clickable { hero?.let { onOpen(it.symbol) } }
            .padding(20.dp)
    ) {
        Column {
            com.capitol.app.ui.components.PillTabs(HomeViewModel.INDICES.take(5), st.heroIndex, { it.label }, onSelect)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.capitol.app.ui.components.Overline("${st.heroIndex.label} · ${st.heroIndex.region}")
                Spacer(Modifier.weight(1f))
                com.capitol.app.ui.components.LiveBadge(st.streaming, st.lastTick, marketOpen(st.heroIndex))
            }
            Spacer(Modifier.height(6.dp))
            if (hero != null) {
                // Live price and day change; the chart below stays as loaded.
                val q = st.heroQuote
                com.capitol.app.ui.components.FlashingPrice(q?.price ?: hero.regularMarketPrice, fontSize = 38)
                Spacer(Modifier.height(6.dp))
                if (q != null) com.capitol.app.ui.components.ChangePill(q.change, q.changePercent, showAbsolute = true)
                else com.capitol.app.ui.components.ChangePill(hero.change, hero.changePercent, showAbsolute = true)
                Spacer(Modifier.height(14.dp))
                PriceChart(hero.candles, mode = PriceChartMode.LINE, height = 180.dp, intraday = st.heroRange.isIntraday)
                Spacer(Modifier.height(10.dp))
                com.capitol.app.ui.components.PillTabs(com.capitol.app.data.market.ChartRange.PICKER, st.heroRange, { it.label }, onRange)
            } else {
                ShimmerBox(Modifier.fillMaxWidth().height(260.dp))
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
private fun QuickAction(icon: ImageVector, label: String, @Suppress("UNUSED_PARAMETER") color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (CapitolColors.market.isDark) 0.55f else 0.9f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun IndexCard(idx: MarketIndex, q: QuoteSnapshot, onClick: () -> Unit) {
    CapitolCard(Modifier.width(168.dp).clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        com.capitol.app.ui.components.Overline(idx.region)
        Text(idx.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        com.capitol.app.ui.components.FlashingPrice(q.price, fontSize = 18)
        Spacer(Modifier.height(4.dp))
        com.capitol.app.ui.components.ChangePill(q.change, q.changePercent, fontSize = 11)
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
                            .background(c.copy(alpha = 0.12f))
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(q.symbol.substringBefore('.'), style = MaterialTheme.typography.titleSmall)
            Text(q.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Sparkline(q.sparkline, Modifier.width(64.dp).height(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("%,.2f".format(q.price), style = NumberStyle.copy(fontSize = 14.sp))
            Spacer(Modifier.height(2.dp))
            com.capitol.app.ui.components.ChangePill(q.change, q.changePercent, fontSize = 11)
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

/** Whether the market the index trades on is open now (crypto trades around the clock). */
private fun marketOpen(i: MarketIndex): Boolean = when (i.region) {
    "India" -> com.capitol.app.data.market.MarketHours.indiaOpen()
    "US" -> com.capitol.app.data.market.MarketHours.usOpen()
    "Crypto" -> true
    else -> com.capitol.app.data.market.MarketHours.anyOpen()
}

@Composable
private fun MoversSection(st: HomeState, onUniverse: (com.capitol.app.analysis.Universe) -> Unit, onOpen: (String) -> Unit) {
    val open = if (st.moversUniverse == com.capitol.app.analysis.Universe.US_LARGE) com.capitol.app.data.market.MarketHours.usOpen()
        else com.capitol.app.data.market.MarketHours.indiaOpen()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
            Text("Today's top movers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            com.capitol.app.ui.components.LiveBadge(st.streaming, st.lastTick, open)
        }
        com.capitol.app.ui.components.PillTabs(listOf(com.capitol.app.analysis.Universe.NIFTY50, com.capitol.app.analysis.Universe.US_LARGE),
            st.moversUniverse, { HomeViewModel.marketFor(it).label }, onUniverse)
        Text(
            if (st.moversWholeMarket) "Whole market · excludes penny & illiquid stocks"
            else "From ${st.moversUniverse.label} (market-wide list unavailable)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        if (st.gainers.isEmpty() && st.losers.isEmpty()) {
            ShimmerBox(Modifier.fillMaxWidth().height(180.dp))
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MoversColumn("Top gainers", st.gainers, CapitolColors.market.bull, Modifier.weight(1f), onOpen)
            MoversColumn("Top losers", st.losers, CapitolColors.market.bear, Modifier.weight(1f), onOpen)
        }
    }
}

@Composable
private fun MoversColumn(title: String, quotes: List<com.capitol.app.data.live.LiveQuote>, color: Color, modifier: Modifier, onOpen: (String) -> Unit) {
    CapitolCard(modifier, padding = PaddingValues(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = color)
        Spacer(Modifier.height(4.dp))
        if (quotes.isEmpty()) Text("None today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        quotes.forEachIndexed { i, q ->
            androidx.compose.animation.AnimatedContent(q.symbol, label = "mover$i",
                transitionSpec = { (fadeIn(tween(300)) + slideInVertically { it / 2 }) togetherWith androidx.compose.animation.fadeOut(tween(200)) }) { sym ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(sym) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sym.substringBefore('.'), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        com.capitol.app.ui.components.FlashingPrice(q.price, fontSize = 11)
                    }
                    Text("%+.2f%%".format(q.changePercent), style = NumberStyle.copy(fontSize = 12.sp), color = color)
                }
            }
        }
    }
}
