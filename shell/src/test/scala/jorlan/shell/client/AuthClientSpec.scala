/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.client

import jorlan.shell.ShellConfig
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.testing.ResponseStub
import sttp.model.{Header, StatusCode}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object AuthClientSpec extends ZIOSpecDefault {

  private val cfg = ShellConfig(serverUrl = "http://test-host:8080")

  private def makeClient(backend: Backend[Task]): UIO[AuthClient] =
    Ref.make(Option.empty[String]).map(tokenRef => AuthClient.makeForTesting(cfg, tokenRef, backend))

  private val loginBody = """{"displayName":"Alice","email":"alice@test.com"}"""
  private val authHeader = Header("Authorization", "Bearer abc123")

  override def spec: Spec[Any, String] =
    suite("AuthClient")(
      suite("login")(
        test("returns LoginResult on successful response with Authorization header") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust(loginBody, StatusCode.Ok, List(authHeader))
            }
          for {
            client <- makeClient(stub)
            result <- client.login("alice@test.com", "pass")
          } yield assertTrue(
            result.token == "abc123" &&
              result.displayName == "Alice" &&
              result.email.contains("alice@test.com"),
          )
        },
        test("stores token in Ref after successful login") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust(
                  """{"displayName":"Bob","email":"bob@test.com"}""",
                  StatusCode.Ok,
                  List(Header("Authorization", "Bearer tok42")),
                )
            }
          for {
            client <- makeClient(stub)
            _      <- client.login("bob@test.com", "pass")
            token  <- client.currentToken
          } yield assertTrue(token.contains("tok42"))
        },
        test("fails when Authorization header is missing on 200 response") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust("""{"displayName":"X","email":null}""", StatusCode.Ok)
            }
          for {
            client <- makeClient(stub)
            result <- client.login("x@test.com", "pass").either
          } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("no Authorization header")))
        },
        test("fails with status code in message on non-2xx response") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust("Unauthorized", StatusCode.Unauthorized)
            }
          for {
            client <- makeClient(stub)
            result <- client.login("bad@test.com", "wrong").either
          } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("401")))
        },
        test("fails with decode error on malformed JSON body") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust("not json", StatusCode.Ok, List(Header("Authorization", "Bearer tok")))
            }
          for {
            client <- makeClient(stub)
            result <- client.login("x@test.com", "pass").either
          } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("Failed to decode user")))
        },
      ),
      suite("whoAmI")(
        test("returns body on successful 200 response") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/whoami") =>
                ResponseStub.adjust("alice@test.com", StatusCode.Ok)
            }
          for {
            client <- makeClient(stub)
            result <- client.whoAmI
          } yield assertTrue(result == "alice@test.com")
        },
        test("fails on non-2xx response — P7-011 fix") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/whoami") =>
                ResponseStub.adjust("Internal Server Error", StatusCode.InternalServerError)
            }
          for {
            client <- makeClient(stub)
            result <- client.whoAmI.either
          } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("500")))
        },
        test("token is stored after successful login") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust(loginBody, StatusCode.Ok, List(Header("Authorization", "Bearer stored-token")))
            }
          for {
            client <- makeClient(stub)
            _      <- client.login("alice@test.com", "pass")
            token  <- client.currentToken
          } yield assertTrue(token.contains("stored-token"))
        },
      ),
      suite("currentToken")(
        test("returns None before any login") {
          val stub = HttpClientZioBackend.stub
          for {
            client <- makeClient(stub)
            token  <- client.currentToken
          } yield assertTrue(token.isEmpty)
        },
      ),
      suite("companion accessors")(
        test("AuthClient.login delegates to the service") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust(loginBody, StatusCode.Ok, List(authHeader))
            }
          for {
            client <- makeClient(stub)
            result <- AuthClient.login("alice@test.com", "pass").provideLayer(ZLayer.succeed(client))
          } yield assertTrue(result.token == "abc123" && result.displayName == "Alice")
        },
        test("AuthClient.whoAmI delegates to the service") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/whoami") =>
                ResponseStub.adjust("alice@test.com", StatusCode.Ok)
            }
          for {
            client <- makeClient(stub)
            result <- AuthClient.whoAmI.provideLayer(ZLayer.succeed(client))
          } yield assertTrue(result == "alice@test.com")
        },
        test("AuthClient.currentToken delegates to the service — None before login") {
          val stub = HttpClientZioBackend.stub
          for {
            client <- makeClient(stub)
            token  <- AuthClient.currentToken.provideLayer(ZLayer.succeed(client))
          } yield assertTrue(token.isEmpty)
        },
        test("AuthClient.currentToken delegates to the service — Some after login") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/login") =>
                ResponseStub.adjust(loginBody, StatusCode.Ok, List(authHeader))
            }
          for {
            client <- makeClient(stub)
            _      <- client.login("alice@test.com", "pass")
            token  <- AuthClient.currentToken.provideLayer(ZLayer.succeed(client))
          } yield assertTrue(token.contains("abc123"))
        },
        test("AuthClient.whoAmI fails on non-2xx via companion") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/whoami") =>
                ResponseStub.adjust("Forbidden", StatusCode.Forbidden)
            }
          for {
            client <- makeClient(stub)
            result <- AuthClient.whoAmI.provideLayer(ZLayer.succeed(client)).either
          } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("403")))
        },
        test("whoAmI fails when body is Left on a non-2xx response") {
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/whoami") =>
                ResponseStub.adjust("Unauthorized", StatusCode.Unauthorized)
            }
          for {
            client <- makeClient(stub)
            result <- client.whoAmI.either
          } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("401")))
        },
      ),
    )

}
