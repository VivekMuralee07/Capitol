package com.capitol.app.data.broker

import com.capitol.app.data.prefs.SecretStore
import com.capitol.app.data.prefs.SecretStore.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

/**
 * Read-only Zerodha Kite Connect v3 client (https://kite.trade/docs/connect/v3/).
 * Capitol only reads holdings/positions/margins for analysis — it never places orders.
 *
 * Login flow:
 * 1. Open [loginUrl] (in the in-app WebView).
 * 2. Kite redirects to your app's redirect URL with `request_token=...`.
 * 3. [createSession] exchanges it for an access token (valid until ~6 AM next day).
 */
class KiteConnectClient(private val http: OkHttpClient, private val secrets: SecretStore, private val base: String = BASE) {

    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured get() = secrets.has(Key.KITE_API_KEY) && secrets.has(Key.KITE_API_SECRET)
    val isConnected get() = secrets.has(Key.KITE_ACCESS_TOKEN)
    val userName get() = secrets.get(Key.KITE_USER)

    fun loginUrl(): String {
        val key = secrets.get(Key.KITE_API_KEY) ?: throw BrokerException("Add your Kite API key first")
        return "https://kite.zerodha.com/connect/login?v=3&api_key=$key"
    }

    suspend fun createSession(requestToken: String) = withContext(Dispatchers.IO) {
        val key = secrets.get(Key.KITE_API_KEY) ?: throw BrokerException("Missing Kite API key")
        val secret = secrets.get(Key.KITE_API_SECRET) ?: throw BrokerException("Missing Kite API secret")
        val checksum = sha256(key + requestToken + secret)
        val req = Request.Builder().url("$base/session/token")
            .header("X-Kite-Version", "3")
            .post(FormBody.Builder().add("api_key", key).add("request_token", requestToken).add("checksum", checksum).build())
            .build()
        val data = call(req)
        val token = (data as? JsonObject)?.str("access_token") ?: throw BrokerException("Kite did not return an access token")
        secrets.put(Key.KITE_ACCESS_TOKEN, token)
        secrets.put(Key.KITE_USER, (data as JsonObject).str("user_name") ?: data.str("user_id"))
    }

    fun logout() {
        secrets.put(Key.KITE_ACCESS_TOKEN, null)
        secrets.put(Key.KITE_USER, null)
    }

    suspend fun holdings(): List<Holding> = withContext(Dispatchers.IO) {
        val arr = call(authed("/portfolio/holdings")) as? JsonArray ?: return@withContext emptyList()
        arr.map { it.jsonObject }.map { o ->
            Holding(
                broker = Broker.KITE,
                tradingSymbol = o.str("tradingsymbol") ?: "",
                exchange = o.str("exchange") ?: "NSE",
                quantity = (o.num("quantity") ?: 0.0) + (o.num("t1_quantity") ?: 0.0),
                averagePrice = o.num("average_price") ?: 0.0,
                lastPrice = o.num("last_price") ?: 0.0,
                dayChangePercent = o.num("day_change_percentage"),
            )
        }
    }

    suspend fun positions(): List<Holding> = withContext(Dispatchers.IO) {
        val net = (call(authed("/portfolio/positions")) as? JsonObject)?.get("net") as? JsonArray ?: return@withContext emptyList()
        net.map { it.jsonObject }.filter { (it.num("quantity") ?: 0.0) != 0.0 }.map { o ->
            Holding(Broker.KITE, o.str("tradingsymbol") ?: "", o.str("exchange") ?: "NSE",
                o.num("quantity") ?: 0.0, o.num("average_price") ?: 0.0, o.num("last_price") ?: 0.0, null)
        }
    }

    /** Zerodha Coin mutual-fund holdings (`GET /mf/holdings`). */
    suspend fun mfHoldings(): List<Holding> = withContext(Dispatchers.IO) {
        (call(authed("/mf/holdings")) as? JsonArray)?.let { MutualFunds.parse(it) } ?: emptyList()
    }

    /** Available equity cash, if the margins endpoint returns it. */
    suspend fun availableCash(): Double? = withContext(Dispatchers.IO) {
        val eq = (call(authed("/user/margins")) as? JsonObject)?.get("equity") as? JsonObject
        (eq?.get("available") as? JsonObject)?.num("live_balance") ?: eq?.num("net")
    }

    private fun authed(path: String): Request {
        val key = secrets.get(Key.KITE_API_KEY) ?: throw BrokerException("Missing Kite API key")
        val token = secrets.get(Key.KITE_ACCESS_TOKEN) ?: throw BrokerException("Not logged in to Kite")
        return Request.Builder().url("$base$path")
            .header("X-Kite-Version", "3")
            .header("Authorization", "token $key:$token")
            .build()
    }

    private fun call(req: Request) = http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw BrokerException("Kite HTTP ${resp.code}")
        if (root.str("status") != "success") {
            val type = root.str("error_type")
            if (type == "TokenException") logout()
            throw BrokerException("Kite: ${root.str("message") ?: "HTTP ${resp.code}"}")
        }
        root["data"]
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val BASE = "https://api.kite.trade"
        /** Extract `request_token` from a redirect URL, if present. */
        fun requestTokenFrom(url: String): String? =
            Regex("[?&]request_token=([^&#]+)").find(url)?.groupValues?.get(1)
    }
}

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
