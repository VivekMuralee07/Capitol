package com.capitol.app.data.watchlist

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WatchItem(
    val symbol: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** Optional price alerts evaluated by the background agent. */
    val alertAbove: Double? = null,
    val alertBelow: Double? = null,
    val note: String = "",
)

private val Context.watchStore by preferencesDataStore(name = "capitol_watchlist")

class WatchlistStore(private val context: Context) {
    private val key = stringPreferencesKey("items")
    private val json = Json { ignoreUnknownKeys = true }

    val items: Flow<List<WatchItem>> = context.watchStore.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString<List<WatchItem>>(it) }.getOrNull() } ?: DEFAULT
    }

    suspend fun current(): List<WatchItem> = items.first()

    private suspend fun write(transform: (List<WatchItem>) -> List<WatchItem>) {
        context.watchStore.edit { p ->
            val cur = p[key]?.let { runCatching { json.decodeFromString<List<WatchItem>>(it) }.getOrNull() } ?: DEFAULT
            p[key] = json.encodeToString(transform(cur))
        }
    }

    suspend fun add(symbol: String, name: String) = write { list ->
        val s = symbol.uppercase()
        if (list.any { it.symbol == s }) list else list + WatchItem(s, name)
    }

    suspend fun remove(symbol: String) = write { list -> list.filterNot { it.symbol.equals(symbol, true) } }

    suspend fun setAlerts(symbol: String, above: Double?, below: Double?) = write { list ->
        list.map { if (it.symbol.equals(symbol, true)) it.copy(alertAbove = above, alertBelow = below) else it }
    }

    suspend fun move(from: Int, to: Int) = write { list ->
        if (from !in list.indices || to !in list.indices) list
        else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    suspend fun contains(symbol: String) = current().any { it.symbol.equals(symbol, true) }

    companion object {
        val DEFAULT = listOf(
            WatchItem("RELIANCE.NS", "Reliance Industries"),
            WatchItem("TCS.NS", "Tata Consultancy Services"),
            WatchItem("HDFCBANK.NS", "HDFC Bank"),
            WatchItem("AAPL", "Apple Inc."),
        )
    }
}
