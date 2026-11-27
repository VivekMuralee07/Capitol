package com.capitol.app.data.mcp

import com.capitol.app.data.prefs.SecretStore
import com.capitol.app.data.prefs.SecretStore.Key
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Connection to INDmoney's official MCP server (https://mcp.indmoney.com/mcp).
 * The user signs in on INDmoney's own page; Capitol stores only the resulting OAuth
 * tokens (encrypted) and talks to the server directly — tokens are never sent to Claude.
 */
class IndMoneyMcp(
    http: OkHttpClient,
    private val secrets: SecretStore,
    private val serverUrl: String = SERVER_URL,
) {
    private val oauth = McpOAuth(http)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()
    private val client = McpClient(http, serverUrl, token = { validAccessToken() })
    private var toolCache: List<McpTool>? = null

    val isConnected get() = secrets.has(Key.MCP_INDMONEY_ACCESS)

    /** Step 1: discover, register (once) and build INDmoney's sign-in URL. */
    suspend fun beginLogin(): String {
        val cfg = oauth.discover(serverUrl)
        secrets.put(Key.MCP_INDMONEY_AUTH_CONFIG, json.encodeToString(cfg))
        val clientId = secrets.get(Key.MCP_INDMONEY_CLIENT_ID) ?: oauth.register(cfg, REDIRECT_URI).let { (id, secret) ->
            secrets.put(Key.MCP_INDMONEY_CLIENT_ID, id); secrets.put(Key.MCP_INDMONEY_CLIENT_SECRET, secret); id
        }
        val verifier = McpOAuth.newVerifier()
        val state = McpOAuth.newState()
        secrets.put(Key.MCP_INDMONEY_VERIFIER, verifier)
        secrets.put(Key.MCP_INDMONEY_STATE, state)
        return oauth.authorizationUrl(cfg, clientId, REDIRECT_URI, McpOAuth.challengeFor(verifier), state)
    }

    /** Step 2: called with the redirect URL INDmoney sends the user back to. */
    suspend fun completeLogin(redirectUrl: String) {
        val params = redirectUrl.substringAfter('?', "").split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }.toMap()
        params["error"]?.let { throw McpAuthException("INDmoney sign-in was cancelled or refused: ${params["error_description"] ?: it}") }
        val code = params["code"] ?: throw McpAuthException("INDmoney didn't return an authorization code")
        if (params["state"] != secrets.get(Key.MCP_INDMONEY_STATE)) throw McpAuthException("Sign-in state mismatch — please try again")
        val cfg = config()
        val tokens = oauth.exchangeCode(cfg, secrets.get(Key.MCP_INDMONEY_CLIENT_ID)!!, secrets.get(Key.MCP_INDMONEY_CLIENT_SECRET),
            code, secrets.get(Key.MCP_INDMONEY_VERIFIER)!!, REDIRECT_URI)
        save(tokens)
        secrets.put(Key.MCP_INDMONEY_VERIFIER, null); secrets.put(Key.MCP_INDMONEY_STATE, null)
        client.reset(); toolCache = null
    }

    fun disconnect() {
        listOf(Key.MCP_INDMONEY_ACCESS, Key.MCP_INDMONEY_REFRESH, Key.MCP_INDMONEY_EXPIRES_AT).forEach { secrets.put(it, null) }
        client.reset(); toolCache = null
    }

    suspend fun tools(): List<McpTool> = toolCache ?: withAuthRetry { client.listTools() }.also { toolCache = it }

    suspend fun call(name: String, args: JsonObject): String = withAuthRetry { client.callTool(name, args) }

    private suspend fun <T> withAuthRetry(block: suspend () -> T): T = try {
        block()
    } catch (e: McpException) {
        if (!e.unauthorized || !forceRefresh()) throw e
        client.reset(); block()
    }

    private fun config(): McpAuthConfig = secrets.get(Key.MCP_INDMONEY_AUTH_CONFIG)?.let { json.decodeFromString<McpAuthConfig>(it) }
        ?: throw McpAuthException("Please connect INDmoney again")

    private fun save(t: McpTokens) {
        secrets.put(Key.MCP_INDMONEY_ACCESS, t.accessToken)
        t.refreshToken?.let { secrets.put(Key.MCP_INDMONEY_REFRESH, it) }
        secrets.put(Key.MCP_INDMONEY_EXPIRES_AT, t.expiresAtMillis?.toString())
    }

    private suspend fun validAccessToken(): String? {
        val exp = secrets.get(Key.MCP_INDMONEY_EXPIRES_AT)?.toLongOrNull()
        if (exp != null && System.currentTimeMillis() > exp - 60_000) forceRefresh()
        return secrets.get(Key.MCP_INDMONEY_ACCESS)
    }

    /** Uses the refresh token; returns false (and disconnects) if that's impossible. */
    private suspend fun forceRefresh(): Boolean = refreshLock.withLock {
        val refresh = secrets.get(Key.MCP_INDMONEY_REFRESH) ?: return false.also { disconnect() }
        runCatching {
            save(oauth.refresh(config(), secrets.get(Key.MCP_INDMONEY_CLIENT_ID)!!, secrets.get(Key.MCP_INDMONEY_CLIENT_SECRET), refresh))
        }.onFailure { disconnect() }.isSuccess
    }

    companion object {
        const val SERVER_URL = "https://mcp.indmoney.com/mcp"
        const val REDIRECT_URI = "capitol://indmoney-callback"
        fun isRedirect(url: String) = url.startsWith(REDIRECT_URI)
    }
}
