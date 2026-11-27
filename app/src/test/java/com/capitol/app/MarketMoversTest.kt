package com.capitol.app

import com.capitol.app.data.market.MoversMarket
import com.capitol.app.data.market.YahooFinanceDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-market top movers via Yahoo's screener (the request yfinance's `yf.screen` sends). */
class MarketMoversTest {
    private fun screenerBody(vararg q: Pair<String, Double>) = """{"finance":{"result":[{"count":${q.size},"quotes":[${
        q.joinToString { (s, pct) -> """{"symbol":"$s","shortName":"$s Inc","regularMarketPrice":100.0,"regularMarketChange":${pct},"regularMarketChangePercent":$pct,"currency":"USD","marketState":"REGULAR"}""" }
    }]}],"error":null}}"""

    @Test fun queryMatchesYahooDayGainersFilters() {
        val us = YahooFinanceDataSource.screenerQuery(MoversMarket.US, gainers = true, count = 25).toString()
        assertTrue(us, us.contains(""""sortField":"percentchange"""") && us.contains(""""sortType":"DESC""""))
        assertTrue(us, us.contains("""{"operator":"EQ","operands":["region","us"]}"""))
        assertTrue(us, us.contains("""{"operator":"GT","operands":["percentchange",0]}"""))
        assertTrue(us, us.contains("""{"operator":"GTE","operands":["intradaymarketcap",2000000000]}"""))
        val india = YahooFinanceDataSource.screenerQuery(MoversMarket.INDIA, gainers = false, count = 25).toString()
        assertTrue(india, india.contains(""""sortType":"ASC"""") && india.contains("""["region","in"]""") && india.contains("""["exchange","NSI"]"""))
        assertTrue(india, india.contains("""{"operator":"LT","operands":["percentchange",0]}"""))
    }

    @Test fun fetchesGainersAndLosersWithCrumb() = runBlocking {
        MockWebServer().use { server ->
            val posts = mutableListOf<String>()
            var crumbs = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/cookie") -> MockResponse().setHeader("Set-Cookie", "A3=x; Path=/")
                        path.startsWith("/v1/test/getcrumb") -> { crumbs++; MockResponse().setBody("crumb$crumbs") }
                        path.startsWith("/v1/finance/screener") && request.method == "POST" -> {
                            // First call: stale crumb → 401, then a fresh crumb works.
                            if ("crumb=crumb1" in path) return MockResponse().setResponseCode(401)
                            val body = request.body.readUtf8()
                            posts += body
                            val sort = Json.parseToJsonElement(body).jsonObject["sortType"]!!.jsonPrimitive.content
                            MockResponse().setHeader("Content-Type", "application/json").setBody(
                                if (sort == "DESC") screenerBody("MRVL" to 9.4, "SMCI" to 6.1) else screenerBody("INTC" to -5.2))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val yahoo = YahooFinanceDataSource(OkHttpClient(), server.url("/").toString().trimEnd('/'), server.url("/cookie").toString())
            val (g, l) = yahoo.dayMovers(MoversMarket.US)
            assertEquals(listOf("MRVL", "SMCI"), g.map { it.symbol })
            assertEquals(9.4, g.first().changePercent, 0.0)
            assertEquals("MRVL Inc", g.first().name)
            assertEquals("INTC", l.single().symbol)
            assertEquals(2, posts.size)
        }
    }

    @Test fun parsesEmptyOrOddResponses() {
        val y = YahooFinanceDataSource(OkHttpClient())
        assertTrue(y.parseScreener("""{"finance":{"result":null,"error":{"code":"Bad Request"}}}""").isEmpty())
        assertTrue(y.parseScreener("""{"finance":{"result":[{"quotes":[{"symbol":"NOPRICE"}]}]}}""").isEmpty())
    }
}
