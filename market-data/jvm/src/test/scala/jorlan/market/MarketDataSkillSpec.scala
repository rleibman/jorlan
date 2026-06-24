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
    ) @@ TestAspect.withLiveClock

}
