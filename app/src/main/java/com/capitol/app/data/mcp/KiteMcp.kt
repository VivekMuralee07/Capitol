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
 * Tools are found in the server's own list: either per-purpose tools (`get_holdings`) or
 * mode-based ones (`portfolio`/`mutual_funds`/`orders` with `mode: "holdings"` …).
 * Capitol is read-only: write tools are dropped, and write modes (place/modify/cancel …) are
 * removed from schemas and refused at call time.
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

    /** Stock holdings (e.g. `portfolio` with `mode: "holdings"`, or `get_holdings`). */
    suspend fun holdings(): List<Holding> = parseEquity(callResolved(ToolKind.HOLDINGS))

    /** Coin mutual-fund holdings (e.g. `mutual_funds` with `mode: "holdings"`, or `get_mf_holdings`). */
    suspend fun mfHoldings(): List<Holding> = MutualFunds.parse(callResolved(ToolKind.MF_HOLDINGS))

    /** Calls the resolved tool; if the server rejects a mode ("Must be one of: …"), retries once with an allowed read mode. */
    private suspend fun callResolved(kind: ToolKind): String {
        val (name, args) = resolveTool(kind)
        return try {
            call { rawCall(name, args) }
        } catch (e: McpException) {
            val retry = retryArgs(e.message.orEmpty(), args) ?: throw e
            com.capitol.app.diagnostics.Diagnostics.info("KiteMCP", "$name rejected $args; retrying with $retry")
            call { rawCall(name, retry) }
        }
    }

    internal enum class ToolKind { HOLDINGS, MF_HOLDINGS }

    /**
     * Finds the server's tool for [kind] from its own tool list. Zerodha's README documents
     * `get_holdings` / `get_mf_holdings`, but the sign-in-protected server can name tools differently,
     * so we match by name instead of hard-coding.
     */
    private suspend fun resolveTool(kind: ToolKind): Pair<String, JsonObject> {
        val tools = readTools()
        val names = tools.map { it.name }
        val name = pickTool(kind, names) ?: run {
            com.capitol.app.diagnostics.Diagnostics.info("KiteMCP", "tools offered: ${names.joinToString()}")
            throw BrokerException("Zerodha's MCP server has no ${if (kind == ToolKind.MF_HOLDINGS) "mutual-fund holdings" else "holdings"} tool. " +
                "Tools it offers: ${names.joinToString().ifBlank { "none" }}")
        }
        return name to holdingsArgs(tools.first { it.name == name })
    }

    /**
     * Read-only tools for the AI agent. Tools with a mode/action enum (e.g. `orders`) keep only their
     * read modes in the schema; a tool left with no read mode is dropped.
     */
    suspend fun readTools(): List<McpTool> = toolCache ?: call { rawTools() }
        .filter { isReadOnly(it.name) }.mapNotNull { restrictToReadModes(it) }.also { toolCache = it }

    suspend fun callRead(name: String, args: JsonObject): String {
        require(isReadOnly(name)) { "Capitol only allows read-only Kite tools" }
        require(argsAreReadOnly(args)) { "Capitol only allows read-only Kite tools — that mode could change your account" }
        return call { rawCall(name, args) }
    }

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

        /** Argument names that select what a multi-purpose tool does. */
        private val MODE_KEYS = setOf("mode", "action", "operation", "op", "type", "method", "command", "kind", "view")

        /** A mode/action value that only reads (no write verb as a word part). */
        internal fun isReadValue(v: String): Boolean {
            val n = v.lowercase()
            return WRITE_WORDS.none { it.trimEnd('_') in n.split(Regex("[^a-z0-9]+")) || it in n } && n != "login"
        }

        /** Rejects calls whose mode-like string arguments name a write action (e.g. orders with mode "place"). */
        internal fun argsAreReadOnly(args: JsonObject): Boolean = args.all { (k, v) ->
            val p = v as? kotlinx.serialization.json.JsonPrimitive
            k.lowercase() !in MODE_KEYS || p == null || !p.isString || isReadValue(p.content)
        }

        private fun JsonObject.props(): JsonObject? = this["properties"] as? JsonObject
        private fun JsonObject.enumValues(): List<String>? = (this["enum"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

        /** Keeps only read-only enum values of mode-like properties; null if one has no read value left. */
        internal fun restrictToReadModes(tool: McpTool): McpTool? {
            val props = tool.inputSchema.props() ?: return tool
            var changed = false
            val newProps = props.mapValues { (k, v) ->
                val o = v as? JsonObject ?: return@mapValues v
                val values = o.enumValues() ?: return@mapValues v
                if (k.lowercase() !in MODE_KEYS) return@mapValues v
                val keep = values.filter { isReadValue(it) }
                if (keep.size == values.size) return@mapValues v
                if (keep.isEmpty()) return null
                changed = true
                JsonObject(o + ("enum" to kotlinx.serialization.json.JsonArray(keep.map { kotlinx.serialization.json.JsonPrimitive(it) })))
            }
            if (!changed) return tool
            return tool.copy(inputSchema = JsonObject(tool.inputSchema + ("properties" to JsonObject(newProps))))
        }

        /** Arguments asking a tool for holdings: a mode-like enum gets "holdings"; a required mode with no enum gets "holdings" too. */
        internal fun holdingsArgs(tool: McpTool): JsonObject {
            val props = tool.inputSchema.props() ?: return JsonObject(emptyMap())
            val required = (tool.inputSchema["required"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            val args = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            for ((k, v) in props) {
                val o = v as? JsonObject ?: continue
                val values = o.enumValues()
                val pick = when {
                    values != null && (k.lowercase() in MODE_KEYS || k in required) ->
                        values.firstOrNull { it.equals("holdings", true) } ?: values.firstOrNull { "holding" in it.lowercase() }
                            ?: values.firstOrNull { isReadValue(it) }.takeIf { k in required }
                    k.lowercase() in MODE_KEYS && (k in required || "holding" in o.toString().lowercase()) -> "holdings"
                    else -> null
                } ?: continue
                args[k] = kotlinx.serialization.json.JsonPrimitive(pick)
            }
            return JsonObject(args)
        }

        /** From "Invalid mode. Must be one of: holdings[, …]" builds read-only arguments to retry with. */
        internal fun retryArgs(error: String, args: JsonObject): JsonObject? {
            val m = Regex("""(?i)invalid\s+([a-z_]+)\W+must be one of:?\s*([^\n]+)""").find(error) ?: return null
            val key = m.groupValues[1].lowercase()
            val options = m.groupValues[2].split(Regex("[,|/]| or ")).map { it.trim().trim('"', '\'', '`', '.', '[', ']') }.filter { it.isNotBlank() }
            val pick = options.firstOrNull { it.equals("holdings", true) } ?: options.firstOrNull { "holding" in it.lowercase() }
                ?: options.firstOrNull { isReadValue(it) } ?: return null
            val retry = JsonObject(args + (key to kotlinx.serialization.json.JsonPrimitive(pick)))
            return retry.takeIf { it != args }
        }

        /** Picks the tool for [kind] among [names] (exact README name first, then by keywords). */
        internal fun pickTool(kind: ToolKind, names: List<String>): String? {
            fun mfToken(n: String) = Regex("(^|[_\\-.])mf([_\\-.]|$)").containsMatchIn(n)
            fun isMf(n: String) = mfToken(n) || "mutual" in n || "fund" in n
            val lower = names.associateBy { it.lowercase() }
            return when (kind) {
                ToolKind.HOLDINGS -> lower["get_holdings"]
                    ?: lower.entries.firstOrNull { (n, _) -> "holding" in n && !isMf(n) }?.value
                    // Mode-based servers: a single `portfolio` tool (mode "holdings" / "positions" / …).
                    ?: lower.entries.firstOrNull { (n, _) -> "portfolio" in n && !isMf(n) }?.value
                ToolKind.MF_HOLDINGS -> lower["get_mf_holdings"]
                    ?: lower.entries.firstOrNull { (n, _) -> "holding" in n && isMf(n) }?.value
                    ?: lower.entries.firstOrNull { (n, _) -> (mfToken(n) || "mutual" in n) && "order" !in n && "sip" !in n && "instrument" !in n }?.value
            }
        }

        internal fun parseEquity(text: String): List<Holding> {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            val el = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()
                ?: MutualFunds.extractJson(text)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return emptyList()
            val arr = MutualFunds.findRecords(el, "tradingsymbol") ?: return emptyList()
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
