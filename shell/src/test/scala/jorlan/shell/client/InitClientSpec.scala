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

import jorlan.init.ServerStatus
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import scala.language.unsafeNulls

/** P8.1-004/028: Tests for [[InitClient]] error branches and the codec contract between the server-side `ServerStatus`
  * JSON shape and the shell-side [[ServerStatus]] decoder.
  */
object InitClientSpec extends ZIOSpecDefault {

  private val serverUrl = "http://test-host:8080"

  private def makeClient = HttpClientZioBackend.stub

  override def spec: Spec[Any, Any] =
    suite("InitClient")(
      suite("checkStatus")(
        test("decodes a valid 200 response into ServerStatus") {
          val body =
            """{"initialized":false,"version":"1.0","buildTime":1700000000000,"serverName":"Jorlan","uptimeMs":123}"""
          val stub = makeClient.whenRequestMatchesPartial {
            case r if r.uri.path.endsWith(List("status")) =>
              ResponseStub.adjust(body, StatusCode.Ok)
          }
          val client = InitClient.makeForTesting(stub)
          for {
            status <- client.checkStatus(serverUrl)
          } yield assertTrue(
            !status.initialized,
            status.serverName == "Jorlan",
            status.uptimeMs == 123L,
          )
        },
        test("fails with error string on non-2xx response") {
          val stub = makeClient.whenRequestMatchesPartial {
            case r if r.uri.path.endsWith(List("status")) =>
              ResponseStub.adjust("Service Unavailable", StatusCode.ServiceUnavailable)
          }
          val client = InitClient.makeForTesting(stub)
          for {
            result <- client.checkStatus(serverUrl).either
          } yield assertTrue(
            result.isLeft,
            result.left.toOption.exists(_.contains("503")),
          )
        },
        test("fails with decode error on malformed JSON body") {
          val stub = makeClient.whenRequestMatchesPartial {
            case r if r.uri.path.endsWith(List("status")) =>
              ResponseStub.adjust("not json", StatusCode.Ok)
          }
          val client = InitClient.makeForTesting(stub)
          for {
            result <- client.checkStatus(serverUrl).either
          } yield assertTrue(result.isLeft)
        },
      ),
      suite("complete")(
        test("succeeds on 200 response") {
          val stub = makeClient.whenRequestMatchesPartial {
            case r if r.uri.path.endsWith(List("init")) =>
              ResponseStub.adjust("""{"success":true}""", StatusCode.Ok)
          }
          val client = InitClient.makeForTesting(stub)
          for {
            result <- client.complete(serverUrl, "tok", "Server", "admin@x.com", "Admin", "pass123!").either
          } yield assertTrue(result.isRight)
        },
        test("fails with error string on 403 response") {
          val stub = makeClient.whenRequestMatchesPartial {
            case r if r.uri.path.endsWith(List("init")) =>
              ResponseStub.adjust("""{"error":"Invalid setup token"}""", StatusCode.Forbidden)
          }
          val client = InitClient.makeForTesting(stub)
          for {
            result <- client.complete(serverUrl, "bad", "Server", "admin@x.com", "Admin", "pass123!").either
          } yield assertTrue(
            result.isLeft,
            result.left.toOption.exists(_.contains("403")),
          )
        },
        test("fails with error string on 400 response") {
          val stub = makeClient.whenRequestMatchesPartial {
            case r if r.uri.path.endsWith(List("init")) =>
              ResponseStub.adjust("""{"error":"Password must be at least 12 characters"}""", StatusCode.BadRequest)
          }
          val client = InitClient.makeForTesting(stub)
          for {
            result <- client.complete(serverUrl, "tok", "Server", "admin@x.com", "Admin", "short").either
          } yield assertTrue(
            result.isLeft,
            result.left.toOption.exists(_.contains("400")),
          )
        },
      ),
      // P8.1-028: Codec contract — server-side JSON format must be decodable by shell-side decoder
      suite("ServerStatus codec contract")(
        test("server-side JSON fields match shell-side decoder field names") {
          val json = Map(
            "initialized" -> "true",
            "version"     -> "\"1.2.3\"",
            "buildTime"   -> "1700000000000",
            "serverName"  -> "\"TestServer\"",
            "uptimeMs"    -> "9876",
          ).map { case (k, v) => s""""$k":$v""" }.mkString("{", ",", "}")

          val decoded = json.fromJson[ServerStatus]
          assertTrue(
            decoded.isRight,
            decoded.toOption.exists(_.initialized),
            decoded.toOption.exists(_.version == "1.2.3"),
            decoded.toOption.exists(_.serverName == "TestServer"),
            decoded.toOption.exists(_.uptimeMs == 9876L),
          )
        },
        test("missing field produces a decode error") {
          val json = """{"initialized":true,"version":"1.0","uptimeMs":100}"""
          assertTrue(json.fromJson[ServerStatus].isLeft)
        },
        test("decodes buildTime field correctly") {
          val json =
            """{"initialized":true,"version":"2.0.0","buildTime":1750000000000,"serverName":"Srv","uptimeMs":0}"""
          val decoded = json.fromJson[ServerStatus]
          assertTrue(decoded.toOption.exists(_.buildTime == 1750000000000L))
        },
      ),
    )

}
