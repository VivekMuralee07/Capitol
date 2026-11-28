package com.capitol.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.capitol.app.data.market.Candle
import com.capitol.app.ui.components.PriceChart
import com.capitol.app.ui.components.PriceChartMode
import com.capitol.app.ui.theme.CapitolTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scrubbing the chart inside a scrolling page: horizontal drags scrub (no scroll), vertical drags scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h800dp-xxhdpi")
class ChartScrubTest {
    @get:Rule val compose = createComposeRule()

    private val candles = (0 until 100).map { i ->
        val p = 100.0 + i
        Candle(1_700_000_000L + i * 86_400L, p, p + 1, p - 1, p + 0.5, 1000.0)
    }

    @Test fun horizontalDragScrubsWithoutScrollingThePage() {
        val seen = mutableListOf<Candle?>()
        var listState: androidx.compose.foundation.lazy.LazyListState? = null
        compose.setContent {
            CapitolTheme(darkTheme = true) {
                val state = rememberLazyListState().also { listState = it }
                LazyColumn(state = state) {
                    item {
                        PriceChart(candles, Modifier.testTag("chart"), mode = PriceChartMode.LINE, height = 200.dp,
                            onScrub = { seen += it })
                    }
                    items(30) { Box(Modifier.fillMaxWidth().height(120.dp)) }
                }
            }
        }
        // Horizontal scrub: readout candles reported, page stays put.
        compose.onNodeWithTag("chart").performTouchInput {
            down(centerLeft.copy(x = 40f)); repeat(10) { i -> moveTo(center.copy(x = 40f + i * 60f, y = center.y + i * 2f)) }
        }
        compose.waitForIdle()
        assertTrue("no scrub reported", seen.any { it != null })
        val scrubbed = seen.last()
        assertNotNull(scrubbed)
        assertTrue("should move right along the series", scrubbed!!.close > candles.first().close)
        assertEquals(0, listState!!.firstVisibleItemIndex); assertEquals(0, listState!!.firstVisibleItemScrollOffset)
        compose.onNodeWithTag("chart").performTouchInput { up() }
        compose.waitForIdle()
        assertNull("readout clears on lift", seen.last())

        // Vertical drag: the page scrolls and the readout hides.
        compose.onNodeWithTag("chart").performTouchInput { swipeUp(startY = bottom - 5f, endY = top + 5f) }
        compose.waitForIdle()
        assertTrue("page should scroll", listState!!.firstVisibleItemIndex > 0 || listState!!.firstVisibleItemScrollOffset > 0)
        assertNull(seen.last())
    }
}
