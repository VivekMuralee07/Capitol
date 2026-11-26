package com.capitol.app.data.market

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class EventType(val label: String) { EARNINGS("Earnings"), EX_DIVIDEND("Ex-dividend"), DIVIDEND("Dividend paid") }

data class CalendarEvent(val symbol: String, val name: String, val type: EventType, val epochSeconds: Long) {
    fun daysFrom(nowSeconds: Long): Long = Math.floorDiv(epochSeconds - nowSeconds, 86_400L)
}

/** Upcoming earnings and dividend dates for a set of symbols (from yfinance-style fundamentals). */
object EventsCalendar {
    fun eventsFor(f: Fundamentals, name: String, nowSeconds: Long, horizonDays: Int = 90): List<CalendarEvent> =
        listOfNotNull(
            f.nextEarningsDate?.let { CalendarEvent(f.symbol, name, EventType.EARNINGS, it) },
            f.exDividendDate?.let { CalendarEvent(f.symbol, name, EventType.EX_DIVIDEND, it) },
            f.dividendDate?.let { CalendarEvent(f.symbol, name, EventType.DIVIDEND, it) },
        ).filter { it.daysFrom(nowSeconds) in -1L..horizonDays.toLong() }

    suspend fun load(repo: MarketRepository, symbols: List<Pair<String, String>>, nowSeconds: Long = System.currentTimeMillis() / 1000): List<CalendarEvent> =
        coroutineScope {
            val gate = Semaphore(4)
            symbols.map { (sym, name) ->
                async { gate.withPermit { runCatching { eventsFor(repo.fundamentals(sym), name, nowSeconds) }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten().sortedBy { it.epochSeconds }
        }
}
