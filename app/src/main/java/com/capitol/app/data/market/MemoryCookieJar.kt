package com.capitol.app.data.market

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** In-memory cookie jar (Yahoo's crumb handshake needs its consent cookie). */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.removeAll { c -> cookies.any { it.name == c.name && it.domain == c.domain } }
        this.cookies += cookies
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}
