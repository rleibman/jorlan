# Market Data Skill

Fetches real-time stock quotes, ticker search, and financial news from [Alpha Vantage](https://www.alphavantage.co/).

**Skill name:** `market`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Provides an agent with live stock market data, company search, and news sentiment analysis.

## Tools

| Tool            | Description                                      |
|-----------------|--------------------------------------------------|
| `market.quote`  | Real-time stock quote for a ticker symbol        |
| `market.search` | Search for tickers matching a keyword            |
| `market.news`   | Latest news headlines and sentiment for a ticker |

### `market.quote`

**Input:** `{ "symbol": "AAPL" }`

**Output:** price, change, changePercent, volume, latestTradingDay

### `market.search`

**Input:** `{ "keywords": "Apple" }`

**Output:** up to 5 results with symbol, name, type, region

### `market.news`

**Input:** `{ "symbol": "AAPL", "limit": 5 }`

**Output:** title, url, summary, sentiment (Bullish/Bearish/Neutral), relevanceScore

---

## Capabilities required

| Capability    | Tools     |
|---------------|-----------|
| `market.read` | All tools |

---

## Example prompts

- "What is the current price of Apple stock?"
- "How is Tesla doing today?"
- "Search for semiconductor ETFs"
- "What's the latest news about NVIDIA?"
- "What is the S&P 500 doing today?"

---

## Configuration

`configKey`: `skill.market`  
`configJsModule`: `jorlan-market-data`

```json
{
  "apiKey": "",
  "baseUrl": "https://www.alphavantage.co/query"
}
```

| Field     | Type   | Default                             | Description                         |
|-----------|--------|-------------------------------------|-------------------------------------|
| `apiKey`  | string | `""`                                | **Required.** Alpha Vantage API key |
| `baseUrl` | string | `https://www.alphavantage.co/query` | API base URL (do not change)        |

See [INSTALL.md](INSTALL.md) for setup instructions.
