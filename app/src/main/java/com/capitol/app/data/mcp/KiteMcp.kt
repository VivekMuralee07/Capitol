package com.capitol.app.data.mcp

import com.capitol.app.data.broker.BrokerException
import com.capitol.app.data.broker.Holding
import com.capitol.app.data.broker.MutualFunds
import com.capitol.app.data.prefs.SecretStore
import com.capitol.app.data.prefs.SecretStore.Key
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Zerodha's official hosted Kite MCP server (https://mcp.kite.trade/mcp). No Kite developer app
 * or API key is needed. Two sign-in methods are supported, picked automatically:
 *  - **MCP OAuth** (the server answers 401 and publishes OAuth metadata): sign in on Zerodha's page,
 *    tokens stored encrypted and refreshed — same flow as INDmoney.
 *  - **`login` tool** (as described in Zerodha's README): the tool returns a Kite login link and the
 *    persisted MCP session is authorised once the user logs in.
 * Capitol only uses read tools — the hosted server excludes trading operations, and Capitol
 * additionally exposes only `get_*`/`search_*`.
 */
class KiteMcp(
    http: OkHttpClient,
    private val secrets: SecretStore,
    serverUrl: String = SERVER_URL,
) {
    private val client = McpClient(http, serverUrl, token = { null }, sessionStore = object : McpClient.SessionStore {
        override fun load() = secrets.get(Key.MCP_KITE_SESSION)
        override fun save(id: String?) = secrets.put(Key.MCP_KITE_SESSION, id)
    })
    // Zerodha requires an HTTPS or HTTP-loopback redirect (it refuses capitol://), so loopback goes first.
    private val oauth = OAuthMcp(http, secrets, serverUrl, keyPrefix = "MCP_KITE_OAUTH",
        redirectUris = listOf(LOOPBACK_REDIRECT_URI, REDIRECT_URI), providerName = "Zerodha")
    private var toolCache: List<McpTool>? = null

    /** True when the last sign-in used MCP OAuth (vs. the `login` tool). */
    val usesOAuth get() = secrets.getRaw(MODE_KEY) == "oauth"

    val isConnected get() =
        if (usesOAuth) oauth.isConnected
        else secrets.has(Key.MCP_KITE_CONNECTED) && secrets.has(Key.MCP_KITE_SESSION)

    /** Step 1: returns the URL to open — Zerodha's OAuth sign-in page, or the `login` tool's Kite link. */
    suspend fun beginLogin(): String {
        secrets.put(Key.MCP_KITE_CONNECTED, null)
        // Prefer standard MCP OAuth when the server advertises it.
        val oauthErr = runCatching { return oauth.beginLogin().also { secrets.putRaw(MODE_KEY, "oauth") } }.exceptionOrNull()
        secrets.putRaw(MODE_KEY, "tool")
        val text = try {
            runCatching { client.callTool("login", JsonObject(emptyMap())) }.getOrElse {
                if (client.sessionLost) client.callTool("login", JsonObject(emptyMap())) else throw it
            }
        } catch (e: McpException) {
            throw BrokerException(if (e.unauthorized)
                "Zerodha sign-in couldn't start: ${oauthErr?.message ?: "unknown error"}"
            else "Couldn't reach Zerodha's Kite MCP server: ${e.message}")
        }
        return extractUrl(text) ?: throw BrokerException("Kite MCP didn't return a login link: ${text.take(200)}")
    }

    /** Step 2: [redirectUrl] is the OAuth callback (OAuth mode); in `login`-tool mode we verify the session. */
    suspend fun completeLogin(redirectUrl: String? = null) {
        if (usesOAuth) {
            oauth.completeLogin(redirectUrl ?: throw BrokerException("Sign-in didn't finish — please try again."))
        } else {
            val profile = client.callTool("get_profile", JsonObject(emptyMap()))
            if (looksUnauthenticated(profile)) throw BrokerException("Kite login isn't complete yet — finish logging in, then tap Done.")
            secrets.put(Key.MCP_KITE_CONNECTED, "1")
        }
        toolCache = null
        runCatching { rawTools() }.onSuccess { t -> com.capitol.app.diagnostics.Diagnostics.info("KiteMCP", "connected; tools offered: ${t.joinToString { it.name }}") }
    }

    fun disconnect() {
        secrets.put(Key.MCP_KITE_CONNECTED, null); client.reset(); oauth.disconnect(); toolCache = null
    }

    private suspend fun rawCall(name: String, args: JsonObject): String = if (usesOAuth) oauth.call(name, args) else client.callTool(name, args)
    private suspend fun rawTools(): List<McpTool> = if (usesOAuth) oauth.tools() else client.listTools()

    /** Stock holdings via `get_holdings` (Kite Connect `/portfolio/holdings` shape). */
    suspend fun holdings(): List<Holding> = parseEquity(call(resolveTool(ToolKind.HOLDINGS)))

    /** Coin mutual-fund holdings via `get_mf_holdings`. */
    suspend fun mfHoldings(): List<Holding> = MutualFunds.parse(call(resolveTool(ToolKind.MF_HOLDINGS)))

    internal enum class ToolKind { HOLDINGS, MF_HOLDINGS }

    /**
     * Finds the server's tool for [kind] from its own tool list. Zerodha's README documents
     * `get_holdings` / `get_mf_holdings`, but the sign-in-protected server can name tools differently,
     * so we match by name instead of hard-coding.
     */
    private suspend fun resolveTool(kind: ToolKind): String {
        val names = readTools().map { it.name }
        return pickTool(kind, names) ?: run {
            com.capitol.app.diagnostics.Diagnostics.info("KiteMCP", "tools offered: ${names.joinToString()}")
            throw BrokerException("Zerodha's MCP server has no ${if (kind == ToolKind.MF_HOLDINGS) "mutual-fund holdings" else "holdings"} tool. " +
                "Tools it offers: ${names.joinToString().ifBlank { "none" }}")
        }
    }

    /** Read-only tools for the AI agent. */
    suspend fun readTools(): List<McpTool> = toolCache ?: call { rawTools() }.filter { isReadOnly(it.name) }.also { toolCache = it }

    suspend fun callRead(name: String, args: JsonObject): String {
        require(isReadOnly(name)) { "Capitol only allows read-only Kite tools" }
        return call { rawCall(name, args) }
    }

    private suspend fun call(tool: String): String = call { rawCall(tool, JsonObject(emptyMap())) }

    private suspend fun <T> call(block: suspend () -> T): T {
        if (!isConnected) throw BrokerException("Connect Zerodha via Kite MCP first")
        return try {
            block().also { if (it is String && looksUnauthenticated(it)) { expire(); throw BrokerException(EXPIRED) } }
        } catch (e: McpException) {
            if (client.sessionLost || e.unauthorized) { expire(); throw BrokerException(EXPIRED) }
            throw e
        }
    }

    private fun expire() {
        if (usesOAuth) oauth.disconnect() else secrets.put(Key.MCP_KITE_CONNECTED, null)
    }

    companion object {
        const val SERVER_URL = "https://mcp.kite.trade/mcp"
        const val PREFIX = "kite__"
        const val REDIRECT_URI = "capitol://kite-mcp-callback"
        /** RFC 8252 loopback redirect; intercepted by the in-app sign-in WebView, never actually loaded. */
        const val LOOPBACK_REDIRECT_URI = "http://127.0.0.1:43217/kite-mcp-callback"
        private const val MODE_KEY = "MCP_KITE_MODE"
        fun isRedirect(url: String) = url.startsWith(REDIRECT_URI) || url.startsWith(LOOPBACK_REDIRECT_URI)
        private const val EXPIRED = "Your Zerodha session has expired (Kite logins last a day). Tap Connect to log in again."

        /** Only read tools — never order placement/modification, even if a server exposes them. */
        private val WRITE_WORDS = listOf("place", "modify", "cancel", "delete", "create", "update", "exit", "convert",
            "buy", "sell", "transfer", "withdraw", "pledge", "redeem", "sip_", "invest", "submit", "execute", "trade_")
        private val READ_WORDS = listOf("get", "list", "search", "fetch", "view", "read", "show", "holding", "position",
            "profile", "margin", "quote", "ltp", "ohlc", "historical", "instrument", "portfolio", "fund", "order", "trades")

        /**
         * Read-only tools only. A name with any write verb is refused even if it also contains a read word
         * (e.g. "place_order", "kite_cancel_order"); others must look like a read.
         */
        fun isReadOnly(name: String): Boolean {
            val n = name.lowercase()
            if (n == "login" || n.endsWith("_login")) return false
            if (WRITE_WORDS.any { it in n }) return false
            return READ_WORDS.any { it in n }
        }

        /** Picks the tool for [kind] among [names] (exact README name first, then by keywords). */
        internal fun pickTool(kind: ToolKind, names: List<String>): String? {
            fun mfToken(n: String) = Regex("(^|[_\\-.])mf([_\\-.]|$)").containsMatchIn(n)
            fun isMf(n: String) = mfToken(n) || "mutual" in n || "fund" in n
            val lower = names.associateBy { it.lowercase() }
            return when (kind) {
                ToolKind.HOLDINGS -> lower["get_holdings"]
                    ?: lower.entries.firstOrNull { (n, _) -> "holding" in n && !isMf(n) }?.value
                ToolKind.MF_HOLDINGS -> lower["get_mf_holdings"]
                    ?: lower.entries.firstOrNull { (n, _) -> "holding" in n && isMf(n) }?.value
                    ?: lower.entries.firstOrNull { (n, _) -> (mfToken(n) || "mutual" in n) && "order" !in n && "sip" !in n && "instrument" !in n }?.value
            }
        }

        internal fun parseEquity(text: String): List<Holding> {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            val el = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()
                ?: MutualFunds.extractJson(text)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return emptyList()
            val arr = (el as? kotlinx.serialization.json.JsonArray) ?: ((el as? JsonObject)?.get("data") as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
            fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            fun JsonObject.n(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val sym = o.s("tradingsymbol") ?: return@mapNotNull null
                val qty = (o.n("quantity") ?: 0.0) + (o.n("t1_quantity") ?: 0.0)
                if (qty <= 0) return@mapNotNull null
                Holding(com.capitol.app.data.broker.Broker.KITE, sym, o.s("exchange") ?: "NSE", qty, o.n("average_price") ?: 0.0,
                    o.n("last_price") ?: 0.0, o.n("day_change_percentage"))
            }
        }

        internal fun extractUrl(text: String): String? =
            Regex("""https://[^\s"')\]<>]+""").findAll(text).map { it.value.trimEnd('.', ',') }
                .sortedByDescending { it.contains("kite.zerodha.com") || it.contains("login") }.firstOrNull()

        internal fun looksUnauthenticated(text: String): Boolean {
            val t = text.lowercase()
            return listOf("please log in", "please login", "not logged in", "login first", "use the login tool", "session expired", "invalid session", "unauthorized")
                .any { it in t }
        }
    }
}
