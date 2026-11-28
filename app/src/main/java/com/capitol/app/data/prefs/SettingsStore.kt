package com.capitol.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.capitol.app.analysis.IndicatorParams
import com.capitol.app.analysis.IndicatorType
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
/** Visual style: colourful gradients (default) or the calm single-accent look. */
enum class DesignStyle { VIBRANT, PREMIUM }
enum class DataSourceMode { DIRECT, YFINANCE_SERVICE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val designStyle: DesignStyle = DesignStyle.VIBRANT,
    val dataSource: DataSourceMode = DataSourceMode.DIRECT,
    val backendUrl: String = "http://10.0.2.2:8000",
    val agentAutoScan: Boolean = false,
    val agentScanHours: Long = 6,
    val defaultIndicators: Set<IndicatorType> = IndicatorType.DEFAULTS,
    val autoCheckUpdates: Boolean = true,
    val indicatorParams: IndicatorParams = IndicatorParams.DEFAULT,
    val appLock: Boolean = false,
    /** Trade planner defaults. */
    val tradeCapital: Double = 100_000.0,
    val tradeRiskPct: Double = 1.0,
)

private val Context.dataStore by preferencesDataStore(name = "capitol_settings")

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val style = stringPreferencesKey("design_style")
        val source = stringPreferencesKey("data_source")
        val backend = stringPreferencesKey("backend_url")
        val autoScan = booleanPreferencesKey("agent_auto_scan")
        val scanHours = longPreferencesKey("agent_scan_hours")
        val indicators = stringSetPreferencesKey("default_indicators")
        val autoUpdates = booleanPreferencesKey("auto_updates")
        val params = stringPreferencesKey("indicator_params")
        val appLock = booleanPreferencesKey("app_lock")
        val capital = stringPreferencesKey("trade_capital")
        val risk = stringPreferencesKey("trade_risk_pct")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        themeMode = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[K.dynamic] ?: false,
        designStyle = this[K.style]?.let { runCatching { DesignStyle.valueOf(it) }.getOrNull() } ?: DesignStyle.VIBRANT,
        dataSource = this[K.source]?.let { runCatching { DataSourceMode.valueOf(it) }.getOrNull() } ?: DataSourceMode.DIRECT,
        backendUrl = this[K.backend] ?: AppSettings().backendUrl,
        agentAutoScan = this[K.autoScan] ?: false,
        agentScanHours = this[K.scanHours] ?: 6,
        defaultIndicators = this[K.indicators]
            ?.mapNotNull { runCatching { IndicatorType.valueOf(it) }.getOrNull() }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: IndicatorType.DEFAULTS,
        autoCheckUpdates = this[K.autoUpdates] ?: true,
        indicatorParams = this[K.params]?.let { runCatching { paramsJson.decodeFromString<IndicatorParams>(it) }.getOrNull() }
            ?.sanitized() ?: IndicatorParams.DEFAULT,
        appLock = this[K.appLock] ?: false,
        tradeCapital = this[K.capital]?.toDoubleOrNull() ?: 100_000.0,
        tradeRiskPct = this[K.risk]?.toDoubleOrNull() ?: 1.0,
    )

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[K.theme] = mode.name }
    suspend fun setDesignStyle(style: DesignStyle) = context.dataStore.edit { it[K.style] = style.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[K.dynamic] = on }
    suspend fun setDataSource(mode: DataSourceMode) = context.dataStore.edit { it[K.source] = mode.name }
    suspend fun setBackendUrl(url: String) = context.dataStore.edit { it[K.backend] = url.trim() }
    suspend fun setAgentAutoScan(on: Boolean) = context.dataStore.edit { it[K.autoScan] = on }
    suspend fun setAgentScanHours(h: Long) = context.dataStore.edit { it[K.scanHours] = h.coerceIn(1, 24) }
    suspend fun setDefaultIndicators(set: Set<IndicatorType>) =
        context.dataStore.edit { it[K.indicators] = set.map { t -> t.name }.toSet() }
    suspend fun setIndicatorParams(p: IndicatorParams) =
        context.dataStore.edit { it[K.params] = paramsJson.encodeToString(p.sanitized()) }

    private val paramsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun setAppLock(on: Boolean) = context.dataStore.edit { it[K.appLock] = on }
    suspend fun setTradeDefaults(capital: Double, riskPct: Double) = context.dataStore.edit {
        it[K.capital] = capital.coerceAtLeast(0.0).toString(); it[K.risk] = riskPct.coerceIn(0.1, 10.0).toString()
    }

    suspend fun setAutoCheckUpdates(on: Boolean) = context.dataStore.edit { it[K.autoUpdates] = on }
}
