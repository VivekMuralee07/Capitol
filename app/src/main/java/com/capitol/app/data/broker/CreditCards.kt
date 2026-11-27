package com.capitol.app.data.broker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A credit card bill as INDmoney's net worth reports it (read-only; Capitol never pays bills). */
data class CreditCardBill(
    val name: String,
    val last4: String?,
    val totalDue: Double,
    val minDue: Double? = null,
    val dueDate: LocalDate? = null,
) {
    fun daysUntilDue(today: LocalDate = LocalDate.now()): Long? = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
    val label get() = if (last4 != null) "$name ••$last4" else name
}

/**
 * Parses the `liabilities.credit_cards` section of INDmoney's `networth_snapshot` reply, e.g.
 * `{"card_variant":"Citi Rewards Platinum","last_4":"1433","total_due":633283.39,"due_date":"2026-12-12"}`.
 * Field names are matched tolerantly since INDmoney doesn't document them.
 */
object CreditCards {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val NAME = listOf("card_variant", "card_name", "name", "card_type", "product_name", "bank_name", "issuer", "bank")
    private val LAST4 = listOf("last_4", "last4", "last_four", "last_four_digits", "card_last4", "card_last_4", "masked_card_number",
        "masked_number", "card_number", "number")
    private val DUE = listOf("total_due", "total_amount_due", "amount_due", "due_amount", "bill_amount", "statement_amount",
        "outstanding", "current_outstanding", "total_outstanding")
    private val MIN_DUE = listOf("min_due", "minimum_due", "min_amount_due", "minimum_amount_due", "min_payment")
    private val DATE = listOf("due_date", "payment_due_date", "bill_due_date", "pay_by", "pay_by_date")

    fun parse(text: String): List<CreditCardBill> {
        val el = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()
            ?: MutualFunds.extractJson(text)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return emptyList()
        return parse(el)
    }

    fun parse(el: JsonElement): List<CreditCardBill> {
        val arr = findCards(el) ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::toBill) }
    }

    /** The `credit_cards` array, or failing that the first array of objects with a due amount and a card name. */
    private fun findCards(el: JsonElement, depth: Int = 0): JsonArray? {
        if (depth > 6) return null
        return when (el) {
            is JsonObject -> el.entries.firstOrNull { (k, v) -> v is JsonArray && k.lowercase().replace("_", "").contains("creditcard") }?.value as? JsonArray
                ?: el.values.firstNotNullOfOrNull { findCards(it, depth + 1) }
            is JsonArray -> if (el.any { o -> o is JsonObject && o.keys.any { it.lowercase() in DUE } && o.keys.any { it.lowercase() in NAME } &&
                    o.keys.any { it.lowercase().contains("card") } }) el
                else el.firstNotNullOfOrNull { findCards(it, depth + 1) }
            else -> null
        }
    }

    private fun toBill(o: JsonObject): CreditCardBill? {
        val m = o.entries.associate { it.key.lowercase() to it.value }
        fun str(keys: List<String>) = keys.firstNotNullOfOrNull { (m[it] as? JsonPrimitive)?.takeIf { p -> p.content.isNotBlank() && p.content != "null" }?.content?.trim() }
        fun num(keys: List<String>) = keys.firstNotNullOfOrNull { (m[it] as? JsonPrimitive)?.content?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull() }
        val name = str(NAME) ?: return null
        val due = num(DUE) ?: return null
        val last4 = str(LAST4)?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }
        return CreditCardBill(name, last4, due, num(MIN_DUE), date(str(DATE)))
    }

    internal fun date(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        s.toLongOrNull()?.let { n ->
            val ms = if (n > 10_000_000_000L) n else n * 1000
            return Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
        }
        return runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
    }
}
