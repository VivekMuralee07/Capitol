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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            CapitolCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
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
