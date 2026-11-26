package com.capitol.app.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Endpoints discovered for an MCP server's authorization server. */
@Serializable
data class McpAuthConfig(
    val resource: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String? = null,
    val scopes: List<String> = emptyList(),
)

@Serializable
data class McpTokens(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long?)

class McpAuthException(message: String) : Exception(message)

/**
 * OAuth 2.1 client for remote MCP servers, following the MCP authorization spec:
 * protected-resource metadata (RFC 9728) → authorization-server metadata (RFC 8414)
 * → dynamic client registration (RFC 7591) → authorization code + PKCE (RFC 7636)
 * with the `resource` indicator (RFC 8707).
 */
class McpOAuth(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(serverUrl: String): McpAuthConfig = withContext(Dispatchers.IO) {
        val server = serverUrl.toHttpUrl()
        val origin = server.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')

        // 1. Protected resource metadata: from the 401 challenge, else the well-known locations.
        val prmCandidates = buildList {
            challengeResourceMetadata(server)?.let { add(it) }
            val path = server.encodedPath.trimEnd('/')
            if (path.isNotEmpty()) add("$origin/.well-known/oauth-protected-resource$path")
            add("$origin/.well-known/oauth-protected-resource")
        }
        val prm = prmCandidates.firstNotNullOfOrNull { getJson(it) }
        val issuer = (prm?.get("authorization_servers") as? JsonArray)?.firstOrNull()?.str() ?: origin
        val resource = prm?.get("resource")?.str() ?: serverUrl
        val prmScopes = (prm?.get("scopes_supported") as? JsonArray)?.mapNotNull { it.str() }.orEmpty()

        // 2. Authorization server metadata (OAuth, then OpenID Connect as a fallback).
        val iss = issuer.toHttpUrl()
        val issOrigin = iss.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val issPath = iss.encodedPath.trimEnd('/')
        val asCandidates = listOf(
            "$issOrigin/.well-known/oauth-authorization-server$issPath",
            "$issOrigin/.well-known/openid-configuration$issPath",
            "${issuer.trimEnd('/')}/.well-known/openid-configuration",
        ).distinct()
        val asMeta = asCandidates.firstNotNullOfOrNull { getJson(it) }
            ?: throw McpAuthException("INDmoney's MCP server didn't publish OAuth metadata ($issuer).")
        McpAuthConfig(
            resource = resource,
            authorizationEndpoint = asMeta["authorization_endpoint"]?.str() ?: throw McpAuthException("No authorization endpoint"),
            tokenEndpoint = asMeta["token_endpoint"]?.str() ?: throw McpAuthException("No token endpoint"),
            registrationEndpoint = asMeta["registration_endpoint"]?.str(),
            scopes = prmScopes.ifEmpty { (asMeta["scopes_supported"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty() },
        )
    }

    /** Registers Capitol as a public client; returns (client_id, client_secret?). */
    suspend fun register(cfg: McpAuthConfig, redirectUri: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val endpoint = cfg.registrationEndpoint
            ?: throw McpAuthException("INDmoney's MCP server doesn't allow app registration, so Capitol can't sign in to it.")
        val body = buildJsonObject {
            put("client_name", "Capitol")
            putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
            if (cfg.scopes.isNotEmpty()) put("scope", cfg.scopes.joinToString(" "))
        }
        val req = Request.Builder().url(endpoint)
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw McpAuthException("App registration was refused by INDmoney (HTTP ${resp.code}): ${text.take(200)}")
            val o = json.parseToJsonElement(text).jsonObject
            (o["client_id"]?.str() ?: throw McpAuthException("Registration returned no client_id")) to o["client_secret"]?.str()
        }
    }

    fun authorizationUrl(cfg: McpAuthConfig, clientId: String, redirectUri: String, challenge: String, state: String): String =
        cfg.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("resource", cfg.resource)
            .apply { if (cfg.scopes.isNotEmpty()) addQueryParameter("scope", cfg.scopes.joinToString(" ")) }
            .build().toString()

    suspend fun exchangeCode(cfg: McpAuthConfig, clientId: String, clientSecret: String?, code: String, verifier: String, redirectUri: String) =
        tokenRequest(cfg, clientId, clientSecret) {
            add("grant_type", "authorization_code"); add("code", code)
            add("code_verifier", verifier); add("redirect_uri", redirectUri)
        }

    suspend fun refresh(cfg: McpAuthConfig, clientId: String, clientSecret: String?, refreshToken: String) =
        tokenRequest(cfg, clientId, clientSecret) { add("grant_type", "refresh_token"); add("refresh_token", refreshToken) }

    private suspend fun tokenRequest(cfg: McpAuthConfig, clientId: String, clientSecret: String?, fill: FormBody.Builder.() -> Unit): McpTokens =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder().apply {
                fill(); add("client_id", clientId); add("resource", cfg.resource)
                clientSecret?.let { add("client_secret", it) }
            }.build()
            http.newCall(Request.Builder().url(cfg.tokenEndpoint).post(form).header("Accept", "application/json").build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw McpAuthException("INDmoney sign-in failed (HTTP ${resp.code}): ${text.take(200)}")
                val o = json.parseToJsonElement(text).jsonObject
                McpTokens(
                    accessToken = o["access_token"]?.str() ?: throw McpAuthException("No access token returned"),
                    refreshToken = o["refresh_token"]?.str(),
                    expiresAtMillis = (o["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 },
                )
            }
        }

    private fun challengeResourceMetadata(server: HttpUrl): String? = runCatching {
        val req = Request.Builder().url(server).post("{}".toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code != 401) return null
            Regex("resource_metadata=\"([^\"]+)\"").find(resp.header("WWW-Authenticate").orEmpty())?.groupValues?.get(1)
        }
    }.getOrNull()

    private fun getJson(url: String): JsonObject? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { resp ->
            if (!resp.isSuccessful) null else json.parseToJsonElement(resp.body!!.string()) as? JsonObject
        }
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private val rnd = SecureRandom()
        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun newVerifier(): String = b64(ByteArray(32).also { rnd.nextBytes(it) })
        fun challengeFor(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        fun newState(): String = b64(ByteArray(16).also { rnd.nextBytes(it) })
    }
}
