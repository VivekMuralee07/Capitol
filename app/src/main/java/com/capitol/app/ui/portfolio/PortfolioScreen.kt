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
import androidx.compose.foundation.layout.size
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
import com.capitol.app.analysis.shortLabel
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PortfolioViewModel(val c: AppContainer) : ViewModel() {
    val state = c.portfolio.state
    val message = MutableStateFlow<String?>(null)
    val secretsVersion = c.secrets.version
    val kite get() = c.portfolio.kite
    val ind get() = c.portfolio.indmoney

    val analytics = MutableStateFlow<List<com.capitol.app.analysis.CurrencyAnalytics>>(emptyList())
    val analyticsLoading = MutableStateFlow(false)
    private var analyticsJob: kotlinx.coroutines.Job? = null

    init {
        if (c.portfolio.anyConnected) refresh()
        viewModelScope.launch {
            c.portfolio.state.map { it.holdings }.distinctUntilChanged().collect { if (it.isNotEmpty()) loadAnalytics(it) else analytics.value = emptyList() }
        }
    }

    fun refresh() = viewModelScope.launch { c.portfolio.refresh() }

    private fun loadAnalytics(holdings: List<Holding>) {
        analyticsJob?.cancel()
        analyticsJob = viewModelScope.launch {
            analyticsLoading.value = true
            analytics.value = com.capitol.app.analysis.PortfolioLoader.load(c, holdings)
            analyticsLoading.value = false
        }
    }
    fun saveKiteKeys(key: String, secret: String) { c.secrets.put(Key.KITE_API_KEY, key); c.secrets.put(Key.KITE_API_SECRET, secret) }
    fun completeKiteLogin(requestToken: String) = viewModelScope.launch {
        runCatching { kite.createSession(requestToken) }
            .onSuccess { message.value = "Connected to Zerodha Kite"; refresh() }
            .onFailure { com.capitol.app.diagnostics.Diagnostics.error("Portfolio", it.message ?: "error", it); message.value = it.message }
    }
    fun kiteLogout() { kite.logout(); refresh() }
    fun saveIndToken(token: String) { c.secrets.put(Key.INDMONEY_ACCESS_TOKEN, token); refresh() }
    fun indDisconnect() { ind.disconnect(); refresh() }

    // INDmoney via its official MCP server (OAuth sign-in): holdings in the Portfolio tab + AI agent tools.
    val mcp get() = c.indmoneyMcp
    val mcpBusy = MutableStateFlow(false)
    fun beginMcpLogin(onUrl: (String) -> Unit) = viewModelScope.launch {
        mcpBusy.value = true
        runCatching { mcp.beginLogin() }.onSuccess(onUrl).onFailure { com.capitol.app.diagnostics.Diagnostics.error("Portfolio", it.message ?: "error", it); message.value = it.message }
        mcpBusy.value = false
    }
    fun completeMcpLogin(redirect: String) = viewModelScope.launch {
        mcpBusy.value = true
        runCatching { mcp.completeLogin(redirect); mcp.tools() }
            .onSuccess { message.value = "Connected to INDmoney · loading your Indian and US stocks"; refresh() }
            .onFailure { com.capitol.app.diagnostics.Diagnostics.error("Portfolio", it.message ?: "error", it); message.value = it.message }
        mcpBusy.value = false
    }
    fun mcpDisconnect() { mcp.disconnect(); message.value = "Disconnected INDmoney MCP"; refresh() }

    // Zerodha via Kite MCP (login tool; no API key needed). Includes Coin mutual funds.
    val kiteMcp get() = c.kiteMcp
    val kiteMcpBusy = MutableStateFlow(false)
    /** Shown inside the Kite card, so failures are never silent. */
    val kiteMcpError = MutableStateFlow<String?>(null)
    fun beginKiteMcpLogin(onUrl: (String) -> Unit) = viewModelScope.launch {
        kiteMcpBusy.value = true; kiteMcpError.value = null
        runCatching { kiteMcp.beginLogin() }.onSuccess(onUrl)
            .onFailure { com.capitol.app.diagnostics.Diagnostics.error("KiteMCP", it.message ?: "error", it); kiteMcpError.value = it.message ?: "Couldn't connect" }
        kiteMcpBusy.value = false
    }
    fun completeKiteMcpLogin(redirectUrl: String? = null) = viewModelScope.launch {
        kiteMcpBusy.value = true; kiteMcpError.value = null
        runCatching { kiteMcp.completeLogin(redirectUrl) }
            .onSuccess { message.value = "Connected to Zerodha — loading stocks and Coin mutual funds"; refresh() }
            .onFailure { com.capitol.app.diagnostics.Diagnostics.error("KiteMCP", it.message ?: "error", it); kiteMcpError.value = it.message ?: "Sign-in failed" }
        kiteMcpBusy.value = false
    }
    fun kiteMcpDisconnect() { kiteMcp.disconnect(); message.value = "Disconnected Kite MCP"; refresh() }
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
    var kiteMcpUrl by remember { mutableStateOf<String?>(null) }
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
        if (st.cards.isNotEmpty()) item { CreditCardsCard(st.cards) {
            onAskAi("Using INDmoney, look at my credit card bills (networth_snapshot liabilities): amounts, due dates, and which to pay first.")
        } }
        if (st.holdings.isNotEmpty()) {
            item { SummaryCard(st.totalsByCurrency) {
                onAskAi("Review my portfolio holdings technically. Which positions look strongest and weakest, and what levels should I watch?")
            } }
            item { SectionTitle("Holdings (${st.holdings.size})") }
            item {
                val analytics by vm.analytics.collectAsStateWithLifecycle()
                val loading by vm.analyticsLoading.collectAsStateWithLifecycle()
                if (loading && analytics.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                analytics.forEach { a -> AnalyticsCard(a); Spacer(Modifier.height(12.dp)) }
            }
            items(st.holdings, key = { it.broker.name + it.tradingSymbol + it.exchange + (it.folio ?: "") }) { h -> HoldingRow(h) { onAnalyze(h.yahooSymbol) } }
        }
        item { SectionTitle("Connections") }
        item { secretsV.let { KiteMcpCard(vm, onLogin = { kiteMcpUrl = it }, onAskAi = onAskAi) } }
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

    kiteMcpUrl?.let { url ->
        val oauthMode = vm.kiteMcp.usesOAuth
        OAuthWebDialog("Log in to Kite", url,
            // OAuth: Zerodha redirects to capitol://kite-mcp-callback. login-tool mode: the user taps Done.
            isRedirect = { u -> com.capitol.app.data.mcp.KiteMcp.isRedirect(u) ||
                (!oauthMode && u.contains("mcp.kite.trade") && !u.contains("/login") && u.contains("status=success")) },
            onRedirect = { r -> kiteMcpUrl = null; vm.completeKiteMcpLogin(r.takeIf { oauthMode }) },
            onDismiss = { kiteMcpUrl = null },
            onDone = if (oauthMode) null else ({ kiteMcpUrl = null; vm.completeKiteMcpLogin() }))
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

/** Credit card bills from INDmoney — read-only; pay in your bank or INDmoney app. */
@Composable
private fun CreditCardsCard(cards: List<com.capitol.app.data.broker.CreditCardBill>, onAskAi: () -> Unit) {
    CapitolCard {
        Text("Credit card bills · INDmoney", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedNumber(cards.sumOf { it.totalDue }, fontSize = 28, format = { "₹%,.2f".format(it) })
        Text("Total due across ${cards.size} card${if (cards.size > 1) "s" else ""}", style = MaterialTheme.typography.bodySmall)
        cards.forEach { c ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    val days = c.daysUntilDue()
                    val pre = c.last4?.let { "••$it · " } ?: ""
                    val (txt, col) = when {
                        c.totalDue <= 0 -> "${pre}nothing due" to MaterialTheme.colorScheme.onSurfaceVariant
                        days == null -> pre.removeSuffix(" · ") to MaterialTheme.colorScheme.onSurfaceVariant
                        days < 0 -> "${pre}overdue by ${-days} day${if (days < -1) "s" else ""}" to CapitolColors.market.bear
                        days == 0L -> "${pre}due today" to CapitolColors.market.bear
                        days <= 3 -> "${pre}due in $days day${if (days > 1) "s" else ""}" to CapitolColors.market.bear
                        else -> "${pre}due ${c.dueDate!!.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))} (in $days days)" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(txt, style = MaterialTheme.typography.bodySmall, color = col)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("₹%,.2f".format(c.totalDue), style = NumberStyle.copy(fontSize = 14.sp))
                    c.minDue?.let { Text("min ₹%,.0f".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Read-only — pay in your bank or INDmoney app. Autopilot reminds you 3 days before and on the due date.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onAskAi) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Ask AI") }
    }
}

@Composable
private fun HoldingRow(h: Holding, onClick: () -> Unit) {
    // Mutual funds have no chart to open.
    CapitolCard(Modifier.fillMaxWidth().then(if (h.isMutualFund) Modifier else Modifier.clickable(onClick = onClick)), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (h.isMutualFund) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MF", style = MaterialTheme.typography.labelSmall, color = CapitolColors.market.chart3,
                        modifier = Modifier.background(CapitolColors.market.chart3.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(h.displayName ?: h.tradingSymbol, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                } else Text(h.tradingSymbol, style = MaterialTheme.typography.titleSmall)
                Text(if (h.isMutualFund) "${h.broker.label}${h.folio?.let { " · folio $it" } ?: ""} · ${"%,.3f".format(h.quantity)} units @ ${"%,.2f".format(h.averagePrice)}"
                    else "${h.broker.label} · ${h.exchange} · ${"%.4g".format(h.quantity)} @ ${"%,.2f".format(h.averagePrice)}",
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
private fun KiteMcpCard(vm: PortfolioViewModel, onLogin: (String) -> Unit, onAskAi: (String) -> Unit) {
    val busy by vm.kiteMcpBusy.collectAsStateWithLifecycle()
    val error by vm.kiteMcpError.collectAsStateWithLifecycle()
    CapitolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Zerodha via Kite MCP · recommended", style = MaterialTheme.typography.titleMedium)
                Text("Stocks + Coin mutual funds · no API key needed", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.kiteMcp.isConnected) Icon(Icons.Default.CheckCircle, "Connected", tint = CapitolColors.market.bull)
        }
        Spacer(Modifier.height(4.dp))
        if (vm.kiteMcp.isConnected) {
            Text("Connected through Zerodha's official Kite MCP server (read-only). Kite logins last a day — reconnect when asked.",
                style = MaterialTheme.typography.bodySmall)
            Row {
                Button({ onAskAi("Review my Zerodha portfolio including my Coin mutual funds: allocation, performance and the technical picture of my biggest stock positions.") }) {
                    Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Ask AI")
                }
                TextButton({ vm.beginKiteMcpLogin(onLogin) }) { Text("Re-login") }
                TextButton(vm::kiteMcpDisconnect) { Text("Disconnect") }
            }
        } else {
            Text("Log in to Kite once and Capitol reads your stock holdings and Coin mutual funds through Zerodha's official MCP server. Capitol never places orders.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button({ vm.beginKiteMcpLogin(onLogin) }, enabled = !busy) { Text(if (busy) "Connecting…" else "Connect Zerodha (Kite MCP)") }
        }
        error?.let { ErrorBanner(it, Modifier.padding(top = 8.dp)) }
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
            Text("Connected. Your INDmoney Indian and US stocks show in this tab (pull to refresh), and the AI agent can analyse them.",
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
private fun OAuthWebDialog(title: String, url: String, isRedirect: (String) -> Boolean, onRedirect: (String) -> Unit, onDismiss: () -> Unit,
                           onDone: (() -> Unit)? = null) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onDismiss) { Text("Cancel") }
                onDone?.let { Button(it) { Text("Done") } }
            }
            onDone?.let {
                Text("Log in with your Kite user ID, password and TOTP. When Zerodha says you're logged in, tap Done.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
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

@Composable
private fun AnalyticsCard(a: com.capitol.app.analysis.CurrencyAnalytics) {
    val mc = CapitolColors.market
    val primary = MaterialTheme.colorScheme.primary
    val palette = listOf(primary, mc.chart2, mc.chart3, mc.chart4, mc.bull, mc.neutral, mc.grid.copy(alpha = 0.6f))
    val sym = if (a.currency == "USD") "$" else "₹"
    CapitolCard {
        Text("Portfolio analytics · ${if (a.currency == "USD") "US stocks" else "Indian stocks"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                com.capitol.app.ui.components.DonutChart(a.byHolding.mapIndexed { i, s -> s.value to palette[i % palette.size] }, Modifier.size(120.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.health.size.coerceAtLeast(a.byHolding.size)}", style = MaterialTheme.typography.titleLarge)
                    Text("holdings", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                a.byHolding.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(palette[i % palette.size], androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("%.1f%%".format(s.pct), style = NumberStyle.copy(fontSize = 12.sp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Largest", "%.0f%%".format(a.largestPct), Modifier.weight(1f))
            Metric("Top 5", "%.0f%%".format(a.top5Pct), Modifier.weight(1f))
            Metric("Diversification", "${a.diversificationScore}/100", Modifier.weight(1f))
        }
        a.technicalScore?.let { ts ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Technical health", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("%+.2f".format(ts), style = NumberStyle.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                com.capitol.app.ui.components.SignalChip(com.capitol.app.analysis.Signal.fromScore(ts))
            }
        }
        if (a.bySector.size > 1 || a.bySector.firstOrNull()?.label != "Unknown") {
            Spacer(Modifier.height(10.dp))
            Text("Sectors", style = MaterialTheme.typography.labelLarge)
            a.bySector.take(6).forEach { s ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp), maxLines = 1)
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth((s.pct / 100).toFloat().coerceIn(0.02f, 1f)).height(6.dp)
                            .background(primary, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                    }
                    Text("  %.0f%%".format(s.pct), style = NumberStyle.copy(fontSize = 11.sp))
                }
            }
        }
        if (a.valueHistory.size > 2) {
            Spacer(Modifier.height(10.dp))
            Row {
                Text("Value of current holdings · 1 year", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                a.oneYearChangePct?.let { Text("%+.1f%%".format(it), color = com.capitol.app.ui.components.changeColor(it), style = NumberStyle.copy(fontSize = 12.sp)) }
            }
            com.capitol.app.ui.components.IndicatorPane(listOf(com.capitol.app.analysis.ChartLine("Value", a.valueHistory.map { it.second }, com.capitol.app.analysis.LineRole.PRIMARY)), height = 110.dp)
            Text("Uses today's quantities at past prices — not your actual buy/sell history.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (a.winners.isNotEmpty() || a.losers.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Best: " + a.winners.joinToString { "${it.shortLabel} %+.0f%%".format(it.pnlPercent) }.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = mc.bull)
            Text("Worst: " + a.losers.joinToString { "${it.shortLabel} %+.0f%%".format(it.pnlPercent) }.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = mc.bear)
        }
        a.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = mc.neutral, modifier = Modifier.padding(top = 4.dp)) }
        Text("Total $sym${"%,.0f".format(a.total)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = NumberStyle.copy(fontSize = 14.sp))
    }
}
