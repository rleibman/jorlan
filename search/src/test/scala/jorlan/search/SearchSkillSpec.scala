/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.search

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object SearchSkillSpec extends ZIOSpecDefault {

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  private val sampleSearchResponse: String =
    """|{
       |  "results": [
       |    {
       |      "title": "ZIO Functional Effects",
       |      "url": "https://zio.dev/overview",
       |      "content": "ZIO is a library for asynchronous and concurrent programming in Scala.",
       |      "score": 0.95
       |    },
       |    {
       |      "title": "ZIO Documentation",
       |      "url": "https://zio.dev/docs",
       |      "content": "Comprehensive guide to ZIO effects and fibers.",
       |      "score": 0.88
       |    }
       |  ],
       |  "answer": "ZIO is a powerful functional effects library."
       |}""".stripMargin

  private val sampleExtractResponse: String =
    """|{
       |  "results": [
       |    {
       |      "url": "https://zio.dev",
       |      "raw_content": "Full page content extracted from the URL."
       |    }
       |  ],
       |  "failed_results": []
       |}""".stripMargin

  private val errorResponse: String =
    """|{"detail": "Invalid API Key. Please refer to https://docs.tavily.com for getting a valid API key."}""".stripMargin

  private def routes(
    body:   String,
    status: Status = Status.Ok,
  ): Routes[Any, Nothing] =
    Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          _: Request,
        ) =>
          Response(
            status = status,
            headers = Headers(Header.ContentType(MediaType.application.json).untyped),
            body = Body.fromString(body),
          )
      },
    )

  private def captureRequestRoutes(
    bodyRef: Ref[Option[String]],
    resp:    String,
  ): Routes[Any, Nothing] =
    Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          req: Request,
        ) =>
          (for {
            body <- req.body.asString
            _    <- bodyRef.set(Some(body))
          } yield Response(
            status = Status.Ok,
            headers = Headers(Header.ContentType(MediaType.application.json).untyped),
            body = Body.fromString(resp),
          )).orDie
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SearchSkillSpec")(
      test("search.web returns structured results with title, url, content, score") {
        for {
          port   <- Server.install(routes(sampleSearchResponse))
          client <- ZIO.service[Client]
          cfg     = SearchConfig(apiKey = "test-key", baseUrl = s"http://localhost:$port", maxResults = 5)
          skill   = new SearchSkill(cfg, client)
          result <- skill.invoke(dummyCtx, "search.web", Json.Obj("query" -> Json.Str("ZIO")))
        } yield result match {
          case Json.Arr(items) =>
            assert(items.length)(equalTo(2)) &&
            assert(items.head)(
              equalTo(
                Json.Obj(
                  "title"   -> Json.Str("ZIO Functional Effects"),
                  "url"     -> Json.Str("https://zio.dev/overview"),
                  "content" -> Json.Str("ZIO is a library for asynchronous and concurrent programming in Scala."),
                  "score"   -> Json.Num(0.95),
                ),
              ),
            )
          case other => assert(other)(equalTo(Json.Arr()))
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("search.web respects maxResults param in request body") {
        for {
          bodyRef <- Ref.make(Option.empty[String])
          port    <- Server.install(captureRequestRoutes(bodyRef, sampleSearchResponse))
          client  <- ZIO.service[Client]
          cfg      = SearchConfig(apiKey = "test-key", baseUrl = s"http://localhost:$port", maxResults = 5)
          skill    = new SearchSkill(cfg, client)
          _       <- skill.invoke(
            dummyCtx,
            "search.web",
            Json.Obj("query" -> Json.Str("ZIO"), "maxResults" -> Json.Num(3)),
          )
          captured <- bodyRef.get
        } yield assert(captured)(isSome(containsString("\"max_results\":3")))
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("search.news sends topic=news in request body") {
        for {
          bodyRef <- Ref.make(Option.empty[String])
          port    <- Server.install(captureRequestRoutes(bodyRef, sampleSearchResponse))
          client  <- ZIO.service[Client]
          cfg      = SearchConfig(apiKey = "test-key", baseUrl = s"http://localhost:$port", maxResults = 5)
          skill    = new SearchSkill(cfg, client)
          _       <- skill.invoke(dummyCtx, "search.news", Json.Obj("query" -> Json.Str("AI news")))
          captured <- bodyRef.get
        } yield assert(captured)(isSome(containsString("\"topic\":\"news\"")))
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("search.extract calls /extract and returns url/content pairs") {
        for {
          port   <- Server.install(routes(sampleExtractResponse))
          client <- ZIO.service[Client]
          cfg     = SearchConfig(apiKey = "test-key", baseUrl = s"http://localhost:$port", maxResults = 5)
          skill   = new SearchSkill(cfg, client)
          result <- skill.invoke(
            dummyCtx,
            "search.extract",
            Json.Obj("urls" -> Json.Arr(Json.Str("https://zio.dev"))),
          )
        } yield result match {
          case Json.Arr(items) =>
            assert(items.length)(equalTo(1)) &&
            assert(items.head)(
              equalTo(
                Json.Obj(
                  "url"     -> Json.Str("https://zio.dev"),
                  "content" -> Json.Str("Full page content extracted from the URL."),
                ),
              ),
            )
          case other => assert(other)(equalTo(Json.Arr()))
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("search.web missing required query arg fails with ValidationError") {
        for {
          port   <- Server.install(routes(sampleSearchResponse))
          client <- ZIO.service[Client]
          cfg     = SearchConfig(apiKey = "test-key", baseUrl = s"http://localhost:$port", maxResults = 5)
          skill   = new SearchSkill(cfg, client)
          result <- skill.invoke(dummyCtx, "search.web", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("HTTP 401 bad API key fails with JorlanError containing status code") {
        for {
          port   <- Server.install(routes(errorResponse, Status.Unauthorized))
          client <- ZIO.service[Client]
          cfg     = SearchConfig(apiKey = "bad-key", baseUrl = s"http://localhost:$port", maxResults = 5)
          skill   = new SearchSkill(cfg, client)
          result <- skill.invoke(dummyCtx, "search.web", Json.Obj("query" -> Json.Str("test"))).exit
        } yield assert(result)(fails(hasField("msg", (e: JorlanError) => e.msg, containsString("401"))))
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("invalid JSON response fails with JorlanError") {
        for {
          port   <- Server.install(routes("not valid json at all"))
          client <- ZIO.service[Client]
          cfg     = SearchConfig(apiKey = "test-key", baseUrl = s"http://localhost:$port", maxResults = 5)
          skill   = new SearchSkill(cfg, client)
          result <- skill.invoke(dummyCtx, "search.web", Json.Obj("query" -> Json.Str("test"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("search.extract missing urls arg fails with ValidationError") {
        for {
          client <- ZIO.service[Client]
          cfg     = SearchConfig(apiKey = "test-key", baseUrl = "http://localhost:9999", maxResults = 5)
          skill   = new SearchSkill(cfg, client)
          result <- skill.invoke(dummyCtx, "search.extract", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Client.default),
    ) @@ TestAspect.withLiveClock

}
