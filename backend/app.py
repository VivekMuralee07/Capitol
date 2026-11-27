"""
Capitol yfinance service.

A thin HTTP wrapper around https://github.com/ranaroussi/yfinance so the Capitol
Android app can use yfinance directly (Settings → Market data → "yfinance service").

Run:  pip install -r requirements.txt && uvicorn app:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import math
from functools import lru_cache
from typing import Any

import yfinance as yf
from fastapi import FastAPI, HTTPException, Query

app = FastAPI(title="Capitol yfinance service", version="1.0.0")

VALID_PERIODS = {"1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max"}
VALID_INTERVALS = {"1m", "2m", "5m", "15m", "30m", "60m", "90m", "1h", "1d", "5d", "1wk", "1mo", "3mo"}


def _num(x: Any) -> float | None:
    try:
        f = float(x)
        return None if math.isnan(f) or math.isinf(f) else f
    except (TypeError, ValueError):
        return None


@lru_cache(maxsize=512)
def _meta(symbol: str) -> dict[str, str]:
    """Name/currency/exchange (cached: Ticker.info is slow)."""
    try:
        info = yf.Ticker(symbol).info or {}
    except Exception:  # yfinance raises a variety of errors for odd symbols
        info = {}
    return {
        "name": info.get("longName") or info.get("shortName") or symbol,
        "currency": info.get("currency") or "",
        "exchange": info.get("fullExchangeName") or info.get("exchange") or "",
    }


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "yfinance": yf.__version__}


@app.get("/history")
def history(
    symbol: str = Query(..., min_length=1, max_length=32),
    period: str = "1y",
    interval: str = "1d",
    days: int | None = Query(None, ge=1, le=3650),
) -> dict[str, Any]:
    """Price history by yfinance period, or by a calendar-day lookback (`days`, e.g. 7 or 1095)."""
    if (days is None and period not in VALID_PERIODS) or interval not in VALID_INTERVALS:
        raise HTTPException(400, "invalid period or interval")
    symbol = symbol.strip().upper()
    ticker = yf.Ticker(symbol)
    if days is not None:
        from datetime import datetime, timedelta, timezone
        start = datetime.now(timezone.utc) - timedelta(days=days)
        df = ticker.history(start=start, interval=interval, auto_adjust=False)
    else:
        df = ticker.history(period=period, interval=interval, auto_adjust=False)
    if df is None or df.empty:
        raise HTTPException(404, f"no data for {symbol}")

    candles = []
    for ts, row in df.iterrows():
        close = _num(row.get("Close"))
        if close is None:
            continue
        candles.append({
            "time": int(ts.timestamp()),
            "open": _num(row.get("Open")) or close,
            "high": _num(row.get("High")) or close,
            "low": _num(row.get("Low")) or close,
            "close": close,
            "volume": _num(row.get("Volume")) or 0.0,
        })

    price = prev = None
    try:
        fi = ticker.fast_info
        price = _num(fi.last_price)
        prev = _num(fi.previous_close)
    except Exception:
        pass

    return {"symbol": symbol, **_meta(symbol), "price": price, "previous_close": prev, "candles": candles}


@app.get("/quotes")
def quotes(symbols: str = Query(..., min_length=1, max_length=2000)) -> dict[str, Any]:
    """Latest price and day change for many symbols (yfinance fast_info)."""
    out = []
    for sym in [s.strip().upper() for s in symbols.split(",") if s.strip()][:100]:
        try:
            fi = yf.Ticker(sym).fast_info
            price, prev = _num(fi.last_price), _num(fi.previous_close)
            if price is None:
                continue
            change = price - prev if prev else 0.0
            out.append({"symbol": sym, "price": price, "change": change,
                        "change_percent": (change / prev * 100) if prev else 0.0,
                        "day_high": _num(fi.day_high), "day_low": _num(fi.day_low),
                        "volume": _num(fi.last_volume), "currency": fi.currency})
        except Exception:
            continue
    return {"quotes": out}


@app.get("/search")
def search(q: str = Query(..., min_length=1, max_length=64)) -> dict[str, Any]:
    try:
        quotes = yf.Search(q, max_results=12, news_count=0).quotes
    except Exception as e:  # network / parsing errors from Yahoo
        raise HTTPException(502, str(e)) from e
    return {
        "results": [
            {
                "symbol": r.get("symbol", ""),
                "name": r.get("longname") or r.get("shortname") or r.get("symbol", ""),
                "exchange": r.get("exchDisp") or r.get("exchange") or "",
                "type": r.get("quoteType") or "",
            }
            for r in quotes
            if r.get("symbol")
        ]
    }


@app.get("/info")
def info(symbol: str = Query(..., min_length=1, max_length=32)) -> dict[str, Any]:
    """Selected fundamentals from yfinance's Ticker.info, in the shape the app expects."""
    sym = symbol.strip().upper()
    try:
        d = yf.Ticker(sym).info or {}
    except Exception as e:
        raise HTTPException(502, str(e)) from e
    return {
        "symbol": sym,
        "name": d.get("longName") or d.get("shortName"),
        "sector": d.get("sector"),
        "industry": d.get("industry"),
        "currency": d.get("currency"),
        "marketCap": _num(d.get("marketCap")),
        "trailingPE": _num(d.get("trailingPE")),
        "forwardPE": _num(d.get("forwardPE")),
        "priceToBook": _num(d.get("priceToBook")),
        "dividendYield": _num(d.get("dividendYield")),
        "beta": _num(d.get("beta")),
        "fiftyTwoWeekHigh": _num(d.get("fiftyTwoWeekHigh")),
        "fiftyTwoWeekLow": _num(d.get("fiftyTwoWeekLow")),
        "averageVolume": _num(d.get("averageVolume")),
        "profitMargins": _num(d.get("profitMargins")),
        "returnOnEquity": _num(d.get("returnOnEquity")),
        "debtToEquity": _num(d.get("debtToEquity")),
        "summary": d.get("longBusinessSummary"),
        **_calendar(sym),
    }


def _ts(v: Any) -> int | None:
    """date/datetime/epoch → epoch seconds."""
    from datetime import date, datetime, timezone
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return int(v)
    if isinstance(v, datetime):
        return int(v.timestamp())
    if isinstance(v, date):
        return int(datetime(v.year, v.month, v.day, tzinfo=timezone.utc).timestamp())
    return None


def _calendar(sym: str) -> dict[str, Any]:
    """Next earnings / dividend dates from yfinance's Ticker.calendar."""
    try:
        cal = yf.Ticker(sym).calendar or {}
    except Exception:
        return {}
    earnings = cal.get("Earnings Date") or []
    if not isinstance(earnings, list):
        earnings = [earnings]
    stamps = [t for t in (_ts(e) for e in earnings) if t]
    return {
        "nextEarningsDate": min(stamps) if stamps else None,
        "exDividendDate": _ts(cal.get("Ex-Dividend Date")),
        "dividendDate": _ts(cal.get("Dividend Date")),
    }


@app.get("/news")
def news(symbol: str = Query(..., min_length=1, max_length=32)) -> dict[str, Any]:
    """Recent headlines from yfinance's Ticker.news (handles old and new item shapes)."""
    try:
        items = yf.Ticker(symbol.strip().upper()).news or []
    except Exception as e:
        raise HTTPException(502, str(e)) from e
    out = []
    for it in items[:10]:
        c = it.get("content") or it
        link = (c.get("canonicalUrl") or {}).get("url") if isinstance(c.get("canonicalUrl"), dict) else c.get("link")
        title = c.get("title")
        if not title or not link:
            continue
        published = c.get("providerPublishTime") or 0
        if not published and c.get("pubDate"):
            from datetime import datetime
            try:
                published = int(datetime.fromisoformat(c["pubDate"].replace("Z", "+00:00")).timestamp())
            except ValueError:
                published = 0
        provider = c.get("provider") if isinstance(c.get("provider"), dict) else {}
        out.append({"title": title, "publisher": c.get("publisher") or provider.get("displayName", ""),
                    "link": link, "publishedAt": int(published)})
    return {"news": out}
