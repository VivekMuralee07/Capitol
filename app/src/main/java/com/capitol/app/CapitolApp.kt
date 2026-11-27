package com.capitol.app

import android.app.Application
import android.content.Context
import com.capitol.app.agent.AgentTools
import com.capitol.app.agent.AutopilotWorker
import com.capitol.app.agent.CapitolAgent
import com.capitol.app.data.broker.IndMoneyClient
import com.capitol.app.data.broker.KiteConnectClient
import com.capitol.app.data.broker.PortfolioRepository
import com.capitol.app.data.mcp.IndMoneyMcp
import com.capitol.app.data.market.MarketRepository
import com.capitol.app.data.market.YFinanceBackendDataSource
import com.capitol.app.data.market.YahooFinanceDataSource
import com.capitol.app.data.prefs.SecretStore
import com.capitol.app.data.prefs.SettingsStore
import com.capitol.app.data.watchlist.WatchlistStore
import com.capitol.app.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container — small enough that a DI framework isn't needed. */
class AppContainer(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val settings = SettingsStore(context)
    val secrets = SecretStore(context)
    val watchlist = WatchlistStore(context)

    private val backendUrl: String
        get() = runBlocking { settings.settings.first().backendUrl }

    val market = MarketRepository(
        YahooFinanceDataSource(http),
        YFinanceBackendDataSource(http) { backendUrl },
        settings,
        java.io.File(context.cacheDir, "market"),
    )
    private val yahooForLive = YahooFinanceDataSource(http)
    private val backendForLive = YFinanceBackendDataSource(http) { backendUrl }
    val live = com.capitol.app.data.live.LiveQuotes(
        com.capitol.app.data.live.YahooStreamer(http),
        source = {
            if (settings.settings.first().dataSource == com.capitol.app.data.prefs.DataSourceMode.YFINANCE_SERVICE) backendForLive else yahooForLive
        },
    )
    val portfolio = PortfolioRepository(KiteConnectClient(http, secrets), IndMoneyClient(http, secrets))
    val indmoneyMcp = IndMoneyMcp(http, secrets)
    val agentTools = AgentTools(market, watchlist, portfolio, indmoneyMcp)
    val agent = CapitolAgent(secrets, agentTools)
    init { agentTools.analyticsLoader = { h -> com.capitol.app.analysis.PortfolioLoader.load(this, h) } }
    val chats = com.capitol.app.agent.ChatStore(java.io.File(context.filesDir, "chats"))
    val updates = UpdateManager(context, http)
}

class CapitolApp : Application() {
    lateinit var container: AppContainer
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        com.capitol.app.diagnostics.Diagnostics.init(this)
        container = AppContainer(this)
        AutopilotWorker.ensureChannel(this)
        appScope.launch {
            container.watchlist.data.map { it.activeList }.distinctUntilChanged().collect {
                com.capitol.app.widget.WatchlistWidget.refresh(this@CapitolApp)
            }
        }
        appScope.launch {
            container.settings.settings
                .map { Triple(it.agentAutoScan, it.agentScanHours, it.defaultIndicators to it.indicatorParams) }
                .distinctUntilChanged()
                .collect { (on, hours, ip) ->
                    container.agentTools.defaultIndicators = ip.first
                    container.agentTools.params = ip.second
                    container.settings.settings.first().let { container.agentTools.tradeCapital = it.tradeCapital; container.agentTools.tradeRiskPct = it.tradeRiskPct }
                    AutopilotWorker.schedule(this@CapitolApp, on, hours)
                }
        }
    }
}
