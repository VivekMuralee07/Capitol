package com.capitol.app.data.watchlist

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Conditions the background autopilot can watch for. Each fires when it *becomes* true. */
@Serializable
enum class AlertCondition(val label: String, val needsValue: Boolean = false) {
    RSI_BELOW("RSI falls below", true),
    RSI_ABOVE("RSI rises above", true),
    SUPERTREND_BUY("Supertrend turns green (buy)"),
    SUPERTREND_SELL("Supertrend turns red (sell)"),
    MACD_BULL_CROSS("MACD crosses above its signal line"),
    MACD_BEAR_CROSS("MACD crosses below its signal line"),
    GOLDEN_CROSS("Golden cross (medium SMA above long SMA)"),
    DEATH_CROSS("Death cross (medium SMA below long SMA)"),
    ABOVE_LONG_SMA("Price closes above the long SMA"),
    BELOW_LONG_SMA("Price closes below the long SMA"),
    VERDICT_BUY("Capitol verdict turns Buy or better"),
    VERDICT_SELL("Capitol verdict turns Sell or worse"),
}

@Serializable
data class IndicatorAlert(val condition: AlertCondition, val value: Double? = null) {
    val key: String get() = condition.name + (value?.let { "@$it" } ?: "")
    val description: String get() = condition.label + (value?.let { " ${if (it % 1.0 == 0.0) "%.0f".format(it) else it}" } ?: "")
}

@Serializable
data class WatchItem(
    val symbol: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** Optional price alerts evaluated by the background agent. */
    val alertAbove: Double? = null,
    val alertBelow: Double? = null,
    val note: String = "",
    val indicatorAlerts: List<IndicatorAlert> = emptyList(),
)

@Serializable
data class Watchlist(val name: String, val items: List<WatchItem> = emptyList())

@Serializable
data class WatchlistData(val lists: List<Watchlist>, val active: Int = 0) {
    val activeList: Watchlist get() = lists[active.coerceIn(0, lists.lastIndex)]
    /** Every symbol across all lists (first occurrence wins). */
    val allItems: List<WatchItem> get() = lists.flatMap { it.items }.distinctBy { it.symbol }
}

private val Context.watchStore by preferencesDataStore(name = "capitol_watchlist")

class WatchlistStore(private val context: Context) {
    private val v2 = stringPreferencesKey("lists_v2")
    private val legacy = stringPreferencesKey("items")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(p: Preferences): WatchlistData =
        p[v2]?.let { runCatching { json.decodeFromString<WatchlistData>(it) }.getOrNull() }?.takeIf { it.lists.isNotEmpty() }
            ?: migrate(p[legacy])

    /** Older versions kept a single list under "items"; it becomes "My Watchlist". */
    internal fun migrate(legacyJson: String?): WatchlistData {
        val items = legacyJson?.let { runCatching { json.decodeFromString<List<WatchItem>>(it) }.getOrNull() } ?: DEFAULT
        return WatchlistData(listOf(Watchlist(DEFAULT_NAME, items)))
    }

    val data: Flow<WatchlistData> = context.watchStore.data.map(::decode)

    /** Items of the active list. */
    val items: Flow<List<WatchItem>> = data.map { it.activeList.items }

    suspend fun current(): List<WatchItem> = data.first().activeList.items
    suspend fun all(): List<WatchItem> = data.first().allItems
    suspend fun snapshot(): WatchlistData = data.first()

    private suspend fun write(transform: (WatchlistData) -> WatchlistData) {
        context.watchStore.edit { p -> p[v2] = json.encodeToString(transform(decode(p))) }
    }

    private suspend fun editActive(f: (List<WatchItem>) -> List<WatchItem>) = write { d ->
        val i = d.active.coerceIn(0, d.lists.lastIndex)
        d.copy(lists = d.lists.mapIndexed { idx, l -> if (idx == i) l.copy(items = f(l.items)) else l })
    }

    /** Applies [f] to the item with [symbol] in every list that contains it. */
    private suspend fun editSymbol(symbol: String, f: (WatchItem) -> WatchItem) = write { d ->
        d.copy(lists = d.lists.map { l -> l.copy(items = l.items.map { if (it.symbol.equals(symbol, true)) f(it) else it }) })
    }

    suspend fun add(symbol: String, name: String, listName: String? = null) = write { d ->
        val s = symbol.uppercase()
        val target = listName?.let { n -> d.lists.indexOfFirst { it.name.equals(n, true) } }?.takeIf { it >= 0 } ?: d.active
        d.copy(lists = d.lists.mapIndexed { i, l ->
            if (i != target || l.items.any { it.symbol == s }) l else l.copy(items = l.items + WatchItem(s, name))
        })
    }

    suspend fun remove(symbol: String) = editActive { list -> list.filterNot { it.symbol.equals(symbol, true) } }

    suspend fun setAlerts(symbol: String, above: Double?, below: Double?) = editSymbol(symbol) { it.copy(alertAbove = above, alertBelow = below) }

    suspend fun setIndicatorAlerts(symbol: String, alerts: List<IndicatorAlert>) = editSymbol(symbol) { it.copy(indicatorAlerts = alerts.distinctBy { a -> a.key }) }

    suspend fun move(from: Int, to: Int) = editActive { list ->
        if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    suspend fun contains(symbol: String) = current().any { it.symbol.equals(symbol, true) }

    // ---- multiple lists
    suspend fun createList(name: String) = write { d ->
        val n = name.trim().ifBlank { "List ${d.lists.size + 1}" }
        if (d.lists.any { it.name.equals(n, true) }) d else d.copy(lists = d.lists + Watchlist(n), active = d.lists.size)
    }
    suspend fun renameList(index: Int, name: String) = write { d ->
        d.copy(lists = d.lists.mapIndexed { i, l -> if (i == index && name.isNotBlank()) l.copy(name = name.trim()) else l })
    }
    suspend fun deleteList(index: Int) = write { d ->
        if (d.lists.size <= 1 || index !in d.lists.indices) d
        else d.copy(lists = d.lists.filterIndexed { i, _ -> i != index }, active = (if (d.active >= index) d.active - 1 else d.active).coerceAtLeast(0))
    }
    suspend fun setActive(index: Int) = write { d -> d.copy(active = index.coerceIn(0, d.lists.lastIndex)) }

    companion object {
        const val DEFAULT_NAME = "My Watchlist"
        val DEFAULT = listOf(
            WatchItem("RELIANCE.NS", "Reliance Industries"),
            WatchItem("TCS.NS", "Tata Consultancy Services"),
            WatchItem("HDFCBANK.NS", "HDFC Bank"),
            WatchItem("AAPL", "Apple Inc."),
        )
    }
}
