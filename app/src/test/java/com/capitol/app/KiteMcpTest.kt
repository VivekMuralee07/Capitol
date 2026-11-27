package com.capitol.app

import androidx.test.core.app.ApplicationProvider
import com.capitol.app.analysis.shortLabel
import com.capitol.app.data.broker.AssetKind
import com.capitol.app.data.broker.BrokerException
import com.capitol.app.data.broker.IndMoneyClient
import com.capitol.app.data.broker.KiteConnectClient
import com.capitol.app.data.broker.MutualFunds
import com.capitol.app.data.broker.PortfolioRepository
import com.capitol.app.data.mcp.KiteMcp
import com.capitol.app.data.prefs.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MF_JSON = """[{"folio":"123456/78","fund":"Parag Parikh Flexi Cap Fund - Direct Plan","tradingsymbol":"INF879O01027",
  "average_price":55.20,"last_price":82.41,"pnl":2721.0,"quantity":100.0},
 {"folio":"999","fund":"Redeemed Fund","tradingsymbol":"INF000000000","average_price":10,"last_price":11,"quantity":0}]"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KiteMcpTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesMutualFundsInAllShapes() {
        val api = MutualFunds.parse("""{"status":"success","data":$MF_JSON}""")
        val raw = MutualFunds.parse(MF_JSON)
        val prose = MutualFunds.parse("Here are your mutual fund holdings:\n$MF_JSON\nTotal: 1 fund.")
        listOf(api, raw, prose).forEach { list ->
            val h = list.single() // zero-quantity scheme dropped
            assertEquals(AssetKind.MUTUAL_FUND, h.kind)
            assertEquals("INF879O01027", h.tradingSymbol)
            assertEquals("Parag Parikh Flexi Cap Fund - Direct Plan", h.displayName)
            assertEquals("123456/78", h.folio)
            assertEquals(100 * 82.41 - 100 * 55.20, h.pnl, 1e-6)
        }
        assertEquals("Parag Parikh Flexi Cap", api.single().shortLabel)
    }

    @Test fun kiteConnectMfHoldings() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"success","data":$MF_JSON}"""))
            val secrets = SecretStore(ApplicationProvider.getApplicationContext())
            secrets.put(SecretStore.Key.KITE_API_KEY, "key"); secrets.put(SecretStore.Key.KITE_ACCESS_TOKEN, "tok")
            val kite = KiteConnectClient(OkHttpClient(), secrets, server.url("").toString().trimEnd('/'))
            assertEquals(1, kite.mfHoldings().size)
            val req = server.takeRequest()
            assertEquals("/mf/holdings", req.path)
            assertEquals("token key:tok", req.getHeader("Authorization"))
            secrets.put(SecretStore.Key.KITE_ACCESS_TOKEN, null); secrets.put(SecretStore.Key.KITE_API_KEY, null)
        }
    }

    /** Fake hosted Kite MCP: session-bound login via a `login` tool, like mcp.kite.trade. */
    private inner class FakeKite : Dispatcher() {
        var loggedIn = false
        var initializeCount = 0
        var dropSession = false
        val sessions = mutableSetOf<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val msg = json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val method = msg["method"]!!.jsonPrimitive.content
            val id = msg["id"]
            val sid = request.getHeader("Mcp-Session-Id")
            if (method == "initialize") {
                initializeCount++
                sessions += "s$initializeCount"
                return ok("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}""").setHeader("Mcp-Session-Id", "s$initializeCount")
            }
            if (sid == null || sid !in sessions || dropSession) return MockResponse().setResponseCode(404)
            if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
            if (method == "tools/list") return ok("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[
                {"name":"login","inputSchema":{"type":"object"}},{"name":"get_holdings","inputSchema":{"type":"object"}},
                {"name":"get_mf_holdings","inputSchema":{"type":"object"}},{"name":"place_order","inputSchema":{"type":"object"}},
                {"name":"search_instruments","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}}]}}""")
            val name = msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
            val text = when (name) {
                "login" -> "Please open this link to log in to Kite: https://kite.zerodha.com/connect/login?api_key=kitemcp&v=3&redirect_params=session_id%3D$sid ."
                else -> if (!loggedIn) "Please log in first using the login tool." else when (name) {
                    "get_profile" -> """{"user_id":"AB1234","user_name":"Test User"}"""
                    "get_mf_holdings" -> MF_JSON
                    "get_holdings" -> """[{"tradingsymbol":"INFY","exchange":"NSE","quantity":10,"t1_quantity":2,"average_price":1400,"last_price":1500,"day_change_percentage":1.2}]"""
                    else -> "ok"
                }
            }
            return ok("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}]}}""")
        }
        private fun ok(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
    }

    @Test fun kiteMcpLoginMutualFundsSessionsAndSafety() = runBlocking {
        MockWebServer().use { server ->
            val fake = FakeKite()
            server.dispatcher = fake
            val ctx = ApplicationProvider.getApplicationContext<CapitolApp>()
            val secrets = SecretStore(ctx)
            val url = server.url("/mcp").toString()
            val http = OkHttpClient()
            val kite = KiteMcp(http, secrets, url)
            assertFalse(kite.isConnected)

            // login tool → Kite URL; completing before the user logs in fails clearly
            val link = kite.beginLogin()
            assertTrue(link.startsWith("https://kite.zerodha.com/connect/login"))
            assertTrue(runCatching { kite.completeLogin() }.exceptionOrNull() is BrokerException)
            fake.loggedIn = true
            kite.completeLogin()
            assertTrue(kite.isConnected)

            // Mutual funds and stocks
            assertEquals("INF879O01027", kite.mfHoldings().single().tradingSymbol)
            assertEquals(12.0, kite.holdings().single().quantity, 0.0)

            // Read-only: order tools are never exposed or callable
            assertEquals(setOf("get_holdings", "get_mf_holdings", "search_instruments"), kite.readTools().map { it.name }.toSet())
            assertTrue(runCatching { kite.callRead("place_order", JsonObject(emptyMap())) }.isFailure)

            // Session persists across app restarts: a new instance reuses it without re-initializing
            val again = KiteMcp(http, secrets, url)
            assertTrue(again.isConnected)
            assertEquals(1, again.mfHoldings().size)
            assertEquals(1, fake.initializeCount)

            // Portfolio pulls stocks + Coin MFs through MCP when Kite Connect isn't set up
            val repo = PortfolioRepository(KiteConnectClient(http, secrets), IndMoneyClient(http, secrets), again)
            repo.refresh()
            assertEquals(setOf(AssetKind.EQUITY, AssetKind.MUTUAL_FUND), repo.state.value.holdings.map { it.kind }.toSet())

            // Server drops the session (Kite login expired) → disconnected with a clear message
            fake.dropSession = true
            val err = runCatching { again.mfHoldings() }.exceptionOrNull()
            assertTrue(err is BrokerException && err.message!!.contains("expired"))
            assertFalse(again.isConnected)
        }
    }
}
