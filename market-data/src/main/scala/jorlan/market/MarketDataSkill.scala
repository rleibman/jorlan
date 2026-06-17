/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.market

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Built-in skill for fetching market data via the Alpha Vantage API.
  *
  * Exposes three tools:
  *   - `market.quote` — real-time quote for a ticker symbol
  *   - `market.search` — symbol search by keyword
  *   - `market.news` — news sentiment for a ticker symbol
  *
  * All tools require the `market.read` capability. When the API key is absent every call returns an error JSON without
  * making a network request.
  */
class MarketDataSkill(
  apiKey:  String,
  client:  Client,
  baseUrl: String = "https://www.alphavantage.co/query",
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "market",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "market.quote",
        description = "Fetch a real-time stock quote for a ticker symbol. Returns price, change, change percentage, volume, and the latest trading day.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"symbol":{"type":"string","description":"Ticker symbol, e.g. AAPL"}},"required":["symbol"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("market.read")),
        examplePrompts = List(
          "What is the current price of Apple stock?",
          "Get a quote for TSLA",
          "How is MSFT trading today?",
        ),
      ),
      ToolDescriptor(
        name = "market.search",
        description = "Search for ticker symbols matching a keyword query. Returns up to 5 matching securities with symbol, name, type, and region.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"query":{"type":"string","description":"Search keyword, e.g. Apple or Tesla"}},"required":["query"]}""",
          ).getOrElse(Json.Obj()),
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
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"symbol":{"type":"string","description":"Ticker symbol, e.g. AAPL"}},"required":["symbol"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("market.read")),
        examplePrompts = List(
          "What is the latest news about Apple?",
          "Any news on Tesla today?",
          "Show me recent headlines for AMZN",
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
        case "market.quote"  => quote(args)
        case "market.search" => search(args)
        case "market.news"   => news(args)
        case other           => ZIO.fail(ValidationError(s"MarketDataSkill: unknown tool '$other'"))
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
      url = s"$baseUrl?function=GLOBAL_QUOTE&symbol=${java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8)}&apikey=$apiKey"
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
      url = s"$baseUrl?function=SYMBOL_SEARCH&keywords=${java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)}&apikey=$apiKey"
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
      url = s"$baseUrl?function=NEWS_SENTIMENT&tickers=${java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8)}&apikey=$apiKey&limit=5"
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

}

object MarketDataSkill {

  case class AlphaVantageConfig(
    apiKey:  String,
    baseUrl: String = "https://www.alphavantage.co/query",
  )

  object AlphaVantageConfig {
    given JsonDecoder[AlphaVantageConfig] = DeriveJsonDecoder.gen[AlphaVantageConfig]
  }

}
