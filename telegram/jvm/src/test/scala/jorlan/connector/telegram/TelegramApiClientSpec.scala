/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.telegram

import jorlan.*
import jorlan.telegram.{TelegramApiClientLive, TelegramConfig}
import zio.*
import zio.http.*
import zio.test.*

object TelegramApiClientSpec extends ZIOSpecDefault {

  private def makeApi(port: Int): ZIO[Client, Nothing, TelegramApiClientLive] =
    ZIO.service[Client].map { client =>
      TelegramApiClientLive(
        TelegramConfig(botToken = "test-token", apiBaseUrl = s"http://localhost:$port"),
        client,
      )
    }

  private val updatesEmpty =
    Routes(Method.ANY / trailing -> handler(Response.json("""{"ok":true,"result":[]}""")))

  private val updatesOkFalse =
    Routes(Method.ANY / trailing -> handler(Response.json("""{"ok":false,"description":"test error"}""")))

  private val malformedJson =
    Routes(Method.ANY / trailing -> handler(Response.text("NOT_JSON")))

  private val nonSuccessStatus =
    Routes(
      Method.ANY / trailing -> handler(Response(status = Status.BadRequest, body = Body.fromString("""{"ok":false}"""))),
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TelegramApiClientLive")(
      test("getUpdates returns empty list on successful response with no updates") {
        for {
          port   <- Server.install(updatesEmpty)
          api    <- makeApi(port)
          result <- api.getUpdates(0L, 0)
        } yield assertTrue(result.isEmpty)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("getUpdates returns empty list when API returns ok:false") {
        for {
          port   <- Server.install(updatesOkFalse)
          api    <- makeApi(port)
          result <- api.getUpdates(0L, 0)
        } yield assertTrue(result.isEmpty)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("getUpdates fails on malformed JSON") {
        for {
          port   <- Server.install(malformedJson)
          api    <- makeApi(port)
          result <- api.getUpdates(0L, 0).either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("sendMessage succeeds on ok:true response") {
        for {
          port   <- Server.install(updatesEmpty)
          api    <- makeApi(port)
          result <- api.sendMessage("42", "hello world").either
        } yield assertTrue(result.isRight)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("sendMessage fails on non-2xx response") {
        for {
          port   <- Server.install(nonSuccessStatus)
          api    <- makeApi(port)
          result <- api.sendMessage("42", "hello").either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("sendPhoto succeeds on ok:true response") {
        for {
          port   <- Server.install(updatesEmpty)
          api    <- makeApi(port)
          result <- api.sendPhoto("42", "fake-image".getBytes("UTF-8"), Some("caption")).either
        } yield assertTrue(result.isRight)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("sendPhoto fails on non-2xx response") {
        for {
          port   <- Server.install(nonSuccessStatus)
          api    <- makeApi(port)
          result <- api.sendPhoto("42", "img".getBytes("UTF-8"), None).either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("sendDocument succeeds on ok:true response") {
        for {
          port   <- Server.install(updatesEmpty)
          api    <- makeApi(port)
          result <- api.sendDocument("42", "file-content".getBytes("UTF-8"), "report.pdf").either
        } yield assertTrue(result.isRight)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("sendDocument fails on non-2xx response") {
        for {
          port   <- Server.install(nonSuccessStatus)
          api    <- makeApi(port)
          result <- api.sendDocument("42", "data".getBytes("UTF-8"), "file.txt").either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
    ) @@ TestAspect.withLiveClock

}
