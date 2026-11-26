package com.capitol.app.ui.portfolio

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.capitol.app.AppContainer
import com.capitol.app.data.broker.CurrencyTotal
import com.capitol.app.data.broker.Holding
import com.capitol.app.data.broker.KiteConnectClient
import com.capitol.app.data.mcp.IndMoneyMcp
import com.capitol.app.data.prefs.SecretStore.Key
import com.capitol.app.ui.components.AnimatedNumber
import com.capitol.app.ui.components.CapitolCard
import com.capitol.app.ui.components.ErrorBanner
import com.capitol.app.ui.components.SectionTitle
import com.capitol.app.ui.components.changeColor
import com.capitol.app.ui.theme.CapitolColors
import com.capitol.app.ui.theme.NumberStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PortfolioViewModel(val c: AppContainer) : ViewModel() {
    val state = c.portfolio.state
    val message = MutableStateFlow<String?>(null)
    val secretsVersion = c.secrets.version
    val kite get() = c.portfolio.kite
    val ind get() = c.portfolio.indmoney

    init { if (c.portfolio.anyConnected) refresh() }

    fun refresh() = viewModelScope.launch { c.portfolio.refresh() }
    fun saveKiteKeys(key: String, secret: String) { c.secrets.put(Key.KITE_API_KEY, key); c.secrets.put(Key.KITE_API_SECRET, secret) }
    fun completeKiteLogin(requestToken: String) = viewModelScope.launch {
        runCatching { kite.createSession(requestToken) }
            .onSuccess { message.value = "Connected to Zerodha Kite"; refresh() }
            .onFailure { message.value = it.message }
    }
    fun kiteLogout() { kite.logout(); refresh() }
    fun saveIndToken(token: String) { c.secrets.put(Key.INDMONEY_ACCESS_TOKEN, token); refresh() }
    fun indDisconnect() { ind.disconnect(); refresh() }

    // INDmoney via its official MCP server (OAuth sign-in; used by the AI agent).
    val mcp get() = c.indmoneyMcp
    val mcpBusy = MutableStateFlow(false)
    fun beginMcpLogin(onUrl: (String) -> Unit) = viewModelScope.launch {
        mcpBusy.value = true
        runCatching { mcp.beginLogin() }.onSuccess(onUrl).onFailure { message.value = it.message }
        mcpBusy.value = false
    }
    fun completeMcpLogin(redirect: String) = viewModelScope.launch {
        mcpBusy.value = true
        runCatching { mcp.completeLogin(redirect); mcp.tools() }
            .onSuccess { message.value = "Connected to INDmoney · the AI agent can now read your INDmoney portfolio (${it.size} tools)" }
            .onFailure { message.value = it.message }
        mcpBusy.value = false
    }
    fun mcpDisconnect() { mcp.disconnect(); message.value = "Disconnected INDmoney MCP" }
    fun importCsv(text: String, usStocks: Boolean) {
        val n = c.portfolio.importCsv(text, usStocks)
        message.value = if (n == 0) "No holdings found — the CSV needs symbol and quantity columns"
        else "Imported $n ${if (usStocks) "US" else "Indian"} holdings from CSV"
    }
}

@Composable
fun PortfolioScreen(vm: PortfolioViewModel, onAnalyze: (String) -> Unit, onAskAi: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val secretsV by vm.secretsVersion.collectAsStateWithLifecycle()
    var kiteLoginUrl by remember { mutableStateOf<String?>(null) }
    var mcpLoginUrl by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    var csvIsUs by remember { mutableStateOf(false) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> vm.importCsv(r.readText(), csvIsUs) } }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Portfolio", style = MaterialTheme.typography.headlineMedium)
                    Text("Read-only broker sync for analysis — Capitol never places orders.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        msg?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        st.errors.forEach { e -> item { ErrorBanner(e) } }
        if (st.holdings.isNotEmpty()) {
            item { SummaryCard(st.totalsByCurrency) {
                onAskAi("Review my portfolio holdings technically. Which positions look strongest and weakest, and what levels should I watch?")
            } }
            item { SectionTitle("Holdings (${st.holdings.size})") }
            items(st.holdings, key = { it.broker.name + it.tradingSymbol + it.exchange }) { h -> HoldingRow(h) { onAnalyze(h.yahooSymbol) } }
        }
        item { SectionTitle("Connections") }
        item { secretsV.let { KiteCard(vm) { kiteLoginUrl = it } } }
        item { secretsV.let { IndMoneyMcpCard(vm, onLogin = { mcpLoginUrl = it }, onAskAi = onAskAi) } }
        item { secretsV.let { IndMoneyCard(vm) } }
        item {
            CapitolCard {
                Text("Import holdings CSV", style = MaterialTheme.typography.titleMedium)
                Text("Export holdings from INDmoney (or any broker) and import them. Needs columns: symbol, quantity, average price (optional: ltp, exchange). Import Indian and US files separately — each import replaces earlier holdings of the same market.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("These are US stocks", style = MaterialTheme.typography.bodyLarge)
                        Text("Tickers like AAPL, NVDA (no .NS suffix), values in USD", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(csvIsUs, { csvIsUs = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ csvPicker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) }) {
                    Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Choose CSV")
                }
            }
        }
    }

    mcpLoginUrl?.let { url ->
        OAuthWebDialog("Sign in to INDmoney", url, isRedirect = IndMoneyMcp::isRedirect,
            onRedirect = { r -> mcpLoginUrl = null; vm.completeMcpLogin(r) }, onDismiss = { mcpLoginUrl = null })
    }
    kiteLoginUrl?.let { url ->
        KiteLoginDialog(url, onToken = { token -> kiteLoginUrl = null; vm.completeKiteLogin(token) }, onDismiss = { kiteLoginUrl = null })
    }
}

@Composable
private fun SummaryCard(totals: List<CurrencyTotal>, onAskAi: () -> Unit) {
    CapitolCard {
        totals.forEachIndexed { i, t ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Text("Current value · ${if (t.currency == "USD") "US stocks" else "Indian stocks"}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedNumber(t.current, fontSize = 28, format = { "${t.symbol}%,.2f".format(it) })
            Row {
                Text("Invested ${t.symbol}%,.2f".format(t.invested), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("P&L %+,.2f (%+.2f%%)".format(t.pnl, t.pnlPercent), color = changeColor(t.pnl), style = NumberStyle.copy(fontSize = 13.sp))
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onAskAi) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("AI portfolio review") }
    }
}

@Composable
private fun HoldingRow(h: Holding, onClick: () -> Unit) {
    CapitolCard(Modifier.fillMaxWidth().clickable(onClick = onClick), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.tradingSymbol, style = MaterialTheme.typography.titleSmall)
                Text("${h.broker.label} · ${h.exchange} · ${"%.4g".format(h.quantity)} @ ${"%,.2f".format(h.averagePrice)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (h.isUs) "$" else "₹") + "%,.2f".format(h.current), style = NumberStyle.copy(fontSize = 14.sp))
                Text("%+,.2f (%+.1f%%)".format(h.pnl, h.pnlPercent), color = changeColor(h.pnl), style = NumberStyle.copy(fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun KiteCard(vm: PortfolioViewModel, onLogin: (String) -> Unit) {
    var key by remember { mutableStateOf(vm.c.secrets.get(Key.KITE_API_KEY) ?: "") }
    var secret by remember { mutableStateOf(vm.c.secrets.get(Key.KITE_API_SECRET) ?: "") }
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zerodha Kite", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (vm.kite.isConnected) Icon(Icons.Default.CheckCircle, "Connected", tint = CapitolColors.market.bull)
        }
        if (vm.kite.isConnected) {
            Text("Connected as ${vm.kite.userName ?: "Kite user"}. Kite sessions expire daily around 6 AM — log in again when prompted.",
                style = MaterialTheme.typography.bodySmall)
            Row {
                Button({ runCatching { vm.kite.loginUrl() }.onSuccess(onLogin) }) { Text("Re-login") }
                TextButton(vm::kiteLogout) { Text("Disconnect") }
            }
        } else {
            Text("Create a Kite Connect app at developers.kite.trade, then paste its API key and secret. Set the app's redirect URL to capitol://kite-callback (any URL works — Capitol reads the request token from the login page).",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true)
            OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("API secret") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(8.dp))
            Button({
                vm.saveKiteKeys(key, secret)
                runCatching { vm.kite.loginUrl() }.onSuccess(onLogin)
            }, enabled = key.isNotBlank() && secret.isNotBlank()) { Text("Log in with Kite") }
        }
    }
}

@Composable
private fun IndMoneyMcpCard(vm: PortfolioViewModel, onLogin: (String) -> Unit, onAskAi: (String) -> Unit) {
    val busy by vm.mcpBusy.collectAsStateWithLifecycle()
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("INDmoney (MCP) · recommended", style = MaterialTheme.typography.titleMedium)
                Text("Includes US stocks · official INDmoney server", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.mcp.isConnected) Icon(Icons.Default.CheckCircle, "Connected", tint = CapitolColors.market.bull)
        }
        Spacer(Modifier.height(4.dp))
        if (vm.mcp.isConnected) {
            Text("Connected. The AI agent can read your INDmoney portfolio — Indian and US stocks — and analyse it.",
                style = MaterialTheme.typography.bodySmall)
            Row {
                Button({ onAskAi("Using my INDmoney account, list my holdings (including US stocks) and give a technical review of the biggest positions.") }) {
                    Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Ask AI")
                }
                TextButton(vm::mcpDisconnect) { Text("Disconnect") }
            }
        } else {
            Text("Sign in on INDmoney's own page — no token to copy, no static IP. Capitol stores the sign-in securely on this phone and never sends it to Claude.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button({ vm.beginMcpLogin(onLogin) }, enabled = !busy) { Text(if (busy) "Connecting…" else "Connect with INDmoney") }
        }
    }
}

@Composable
private fun IndMoneyCard(vm: PortfolioViewModel) {
    var token by remember { mutableStateOf("") }
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INDmoney (INDstocks API)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (vm.ind.isConnected) Icon(Icons.Default.CheckCircle, "Connected", tint = CapitolColors.market.bull)
        }
        if (vm.ind.isConnected) {
            Text("Access token saved. INDstocks tokens are short-lived; paste a fresh one if sync fails.", style = MaterialTheme.typography.bodySmall)
            TextButton(vm::indDisconnect) { Text("Disconnect") }
        } else {
            Text("Generate an access token at indstocks.com → API → Generate Token, then paste it here.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Access token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(8.dp))
            Button({ vm.saveIndToken(token) }, enabled = token.isNotBlank()) { Text("Connect INDmoney") }
        }
    }
}

/** OAuth sign-in in a WebView; hands back the redirect URL once [isRedirect] matches. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthWebDialog(title: String, url: String, isRedirect: (String) -> Boolean, onRedirect: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onDismiss) { Text("Cancel") }
            }
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var handled = false
                        private fun check(u: String?): Boolean {
                            if (u == null || !isRedirect(u)) return false
                            if (!handled) { handled = true; onRedirect(u) }
                            return true
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = check(request?.url?.toString())
                        override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) { if (check(u)) view?.stopLoading() }
                    }
                    loadUrl(url)
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Kite login inside a WebView; captures the request_token from the redirect. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun KiteLoginDialog(url: String, onToken: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kite login", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onDismiss) { Text("Cancel") }
            }
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var handled = false
                        private fun check(u: String?): Boolean {
                            val t = u?.let { KiteConnectClient.requestTokenFrom(it) } ?: return false
                            if (!handled) { handled = true; onToken(t) }
                            return true
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = check(request?.url?.toString())
                        override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) { if (check(u)) view?.stopLoading() }
                    }
                    loadUrl(url)
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
