package com.capitol.app.data.broker

enum class Broker(val label: String) { KITE("Zerodha Kite"), INDMONEY("INDmoney"), COIN("Zerodha Coin") }

enum class AssetKind { EQUITY, MUTUAL_FUND }

data class Holding(
    val broker: Broker,
    val tradingSymbol: String,
    val exchange: String,
    val quantity: Double,
    val averagePrice: Double,
    val lastPrice: Double,
    val dayChangePercent: Double?,
    val kind: AssetKind = AssetKind.EQUITY,
    /** Full scheme name for mutual funds (tradingSymbol is the ISIN). */
    val displayName: String? = null,
    val folio: String? = null,
) {
    val isMutualFund get() = kind == AssetKind.MUTUAL_FUND
    val invested get() = quantity * averagePrice
    val current get() = quantity * lastPrice
    val pnl get() = current - invested
    val pnlPercent get() = if (invested == 0.0) 0.0 else pnl / invested * 100

    val isUs: Boolean get() = isUsExchange(exchange)
    val currency: String get() = if (isUs) "USD" else "INR"

    /** Symbol in yfinance / Yahoo notation (NSE → .NS, BSE → .BO, US tickers unchanged). */
    val yahooSymbol: String get() = when {
        isUs -> tradingSymbol
        exchange.equals("BSE", true) -> "$tradingSymbol.BO"
        else -> "$tradingSymbol.NS"
    }

    companion object {
        private val US_EXCHANGES = setOf("US", "USA", "NASDAQ", "NYSE", "AMEX", "NYSEARCA", "ARCA", "BATS", "NMS", "NYQ", "NGM", "NCM")
        fun isUsExchange(ex: String) = ex.trim().uppercase().replace(" ", "") in US_EXCHANGES
    }
}

class BrokerException(message: String) : Exception(message)
