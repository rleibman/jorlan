/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.market

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object MarketDataSkillSpec extends ZIOSpecDefault {

  private val sampleQuoteBody: String =
    """|{
       |  "Global Quote": {
       |    "01. symbol": "AAPL",
       |    "02. open": "182.00",
       |    "03. high": "186.00",
       |    "04. low": "181.50",
       |    "05. price": "185.50",
       |    "06. volume": "45678901",
       |    "07. latest trading day": "2024-01-15",
       |    "08. previous close": "184.27",
       |    "09. change": "+1.23",
       |    "10. change percent": "+0.67%"
       |  }
       |}""".stripMargin

  private val rateLimitBody: String =
    """|{
       |  "Note": "Thank you for using Alpha Vantage! Our standard API rate limit is 25 requests per day."
       |}""".stripMargin

  private val sampleNewsBody: String =
    """|{
       |  "feed": [
       |    {
       |      "title": "Apple Reports Record Earnings",
       |      "url": "https://example.com/apple-earnings",
       |      "summary": "Apple exceeded analyst expectations...",
       |      "overall_sentiment_label": "Bullish",
       |      "ticker_sentiment": [
       |        {
       |          "ticker": "AAPL",
       |          "relevance_score": "0.95",
       |          "ticker_sentiment_label": "Bullish"
       |        }
       |      ]
       |    },
       |    {
       |      "title": "Tech Sector Outlook",
       |      "url": "https://example.com/tech-outlook",
       |      "summary": "The tech sector continues to...",
       |      "overall_sentiment_label": "Neutral",
       |      "ticker_sentiment": [
       |        {
       |          "ticker": "MSFT",
       |          "relevance_score": "0.50",
       |          "ticker_sentiment_label": "Neutral"
       |        }
       |      ]
       |    }
       |  ]
       |}""".stripMargin

  private val informationRateLimitBody: String =
    """|{
       |  "Information": "Thank you for using Alpha Vantage! Our standard API rate limit is..."
       |}""".stripMargin

  private val sampleSearchBody: String =
    """|{
       |  "bestMatches": [
       |    {
       |      "1. symbol": "AAPL",
       |      "2. name": "Apple Inc",
       |      "3. type": "Equity",
       |      "4. region": "United States",
       |      "5. marketOpen": "09:30",
       |      "6. marketClose": "16:00",
       |      "7. timezone": "UTC-04",
       |      "8. currency": "USD",
       |      "9. matchScore": "1.0000"
       |    }
       |  ]
       |}""".stripMargin

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  private def fixedBodyRoutes(body: String): Routes[Any, Nothing] =
    Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          _: Request,
        ) =>
          Response(
            status = Status.Ok,
            headers = Headers(Header.ContentType(MediaType.application.json).untyped),
            body = Body.fromString(body),
          )
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MarketDataSkillSpec")(
      test("returns error JSON immediately when apiKey is empty (no HTTP call made)") {
        for {
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(AlphaVantageConfig(), client)
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(
          equalTo(Json.Obj("error" -> Json.Str("Alpha Vantage API key not configured"))),
        )
      }.provide(Client.default),
      test("parses a GLOBAL_QUOTE response into the simplified schema") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleQuoteBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield {
          val fields = result match {
            case Json.Obj(fs) => fs.toMap
            case _            => Map.empty
          }
          assert(fields.get("symbol"))(isSome(equalTo(Json.Str("AAPL")))) &&
          assert(fields.get("price"))(isSome(equalTo(Json.Str("185.50")))) &&
          assert(fields.get("change"))(isSome(equalTo(Json.Str("+1.23")))) &&
          assert(fields.get("changePercent"))(isSome(equalTo(Json.Str("+0.67%")))) &&
          assert(fields.get("volume"))(isSome(equalTo(Json.Str("45678901")))) &&
          assert(fields.get("latestTradingDay"))(isSome(equalTo(Json.Str("2024-01-15"))))
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("returns rate-limit error when Alpha Vantage Note is present") {
        for {
          port   <- Server.install(fixedBodyRoutes(rateLimitBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(
          equalTo(Json.Obj("error" -> Json.Str("Rate limit exceeded, please wait before retrying"))),
        )
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("parses a SYMBOL_SEARCH response and returns top match") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleSearchBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.search", Json.Obj("query" -> Json.Str("Apple")))
        } yield result match {
          case Json.Arr(items) =>
            assert(items.length)(equalTo(1)) &&
            assert(items.head)(
              equalTo(
                Json.Obj(
                  "symbol" -> Json.Str("AAPL"),
                  "name"   -> Json.Str("Apple Inc"),
                  "type"   -> Json.Str("Equity"),
                  "region" -> Json.Str("United States"),
                ),
              ),
            )
          case _ => assert(result)(equalTo(Json.Arr()))
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("fails with ValidationError for unknown tool name") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.unknown", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.news returns parsed news items with per-ticker sentiment") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleNewsBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.news", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield result match {
          case Json.Arr(items) =>
            assert(items.length)(equalTo(2)) && {
              val firstFields = items.head match {
                case Json.Obj(fs) => fs.toMap
                case _            => Map.empty
              }
              assert(firstFields.get("title"))(isSome(equalTo(Json.Str("Apple Reports Record Earnings")))) &&
              assert(firstFields.get("sentiment"))(isSome(equalTo(Json.Str("Bullish"))))
            }
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.news returns empty array when apiKey is blank") {
        for {
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(AlphaVantageConfig(), client)
          result <- skill.invoke(dummyCtx, "market.news", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(equalTo(Json.Obj("error" -> Json.Str("Alpha Vantage API key not configured"))))
      }.provide(Client.default),
      test("returns rate-limit error for Information key variant") {
        for {
          port   <- Server.install(fixedBodyRoutes(informationRateLimitBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(
          equalTo(Json.Obj("error" -> Json.Str("Rate limit exceeded, please wait before retrying"))),
        )
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("dashboardData returns quotes for SPY DIA GLD") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleQuoteBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.dashboardData(dummyCtx)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("quotes", Json.Arr(items)) => items.length == 3; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("dashboardData returns error when apiKey is blank") {
        for {
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(AlphaVantageConfig(), client)
          result <- skill.dashboardData(dummyCtx)
        } yield assert(result)(equalTo(Json.Obj("error" -> Json.Str("No API key configured"))))
      }.provide(Client.default),
      test("market.search returns empty array for non-matching response") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.search", Json.Obj("query" -> Json.Str("Unknown")))
        } yield assert(result)(equalTo(Json.Arr()))
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.quote returns error when Global Quote is absent") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("error", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      // ─── market.watchlist ────────────────────────────────────────────────
      test("market.watchlist returns quotes for default SPY/DIA/GLD tickers") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleQuoteBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.watchlist", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("quotes", Json.Arr(items)) => items.length == 3; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.watchlist uses configured preferredStocks with colon format") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleQuoteBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(
              apiKey = "dummy-key",
              baseUrl = s"http://localhost:$port/query",
              preferredStocks = List("AAPL:Apple Inc", "TSLA"),
            ),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.watchlist", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("quotes", Json.Arr(items)) => items.length == 2; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.watchlist fetchQuote falls back to N/A when Global Quote absent") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(
              apiKey = "dummy-key",
              baseUrl = s"http://localhost:$port/query",
              preferredStocks = List("AAPL"),
            ),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.watchlist", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val quotes = fields.collectFirst { case ("quotes", Json.Arr(q)) => q }.getOrElse(Chunk.empty)
            val firstPrice = quotes.headOption.flatMap {
              case Json.Obj(fs) => fs.collectFirst { case ("price", Json.Str(p)) => p }
              case _            => None
            }
            assertTrue(firstPrice.contains("N/A"))
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.watchlist catchAll produces N/A when HTTP request fails") {
        for {
          port   <- Server.install(fixedBodyRoutes("not-json-at-all"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(
              apiKey = "dummy-key",
              baseUrl = s"http://localhost:$port/query",
              preferredStocks = List("AAPL"),
            ),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.watchlist", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val quotes = fields.collectFirst { case ("quotes", Json.Arr(q)) => q }.getOrElse(Chunk.empty)
            val firstPrice = quotes.headOption.flatMap {
              case Json.Obj(fs) => fs.collectFirst { case ("price", Json.Str(p)) => p }
              case _            => None
            }
            assertTrue(firstPrice.contains("N/A"))
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      // ─── missing required fields ──────────────────────────────────────────
      test("market.quote fails with JorlanError when symbol field is missing") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.search fails with JorlanError when query field is missing") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.search", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.news fails with JorlanError when symbol field is missing") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.news", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.quote fails when args is not a JSON object") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Str("bad")).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      // ─── HTTP error responses ──────────────────────────────────────────────
      test("market.quote fails with JorlanError on HTTP 500") {
        for {
          port <- Server.install(
            Routes(
              Method.ANY / trailing -> handler {
                (
                  _: Path,
                  _: Request,
                ) =>
                  Response(status = Status.InternalServerError, body = Body.fromString("server error"))
              },
            ),
          )
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.quote fails with JorlanError on invalid JSON body") {
        for {
          port   <- Server.install(fixedBodyRoutes("not valid json!!!"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      // ─── market.news: news feed edge cases ────────────────────────────────
      test("market.news returns empty array when feed field is absent") {
        for {
          port   <- Server.install(fixedBodyRoutes("{}"))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.news", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(equalTo(Json.Arr()))
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.news uses Neutral sentiment when ticker not found in ticker_sentiment") {
        val noMatchNewsBody: String =
          """|{
             |  "feed": [
             |    {
             |      "title": "Generic Tech News",
             |      "url": "https://example.com/tech",
             |      "summary": "Technology sector update",
             |      "ticker_sentiment": [
             |        {
             |          "ticker": "MSFT",
             |          "relevance_score": "0.5",
             |          "ticker_sentiment_label": "Bullish"
             |        }
             |      ]
             |    }
             |  ]
             |}""".stripMargin
        for {
          port   <- Server.install(fixedBodyRoutes(noMatchNewsBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.news", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield result match {
          case Json.Arr(items) =>
            val sentiment = items.headOption.flatMap {
              case Json.Obj(fs) => fs.collectFirst { case ("sentiment", Json.Str(s)) => s }
              case _            => None
            }
            assertTrue(sentiment.contains("Neutral"))
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.news rate limit returns error") {
        for {
          port   <- Server.install(fixedBodyRoutes(rateLimitBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.news", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(
          equalTo(Json.Obj("error" -> Json.Str("Rate limit exceeded, please wait before retrying"))),
        )
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("market.search rate limit returns error") {
        for {
          port   <- Server.install(fixedBodyRoutes(rateLimitBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.invoke(dummyCtx, "market.search", Json.Obj("query" -> Json.Str("Apple")))
        } yield assert(result)(
          equalTo(Json.Obj("error" -> Json.Str("Rate limit exceeded, please wait before retrying"))),
        )
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      // ─── validate() ──────────────────────────────────────────────────────
      test("validate returns not-ok when apiKey is empty") {
        for {
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(AlphaVantageConfig(), client)
          result <- skill.validate()
        } yield assertTrue(!result.ok)
      }.provide(Client.default),
      test("validate returns ok when HTTP call succeeds") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleQuoteBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.validate()
        } yield assertTrue(result.ok)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("validate returns not-ok when HTTP call fails") {
        for {
          port <- Server.install(
            Routes(
              Method.ANY / trailing -> handler {
                (
                  _: Path,
                  _: Request,
                ) =>
                  Response(status = Status.InternalServerError, body = Body.fromString("error"))
              },
            ),
          )
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(apiKey = "dummy-key", baseUrl = s"http://localhost:$port/query"),
            client,
          )
          result <- skill.validate()
        } yield assertTrue(!result.ok)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      // ─── dashboardData with configured preferredStocks ───────────────────
      test("dashboardData uses configured preferredStocks with colon format") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleQuoteBody))
          client <- ZIO.service[Client]
          skill = new MarketDataSkill(
            AlphaVantageConfig(
              apiKey = "dummy-key",
              baseUrl = s"http://localhost:$port/query",
              preferredStocks = List("AAPL:Apple Inc", "MSFT:Microsoft"),
            ),
            client,
          )
          result <- skill.dashboardData(dummyCtx)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("quotes", Json.Arr(items)) => items.length == 2; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
    ) @@ TestAspect.withLiveClock

}
