package com.capitol.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.Candle
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.QuoteSnapshot
import com.capitol.app.ui.analysis.AnalysisScreen
import com.capitol.app.ui.analysis.AnalysisViewModel
import com.capitol.app.ui.home.HomeContent
import com.capitol.app.ui.home.HomeState
import com.capitol.app.ui.home.HomeViewModel
import com.capitol.app.ui.theme.CapitolTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders key screens with synthetic market data. Run with
 * `./gradlew testDebugUnitTest -Pscreenshots` to (re)generate app/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1800dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun fakeSeries(symbol: String, name: String, start: Double, drift: Double, seed: Int, n: Int = 250): PriceSeries {
        val rnd = Random(seed)
        var p = start
        val candles = (0 until n).map { i ->
            val o = p
            p *= 1 + drift + 0.012 * (rnd.nextDouble() - 0.5) * 2 + 0.004 * sin(i / 9.0)
            val c = p
            Candle(1_735_000_000L + i * 86_400L, o, maxOf(o, c) * (1 + rnd.nextDouble() * 0.006), minOf(o, c) * (1 - rnd.nextDouble() * 0.006), c,
                1e6 * (0.6 + rnd.nextDouble()))
        }
        return PriceSeries(symbol, name, "INR", "NSE", candles, candles.last().close, candles[n - 2].close)
    }

    private fun homeState(): HomeState {
        val hero = fakeSeries("^NSEI", "NIFTY 50", 22000.0, 0.0009, 1, 65)
        val idx = HomeViewModel.INDICES.mapIndexed { i, m ->
            val s = fakeSeries(m.symbol, m.label, 1000.0 * (i + 1), if (i % 3 == 0) -0.001 else 0.0012, 10 + i, 22)
            m to QuoteSnapshot(s.symbol, m.label, s.regularMarketPrice, s.change, s.changePercent, s.currency, s.closes)
        }
        val watch = listOf(
            fakeSeries("RELIANCE.NS", "Reliance Industries", 2400.0, 0.0012, 3),
            fakeSeries("TCS.NS", "Tata Consultancy Services", 3900.0, -0.0008, 4),
            fakeSeries("HDFCBANK.NS", "HDFC Bank", 1500.0, 0.0006, 5),
            fakeSeries("INFY.NS", "Infosys", 1450.0, -0.0015, 6),
            fakeSeries("TATAMOTORS.NS", "Tata Motors", 700.0, 0.002, 7),
            fakeSeries("AAPL", "Apple Inc.", 190.0, 0.0003, 8),
        )
        return HomeState(
            loading = false, hero = hero, indices = idx,
            watch = watch.map { QuoteSnapshot(it.symbol, it.name, it.regularMarketPrice, it.change, it.changePercent, it.currency, it.closes.takeLast(40)) },
            signals = watch.map { TechnicalAnalyzer.analyze(it, IndicatorType.DEFAULTS) }.sortedByDescending { it.compositeScore },
            heroRange = com.capitol.app.data.market.ChartRange.M3,
            streaming = true, lastTick = 1_764_241_200_000L,
            gainers = listOf("TATAMOTORS.NS" to 4.82, "ADANIENT.NS" to 3.91, "BEL.NS" to 3.12, "TRENT.NS" to 2.64, "SBIN.NS" to 2.05)
                .map { (s, p) -> com.capitol.app.data.live.LiveQuote(s, 800.0 + p * 37, p * 8, p) },
            losers = listOf("INFY.NS" to -3.44, "WIPRO.NS" to -2.81, "HCLTECH.NS" to -2.10, "TECHM.NS" to -1.72, "CIPLA.NS" to -1.05)
                .map { (s, p) -> com.capitol.app.data.live.LiveQuote(s, 1500.0 + p * 41, p * 12, p) },
        )
    }

    private fun home(dark: Boolean, file: String) {
        val st = homeState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CapitolTheme(darkTheme = dark) { HomeContent(st, dark, {}, {}, {}, {}, {}, {}, {}) }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    @Test fun homeDark() = home(true, "home_dark.png")
    @Test fun homeLight() = home(false, "home_light.png")

    private fun analysis(dark: Boolean, file: String, inds: Set<IndicatorType>) {
        val vm = AnalysisViewModel((ApplicationProvider.getApplicationContext<CapitolApp>()).container)
        vm.present(fakeSeries("RELIANCE.NS", "Reliance Industries Limited", 2400.0, 0.0011, 3), inds)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CapitolTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { AnalysisScreen(vm, null) {} }
            }
        }
        vm.present(fakeSeries("RELIANCE.NS", "Reliance Industries Limited", 2400.0, 0.0011, 3), inds)
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    @Test fun analysisDark() = analysis(true, "analysis_dark.png",
        setOf(IndicatorType.SMA, IndicatorType.BOLLINGER, IndicatorType.RSI, IndicatorType.MACD, IndicatorType.SUPERTREND))
    @Test fun analysisLight() = analysis(false, "analysis_light.png",
        setOf(IndicatorType.EMA, IndicatorType.ICHIMOKU, IndicatorType.STOCHASTIC, IndicatorType.ADX))
}
