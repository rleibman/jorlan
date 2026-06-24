/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.market

import jorlan.*
import jorlan.connector.{HasDashboardData, HasValidation, InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Built-in skill for fetching market data via the Alpha Vantage API.
  *
  * Exposes three tools:
  *   - `market.quote` — real-time quote for a ticker symbol
  *   - `market.search` — symbol search by keyword
  *   - `market.news` — news sentiment for a ticker symbol
  *   - `market.watchlist` — quotes for all configured preferred stocks
  *
  * All tools require the `market.read` capability. When the API key is absent every call returns an error JSON without
  * making a network request.
  */
class MarketDataSkill(
  config: AlphaVantageConfig,
  client: Client,
) extends Skill with HasDashboardData with HasValidation {

  private val apiKey = config.apiKey
  private val baseUrl = config.baseUrl

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "market",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "stocks",
      "market",
      "finance",
      "trading",
      "price",
      "portfolio",
      "investment",
      "shares",
      "equity",
      "ticker",
      "quote",
      "dividend",
      "NASDAQ",
      "NYSE",
      "stock price",
      "earnings",
      "company",
      "index",
      "indices",
      "S&P",
      "S&P 500",
      "Dow Jones",
      "closing price",
      "close",
      "market cap",
      "ETF",
      "fund",
      "today",
      "current price",
    ),
    configKey = Some("skill.market"),
    configJsModule = Some("jorlan-market-data"),
    tools = List(
      ToolDescriptor(
        name = "market.quote",
        description = "Fetch a real-time stock quote for a ticker symbol. Returns price, change, change percentage, volume, and the latest trading day.",
        inputSchema = json"""{"type":"object","properties":{"symbol":{"type":"string","description":"Ticker symbol, e.g. AAPL"}},"required":["symbol"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("market.read")),
        examplePrompts = List(
          "What is the current price of Apple stock?",
          "Get a quote for TSLA",
          "How is MSFT trading today?",
          "What did the S&P 500 close at today?",
          "What is the Dow Jones index at right now?",
        ),
      ),
      ToolDescriptor(
        name = "market.search",
        description = "Search for ticker symbols matching a keyword query. Returns up to 5 matching securities with symbol, name, type, and region.",
        inputSchema = json"""{"type":"object","properties":{"query":{"type":"string","description":"Search keyword, e.g. Apple or Tesla"}},"required":["query"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("market.read")),
        examplePrompts = List(
          "Search for Amazon stock symbol",
          "Find the ticker for Tesla",
          "What is the symbol for Alphabet?",
        ),
      ),
      ToolDescriptor(
        name = "market.news",
        description = "Fetch the latest news headlines and sentiment for a ticker symbol. Returns up to 5 recent news items with title, URL, summary, sentiment, and relevance score.",
        inputSchema = json"""{"type":"object","properties":{"symbol":{"type":"string","description":"Ticker symbol, e.g. AAPL"}},"required":["symbol"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("market.read")),
        examplePrompts = List(
          "What is the latest news about Apple?",
          "Any news on Tesla today?",
          "Show me recent headlines for AMZN",
        ),
      ),
      ToolDescriptor(
        name = "market.watchlist",
        description = "Get quotes for all preferred/watchlist stocks configured for this skill. Use this to answer general market questions like 'what's the market doing?' or 'how is my watchlist performing?'",
        inputSchema = json"""{"type":"object","properties":{},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("market.read")),
        examplePrompts = List(
          "What's the market doing today?",
          "How is my watchlist performing?",
          "Show me my preferred stocks",
          "Give me a market overview",
          "How are stocks doing?",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    if (apiKey.isBlank) {
      ZIO.succeed(Json.Obj("error" -> Json.Str("Alpha Vantage API key not configured")))
    } else {
      tool match {
        case "market.quote"     => quote(args)
        case "market.search"    => search(args)
        case "market.news"      => news(args)
        case "market.watchlist" => watchlist()
        case other              => ZIO.fail(ValidationError(s"MarketDataSkill: unknown tool '$other'"))
      }
    }
  }

  private def field(
    args: Json,
    name: String,
  ): IO[JorlanError, String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`name`, Json.Str(v)) => v }
          .fold(ZIO.fail(ValidationError(s"missing field '$name'")): IO[JorlanError, String])(ZIO.succeed(_))
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  private def fetchJson(url: String): IO[JorlanError, Json] =
    client
      .batched(Request.get(url))
      .mapError(e => JorlanError("HTTP error", Some(e)))
      .flatMap { resp =>
        resp.body.asString
          .mapError(e => JorlanError("Failed to read Alpha Vantage response body", Some(e)))
          .flatMap { body =>
            if (!resp.status.isSuccess)
              ZIO.fail(JorlanError(s"Alpha Vantage HTTP ${resp.status.code}: $body"))
            else
              ZIO
                .fromEither(Json.decoder.decodeJson(body))
                .mapError(err => JorlanError(s"Failed to parse Alpha Vantage response: $err"))
          }
      }

  private def checkRateLimit(json: Json): IO[JorlanError, Json] =
    json match {
      case Json.Obj(fields) if fields.exists { case ("Note", _) => true; case _ => false } =>
        ZIO.succeed(Json.Obj("error" -> Json.Str("Rate limit exceeded, please wait before retrying")))
      case Json.Obj(fields) if fields.exists { case ("Information", _) => true; case _ => false } =>
        ZIO.succeed(Json.Obj("error" -> Json.Str("Rate limit exceeded, please wait before retrying")))
      case other => ZIO.succeed(other)
    }

  private def quote(args: Json): IO[JorlanError, Json] =
    for {
      symbol <- field(args, "symbol")
      url =
        s"$baseUrl?function=GLOBAL_QUOTE&symbol=${java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8)}&apikey=$apiKey"
      raw     <- fetchJson(url)
      limited <- checkRateLimit(raw)
    } yield limited match {
      case obj @ Json.Obj(fields) if fields.exists { case ("error", _) => true; case _ => false } => obj
      case Json.Obj(fields)                                                                       =>
        fields
          .collectFirst { case ("Global Quote", Json.Obj(quote)) => quote }
          .map { quote =>
            def qfield(key: String): Json =
              quote.collectFirst { case (`key`, v) => v }.getOrElse(Json.Str(""))

            Json.Obj(
              "symbol"           -> qfield("01. symbol"),
              "price"            -> qfield("05. price"),
              "change"           -> qfield("09. change"),
              "changePercent"    -> qfield("10. change percent"),
              "volume"           -> qfield("06. volume"),
              "latestTradingDay" -> qfield("07. latest trading day"),
            )
          }
          .getOrElse(Json.Obj("error" -> Json.Str("No quote data returned")))
      case other => other
    }

  private def search(args: Json): IO[JorlanError, Json] =
    for {
      query <- field(args, "query")
      url =
        s"$baseUrl?function=SYMBOL_SEARCH&keywords=${java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)}&apikey=$apiKey"
      raw     <- fetchJson(url)
      limited <- checkRateLimit(raw)
    } yield limited match {
      case obj @ Json.Obj(fields) if fields.exists { case ("error", _) => true; case _ => false } => obj
      case Json.Obj(fields)                                                                       =>
        fields
          .collectFirst { case ("bestMatches", Json.Arr(matches)) => matches }
          .map { matches =>
            Json.Arr(
              matches.take(5).collect { case Json.Obj(m) =>
                def mfield(key: String): Json =
                  m.collectFirst { case (`key`, v) => v }.getOrElse(Json.Str(""))

                Json.Obj(
                  "symbol" -> mfield("1. symbol"),
                  "name"   -> mfield("2. name"),
                  "type"   -> mfield("3. type"),
                  "region" -> mfield("4. region"),
                )
              }*,
            )
          }
          .getOrElse(Json.Arr())
      case _ => Json.Arr()
    }

  private def news(args: Json): IO[JorlanError, Json] =
    for {
      symbol <- field(args, "symbol")
      url =
        s"$baseUrl?function=NEWS_SENTIMENT&tickers=${java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8)}&apikey=$apiKey&limit=5"
      raw     <- fetchJson(url)
      limited <- checkRateLimit(raw)
    } yield limited match {
      case obj @ Json.Obj(fields) if fields.exists { case ("error", _) => true; case _ => false } => obj
      case Json.Obj(fields)                                                                       =>
        fields
          .collectFirst { case ("feed", Json.Arr(items)) => items }
          .map { items =>
            Json.Arr(
              items.take(5).collect { case Json.Obj(item) =>
                def ifield(key: String): Json =
                  item.collectFirst { case (`key`, v) => v }.getOrElse(Json.Str(""))

                // Find the per-ticker sentiment score for the requested symbol
                val tickerSentiment: Option[String] = item
                  .collectFirst { case ("ticker_sentiment", Json.Arr(ts)) => ts }
                  .flatMap { ts =>
                    ts.collectFirst {
                      case Json.Obj(t)
                          if t
                            .collectFirst { case ("ticker", Json.Str(s)) => s }
                            .exists(_.equalsIgnoreCase(symbol)) =>
                        t.collectFirst { case ("ticker_sentiment_label", Json.Str(l)) => l }
                          .getOrElse("Neutral")
                    }
                  }

                val relevanceScore: Json = item
                  .collectFirst { case ("ticker_sentiment", Json.Arr(ts)) => ts }
                  .flatMap { ts =>
                    ts.collectFirst {
                      case Json.Obj(t)
                          if t
                            .collectFirst { case ("ticker", Json.Str(s)) => s }
                            .exists(_.equalsIgnoreCase(symbol)) =>
                        t.collectFirst { case ("relevance_score", v) => v }
                    }.flatten
                  }
                  .getOrElse(Json.Num(0.0))

                Json.Obj(
                  "title"          -> ifield("title"),
                  "url"            -> ifield("url"),
                  "summary"        -> ifield("summary"),
                  "sentiment"      -> Json.Str(tickerSentiment.getOrElse("Neutral")),
                  "relevanceScore" -> relevanceScore,
                )
              }*,
            )
          }
          .getOrElse(Json.Arr())
      case _ => Json.Arr()
    }

  private def fetchQuote(
    symbol: String,
    name:   String,
  ): IO[JorlanError, Json] = {
    val url =
      s"$baseUrl?function=GLOBAL_QUOTE&symbol=${java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8)}&apikey=$apiKey"
    fetchJson(url)
      .flatMap(checkRateLimit)
      .map {
        case Json.Obj(fields) if fields.exists { case ("error", _) => true; case _ => false } =>
          val msg = fields.collectFirst { case ("error", Json.Str(s)) => s }.getOrElse("API error")
          Json.Obj(
            "symbol"        -> Json.Str(symbol),
            "name"          -> Json.Str(name),
            "price"         -> Json.Str("N/A"),
            "change"        -> Json.Str(""),
            "changePercent" -> Json.Str(""),
            "error"         -> Json.Str(msg),
          )
        case Json.Obj(fields) =>
          fields
            .collectFirst { case ("Global Quote", Json.Obj(q)) => q }
            .map { q =>
              def qfield(key: String): Json = q.collectFirst { case (`key`, v) => v }.getOrElse(Json.Str(""))
              Json.Obj(
                "symbol"        -> Json.Str(symbol),
                "name"          -> Json.Str(name),
                "price"         -> qfield("05. price"),
                "change"        -> qfield("09. change"),
                "changePercent" -> qfield("10. change percent"),
              )
            }
            .getOrElse(
              Json.Obj(
                "symbol"        -> Json.Str(symbol),
                "name"          -> Json.Str(name),
                "price"         -> Json.Str("N/A"),
                "change"        -> Json.Str(""),
                "changePercent" -> Json.Str(""),
              ),
            )
        case other => other
      }
      .catchAll(_ =>
        ZIO.succeed(
          Json.Obj(
            "symbol"        -> Json.Str(symbol),
            "name"          -> Json.Str(name),
            "price"         -> Json.Str("N/A"),
            "change"        -> Json.Str(""),
            "changePercent" -> Json.Str(""),
          ),
        ),
      )
  }

  private def watchlist(): IO[JorlanError, Json] = {
    val tickers = activeDashboardTickers
    if (tickers.isEmpty)
      ZIO.succeed(Json.Obj("quotes" -> Json.Arr(), "message" -> Json.Str("No preferred stocks configured")))
    else
      ZIO
        .foreach(tickers.zipWithIndex) { case ((symbol, name), idx) =>
          // Small delay between requests to avoid Alpha Vantage burst rate limits
          (if (idx == 0) ZIO.unit else ZIO.sleep(250.millis)) *> fetchQuote(symbol, name)
        }
        .map(quotes => Json.Obj("quotes" -> Json.Arr(quotes*)))
  }

  private val defaultDashboardTickers = List(
    ("SPY", "S&P 500"),
    ("DIA", "Dow Jones"),
    ("GLD", "Gold"),
  )

  private def parseTicker(s: String): (String, String) = {
    val trimmed = s.trim
    val colonIdx = trimmed.indexOf(':')
    if (colonIdx > 0) (trimmed.take(colonIdx).trim.toUpperCase, trimmed.drop(colonIdx + 1).trim)
    else (trimmed.toUpperCase, trimmed.toUpperCase)
  }

  private def activeDashboardTickers: List[(String, String)] = {
    val parsed = config.preferredStocks.map(_.trim).filter(_.nonEmpty).map(parseTicker).filter(_._1.nonEmpty)
    if (parsed.nonEmpty) parsed else defaultDashboardTickers
  }

  override def dashboardData(ctx: InvocationContext): IO[JorlanError, Json] = {
    if (apiKey.isBlank)
      ZIO.succeed(Json.Obj("error" -> Json.Str("No API key configured")))
    else
      ZIO
        .foreach(activeDashboardTickers.zipWithIndex) { case ((symbol, name), idx) =>
          // Small delay between requests to avoid Alpha Vantage burst rate limits
          (if (idx == 0) ZIO.unit else ZIO.sleep(250.millis)) *> fetchQuote(symbol, name)
        }
        .map(quotes => Json.Obj("quotes" -> Json.Arr(quotes*)))
  }

  override def validate(): IO[JorlanError, SkillValidationResult] = {
    if (apiKey.isBlank)
      ZIO.succeed(SkillValidationResult(ok = false, message = "API key is not configured"))
    else {
      val url =
        s"$baseUrl?function=GLOBAL_QUOTE&symbol=IBM&apikey=$apiKey"
      fetchJson(url)
        .flatMap(checkRateLimit)
        .as(SkillValidationResult(ok = true, message = "OK — Alpha Vantage API key is valid"))
        .catchAll(e => ZIO.succeed(SkillValidationResult(ok = false, message = e.msg)))
    }
  }

}
