package com.capitol.app

import androidx.test.core.app.ApplicationProvider
import com.capitol.app.agent.AgentTools
import com.capitol.app.data.mcp.IndMoneyMcp
import com.capitol.app.data.mcp.McpClient
import com.capitol.app.data.mcp.McpOAuth
import com.capitol.app.data.prefs.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end test of the INDmoney MCP connector against a fake server that follows the MCP
 * authorization spec (RFC 9728 / 8414 / 7591 / 7636 / 8707) and the Streamable HTTP transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IndMoneyMcpTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private var issuedAccess = "access-1"
    private var expireNextCall = false
    private val seen = mutableListOf<String>()
    private var registeredRedirect: String? = null
    private var expectedChallenge: String? = null

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val base = server.url("/").toString().trimEnd('/')
                val body = request.body.readUtf8()
                return when {
                    path == "/.well-known/oauth-protected-resource/mcp" -> ok("""{"resource":"$base/mcp","authorization_servers":["$base/auth"],"scopes_supported":["portfolio.read"]}""")
                    path == "/.well-known/oauth-authorization-server/auth" -> ok(
                        """{"issuer":"$base/auth","authorization_endpoint":"$base/auth/authorize","token_endpoint":"$base/auth/token","registration_endpoint":"$base/auth/register"}""")
                    path == "/auth/register" -> {
                        val o = json.parseToJsonElement(body).jsonObject
                        registeredRedirect = o["redirect_uris"].toString()
                        assertEquals("\"none\"", o["token_endpoint_auth_method"].toString())
                        ok("""{"client_id":"capitol-client"}""")
                    }
                    path == "/auth/token" -> {
                        val form = body.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        assertEquals("$base/mcp", form["resource"])
                        assertEquals("capitol-client", form["client_id"])
                        when (form["grant_type"]) {
                            "authorization_code" -> {
                                assertEquals("the-code", form["code"])
                                assertEquals(expectedChallenge, McpOAuth.challengeFor(form["code_verifier"]!!))
                                ok("""{"access_token":"$issuedAccess","refresh_token":"refresh-1","expires_in":3600,"token_type":"Bearer"}""")
                            }
                            "refresh_token" -> { issuedAccess = "access-2"; ok("""{"access_token":"access-2","expires_in":3600}""") }
                            else -> MockResponse().setResponseCode(400)
                        }
                    }
                    path == "/mcp" -> mcp(request, body)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun mcp(request: RecordedRequest, body: String): MockResponse {
        val auth = request.getHeader("Authorization")
        if (auth == null) return MockResponse().setResponseCode(401)
            .setHeader("WWW-Authenticate", "Bearer resource_metadata=\"${server.url("/.well-known/oauth-protected-resource/mcp")}\"")
        if (expireNextCall && auth == "Bearer access-1") { expireNextCall = false; return MockResponse().setResponseCode(401) }
        assertEquals("Bearer $issuedAccess", auth)
        val msg = json.parseToJsonElement(body).jsonObject
        val method = msg["method"]!!.jsonPrimitive.content
        seen += method
        val id = msg["id"]
        return when (method) {
            "initialize" -> ok("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake-indmoney","version":"1"}}}""")
                .setHeader("Mcp-Session-Id", "sess-1")
            "notifications/initialized" -> MockResponse().setResponseCode(202)
            "tools/list" -> {
                assertEquals("sess-1", request.getHeader("Mcp-Session-Id"))
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[" +
                        "{\"name\":\"get_us_stocks\",\"description\":\"US stock holdings\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"include_pnl\":{\"type\":\"boolean\"}}}}," +
                        "{\"name\":\"portfolio.summary\",\"description\":\"Net worth\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n")
            }
            "tools/call" -> {
                val name = msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                ok("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"$name: AAPL 2.5 sh, NVDA 5 sh"}],"isError":false}}""")
            }
            else -> ok("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no"}}""")
        }
    }

    @Test fun fullOAuthAndToolFlow() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<CapitolApp>()
        val secrets = SecretStore(ctx)
        val mcp = IndMoneyMcp(OkHttpClient(), secrets, server.url("/mcp").toString())
        assertFalse(mcp.isConnected)

        // Discovery + dynamic registration + PKCE authorization URL.
        val authUrl = mcp.beginLogin().toHttpUrl()
        assertEquals("/auth/authorize", authUrl.encodedPath)
        assertEquals("capitol-client", authUrl.queryParameter("client_id"))
        assertEquals("S256", authUrl.queryParameter("code_challenge_method"))
        assertEquals(server.url("/mcp").toString(), authUrl.queryParameter("resource"))
        assertEquals("portfolio.read", authUrl.queryParameter("scope"))
        assertEquals(IndMoneyMcp.REDIRECT_URI, authUrl.queryParameter("redirect_uri"))
        assertTrue(registeredRedirect!!.contains(IndMoneyMcp.REDIRECT_URI))
        expectedChallenge = authUrl.queryParameter("code_challenge")

        // INDmoney redirects back; a wrong state is rejected.
        val state = authUrl.queryParameter("state")!!
        assertTrue(runCatching { mcp.completeLogin("${IndMoneyMcp.REDIRECT_URI}?code=the-code&state=evil") }.isFailure)
        mcp.completeLogin("${IndMoneyMcp.REDIRECT_URI}?code=the-code&state=$state")
        assertTrue(mcp.isConnected)

        // MCP session: initialize (JSON) → initialized → tools/list (SSE) → tools/call.
        val tools = mcp.tools()
        assertEquals(listOf("get_us_stocks", "portfolio.summary"), tools.map { it.name })
        assertEquals("indmoney__portfolio_summary", AgentTools.claudeToolName("portfolio.summary"))
        assertEquals("get_us_stocks: AAPL 2.5 sh, NVDA 5 sh", mcp.call("get_us_stocks", buildJsonObject { put("include_pnl", true) }))
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), seen)

        // A 401 triggers a refresh-token exchange and a transparent retry.
        expireNextCall = true
        assertEquals("portfolio.summary: AAPL 2.5 sh, NVDA 5 sh", mcp.call("portfolio.summary", buildJsonObject { }))
        assertEquals("access-2", secrets.get(SecretStore.Key.MCP_INDMONEY_ACCESS))

        mcp.disconnect()
        assertFalse(mcp.isConnected)
    }

    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", McpOAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun sseParsingPicksMatchingResponse() {
        val body = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n\n"
        val r = McpClient.parseSse(body, JsonPrimitive(7))!!
        assertEquals("true", r["result"]!!.jsonObject["ok"].toString())
    }
}
