package com.capitol.app

import androidx.test.core.app.ApplicationProvider
import com.capitol.app.agent.AgentTools
import com.capitol.app.agent.CapitolAgent
import com.capitol.app.data.market.ChartRange
import com.capitol.app.data.market.MarketRepository
import com.capitol.app.data.market.YFinanceBackendDataSource
import com.capitol.app.data.market.YahooFinanceDataSource
import com.capitol.app.data.prefs.SettingsStore
import com.capitol.app.data.watchlist.AlertCondition
import com.capitol.app.data.watchlist.IndicatorAlert
import com.capitol.app.data.watchlist.WatchlistStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobolectricFeaturesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<CapitolApp>()

    private val chartJson = """{"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD","regularMarketPrice":12.0,"longName":"Apple"},
        "timestamp":[1700000000,1700086400,1700172800],"indicators":{"quote":[{"open":[10,11,12],"high":[10,11,12],"low":[10,11,12],"close":[10,11,12],"volume":[1,1,1]}]}}],"error":null}}"""

    // --- 2. offline cache fallback
    @Test fun servesDiskCacheWhenSourceFails() = runBlocking {
        var fail = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (fail) MockResponse().setResponseCode(500) else MockResponse().setBody(chartJson)
            }
            val http = OkHttpClient()
            val repo = MarketRepository(YahooFinanceDataSource(http, server.url("").toString().trimEnd('/')),
                YFinanceBackendDataSource(http) { "http://unused" }, SettingsStore(ctx), Files.createTempDirectory("mkt").toFile())
            val fresh = repo.history("AAPL", ChartRange.M1)
            assertNull(fresh.cachedAt)
            fail = true
            val cached = repo.history("AAPL", ChartRange.M1, force = true)
            assertNotNull(cached.cachedAt)
            assertEquals(12.0, cached.regularMarketPrice, 0.0)
        }
    }

    // --- 8. fundamentals via Yahoo crumb handshake, and news
    @Test fun fundamentalsUseCrumbAndNewsParse() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/cookie" -> MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=abc; Path=/")
                        path == "/v1/test/getcrumb" ->
                            if (request.getHeader("Cookie")?.contains("A3=abc") == true) MockResponse().setBody("CRUMB123") else MockResponse().setResponseCode(401)
                        path.startsWith("/v10/finance/quoteSummary/") -> {
                            assertEquals("CRUMB123", request.requestUrl!!.queryParameter("crumb"))
                            MockResponse().setBody("""{"quoteSummary":{"result":[{"price":{"longName":"Apple Inc.","currency":"USD","marketCap":{"raw":3.5E12,"fmt":"3.5T"}},
                                "summaryDetail":{"trailingPE":{"raw":31.2},"dividendYield":{"raw":0.005},"fiftyTwoWeekHigh":{"raw":260.1}},
                                "assetProfile":{"sector":"Technology","industry":"Consumer Electronics"},"financialData":{"returnOnEquity":{"raw":1.5}}}],"error":null}}""")
                        }
                        path == "/v1/finance/search" -> MockResponse().setBody(
                            """{"news":[{"title":"Apple hits record","publisher":"Reuters","link":"https://x/1","providerPublishTime":1700000000}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val base = server.url("").toString().trimEnd('/')
            val yahoo = YahooFinanceDataSource(OkHttpClient(), base, "$base/cookie")
            val f = yahoo.fundamentals("aapl")
            assertEquals("Apple Inc.", f.name); assertEquals(3.5e12, f.marketCap!!, 0.0); assertEquals(31.2, f.trailingPE!!, 0.0)
            assertEquals("Technology", f.sector)
            assertTrue(f.toPlainText().contains("P/E (TTM): 31.2"))
            val news = yahoo.news("AAPL")
            assertEquals("Apple hits record", news.single().title)
        }
    }

    // --- 10. multiple watchlists + migration
    @Test fun multipleWatchlists() = runBlocking {
        val store = WatchlistStore(ctx)
        val migrated = store.migrate("""[{"symbol":"INFY.NS","name":"Infosys"}]""")
        assertEquals(WatchlistStore.DEFAULT_NAME, migrated.lists.single().name)
        assertEquals("INFY.NS", migrated.lists.single().items.single().symbol)

        store.createList("US stocks")
        assertEquals("US stocks", store.snapshot().activeList.name)
        store.add("aapl", "Apple")
        store.add("TCS.NS", "TCS", listName = WatchlistStore.DEFAULT_NAME)
        val d = store.snapshot()
        assertEquals(listOf("AAPL"), d.activeList.items.map { it.symbol })
        assertTrue(d.allItems.any { it.symbol == "TCS.NS" } && d.allItems.any { it.symbol == "AAPL" })

        store.setIndicatorAlerts("AAPL", listOf(IndicatorAlert(AlertCondition.RSI_BELOW, 30.0), IndicatorAlert(AlertCondition.RSI_BELOW, 30.0)))
        assertEquals(1, store.current().single().indicatorAlerts.size) // de-duplicated

        store.deleteList(store.snapshot().active)
        assertTrue(store.snapshot().lists.none { it.name == "US stocks" })
        store.deleteList(0) // can't delete the last list
        assertEquals(1, store.snapshot().lists.size)
    }

    // --- 13. agent session survives save/restore (SDK message serialization)
    @Test fun agentSessionRoundTrip() {
        val c = ctx.container
        val agent = CapitolAgent(c.secrets, AgentTools(c.market, c.watchlist, c.portfolio, c.indmoneyMcp))
        val session = CapitolAgent.Session(
            listOf(com.anthropic.core.jsonMapper().writeValueAsString(com.anthropic.models.messages.MessageParam.ofUser("Analyse INFY"))),
            listOf("Analyse INFY" to "Buy"), 1234)
        agent.restore(session)
        val again = agent.exportSession()
        assertEquals(session.transcript, again.transcript)
        assertEquals(1234, again.lastInputTokens)
        assertTrue(again.apiHistory.single().contains("Analyse INFY"))
    }
}
