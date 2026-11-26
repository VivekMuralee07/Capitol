package com.capitol.app

import androidx.test.core.app.ApplicationProvider
import com.capitol.app.analysis.Direction
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.analysis.TradePlanner
import com.capitol.app.data.market.Candle
import com.capitol.app.data.market.Fundamentals
import com.capitol.app.data.market.PriceSeries
import com.capitol.app.data.market.YahooFinanceDataSource
import com.capitol.app.export.ReportContent
import com.capitol.app.export.ReportExporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportAndCalendarParseTest {
    private fun series(): PriceSeries {
        val rnd = Random(3); var p = 2400.0
        val c = (0 until 250).map { i ->
            val o = p; p *= 1 + 0.0011 + 0.012 * (rnd.nextDouble() - 0.5) * 2 + 0.004 * sin(i / 9.0)
            Candle(1_735_000_000L + i * 86_400L, o, maxOf(o, p) * 1.004, minOf(o, p) * 0.996, p, 1e6 * (0.6 + rnd.nextDouble()))
        }
        return PriceSeries("RELIANCE.NS", "Reliance Industries Limited", "INR", "NSE", c, c.last().close, c[248].close)
    }

    // --- 12. share/export
    @Test fun rendersPngAndPdf() {
        val ctx = ApplicationProvider.getApplicationContext<CapitolApp>()
        val s = series()
        val r = TechnicalAnalyzer.analyze(s, setOf(IndicatorType.SMA, IndicatorType.RSI, IndicatorType.MACD, IndicatorType.SUPERTREND, IndicatorType.BOLLINGER))
        val content = ReportContent(r, s, TradePlanner.plan(s, Direction.LONG, 200_000.0, 1.0, verdict = r.verdict),
            Fundamentals("RELIANCE.NS", sector = "Energy", marketCap = 1.9e13, trailingPE = 24.1, nextEarningsDate = 1_800_000_000))
        val exporter = ReportExporter(ctx)
        val png = exporter.toPng(content)
        assertTrue(png.length() > 20_000)
        // PDF layout paginates onto A4 pages. (Robolectric can't instantiate android.graphics.pdf.PdfDocument,
        // so the PDF writer itself — a thin wrapper over that API — is exercised on devices only.)
        assertTrue(exporter.pageCount(content) >= 2)
        assertEquals(1, exporter.pageCount(content.copy(plan = null, fundamentals = null,
            report = content.report.copy(results = content.report.results.take(1))), h = 4000))
        assertTrue(exporter.toText(content).contains("Trade") || exporter.toText(content).contains("plan"))
        if (System.getProperty("roborazzi.test.record") == "true") png.copyTo(File("screenshots/report_example.png"), overwrite = true)
    }

    // --- 13. Yahoo calendarEvents parsing
    @Test fun parsesEarningsAndDividendDates() {
        val future = System.currentTimeMillis() / 1000 + 10 * 86_400
        val json = Json.parseToJsonElement("""{"price":{"longName":"X"},"summaryDetail":{"exDividendDate":{"raw":1700000000}},
            "calendarEvents":{"earnings":{"earningsDate":[{"raw":1600000000},{"raw":$future}]},"exDividendDate":{"raw":1750000000},"dividendDate":{"raw":1760000000}}}""").jsonObject
        val f = YahooFinanceDataSource(OkHttpClient()).parseQuoteSummary("X", json)
        assertEquals(future, f.nextEarningsDate)   // past earnings date ignored
        assertEquals(1_750_000_000L, f.exDividendDate)
        assertEquals(1_760_000_000L, f.dividendDate)
    }
}
