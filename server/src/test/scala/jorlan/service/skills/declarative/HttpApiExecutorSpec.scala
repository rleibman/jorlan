/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import zio.*
import zio.http.*
import zio.http.ZClient
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object HttpApiExecutorSpec extends ZIOSpecDefault {

  private def stubClient(
    responseBody: String,
    status:       Status = Status.Ok,
  ): Client = {
    val driver = new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
        version:        Version,
        method:         Method,
        url:            URL,
        headers:        Headers,
        body:           Body,
        sslConfig:      Option[ClientSSLConfig],
        proxy:          Option[Proxy],
      )(implicit trace: Trace,
      ): ZIO[Any & Scope, Throwable, Response] =
        ZIO.succeed(Response(status = status, body = Body.fromString(responseBody)))

      override def socket[Env1 <: Any](
        version: Version,
        url:     URL,
        headers: Headers,
        app:     WebSocketApp[Env1],
      )(implicit
        trace: Trace,
        ev:    Scope =:= Scope,
      ): ZIO[Env1 & Scope, Throwable, Response] =
        ZIO.fail(new Exception("websocket not supported in stub"))
    }
    ZClient.fromDriver(driver)
  }

  private val baseConfig = HttpApiExecutorConfig(
    method = "GET",
    url = "https://api.example.com/data",
  )

  override def spec =
    suite("HttpApiExecutorSpec")(
      suite("URL template substitution")(
        test("substitutes {{param}} tokens from args") {
          val config = baseConfig.copy(url = "https://api.example.com/users/{{userId}}")
          val args = Json.Obj("userId" -> Json.Str("42"))
          for {
            result <- HttpApiExecutor.execute(config, args, stubClient("""{"id": 42}"""))
          } yield assertTrue(result == Json.Obj("id" -> Json.Num(42)))
        },
        test("ignores extra args that have no corresponding token") {
          val config = baseConfig.copy(url = "https://api.example.com/items/42")
          val args = Json.Obj("ignored" -> Json.Str("extra"))
          for {
            result <- HttpApiExecutor.execute(config, args, stubClient("""{"ok": true}"""))
          } yield assertTrue(result == Json.Obj("ok" -> Json.Bool(true)))
        },
      ),
      suite("body template")(
        test("substitutes {{param}} tokens in body template") {
          val config = baseConfig.copy(
            method = "POST",
            bodyTemplate = Some("""{"name":"{{name}}"}"""),
          )
          val args = Json.Obj("name" -> Json.Str("Alice"))
          for {
            result <- HttpApiExecutor.execute(config, args, stubClient("""{"created": true}"""))
          } yield assertTrue(result == Json.Obj("created" -> Json.Bool(true)))
        },
        test("sends empty body when no bodyTemplate") {
          for {
            result <- HttpApiExecutor.execute(baseConfig, Json.Obj(), stubClient("""{"ok": true}"""))
          } yield assertTrue(result == Json.Obj("ok" -> Json.Bool(true)))
        },
      ),
      suite("response handling")(
        test("returns parsed JSON on success") {
          for {
            result <- HttpApiExecutor.execute(baseConfig, Json.Obj(), stubClient("""{"value": 123}"""))
          } yield assertTrue(result == Json.Obj("value" -> Json.Num(123)))
        },
        test("returns raw string when response body is not JSON") {
          for {
            result <- HttpApiExecutor.execute(baseConfig, Json.Obj(), stubClient("plain text"))
          } yield assertTrue(result == Json.Str("plain text"))
        },
        test("extracts responseJsonPath when configured") {
          val config = baseConfig.copy(responseJsonPath = Some("data.items"))
          val body = """{"data": {"items": [1, 2, 3]}}"""
          for {
            result <- HttpApiExecutor.execute(config, Json.Obj(), stubClient(body))
          } yield assertTrue(result == Json.Arr(Json.Num(1), Json.Num(2), Json.Num(3)))
        },
        test("falls back to full body when responseJsonPath does not match") {
          val config = baseConfig.copy(responseJsonPath = Some("nonexistent.path"))
          val body = """{"data": "value"}"""
          for {
            result <- HttpApiExecutor.execute(config, Json.Obj(), stubClient(body))
          } yield assertTrue(result == Json.Str(body))
        },
      ),
      suite("error handling")(
        test("fails with JorlanError on non-2xx HTTP response") {
          for {
            result <- HttpApiExecutor.execute(baseConfig, Json.Obj(), stubClient("Forbidden", Status.Forbidden)).either
          } yield assertTrue(result.isLeft)
        },
        test("fails with JorlanError on 500 server error") {
          for {
            result <- HttpApiExecutor
              .execute(baseConfig, Json.Obj(), stubClient("Internal Server Error", Status.InternalServerError)).either
          } yield assertTrue(result.isLeft)
        },
        test("fails with JorlanError on invalid URL") {
          val config = baseConfig.copy(url = "not a url ://bad")
          for {
            result <- HttpApiExecutor.execute(config, Json.Obj(), stubClient("ok")).either
          } yield assertTrue(result.isLeft)
        },
      ),
      suite("HTTP methods")(
        test("GET method works") {
          val config = baseConfig.copy(method = "GET")
          for {
            result <- HttpApiExecutor.execute(config, Json.Obj(), stubClient("""{"method": "get"}"""))
          } yield assertTrue(result == Json.Obj("method" -> Json.Str("get")))
        },
        test("POST method works") {
          val config = baseConfig.copy(method = "POST")
          for {
            result <- HttpApiExecutor.execute(config, Json.Obj(), stubClient("""{"method": "post"}"""))
          } yield assertTrue(result == Json.Obj("method" -> Json.Str("post")))
        },
        test("unknown method defaults to GET") {
          val config = baseConfig.copy(method = "CONNECT")
          for {
            result <- HttpApiExecutor.execute(config, Json.Obj(), stubClient("""{"ok": true}"""))
          } yield assertTrue(result == Json.Obj("ok" -> Json.Bool(true)))
        },
      ),
    )

}
