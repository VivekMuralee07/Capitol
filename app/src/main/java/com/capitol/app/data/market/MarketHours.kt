package com.capitol.app.data.market

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Regular trading sessions (holidays not modelled) — used to auto-refresh only while markets trade. */
object MarketHours {
    private val IST = ZoneId.of("Asia/Kolkata")
    private val NY = ZoneId.of("America/New_York")

    fun indiaOpen(now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        open(now.withZoneSameInstant(IST), LocalTime.of(9, 15), LocalTime.of(15, 30))

    fun usOpen(now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        open(now.withZoneSameInstant(NY), LocalTime.of(9, 30), LocalTime.of(16, 0))

    fun anyOpen(now: ZonedDateTime = ZonedDateTime.now()) = indiaOpen(now) || usOpen(now)

    private fun open(t: ZonedDateTime, from: LocalTime, to: LocalTime): Boolean {
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val lt = t.toLocalTime()
        return !lt.isBefore(from) && lt.isBefore(to)
    }
}
