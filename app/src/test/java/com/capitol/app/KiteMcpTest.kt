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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
            if (request.method != "POST") return MockResponse().setResponseCode(404) // no OAuth metadata
            val msg = json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val method = msg["method"]?.jsonPrimitive?.content ?: return MockResponse().setResponseCode(400)
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

    /** Fake Kite MCP that uses standard MCP OAuth (401 + protected-resource metadata), like INDmoney. */
    @Test fun kiteMcpOAuthMode() = runBlocking {
        MockWebServer().use { server ->
            var issued = false
            val registered = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val base = server.url("/").toString().trimEnd('/')
                    val path = request.requestUrl!!.encodedPath
                    val body = request.body.readUtf8()
                    fun ok(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                    return when {
                        path == "/.well-known/oauth-protected-resource/mcp" -> ok("""{"resource":"$base/mcp","authorization_servers":["$base"]}""")
                        path == "/.well-known/oauth-authorization-server" -> ok("""{"issuer":"$base","authorization_endpoint":"$base/oauth/authorize","token_endpoint":"$base/oauth/token","registration_endpoint":"$base/oauth/register"}""")
                        path == "/oauth/register" -> {
                            // Like Zerodha: only HTTPS or HTTP-loopback redirects are accepted.
                            val uris = json.parseToJsonElement(body).jsonObject["redirect_uris"].toString()
                            registered += uris
                            if (uris.contains("https://") || uris.contains("http://127.0.0.1")) ok("""{"client_id":"capitol"}""")
                            else MockResponse().setResponseCode(400).setBody("""{"error":"invalid_redirect_uri","error_description":"Use an HTTPS redirect URI or an HTTP loopback callback."}""")
                        }
                        path == "/oauth/token" -> { issued = true; ok("""{"access_token":"kite-at","refresh_token":"kite-rt","expires_in":86400}""") }
                        path == "/mcp" -> {
                            if (request.getHeader("Authorization") != "Bearer kite-at") return MockResponse().setResponseCode(401)
                                .setHeader("WWW-Authenticate", "Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource/mcp\"")
                            val msg = json.parseToJsonElement(body).jsonObject
                            val id = msg["id"]
                            when (msg["method"]!!.jsonPrimitive.content) {
                                "initialize" -> ok("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""").setHeader("Mcp-Session-Id", "o1")
                                "notifications/initialized" -> MockResponse().setResponseCode(202)
                                "tools/list" -> ok("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"get_mf_holdings","inputSchema":{"type":"object"}},{"name":"place_order","inputSchema":{"type":"object"}}]}}""")
                                else -> ok("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(MF_JSON))}}]}}""")
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val secrets = SecretStore(ApplicationProvider.getApplicationContext())
            val kite = KiteMcp(OkHttpClient(), secrets, server.url("/mcp").toString())
            val authUrl = kite.beginLogin()
            assertTrue(kite.usesOAuth)
            // Loopback redirect registered first (Zerodha refuses capitol://), used in the authorize URL.
            assertEquals(1, registered.size); assertTrue(registered.single().contains(KiteMcp.LOOPBACK_REDIRECT_URI))
            assertEquals(KiteMcp.LOOPBACK_REDIRECT_URI, authUrl.toHttpUrl().queryParameter("redirect_uri"))
            assertTrue(KiteMcp.isRedirect("${KiteMcp.LOOPBACK_REDIRECT_URI}?code=abc"))
            val state = authUrl.toHttpUrl().queryParameter("state")!!
            kite.completeLogin("${KiteMcp.LOOPBACK_REDIRECT_URI}?code=abc&state=$state")
            assertTrue(issued && kite.isConnected)
            assertEquals("INF879O01027", kite.mfHoldings().single().tradingSymbol)
            assertEquals(listOf("get_mf_holdings"), kite.readTools().map { it.name })
            kite.disconnect()
            assertFalse(kite.isConnected)
        }
    }

    /** A server that refuses capitol:// gets the next redirect (loopback) automatically. */
    @Test fun oauthFallsBackToLoopbackWhenAppSchemeIsRefused() = runBlocking {
        MockWebServer().use { server ->
            val tried = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val base = server.url("/").toString().trimEnd('/')
                    fun ok(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                    return when (request.requestUrl!!.encodedPath) {
                        "/.well-known/oauth-protected-resource/mcp" -> ok("""{"resource":"$base/mcp","authorization_servers":["$base"]}""")
                        "/.well-known/oauth-authorization-server" -> ok("""{"authorization_endpoint":"$base/a","token_endpoint":"$base/t","registration_endpoint":"$base/r"}""")
                        "/r" -> {
                            val uri = json.parseToJsonElement(request.body.readUtf8()).jsonObject["redirect_uris"].toString()
                            tried += uri
                            if (uri.contains("capitol://")) MockResponse().setResponseCode(400).setBody("""{"error":"invalid_redirect_uri"}""")
                            else ok("""{"client_id":"c"}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val secrets = SecretStore(ApplicationProvider.getApplicationContext())
            val conn = com.capitol.app.data.mcp.OAuthMcp(OkHttpClient(), secrets, server.url("/mcp").toString(), "TEST_OAUTH",
                listOf("capitol://x-callback", "http://127.0.0.1:43217/x-callback"), "TestBroker")
            val url = conn.beginLogin().toHttpUrl()
            assertEquals(2, tried.size)
            assertEquals("http://127.0.0.1:43217/x-callback", url.queryParameter("redirect_uri"))
            assertEquals("http://127.0.0.1:43217/x-callback", conn.redirectUri)
            // A non-redirect error is reported with the provider name, not retried.
            val conn2 = com.capitol.app.data.mcp.OAuthMcp(OkHttpClient(), secrets, "http://127.0.0.1:1/mcp", "TEST2", listOf("capitol://y"), "TestBroker")
            assertTrue(runCatching { conn2.beginLogin() }.exceptionOrNull()?.message.orEmpty().isNotEmpty())
        }
    }

    @Test fun picksToolsByNameAndKeepsReadOnly() {
        val readme = listOf("login", "get_holdings", "get_mf_holdings", "get_funds", "place_order")
        assertEquals("get_holdings", KiteMcp.pickTool(KiteMcp.ToolKind.HOLDINGS, readme))
        assertEquals("get_mf_holdings", KiteMcp.pickTool(KiteMcp.ToolKind.MF_HOLDINGS, readme))
        val prefixed = listOf("kite_get_profile", "kite_get_holdings", "kite_get_mf_holdings", "kite_place_order")
        assertEquals("kite_get_holdings", KiteMcp.pickTool(KiteMcp.ToolKind.HOLDINGS, prefixed))
        assertEquals("kite_get_mf_holdings", KiteMcp.pickTool(KiteMcp.ToolKind.MF_HOLDINGS, prefixed))
        val other = listOf("portfolio-holdings", "mutual_fund_holdings", "get_funds", "mf_orders")
        assertEquals("portfolio-holdings", KiteMcp.pickTool(KiteMcp.ToolKind.HOLDINGS, other))
        assertEquals("mutual_fund_holdings", KiteMcp.pickTool(KiteMcp.ToolKind.MF_HOLDINGS, other))
        // Margin "funds" or MF orders are never mistaken for MF holdings.
        assertEquals(null, KiteMcp.pickTool(KiteMcp.ToolKind.MF_HOLDINGS, listOf("get_funds", "get_margins", "get_mf_orders", "get_mf_sips")))
        // Read-only filter: any write verb is refused, whatever the prefix.
        listOf("get_holdings", "kite_get_mf_holdings", "search_instruments", "get_orders", "list_positions").forEach { assertTrue(it, KiteMcp.isReadOnly(it)) }
        listOf("place_order", "kite_place_order", "modify_order", "cancel_order", "delete_gtt_order", "place_gtt_order",
            "exit_position", "convert_position", "login", "kite_login", "mf_sip_create", "buy_stock").forEach { assertFalse(it, KiteMcp.isReadOnly(it)) }
    }

    /** The sign-in-protected server can name tools differently from the README; Capitol finds them by name. */
    @Test fun usesServersOwnToolNames() = runBlocking {
        MockWebServer().use { server ->
            var listed = listOf("kite_get_holdings", "kite_get_mf_holdings", "kite_place_order")
            val called = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method != "POST") return MockResponse().setResponseCode(404)
                    val msg = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val id = msg["id"]
                    fun ok(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                    return when (msg["method"]!!.jsonPrimitive.content) {
                        "initialize" -> ok("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""").setHeader("Mcp-Session-Id", "t1")
                        "notifications/initialized" -> MockResponse().setResponseCode(202)
                        "tools/list" -> ok("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[${listed.joinToString { "{\"name\":\"$it\",\"inputSchema\":{\"type\":\"object\"}}" }}]}}""")
                        else -> {
                            val name = msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                            called += name
                            val text = when {
                                name == "login" -> "Open https://kite.zerodha.com/connect/login?v=3 to log in"
                                name == "get_profile" -> """{"user_id":"AB1234"}"""
                                "mf" in name -> MF_JSON
                                "holdings" in name -> """[{"tradingsymbol":"INFY","exchange":"NSE","quantity":5,"average_price":1400,"last_price":1500}]"""
                                else -> "ok"
                            }
                            if (name !in listed && name !in listOf("login", "get_profile"))
                                ok("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"unknown tool \"$name\""}}""")
                            else ok("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}]}}""")
                        }
                    }
                }
            }
            val secrets = SecretStore(ApplicationProvider.getApplicationContext())
            val kite = KiteMcp(OkHttpClient(), secrets, server.url("/mcp").toString())
            kite.beginLogin(); kite.completeLogin()
            assertEquals(5.0, kite.holdings().single().quantity, 0.0)
            assertEquals("INF879O01027", kite.mfHoldings().single().tradingSymbol)
            assertTrue("kite_get_holdings" in called && "kite_get_mf_holdings" in called)
            assertFalse("kite_place_order" in called)
            // No MF tool at all → the error lists what the server offers.
            listed = listOf("kite_get_holdings", "kite_get_profile")
            val fresh = KiteMcp(OkHttpClient(), secrets, server.url("/mcp").toString())
            val msg = runCatching { fresh.mfHoldings() }.exceptionOrNull()?.message.orEmpty()
            assertTrue(msg, msg.contains("no mutual-fund holdings tool") && msg.contains("kite_get_profile"))
            kite.disconnect()
        }
    }
}
