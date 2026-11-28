package com.capitol.app.data.broker

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PortfolioState(
    val holdings: List<Holding> = emptyList(),
    val errors: List<String> = emptyList(),
    val loading: Boolean = false,
    val updatedAt: Long? = null,
    /** Credit card bills from INDmoney (read-only). */
    val cards: List<CreditCardBill> = emptyList(),
) {
    val totalCardDue get() = cards.sumOf { it.totalDue }

    /** Totals per currency — INR and USD holdings are never summed together. */
    val totalsByCurrency: List<CurrencyTotal> get() = holdings.groupBy { it.currency }.map { (ccy, hs) ->
        CurrencyTotal(ccy, hs.sumOf { it.invested }, hs.sumOf { it.current })
    }.sortedBy { if (it.currency == "INR") 0 else 1 }
}

data class CurrencyTotal(val currency: String, val invested: Double, val current: Double) {
    val pnl get() = current - invested
    val pnlPercent get() = if (invested == 0.0) 0.0 else pnl / invested * 100
    val symbol get() = when (currency) { "INR" -> "₹"; "USD" -> "$"; else -> "$currency " }
}

class PortfolioRepository(
    val kite: KiteConnectClient,
    val indmoney: IndMoneyClient,
    val kiteMcp: com.capitol.app.data.mcp.KiteMcp? = null,
    val indmoneyMcp: com.capitol.app.data.mcp.IndMoneyMcp? = null,
) {
    private val _state = MutableStateFlow(PortfolioState())
    val state: StateFlow<PortfolioState> = _state.asStateFlow()
    private var imported: List<Holding> = emptyList()

    suspend fun refresh() = coroutineScope {
        _state.value = _state.value.copy(loading = true, errors = emptyList())
        // Brokers connect only through their official MCP servers (Zerodha Kite MCP, INDmoney MCP).
        // The older API-key clients (Kite Connect, INDstocks) are no longer offered in the app, so
        // any token left over from them is ignored rather than overriding the MCP connections.
        val k = async { if (kiteMcp?.isConnected == true) runCatching { kiteMcp.holdings() } else Result.success(emptyList()) }
        val i = async { if (indmoneyMcp?.isConnected == true) runCatching { indmoneyMcp.holdings() } else Result.success(emptyList()) }
        // Coin mutual funds via Kite MCP.
        val mf = async { if (kiteMcp?.isConnected == true) runCatching { kiteMcp.mfHoldings() } else Result.success(emptyList()) }
        val cc = async { if (indmoneyMcp?.isConnected == true) runCatching { indmoneyMcp.creditCards() } else Result.success(emptyList()) }
        val kr = k.await(); val ir = i.await(); val mr = mf.await()
        val cr = cc.await().onFailure { com.capitol.app.diagnostics.Diagnostics.error("Broker", "credit cards: ${it.message}", it) }
        listOfNotNull(kr.exceptionOrNull(), ir.exceptionOrNull(), mr.exceptionOrNull()).forEach { com.capitol.app.diagnostics.Diagnostics.error("Broker", it.message ?: "error", it) }
        _state.value = PortfolioState(
            holdings = kr.getOrDefault(emptyList()) + mr.getOrDefault(emptyList()) + ir.getOrDefault(emptyList()) + imported,
            errors = listOfNotNull(kr.exceptionOrNull()?.message, ir.exceptionOrNull()?.message, mr.exceptionOrNull()?.message?.let { "Mutual funds: $it" }),
            loading = false,
            updatedAt = System.currentTimeMillis(),
            cards = cr.getOrDefault(emptyList()).sortedWith(compareBy(nullsLast()) { it.dueDate }),
        )
    }

    /**
     * Imports a holdings CSV. Re-importing replaces earlier imported holdings of the
     * same market (India or US), so an Indian and a US file can both be loaded.
     */
    fun importCsv(text: String, usStocks: Boolean = false): Int {
        val previous = imported
        val parsed = IndMoneyClient.parseCsv(text, if (usStocks) "US" else "NSE")
        val markets = parsed.map { it.isUs }.toSet()
        imported = previous.filterNot { it.isUs in markets } + parsed
        _state.value = _state.value.copy(holdings = _state.value.holdings.filter { it !in previous } + imported)
        return parsed.size
    }

    val anyConnected get() = kiteMcp?.isConnected == true || indmoneyMcp?.isConnected == true || imported.isNotEmpty()
}
