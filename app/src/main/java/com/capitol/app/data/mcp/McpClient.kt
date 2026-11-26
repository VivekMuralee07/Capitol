package com.capitol.app.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

class McpException(message: String, val unauthorized: Boolean = false) : Exception(message)

/**
 * Minimal MCP client over the Streamable HTTP transport (JSON-RPC 2.0; responses may be
 * plain JSON or an SSE stream). Supports initialize, tools/list and tools/call.
 */
class McpClient(
    private val http: OkHttpClient,
    private val url: String,
    private val token: suspend () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(1)
    private var sessionId: String? = null
    private var initialized = false
    private var protocolVersion = PROTOCOL_VERSION

    private suspend fun ensureInitialized() {
        if (initialized) return
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "Capitol"); put("version", "1.0") })
        }, skipInit = true)
        (result["protocolVersion"] as? JsonPrimitive)?.content?.let { protocolVersion = it }
        notify("notifications/initialized")
        initialized = true
    }

    suspend fun listTools(): List<McpTool> {
        ensureInitialized()
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val r = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (r["tools"] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val name = (o["name"] as? JsonPrimitive)?.content ?: return@forEach
                out += McpTool(name, (o["description"] as? JsonPrimitive)?.content.orEmpty(),
                    o["inputSchema"] as? JsonObject ?: JsonObject(mapOf("type" to JsonPrimitive("object"))))
            }
            cursor = (r["nextCursor"] as? JsonPrimitive)?.content
        } while (cursor != null)
        return out
    }

    /** Calls a tool and returns its text content. Throws [McpException] if the tool reports an error. */
    suspend fun callTool(name: String, arguments: JsonObject): String {
        ensureInitialized()
        val r = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        val text = (r["content"] as? JsonArray)?.joinToString("\n") { c ->
            val o = c as? JsonObject
            when ((o?.get("type") as? JsonPrimitive)?.content) {
                "text" -> (o?.get("text") as? JsonPrimitive)?.content.orEmpty()
                else -> o.toString()
            }
        }?.ifBlank { null } ?: r["structuredContent"]?.toString() ?: r.toString()
        if ((r["isError"] as? JsonPrimitive)?.booleanOrNull == true) throw McpException(text)
        return text
    }

    fun reset() { initialized = false; sessionId = null }

    private suspend fun notify(method: String) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }, expectResponse = false)
    }

    private suspend fun rpc(method: String, params: JsonObject, skipInit: Boolean = false): JsonObject {
        if (!skipInit) ensureInitialized()
        val id = ids.getAndIncrement()
        val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        val resp = post(msg, expectResponse = true) ?: throw McpException("No response to $method")
        (resp["error"] as? JsonObject)?.let { e ->
            throw McpException("MCP error ${(e["code"] as? JsonPrimitive)?.content}: ${(e["message"] as? JsonPrimitive)?.content}")
        }
        return resp["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun post(msg: JsonObject, expectResponse: Boolean): JsonObject? {
        val bearer = token()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocolVersion)
                .apply { bearer?.let { header("Authorization", "Bearer $it") }; sessionId?.let { header("Mcp-Session-Id", it) } }
                .post(json.encodeToString(msg).toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw McpException("INDmoney session expired — please reconnect.", unauthorized = true)
                if (resp.code == 404 && sessionId != null) { reset(); throw McpException("MCP session expired, please retry") }
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                if (!expectResponse) return@withContext null
                if (!resp.isSuccessful) throw McpException("INDmoney MCP HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val type = resp.header("Content-Type").orEmpty()
                val id = msg["id"]
                if (type.contains("text/event-stream")) parseSse(body, id) else json.parseToJsonElement(body).jsonObject
            }
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        /** Extracts the JSON-RPC response with [id] from an SSE body. */
        fun parseSse(body: String, id: JsonElement?): JsonObject? {
            val json = Json { ignoreUnknownKeys = true }
            val events = mutableListOf<String>()
            val cur = StringBuilder()
            body.lineSequence().forEach { line ->
                when {
                    line.startsWith("data:") -> { if (cur.isNotEmpty()) cur.append('\n'); cur.append(line.removePrefix("data:").trimStart()) }
                    line.isBlank() -> { if (cur.isNotEmpty()) { events += cur.toString(); cur.clear() } }
                }
            }
            if (cur.isNotEmpty()) events += cur.toString()
            return events.mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                .firstOrNull { it["id"] == id && (it.containsKey("result") || it.containsKey("error")) }
        }
    }
}
