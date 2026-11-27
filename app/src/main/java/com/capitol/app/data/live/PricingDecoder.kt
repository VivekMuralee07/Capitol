package com.capitol.app.data.live

import java.util.Base64

/**
 * Decoder for Yahoo Finance's streaming `PricingData` protobuf (the same message yfinance's
 * `WebSocket` decodes — see yfinance/pricing.proto). Hand-rolled to avoid a protobuf dependency.
 */
object PricingDecoder {

    fun decodeBase64(b64: String): LiveQuote? = runCatching { decode(Base64.getDecoder().decode(b64)) }.getOrNull()

    /** Returns null for anything malformed (truncated, wrong types) — never throws. */
    fun decode(bytes: ByteArray): LiveQuote? = try { decodeOrThrow(bytes) } catch (e: RuntimeException) { null }

    private fun decodeOrThrow(bytes: ByteArray): LiveQuote? {
        val r = Reader(bytes)
        var id: String? = null; var price = 0f; var time = 0L; var currency: String? = null
        var marketHours: Int? = null; var changePct = 0f; var volume: Long? = null
        var high: Float? = null; var low: Float? = null; var change = 0f; var name: String? = null
        while (!r.eof()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            when (wire) {
                0 -> {
                    val v = r.varint()
                    when (field) {
                        3 -> time = zigzag(v)
                        7 -> marketHours = v.toInt()
                        9 -> volume = zigzag(v)
                    }
                }
                5 -> {
                    val f = Float.fromBits(r.fixed32())
                    when (field) { 2 -> price = f; 8 -> changePct = f; 10 -> high = f; 11 -> low = f; 12 -> change = f }
                }
                1 -> r.skip(8)
                2 -> {
                    val s = r.bytes(r.varint().toInt())
                    when (field) { 1 -> id = String(s); 4 -> currency = String(s); 13 -> name = String(s) }
                }
                else -> return null // groups / unknown wire types aren't used by this message
            }
        }
        val sym = id ?: return null
        if (price == 0f) return null
        return LiveQuote(
            symbol = sym, price = price.toDouble(), change = change.toDouble(), changePercent = changePct.toDouble(),
            dayHigh = high?.toDouble(), dayLow = low?.toDouble(), dayVolume = volume,
            // Yahoo sends milliseconds; guard against seconds just in case.
            time = if (time in 1..99_999_999_999L) time * 1000 else time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            name = name, currency = currency, marketHours = marketHours, source = LiveQuote.Source.STREAM,
        )
    }

    private fun zigzag(v: Long) = (v ushr 1) xor -(v and 1)

    private class Reader(private val b: ByteArray) {
        private var i = 0
        fun eof() = i >= b.size
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                val x = b[i++].toInt()
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "bad varint" }
            }
        }
        fun fixed32(): Int { val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24); i += 4; return v }
        fun skip(n: Int) { i += n }
        fun bytes(n: Int): ByteArray { val out = b.copyOfRange(i, i + n); i += n; return out }
    }
}
