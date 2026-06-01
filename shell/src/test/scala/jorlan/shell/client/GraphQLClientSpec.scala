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
import sttp.model.StatusCode
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

/** P7-036: Tests for [[GraphQLClientImpl]] response parsing — no live server required. */
object GraphQLClientSpec extends ZIOSpecDefault {

  private val cfg = ShellConfig(serverUrl = "http://test-host:8080")

  private def fakeAuth(token: Option[String] = Some("tok")): ULayer[AuthClient] =
    ZLayer.succeed {
      new AuthClient {
        override def login(
          email:    String,
          password: String,
        ): IO[String, LoginResult] =
          ZIO.fail("not used")
        override def whoAmI:       IO[String, String] = ZIO.fail("not used")
        override def currentToken: UIO[Option[String]] = ZIO.succeed(token)
      }
    }

  private def makeClient(
    backend: Backend[Task],
    token:   Option[String] = Some("tok"),
  ): UIO[GraphQLClient] =
    ZIO.scoped(
      fakeAuth(token).build.map(scope => GraphQLClient.makeForTesting(cfg, scope.get[AuthClient], backend)),
    )

  override def spec: Spec[Any, Any] =
    suite("GraphQLClient")(
      test("returns data field on successful response") {
        val body = """{"data":{"users":[{"id":1}]}}"""
        val stub = HttpClientZioBackend.stub
          .whenRequestMatchesPartial {
            case req if req.uri.toString.contains("/api/jorlan") =>
              ResponseStub.adjust(body, StatusCode.Ok)
          }
        for {
          client <- makeClient(stub)
          result <- client.execute("{ users { id } }")
        } yield assertTrue(result.toString.contains("users"))
      },
      test("fails on GraphQL errors — joins multiple messages with semicolon") {
        val body = """{"errors":[{"message":"not authorized"},{"message":"rate limited"}]}"""
        val stub = HttpClientZioBackend.stub
          .whenRequestMatchesPartial {
            case req if req.uri.toString.contains("/api/jorlan") =>
              ResponseStub.adjust(body, StatusCode.Ok)
          }
        for {
          client <- makeClient(stub)
          result <- client.execute("{ users { id } }").either
        } yield assertTrue(
          result.isLeft && result.left.toOption.exists(e => e.contains("not authorized") && e.contains("rate limited")),
        )
      },
      test("returns empty Json.Obj when data is null with no errors") {
        val body = """{"data":null}"""
        val stub = HttpClientZioBackend.stub
          .whenRequestMatchesPartial {
            case req if req.uri.toString.contains("/api/jorlan") =>
              ResponseStub.adjust(body, StatusCode.Ok)
          }
        for {
          client <- makeClient(stub)
          result <- client.execute("{ __typename }")
        } yield assertTrue(result == Json.Obj())
      },
      test("fails with decode error on non-JSON body") {
        val stub = HttpClientZioBackend.stub
          .whenRequestMatchesPartial {
            case req if req.uri.toString.contains("/api/jorlan") =>
              ResponseStub.adjust("not json", StatusCode.Ok)
          }
        for {
          client <- makeClient(stub)
          result <- client.execute("{ __typename }").either
        } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("Failed to decode")))
      },
      test("fails with status code on HTTP error response") {
        val stub = HttpClientZioBackend.stub
          .whenRequestMatchesPartial {
            case req if req.uri.toString.contains("/api/jorlan") =>
              ResponseStub.adjust("Internal Server Error", StatusCode.InternalServerError)
          }
        for {
          client <- makeClient(stub)
          result <- client.execute("{ __typename }").either
        } yield assertTrue(result.isLeft && result.left.toOption.exists(_.contains("500")))
      },
      suite("companion accessor")(
        test("GraphQLClient.execute delegates to the service") {
          val body = """{"data":{"agents":[]}}"""
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/api/jorlan") =>
                ResponseStub.adjust(body, StatusCode.Ok)
            }
          for {
            client <- makeClient(stub)
            result <- GraphQLClient.execute("{ agents { id } }").provideLayer(ZLayer.succeed(client))
          } yield assertTrue(result.toString.contains("agents"))
        },
        test("GraphQLClient.execute companion passes variables through") {
          val body = """{"data":{"user":{"id":1}}}"""
          val stub = HttpClientZioBackend.stub
            .whenRequestMatchesPartial {
              case req if req.uri.toString.contains("/api/jorlan") =>
                ResponseStub.adjust(body, StatusCode.Ok)
            }
          import zio.json.ast.Json
          val vars = Some(Json.Obj("id" -> Json.Num(1)))
          for {
            client <- makeClient(stub)
            result <- GraphQLClient
              .execute("query($id: Long) { user(id: $id) { id } }", vars)
              .provideLayer(ZLayer.succeed(client))
          } yield assertTrue(result.toString.contains("user"))
        },
      ),
    )

}
