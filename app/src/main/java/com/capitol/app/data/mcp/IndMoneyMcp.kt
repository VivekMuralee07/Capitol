package com.capitol.app.data.mcp

import com.capitol.app.data.prefs.SecretStore
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Connection to INDmoney's official MCP server (https://mcp.indmoney.com/mcp) via standard MCP OAuth.
 * Uses the same stored keys as earlier versions, so existing sign-ins keep working.
 */
class IndMoneyMcp(http: OkHttpClient, secrets: SecretStore, serverUrl: String = SERVER_URL) {
    private val conn = OAuthMcp(http, secrets, serverUrl, keyPrefix = "MCP_INDMONEY",
        redirectUris = listOf(REDIRECT_URI, LOOPBACK_REDIRECT_URI), providerName = "INDmoney")

    val isConnected get() = conn.isConnected
    suspend fun beginLogin(): String = conn.beginLogin()
    suspend fun completeLogin(redirectUrl: String) = conn.completeLogin(redirectUrl)
    fun disconnect() = conn.disconnect()
    suspend fun tools(): List<McpTool> = conn.tools()
    suspend fun call(name: String, args: JsonObject): String = conn.call(name, args)

    companion object {
        const val SERVER_URL = "https://mcp.indmoney.com/mcp"
        const val REDIRECT_URI = "capitol://indmoney-callback"
        const val LOOPBACK_REDIRECT_URI = "http://127.0.0.1:43217/indmoney-callback"
        fun isRedirect(url: String) = url.startsWith(REDIRECT_URI) || url.startsWith(LOOPBACK_REDIRECT_URI)
    }
}
