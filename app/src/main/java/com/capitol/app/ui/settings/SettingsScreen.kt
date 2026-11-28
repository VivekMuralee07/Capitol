package com.capitol.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capitol.app.AppContainer
import com.capitol.app.analysis.IndicatorType
import com.capitol.app.data.prefs.AppSettings
import com.capitol.app.data.prefs.DataSourceMode
import com.capitol.app.data.prefs.SecretStore.Key
import com.capitol.app.data.prefs.ThemeMode
import com.capitol.app.update.UpdateManager
import com.capitol.app.ui.components.CapitolCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onStartUpdate: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val update by c.updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var backend by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
    val secretsV by c.secrets.version.collectAsStateWithLifecycle()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            com.capitol.app.ui.components.ScreenHeader("Settings", onBack = onBack)
        }
        item {
            CapitolCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Style", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    com.capitol.app.data.prefs.DesignStyle.entries.forEachIndexed { i, d ->
                        SegmentedButton(s.designStyle == d, { scope.launch { c.settings.setDesignStyle(d) } },
                            SegmentedButtonDefaults.itemShape(i, com.capitol.app.data.prefs.DesignStyle.entries.size)) {
                            Text(when (d) { com.capitol.app.data.prefs.DesignStyle.VIBRANT -> "Vibrant"; com.capitol.app.data.prefs.DesignStyle.PREMIUM -> "Premium" })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.themeMode == m, { scope.launch { c.settings.setTheme(m) } }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                            Text(when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" })
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) ToggleRow("Material You colours", "Use your wallpaper colours", s.dynamicColor) {
                    scope.launch { c.settings.setDynamicColor(it) }
                }
            }
        }
        item {
            CapitolCard {
                Text("Market data", style = MaterialTheme.typography.titleMedium)
                Text("Direct mode calls the same Yahoo Finance endpoints yfinance uses, on-device. Service mode uses the bundled Python yfinance server (see /backend in the repo) for the full yfinance feature set.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DataSourceMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.dataSource == m, { scope.launch { c.settings.setDataSource(m) } }, SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(if (m == DataSourceMode.DIRECT) "Direct (on-device)" else "yfinance service")
                        }
                    }
                }
                if (s.dataSource == DataSourceMode.YFINANCE_SERVICE) {
                    OutlinedTextField(backend, { backend = it }, Modifier.fillMaxWidth(), label = { Text("Service URL") }, singleLine = true)
                    TextButton({ scope.launch { c.settings.setBackendUrl(backend) } }) { Text("Save URL") }
                }
            }
        }
        item {
            CapitolCard {
                Text("AI Agent (Claude)", style = MaterialTheme.typography.titleMedium)
                val has = remember(secretsV) { c.secrets.has(Key.ANTHROPIC_API_KEY) }
                Text(if (has) "API key saved (encrypted on device)." else "Paste an Anthropic API key from console.anthropic.com. It's stored encrypted and only sent to api.anthropic.com.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (has) "Replace API key" else "Anthropic API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row {
                    Button({ c.secrets.put(Key.ANTHROPIC_API_KEY, apiKey); apiKey = "" }, enabled = apiKey.isNotBlank()) { Text("Save key") }
                    if (has) TextButton({ c.secrets.put(Key.ANTHROPIC_API_KEY, null) }) { Text("Remove") }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Autopilot", "Re-analyse your watchlist in the background and notify on verdict changes & price alerts", s.agentAutoScan) {
                    if (it && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    scope.launch { c.settings.setAgentAutoScan(it) }
                }
                if (s.agentAutoScan) {
                    Text("Scan every ${s.agentScanHours} h", style = MaterialTheme.typography.labelLarge)
                    Slider(s.agentScanHours.toFloat(), { scope.launch { c.settings.setAgentScanHours(it.toLong()) } }, valueRange = 1f..24f, steps = 22)
                }
            }
        }
        item {
            CapitolCard {
                Text("Default indicators", style = MaterialTheme.typography.titleMedium)
                Text("Used by the watchlist, home heatmap, autopilot and the agent when you don't specify.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IndicatorType.entries.forEach { t ->
                        FilterChip(t in s.defaultIndicators, {
                            val next = if (t in s.defaultIndicators) s.defaultIndicators - t else s.defaultIndicators + t
                            if (next.isNotEmpty()) scope.launch { c.settings.setDefaultIndicators(next) }
                        }, { Text(t.shortName) })
                    }
                }
            }
        }
        item { IndicatorParamsCard(s.indicatorParams) { scope.launch { c.settings.setIndicatorParams(it) } } }
        item {
            CapitolCard {
                Text("App updates", style = MaterialTheme.typography.titleMedium)
                Text("Installed version ${c.updates.currentVersion}", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Check automatically", "Look for a new version when the app starts", s.autoCheckUpdates) {
                    scope.launch { c.settings.setAutoCheckUpdates(it) }
                }
                when (val u = update) {
                    UpdateManager.State.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    UpdateManager.State.UpToDate -> Text("You're on the latest version ✓", color = MaterialTheme.colorScheme.secondary)
                    is UpdateManager.State.Available -> Button(onStartUpdate) { Text("Download & install v${u.version}") }
                    is UpdateManager.State.Downloading -> Column {
                        Text("Downloading… ${(u.progress * 100).toInt()}%")
                        LinearProgressIndicator({ u.progress }, Modifier.fillMaxWidth())
                    }
                    UpdateManager.State.ReadyToInstall -> Button(onStartUpdate) { Text("Install update") }
                    is UpdateManager.State.Error -> Text(u.message, color = MaterialTheme.colorScheme.error)
                    UpdateManager.State.Idle -> {}
                }
                OutlinedButton({ scope.launch { c.updates.check() } }) { Text("Check for updates") }
            }
        }
        item {
            val activity = androidx.compose.ui.platform.LocalContext.current as androidx.fragment.app.FragmentActivity
            var lockMsg by remember { mutableStateOf<String?>(null) }
            CapitolCard {
                Text("Security", style = MaterialTheme.typography.titleMedium)
                ToggleRow("App lock", "Require fingerprint, face or screen lock to open Capitol (and after 1 minute away)", s.appLock) { on ->
                    if (!com.capitol.app.ui.lock.AppLock.canLock(activity)) { lockMsg = "Set up a screen lock or fingerprint in Android settings first."; return@ToggleRow }
                    com.capitol.app.ui.lock.AppLock.authenticate(activity, if (on) "Turn on app lock" else "Turn off app lock",
                        onSuccess = { scope.launch { c.settings.setAppLock(on) }; lockMsg = null }, onFailure = { lockMsg = it })
                }
                lockMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { DiagnosticsCard() }
        item {
            CapitolCard {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text("Capitol provides educational technical analysis based on historical price data from Yahoo Finance. It is not investment advice. Markets carry risk — always do your own research.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** Settings → Diagnostics: view, copy, share or clear the on-device error log. */
@Composable
private fun DiagnosticsCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var report by remember { mutableStateOf(com.capitol.app.diagnostics.Diagnostics.report()) }
    var copied by remember { mutableStateOf(false) }
    CapitolCard {
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Text("If something goes wrong, copy this log and send it when reporting the problem. It never contains your API keys or tokens.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text(report.lines().takeLast(12).joinToString("\n"), style = com.capitol.app.ui.theme.NumberStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 12)
        Spacer(Modifier.height(8.dp))
        Row {
            Button({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(report)); copied = true }) { Text(if (copied) "Copied ✓" else "Copy log") }
            TextButton({
                ctx.startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Capitol diagnostics")
                        .putExtra(android.content.Intent.EXTRA_TEXT, report), "Share log"))
            }) { Text("Share") }
            TextButton({ com.capitol.app.diagnostics.Diagnostics.clear(); report = com.capitol.app.diagnostics.Diagnostics.report(); copied = false }) { Text("Clear") }
        }
    }
}

private data class ParamField(val group: String, val label: String, val get: (com.capitol.app.analysis.IndicatorParams) -> Double,
                              val set: (com.capitol.app.analysis.IndicatorParams, Double) -> com.capitol.app.analysis.IndicatorParams, val decimal: Boolean = false)

private val PARAM_FIELDS = listOf(
    ParamField("Moving averages", "SMA short", { it.smaShort.toDouble() }, { p, v -> p.copy(smaShort = v.toInt()) }),
    ParamField("Moving averages", "SMA medium", { it.smaMid.toDouble() }, { p, v -> p.copy(smaMid = v.toInt()) }),
    ParamField("Moving averages", "SMA long", { it.smaLong.toDouble() }, { p, v -> p.copy(smaLong = v.toInt()) }),
    ParamField("Moving averages", "EMA fast", { it.emaFast.toDouble() }, { p, v -> p.copy(emaFast = v.toInt()) }),
    ParamField("Moving averages", "EMA slow", { it.emaSlow.toDouble() }, { p, v -> p.copy(emaSlow = v.toInt()) }),
    ParamField("MACD", "Fast", { it.macdFast.toDouble() }, { p, v -> p.copy(macdFast = v.toInt()) }),
    ParamField("MACD", "Slow", { it.macdSlow.toDouble() }, { p, v -> p.copy(macdSlow = v.toInt()) }),
    ParamField("MACD", "Signal", { it.macdSignal.toDouble() }, { p, v -> p.copy(macdSignal = v.toInt()) }),
    ParamField("RSI", "Period", { it.rsiPeriod.toDouble() }, { p, v -> p.copy(rsiPeriod = v.toInt()) }),
    ParamField("RSI", "Overbought", { it.rsiOverbought.toDouble() }, { p, v -> p.copy(rsiOverbought = v.toInt()) }),
    ParamField("RSI", "Oversold", { it.rsiOversold.toDouble() }, { p, v -> p.copy(rsiOversold = v.toInt()) }),
    ParamField("Bollinger Bands", "Period", { it.bbPeriod.toDouble() }, { p, v -> p.copy(bbPeriod = v.toInt()) }),
    ParamField("Bollinger Bands", "Std dev", { it.bbStdDev }, { p, v -> p.copy(bbStdDev = v) }, decimal = true),
    ParamField("Supertrend", "ATR period", { it.supertrendPeriod.toDouble() }, { p, v -> p.copy(supertrendPeriod = v.toInt()) }),
    ParamField("Supertrend", "Multiplier", { it.supertrendMultiplier }, { p, v -> p.copy(supertrendMultiplier = v) }, decimal = true),
    ParamField("Oscillators", "Stochastic", { it.stochPeriod.toDouble() }, { p, v -> p.copy(stochPeriod = v.toInt()) }),
    ParamField("Oscillators", "CCI", { it.cciPeriod.toDouble() }, { p, v -> p.copy(cciPeriod = v.toInt()) }),
    ParamField("Oscillators", "Williams %R", { it.williamsPeriod.toDouble() }, { p, v -> p.copy(williamsPeriod = v.toInt()) }),
    ParamField("Oscillators", "MFI", { it.mfiPeriod.toDouble() }, { p, v -> p.copy(mfiPeriod = v.toInt()) }),
    ParamField("Oscillators", "ROC", { it.rocPeriod.toDouble() }, { p, v -> p.copy(rocPeriod = v.toInt()) }),
    ParamField("Volatility & trend", "ATR", { it.atrPeriod.toDouble() }, { p, v -> p.copy(atrPeriod = v.toInt()) }),
    ParamField("Volatility & trend", "ADX", { it.adxPeriod.toDouble() }, { p, v -> p.copy(adxPeriod = v.toInt()) }),
)

/** Settings → Indicator settings: edit periods/thresholds used everywhere in the app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorParamsCard(current: com.capitol.app.analysis.IndicatorParams, onSave: (com.capitol.app.analysis.IndicatorParams) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    CapitolCard {
        Text("Indicator settings", style = MaterialTheme.typography.titleMedium)
        Text("RSI ${current.rsiPeriod} (${current.rsiOversold}/${current.rsiOverbought}) · MACD ${current.macdFast}/${current.macdSlow}/${current.macdSignal} · " +
            "SMA ${current.smaShort}/${current.smaMid}/${current.smaLong} · Supertrend ${current.supertrendPeriod}/${current.g(current.supertrendMultiplier)}",
            style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton({ editing = true }) { Text("Edit") }
            if (current != com.capitol.app.analysis.IndicatorParams.DEFAULT) TextButton({ onSave(com.capitol.app.analysis.IndicatorParams.DEFAULT) }) { Text("Reset to defaults") }
        }
    }
    if (editing) {
        val texts = remember { androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            PARAM_FIELDS.forEach { f -> put(f.group + f.label, current.g(f.get(current))) }
        } }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Indicator settings") },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    PARAM_FIELDS.groupBy { it.group }.forEach { (group, fields) ->
                        item {
                            Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fields.forEach { f ->
                                    OutlinedTextField(texts[f.group + f.label] ?: "", { texts[f.group + f.label] = it },
                                        Modifier.width(120.dp), label = { Text(f.label) }, singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = if (f.decimal) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Number))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    var p = current
                    PARAM_FIELDS.forEach { f -> texts[f.group + f.label]?.toDoubleOrNull()?.let { p = f.set(p, it) } }
                    onSave(p.sanitized()); editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } },
        )
    }
}
