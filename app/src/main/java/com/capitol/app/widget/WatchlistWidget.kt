package com.capitol.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.capitol.app.CapitolApp
import com.capitol.app.MainActivity
import com.capitol.app.analysis.Signal
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.ChartRange
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WidgetRow(val symbol: String, val price: Double, val changePct: Double, val verdict: Signal)

/** Home-screen widget: the active watchlist's prices, daily change and Capitol verdicts. */
class WatchlistWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as CapitolApp).container
        val settings = c.settings.settings.first()
        val data = c.watchlist.snapshot()
        val rows = coroutineScope {
            data.activeList.items.take(MAX_ROWS).map { w ->
                async {
                    runCatching {
                        val s = c.market.history(w.symbol, ChartRange.Y1)
                        WidgetRow(w.symbol, s.regularMarketPrice, s.changePercent,
                            TechnicalAnalyzer.analyze(s, settings.defaultIndicators, settings.indicatorParams).verdict)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val updated = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        provideContent { GlanceTheme { Content(data.activeList.name, rows, updated) } }
    }

    @Composable
    private fun Content(title: String, rows: List<WidgetRow>, updated: String) {
        val bg = ColorProvider(Color(0xFF0E1422))
        val fg = ColorProvider(Color(0xFFE6EAF2))
        val muted = ColorProvider(Color(0xFFA6B0C3))
        Column(GlanceModifier.fillMaxSize().background(bg).cornerRadius(20.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Capitol · $title", style = TextStyle(color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp), modifier = GlanceModifier.defaultWeight())
                Text("↻ $updated", style = TextStyle(color = muted, fontSize = 11.sp), modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>()))
            }
            Spacer(GlanceModifier.height(6.dp))
            if (rows.isEmpty()) Text("No data — tap ↻ to retry", style = TextStyle(color = muted, fontSize = 12.sp))
            rows.forEach { r ->
                val ch = ColorProvider(if (r.changePct >= 0) Color(0xFF3DDC97) else Color(0xFFFF5C7A))
                val vc = ColorProvider(when { r.verdict.isBullish -> Color(0xFF3DDC97); r.verdict.isBearish -> Color(0xFFFF5C7A); else -> Color(0xFFFFC857) })
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.symbol.substringBefore('.'), style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight())
                    Text("%,.2f".format(r.price), style = TextStyle(color = fg, fontSize = 13.sp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text("%+.2f%%".format(r.changePct), style = TextStyle(color = ch, fontSize = 12.sp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(r.verdict.label, style = TextStyle(color = vc, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }

    companion object {
        const val MAX_ROWS = 6
        suspend fun refresh(context: Context) = runCatching { WatchlistWidget().updateAll(context) }
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WatchlistWidget().updateAll(context)
    }
}

class WatchlistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WatchlistWidget()
}
