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
 * or API key is needed: the server's `login` tool returns a Kite login link; once the user logs
 * in, that MCP session (persisted here) is authorised. Capitol only uses read tools — the hosted
 * server excludes trading operations, and Capitol additionally exposes only `get_*`/`search_*`.
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
    private var toolCache: List<McpTool>? = null

    /** The user completed Kite login on this MCP session (cleared if the server drops the session). */
    val isConnected get() = secrets.has(Key.MCP_KITE_CONNECTED) && secrets.has(Key.MCP_KITE_SESSION)

    /** Step 1: start (or reuse) an MCP session and get the Kite login URL from the `login` tool. */
    suspend fun beginLogin(): String {
        secrets.put(Key.MCP_KITE_CONNECTED, null)
        val text = runCatching { client.callTool("login", JsonObject(emptyMap())) }.getOrElse {
            if (client.sessionLost) client.callTool("login", JsonObject(emptyMap())) else throw it
        }
        return extractUrl(text) ?: throw BrokerException("Kite MCP didn't return a login link: ${text.take(200)}")
    }

    /** Step 2: after the user finishes logging in, confirm the session works. */
    suspend fun completeLogin() {
        val profile = client.callTool("get_profile", JsonObject(emptyMap()))
        if (looksUnauthenticated(profile)) throw BrokerException("Kite login isn't complete yet — finish logging in, then tap Done.")
        secrets.put(Key.MCP_KITE_CONNECTED, "1")
        toolCache = null
    }

    fun disconnect() {
        secrets.put(Key.MCP_KITE_CONNECTED, null); client.reset(); toolCache = null
    }

    /** Stock holdings via `get_holdings` (Kite Connect `/portfolio/holdings` shape). */
    suspend fun holdings(): List<Holding> = parseEquity(call("get_holdings"))

    /** Coin mutual-fund holdings via `get_mf_holdings`. */
    suspend fun mfHoldings(): List<Holding> = MutualFunds.parse(call("get_mf_holdings"))

    /** Read-only tools for the AI agent. */
    suspend fun readTools(): List<McpTool> = toolCache ?: call { client.listTools() }.filter { isReadOnly(it.name) }.also { toolCache = it }

    suspend fun callRead(name: String, args: JsonObject): String {
        require(isReadOnly(name)) { "Capitol only allows read-only Kite tools" }
        return call { client.callTool(name, args) }
    }

    private suspend fun call(tool: String): String = call { client.callTool(tool, JsonObject(emptyMap())) }

    private suspend fun <T> call(block: suspend () -> T): T {
        if (!isConnected) throw BrokerException("Connect Zerodha via Kite MCP first")
        return try {
            block().also { if (it is String && looksUnauthenticated(it)) { secrets.put(Key.MCP_KITE_CONNECTED, null); throw BrokerException(EXPIRED) } }
        } catch (e: McpException) {
            if (client.sessionLost || e.unauthorized) { secrets.put(Key.MCP_KITE_CONNECTED, null); throw BrokerException(EXPIRED) }
            throw e
        }
    }

    companion object {
        const val SERVER_URL = "https://mcp.kite.trade/mcp"
        const val PREFIX = "kite__"
        private const val EXPIRED = "Your Kite MCP session has expired (Kite logins last a day). Tap Connect to log in again."

        /** Only read tools — never order placement/modification, even if a server exposes them. */
        fun isReadOnly(name: String) = name.startsWith("get_") || name.startsWith("search_")

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
