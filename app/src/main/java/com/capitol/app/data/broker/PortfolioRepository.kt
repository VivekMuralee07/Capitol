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
) {
    val invested get() = holdings.sumOf { it.invested }
    val current get() = holdings.sumOf { it.current }
    val pnl get() = current - invested
    val pnlPercent get() = if (invested == 0.0) 0.0 else pnl / invested * 100
}

class PortfolioRepository(val kite: KiteConnectClient, val indmoney: IndMoneyClient) {
    private val _state = MutableStateFlow(PortfolioState())
    val state: StateFlow<PortfolioState> = _state.asStateFlow()
    private var imported: List<Holding> = emptyList()

    suspend fun refresh() = coroutineScope {
        _state.value = _state.value.copy(loading = true, errors = emptyList())
        val k = async { if (kite.isConnected) runCatching { kite.holdings() } else Result.success(emptyList()) }
        val i = async { if (indmoney.isConnected) runCatching { indmoney.holdings() } else Result.success(emptyList()) }
        val kr = k.await(); val ir = i.await()
        _state.value = PortfolioState(
            holdings = kr.getOrDefault(emptyList()) + ir.getOrDefault(emptyList()) + imported,
            errors = listOfNotNull(kr.exceptionOrNull()?.message, ir.exceptionOrNull()?.message),
            loading = false,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun importCsv(text: String): Int {
        val previous = imported
        imported = IndMoneyClient.parseCsv(text)
        _state.value = _state.value.copy(holdings = _state.value.holdings.filter { it !in previous } + imported)
        return imported.size
    }

    val anyConnected get() = kite.isConnected || indmoney.isConnected || imported.isNotEmpty()
}
