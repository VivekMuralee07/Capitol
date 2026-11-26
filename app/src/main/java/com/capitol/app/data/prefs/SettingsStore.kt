package com.capitol.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.capitol.app.analysis.IndicatorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DataSourceMode { DIRECT, YFINANCE_SERVICE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val dataSource: DataSourceMode = DataSourceMode.DIRECT,
    val backendUrl: String = "http://10.0.2.2:8000",
    val agentAutoScan: Boolean = false,
    val agentScanHours: Long = 6,
    val defaultIndicators: Set<IndicatorType> = IndicatorType.DEFAULTS,
    val autoCheckUpdates: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "capitol_settings")

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val source = stringPreferencesKey("data_source")
        val backend = stringPreferencesKey("backend_url")
        val autoScan = booleanPreferencesKey("agent_auto_scan")
        val scanHours = longPreferencesKey("agent_scan_hours")
        val indicators = stringSetPreferencesKey("default_indicators")
        val autoUpdates = booleanPreferencesKey("auto_updates")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        themeMode = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[K.dynamic] ?: false,
        dataSource = this[K.source]?.let { runCatching { DataSourceMode.valueOf(it) }.getOrNull() } ?: DataSourceMode.DIRECT,
        backendUrl = this[K.backend] ?: AppSettings().backendUrl,
        agentAutoScan = this[K.autoScan] ?: false,
        agentScanHours = this[K.scanHours] ?: 6,
        defaultIndicators = this[K.indicators]
            ?.mapNotNull { runCatching { IndicatorType.valueOf(it) }.getOrNull() }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: IndicatorType.DEFAULTS,
        autoCheckUpdates = this[K.autoUpdates] ?: true,
    )

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[K.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[K.dynamic] = on }
    suspend fun setDataSource(mode: DataSourceMode) = context.dataStore.edit { it[K.source] = mode.name }
    suspend fun setBackendUrl(url: String) = context.dataStore.edit { it[K.backend] = url.trim() }
    suspend fun setAgentAutoScan(on: Boolean) = context.dataStore.edit { it[K.autoScan] = on }
    suspend fun setAgentScanHours(h: Long) = context.dataStore.edit { it[K.scanHours] = h.coerceIn(1, 24) }
    suspend fun setDefaultIndicators(set: Set<IndicatorType>) =
        context.dataStore.edit { it[K.indicators] = set.map { t -> t.name }.toSet() }
    suspend fun setAutoCheckUpdates(on: Boolean) = context.dataStore.edit { it[K.autoUpdates] = on }
}
