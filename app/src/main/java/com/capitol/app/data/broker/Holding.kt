package com.capitol.app.data.broker

enum class Broker(val label: String) { KITE("Zerodha Kite"), INDMONEY("INDmoney") }

data class Holding(
    val broker: Broker,
    val tradingSymbol: String,
    val exchange: String,
    val quantity: Double,
    val averagePrice: Double,
    val lastPrice: Double,
    val dayChangePercent: Double?,
) {
    val invested get() = quantity * averagePrice
    val current get() = quantity * lastPrice
    val pnl get() = current - invested
    val pnlPercent get() = if (invested == 0.0) 0.0 else pnl / invested * 100

    /** Symbol in yfinance / Yahoo notation (NSE → .NS, BSE → .BO). */
    val yahooSymbol: String get() = when (exchange.uppercase()) {
        "NSE" -> "$tradingSymbol.NS"
        "BSE" -> "$tradingSymbol.BO"
        else -> tradingSymbol
    }
}

class BrokerException(message: String) : Exception(message)
