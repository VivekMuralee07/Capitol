# Capitol

This is a financial tool developed to do technical analysis of any stocks listed in stock market.

Capitol is a native **Kotlin / Jetpack Compose** Android app. It pulls market data from Yahoo Finance, either on-device through the same endpoints [yfinance](https://github.com/ranaroussi/yfinance) uses or through a small bundled Python service that calls yfinance itself. It runs 21 technical indicators on any stock, index, ETF, currency or crypto, and explains each one in plain English.

| Home (dark) | Home (light) | Analysis (dark) | Analysis (light) |
|---|---|---|---|
| ![](app/screenshots/home_dark.png) | ![](app/screenshots/home_light.png) | ![](app/screenshots/analysis_dark.png) | ![](app/screenshots/analysis_light.png) |

*(Screenshots are rendered from synthetic data by `ScreenshotTest`.)*

## Features

- **Animated home page**: moving "aurora" background, a scrolling ticker tape, a hero index chart (NIFTY 50 / SENSEX / BANK NIFTY / S&P 500 / NASDAQ) with an animated reveal and drag-to-inspect crosshair, a *market mood* gauge with a spring-animated needle, an advance/decline breadth bar, global market cards with sparklines, a watchlist signal heatmap and top movers. Cards enter with staggered animations.
- **Technical analysis of any symbol** (`RELIANCE.NS`, `TCS.BO`, `^NSEI`, `AAPL`, `BTC-USD` …) over 1D to 5Y, on candlestick or line charts with indicator overlays and sub-panes.
- **Indicator filter with multi-select.** Pick any combination of 21 indicators in 6 categories, or use a preset (Swing trader, Trend, Momentum, Volume, Everything). For each selected indicator Capitol shows:
  - a signal (Strong Buy to Strong Sell) and the current metrics
  - **"What it says now"**: an interpretation written from the live values, e.g. "RSI is 72.4 (up from 65.1)… overbought…"
  - an explainer covering what the indicator measures and how to read it
  - a combined verdict, an agreement score, category scores, key support/resistance levels, and a summary that explains how *your selected* indicators agree or conflict
- **Indicators:** SMA 20/50/200 (golden/death cross), EMA 12/26, MACD, RSI (with divergence detection), Stochastic, Bollinger Bands (squeeze detection), ATR (stop suggestions), ADX/DMI, OBV, VWAP, CCI, Williams %R, MFI, Ichimoku Cloud, Parabolic SAR, ROC, Supertrend, Classic Pivots, Fibonacci retracement, Volume analysis, Candlestick patterns.
- **Watchlist**: add from search, swipe to delete, sort by signal, per-symbol price alerts.
- **AI Agent (Claude)**: a chat agent that runs Capitol's own tools on-device. Its tools are symbol search, technical analysis with any indicator set, price summaries, watchlist scan/add/remove, and portfolio read. Ask things like *"Scan my watchlist and rank the strongest setups"* or *"Analyse INFY with RSI, MACD and Supertrend"*.
- **Autopilot**: a background WorkManager job that re-analyses the watchlist every N hours and notifies you when a verdict changes or a price alert fires. If an API key is set, Claude summarises the changes.
- **Broker connections (read-only, for analysis)**:
  - **Zerodha Kite Connect v3**: in-app login, holdings, positions and margins.
  - **INDmoney** through the INDstocks API (access token), plus a **CSV import** fallback for any broker.
  - Holdings map to Yahoo symbols (`NSE → .NS`, `BSE → .BO`), so you can open any holding in the analyser or ask the agent to review the whole portfolio.
- **In-app updates**:
  - Installed from Google Play: the Play Core *flexible* in-app update flow.
  - Sideloaded: Capitol checks this repo's latest **GitHub Release**, downloads the APK inside the app with a progress bar, and opens the system installer.
- **Dark and light mode**: System / Light / Dark setting, a quick toggle on the home page, and optional Material You colours on Android 12+.

## Project layout

```
app/src/main/java/com/capitol/app/
├── analysis/      Indicator math (Indicators.kt), TechnicalAnalyzer, models & explanations
├── data/market/   Yahoo Finance (yfinance endpoints) + yfinance-service data sources, repository
├── data/broker/   Kite Connect + INDmoney clients, CSV import, portfolio repository
├── data/prefs/    DataStore settings, encrypted secret store
├── data/watchlist Watchlist persistence
├── agent/         Claude agent (tool-use loop), tool definitions, autopilot worker
├── update/        Play in-app updates + GitHub Releases self-updater
└── ui/            Compose screens: home, analysis, watchlist, agent, portfolio, settings, charts
backend/           FastAPI wrapper around yfinance (optional)
```

## Building

Requirements: JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest         # indicator, analyzer, parser and screenshot tests
./gradlew testDebugUnitTest -Pscreenshots   # regenerate app/screenshots/*.png
```

## Configuration

### Market data
*Settings → Market data*
- **Direct (default)**: on-device calls to Yahoo's `/v8/finance/chart` and `/v1/finance/search`, the endpoints yfinance uses. No server needed.
- **yfinance service**: run the Python service and enter its URL. `10.0.2.2` reaches your computer from the Android emulator.
  ```bash
  cd backend && pip install -r requirements.txt && uvicorn app:app --host 0.0.0.0 --port 8000
  # or: docker build -t capitol-yf backend && docker run -p 8000:8000 capitol-yf
  ```
  Endpoints: `/history?symbol=&period=&interval=`, `/search?q=`, `/info?symbol=`, `/health`.

### AI agent
*Settings → AI Agent*: paste an Anthropic API key from <https://console.anthropic.com>. The key is stored encrypted with the Android Keystore and is only sent to `api.anthropic.com`. The agent uses the official `anthropic-java` SDK with model `claude-opus-5-5` and the server-side refusal fallback.

### Zerodha Kite
1. Create an app at <https://developers.kite.trade>. Set the redirect URL to `capitol://kite-callback`. Capitol reads the `request_token` from the in-app login page, so any redirect URL works.
2. In *Portfolio → Zerodha Kite*, enter the API key and secret, then tap **Log in with Kite**.
3. Kite access tokens expire every morning (about 6 AM IST); log in again when asked.

> The token exchange needs your Kite **API secret**. Capitol keeps it encrypted on the device. If you'd rather keep the secret off the phone, run the exchange on your own server.

### INDmoney
Generate a token at indstocks.com → API → *Generate Token* and paste it in *Portfolio → INDmoney*. Tokens are short-lived. If you set a static IP for the token, requests must come from that IP. Without API access, export your holdings and use **Import holdings CSV**.

### Releases and in-app updates
GitHub Actions (`.github/workflows/android.yml`) runs tests on every push and PR. Pushing a tag like `v1.1.0` builds a release APK (versionName from the tag, versionCode from the run number) and publishes it as a GitHub Release. Installed apps find it through the in-app updater.

For updates to install over an existing app, every release must be signed with the **same key**. Add these repository secrets: `CAPITOL_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `CAPITOL_KEYSTORE_PASSWORD`, `CAPITOL_KEY_ALIAS`, `CAPITOL_KEY_PASSWORD`. Without them, CI falls back to a debug key.

## Disclaimer
Capitol provides educational technical analysis based on historical price data. It is **not investment advice**. Data may be delayed or inaccurate, and Yahoo Finance data is intended for personal use. Capitol never places orders.
