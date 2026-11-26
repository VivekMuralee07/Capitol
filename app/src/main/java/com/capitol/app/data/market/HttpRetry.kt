package com.capitol.app.data.market

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Executes [req], retrying transient failures (network errors, HTTP 429 and 5xx) with
 * exponential back-off, honouring a short `Retry-After`. Call from a background thread.
 */
fun OkHttpClient.executeWithRetry(req: Request, attempts: Int = 3, baseDelayMs: Long = 600): Response {
    var last: IOException? = null
    for (i in 0 until attempts) {
        try {
            val resp = newCall(req).execute()
            val retryable = resp.code == 429 || resp.code in 500..599
            if (!retryable || i == attempts - 1) return resp
            val retryAfter = resp.header("Retry-After")?.toLongOrNull()?.times(1000)?.coerceAtMost(5_000)
            resp.close()
            Thread.sleep(retryAfter ?: (baseDelayMs shl i))
        } catch (e: IOException) {
            last = e
            if (i == attempts - 1) throw e
            Thread.sleep(baseDelayMs shl i)
        }
    }
    throw last ?: IOException("Request failed")
}
