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
import jorlan.connector.InvocationContext
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object MarketDataSkillSpec extends ZIOSpecDefault {

  /** Sample Alpha Vantage GLOBAL_QUOTE response body. */
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

  /** Sample Alpha Vantage rate-limit Note response. */
  private val rateLimitBody: String =
    """|{
       |  "Note": "Thank you for using Alpha Vantage! Our standard API rate limit is 25 requests per day."
       |}""".stripMargin

  /** Sample Alpha Vantage SYMBOL_SEARCH response. */
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

  /** Build a stub [[Client]] that always returns a fixed body. */
  private def stubClient(body: String): ZLayer[Any, Nothing, Client] = {
    val stubResponse = Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.application.json).untyped),
      body = Body.fromString(body),
    )
    ZLayer.succeed(
      new Client {
        override def socket[Env1 <: Any](
          version: Version,
          url:     URL,
          headers: Headers,
          app:     WebSocketApp[Env1],
        )(implicit
          trace: Trace,
        ): ZIO[Env1 & Scope, Throwable, Response] = ZIO.succeed(stubResponse)

        override def request(
          version:   Version,
          method:    Method,
          url:       URL,
          headers:   Headers,
          body:      Body,
          sslConfig: Option[ClientSSLConfig],
          proxy:     Option[Proxy],
        )(implicit
          trace: Trace,
        ): ZIO[Scope, Throwable, Response] = ZIO.succeed(stubResponse)
      },
    )
  }

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MarketDataSkillSpec")(
      test("returns error JSON immediately when apiKey is empty (no HTTP call made)") {
        // A stub that always fails if called — proves we short-circuit without HTTP
        val neverCalledClient: ZLayer[Any, Nothing, Client] = ZLayer.succeed(
          new Client {
            override def socket[Env1 <: Any](
              version: Version,
              url:     URL,
              headers: Headers,
              app:     WebSocketApp[Env1],
            )(implicit
              trace: Trace,
            ): ZIO[Env1 & Scope, Throwable, Response] =
              ZIO.fail(new RuntimeException("should not be called"))

            override def request(
              version:   Version,
              method:    Method,
              url:       URL,
              headers:   Headers,
              body:      Body,
              sslConfig: Option[ClientSSLConfig],
              proxy:     Option[Proxy],
            )(implicit
              trace: Trace,
            ): ZIO[Scope, Throwable, Response] =
              ZIO.fail(new RuntimeException("should not be called"))
          },
        )

        for {
          clientSvc <- ZIO.service[Client]
          skill = new MarketDataSkill("", clientSvc)
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(
          equalTo(Json.Obj("error" -> Json.Str("Alpha Vantage API key not configured"))),
        )
      }.provide(neverCalledClient),
      test("parses a GLOBAL_QUOTE response into the simplified schema") {
        for {
          clientSvc <- ZIO.service[Client]
          skill = new MarketDataSkill("dummy-key", clientSvc)
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
      }.provide(stubClient(sampleQuoteBody)),
      test("returns rate-limit error when Alpha Vantage Note is present") {
        for {
          clientSvc <- ZIO.service[Client]
          skill = new MarketDataSkill("dummy-key", clientSvc)
          result <- skill.invoke(dummyCtx, "market.quote", Json.Obj("symbol" -> Json.Str("AAPL")))
        } yield assert(result)(
          equalTo(Json.Obj("error" -> Json.Str("Rate limit exceeded, please wait before retrying"))),
        )
      }.provide(stubClient(rateLimitBody)),
      test("parses a SYMBOL_SEARCH response and returns top match") {
        for {
          clientSvc <- ZIO.service[Client]
          skill = new MarketDataSkill("dummy-key", clientSvc)
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
      }.provide(stubClient(sampleSearchBody)),
      test("fails with ValidationError for unknown tool name") {
        for {
          clientSvc <- ZIO.service[Client]
          skill = new MarketDataSkill("dummy-key", clientSvc)
          result <- skill.invoke(dummyCtx, "market.unknown", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(stubClient("{}")),
    )

}
