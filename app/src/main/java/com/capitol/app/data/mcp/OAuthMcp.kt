package com.capitol.app.data.mcp

import com.capitol.app.data.prefs.SecretStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * A remote MCP server that uses the MCP authorization spec (OAuth 2.1 + PKCE + dynamic client
 * registration). The user signs in on the provider's own page; tokens are stored encrypted under
 * keys prefixed with [keyPrefix] and refreshed automatically. Tokens are only sent to the server.
 */
class OAuthMcp(
    http: OkHttpClient,
    private val secrets: SecretStore,
    private val serverUrl: String,
    private val keyPrefix: String,
    /**
     * Redirect URIs to register, in order of preference. Servers that refuse an app-scheme
     * (`capitol://`) redirect with `invalid_redirect_uri` get the next one — typically an HTTP
     * loopback URL (RFC 8252), which Capitol's in-app sign-in WebView intercepts before it loads.
     */
    private val redirectUris: List<String>,
    /** Shown in error messages, e.g. "Zerodha". */
    private val providerName: String,
) {
    private fun k(name: String) = "${keyPrefix}_$name"
    private fun get(name: String) = secrets.getRaw(k(name))
    private fun put(name: String, v: String?) = secrets.putRaw(k(name), v)
    private val oauth = McpOAuth(http)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()
    private val client = McpClient(http, serverUrl, token = { validAccessToken() })
    private var toolCache: List<McpTool>? = null

    val isConnected get() = (get("ACCESS") != null)

    /** The redirect URI this app registration uses. */
    val redirectUri: String get() = get("REDIRECT") ?: redirectUris.first()

    /** Step 1: discover, register (once) and build the provider's sign-in URL. */
    suspend fun beginLogin(): String {
        val cfg = oauth.discover(serverUrl)
        put("AUTH_CONFIG", json.encodeToString(cfg))
        val clientId = get("CLIENT_ID")?.takeIf { get("REDIRECT") == null || get("REDIRECT") in redirectUris } ?: register(cfg)
        val verifier = McpOAuth.newVerifier()
        val state = McpOAuth.newState()
        put("VERIFIER", verifier)
        put("STATE", state)
        return oauth.authorizationUrl(cfg, clientId, redirectUri, McpOAuth.challengeFor(verifier), state)
    }

    private suspend fun register(cfg: McpAuthConfig): String {
        var last: McpAuthException? = null
        for (uri in redirectUris) {
            try {
                val (id, secret) = oauth.register(cfg, uri)
                put("CLIENT_ID", id); put("CLIENT_SECRET", secret); put("REDIRECT", uri)
                return id
            } catch (e: McpAuthException) {
                last = e
                if (!e.redirectRejected) break // only a rejected redirect is worth retrying with another
            }
        }
        throw McpAuthException("$providerName refused Capitol's app registration: ${last?.message?.substringAfter(": ") ?: "unknown error"}")
    }

    /** Step 2: called with the redirect URL the provider sends the user back to. */
    suspend fun completeLogin(redirectUrl: String) {
        val params = redirectUrl.substringAfter('?', "").split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }.toMap()
        params["error"]?.let { throw McpAuthException("$providerName sign-in was cancelled or refused: ${params["error_description"] ?: it}") }
        val code = params["code"] ?: throw McpAuthException("$providerName didn't return an authorization code")
        if (params["state"] != get("STATE")) throw McpAuthException("Sign-in state mismatch — please try again")
        val cfg = config()
        val tokens = oauth.exchangeCode(cfg, get("CLIENT_ID")!!, get("CLIENT_SECRET"),
            code, get("VERIFIER")!!, redirectUri)
        save(tokens)
        put("VERIFIER", null); put("STATE", null)
        client.reset(); toolCache = null
    }

    fun disconnect() {
        listOf("ACCESS", "REFRESH", "EXPIRES_AT").forEach { put(it, null) }
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

    private fun config(): McpAuthConfig = get("AUTH_CONFIG")?.let { json.decodeFromString<McpAuthConfig>(it) }
        ?: throw McpAuthException("Please connect $providerName again")

    private fun save(t: McpTokens) {
        put("ACCESS", t.accessToken)
        t.refreshToken?.let { put("REFRESH", it) }
        put("EXPIRES_AT", t.expiresAtMillis?.toString())
    }

    private suspend fun validAccessToken(): String? {
        val exp = get("EXPIRES_AT")?.toLongOrNull()
        if (exp != null && System.currentTimeMillis() > exp - 60_000) forceRefresh()
        return get("ACCESS")
    }

    /** Uses the refresh token; returns false (and disconnects) if that's impossible. */
    private suspend fun forceRefresh(): Boolean = refreshLock.withLock {
        val refresh = get("REFRESH") ?: return false.also { disconnect() }
        runCatching {
            save(oauth.refresh(config(), get("CLIENT_ID")!!, get("CLIENT_SECRET"), refresh))
        }.onFailure { disconnect() }.isSuccess
    }

    fun isRedirect(url: String) = redirectUris.any { url.startsWith(it) }
}
