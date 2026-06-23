/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.httpfetch

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object HttpFetchSkillSpec extends ZIOSpecDefault {

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  private val allowAllConfig: HttpFetchConfig = HttpFetchConfig(
    allowedHosts = List("*"),
    maxResponseBytes = 1024,
    timeoutSeconds = 10,
  )

  private val denyAllConfig: HttpFetchConfig = HttpFetchConfig(
    allowedHosts = List.empty,
    maxResponseBytes = 1024,
    timeoutSeconds = 10,
  )

  private val sampleJsonBody: String = """{"status":"ok","value":42}"""
  private val sampleTextBody: String = "Hello, world!"

  private def fixedBodyRoutes(
    body:        String,
    contentType: MediaType = MediaType.application.json,
  ): Routes[Any, Nothing] =
    Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          _: Request,
        ) =>
          Response(
            status = Status.Ok,
            headers = Headers(Header.ContentType(contentType).untyped),
            body = Body.fromString(body),
          )
      },
    )

  private def makeSkill(
    port:   Int,
    config: HttpFetchConfig = allowAllConfig,
  )(
    client: Client,
  ): HttpFetchSkill =
    new HttpFetchSkill(
      config = config,
      client = client,
      urlTransform = url => url.replaceAll("https?://[^/]+", s"http://localhost:$port"),
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("HttpFetchSkillSpec")(
      test("http_fetch.get returns status, body, and contentType for allowed host") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleJsonBody))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill
            .invoke(dummyCtx, "http_fetch.get", Json.Obj("url" -> Json.Str("https://api.example.com/data")))
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("status"))(isSome(equalTo(Json.Num(200)))) &&
            assert(fieldMap.get("body"))(isSome(equalTo(Json.Str(sampleJsonBody)))) &&
            assert(fieldMap.get("contentType"))(isSome(equalTo(Json.Str("application/json"))))
          case _ => assert(false)(isTrue)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("http_fetch.get returns error JSON when host is not in allowlist") {
        for {
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(denyAllConfig, client)
          result <- skill
            .invoke(dummyCtx, "http_fetch.get", Json.Obj("url" -> Json.Str("https://blocked.example.com/data")))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("error", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Client.default),
      test("http_fetch.get rejects missing url field with ValidationError") {
        for {
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(allowAllConfig, client)
          result <- skill.invoke(dummyCtx, "http_fetch.get", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Client.default),
      test("http_fetch.post returns status and body for allowed host") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleJsonBody))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(
            dummyCtx,
            "http_fetch.post",
            Json.Obj(
              "url"  -> Json.Str("https://api.example.com/submit"),
              "body" -> Json.Str("""{"key":"value"}"""),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("status"))(isSome(equalTo(Json.Num(200)))) &&
            assert(fieldMap.get("body"))(isSome(equalTo(Json.Str(sampleJsonBody))))
          case _ => assert(false)(isTrue)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("http_fetch.post with custom contentType sends request and returns body") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleTextBody, MediaType.text.plain))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(
            dummyCtx,
            "http_fetch.post",
            Json.Obj(
              "url"         -> Json.Str("https://api.example.com/submit"),
              "body"        -> Json.Str("plain text body"),
              "contentType" -> Json.Str("text/plain"),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("status"))(isSome(equalTo(Json.Num(200)))) &&
            assert(fieldMap.get("body"))(isSome(equalTo(Json.Str(sampleTextBody))))
          case _ => assert(false)(isTrue)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("http_fetch.post returns error JSON when host is not in allowlist") {
        for {
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(denyAllConfig, client)
          result <- skill.invoke(
            dummyCtx,
            "http_fetch.post",
            Json.Obj("url" -> Json.Str("https://blocked.example.com/submit"), "body" -> Json.Str("{}")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("error", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Client.default),
      test("http_fetch.post rejects missing url field with ValidationError") {
        for {
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(allowAllConfig, client)
          result <- skill.invoke(dummyCtx, "http_fetch.post", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Client.default),
      test("unknown tool name fails with ValidationError") {
        for {
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(allowAllConfig, client)
          result <- skill.invoke(dummyCtx, "http_fetch.unknown", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Client.default),
      test("http_fetch.get with wildcard subdomain pattern allows matching host") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleJsonBody))
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(
            HttpFetchConfig(allowedHosts = List("*.example.com")),
            client,
            url => url.replaceAll("https?://[^/]+", s"http://localhost:$port"),
          )
          result <- skill
            .invoke(dummyCtx, "http_fetch.get", Json.Obj("url" -> Json.Str("https://api.example.com/data")))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("status", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("http_fetch.get with extra headers sends the request") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleJsonBody))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(
            dummyCtx,
            "http_fetch.get",
            Json.Obj(
              "url"     -> Json.Str("https://api.example.com/data"),
              "headers" -> Json.Obj("X-Custom" -> Json.Str("value")),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("status", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("http_fetch.get truncates response body exceeding maxResponseBytes") {
        val longBody = "x" * 2000
        for {
          port   <- Server.install(fixedBodyRoutes(longBody, MediaType.text.plain))
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(
            HttpFetchConfig(allowedHosts = List("*"), maxResponseBytes = 100),
            client,
            url => url.replaceAll("https?://[^/]+", s"http://localhost:$port"),
          )
          result <- skill
            .invoke(dummyCtx, "http_fetch.get", Json.Obj("url" -> Json.Str("https://api.example.com/data")))
        } yield result match {
          case Json.Obj(fields) =>
            val body = fields.collectFirst { case ("body", Json.Str(v)) => v }.getOrElse("")
            assertTrue(body.contains("[truncated]"))
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("host not in specific allowlist returns error with host name in message") {
        for {
          client <- ZIO.service[Client]
          skill = new HttpFetchSkill(
            HttpFetchConfig(allowedHosts = List("allowed.example.com")),
            client,
          )
          result <- skill.invoke(
            dummyCtx,
            "http_fetch.get",
            Json.Obj("url" -> Json.Str("https://blocked.example.com/data")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val error = fields.collectFirst { case ("error", Json.Str(v)) => v }.getOrElse("")
            assertTrue(error.contains("blocked.example.com"))
          case _ => assertTrue(false)
        }
      }.provide(Client.default),
    ) @@ TestAspect.withLiveClock

}
