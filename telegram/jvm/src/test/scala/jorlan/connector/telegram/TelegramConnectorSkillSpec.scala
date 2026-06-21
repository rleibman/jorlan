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
import jorlan.connector.*
import jorlan.telegram.{TelegramConfig, TelegramConnectorSkill}
import telegramium.bots.{Chat, Update}
import telegramium.bots.{Message as TgMessage, User as TgUser}
import zio.*
import zio.json.ast.Json
import zio.test.*

object TelegramConnectorSkillSpec extends ZIOSpecDefault {

  private val instanceId = ConnectorInstanceId(1L)

  private val config = TelegramConfig(
    botToken = "test-token",
    allowedChatIds = Set.empty,
    allowedUserIds = Set.empty,
    unrecognizedPolicy = UnrecognizedIdentityPolicy.Reject,
  )

  private class NoOpIngress(val received: Ref[List[InboundMessage]]) extends MessageIngress {

    override def receive(
      msg:                InboundMessage,
      unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
      onResponse:         Option[String => UIO[Unit]] = None,
    ): IO[JorlanError, Unit] = received.update(_ :+ msg)

  }

  private object NoOpIngress {

    def make: UIO[NoOpIngress] = Ref.make(List.empty[InboundMessage]).map(NoOpIngress(_))

  }

  private def makeUpdate(text: String = "test message"): Update =
    Update(
      updateId = 1,
      message = Some(
        TgMessage(
          messageId = 1,
          date = 0,
          chat = Chat(id = 42L, `type` = "private"),
          from = Some(TgUser(id = 42L, isBot = false, firstName = "Bob")),
          text = Some(text),
        ),
      ),
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TelegramConnectorSkill")(
      test("descriptor has telegram namespace with 3 tools") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
        } yield assertTrue(
          skill.descriptor.name == "telegram",
          skill.descriptor.tools
            .map(_.name) == List("telegram.send_message", "telegram.send_photo", "telegram.send_file"),
          skill.connectorType == ConnectorType.Telegram,
        )
      },
      test("send_message invoke calls apiClient.sendMessage") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          args = Json.Obj("chatId" -> Json.Str("42"), "text" -> Json.Str("hi there"))
          _    <- skill.invoke(ctx, "telegram.send_message", args)
          sent <- fakeClient.sentMessages.get
        } yield assertTrue(sent == List(("42", "hi there")))
      },
      test("invoke unknown tool returns error") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "telegram.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("start forks polling and stop interrupts it") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          _          <- skill.start
          _          <- ZIO.sleep(50.millis)
          _          <- skill.stop
        } yield assertTrue(true)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("long-poll loop normalizes updates and passes to ingress") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeTelegramApiClient.make(List(makeUpdate("test message")), Some(ready))
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          _          <- skill.start
          _          <- ready.await
          _          <- ZIO.sleep(50.millis)
          _          <- skill.stop
          received   <- ingress.received.get
        } yield assertTrue(received.nonEmpty, received.head.content == "test message")
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("send_message invoke fails when chatId is missing") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "telegram.send_message", Json.Obj("text" -> Json.Str("hi"))).either
        } yield assertTrue(result.isLeft)
      },
      test("send_photo invoke calls apiClient.sendPhoto with valid base64") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          validB64 = java.util.Base64.getEncoder.encodeToString("fake-photo".getBytes("UTF-8"))
          args = Json.Obj("chatId" -> Json.Str("42"), "photo" -> Json.Str(validB64))
          result <- skill.invoke(ctx, "telegram.send_photo", args).either
        } yield assertTrue(result.isRight)
      },
      test("send_photo invoke fails with invalid base64") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          args = Json.Obj("chatId" -> Json.Str("42"), "photo" -> Json.Str("not-valid-base64!!!"))
          result <- skill.invoke(ctx, "telegram.send_photo", args).either
        } yield assertTrue(result.isLeft)
      },
      test("send_file invoke calls apiClient.sendDocument with valid base64") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          validB64 = java.util.Base64.getEncoder.encodeToString("fake-file".getBytes("UTF-8"))
          args = Json.Obj("chatId" -> Json.Str("42"), "file" -> Json.Str(validB64), "filename" -> Json.Str("test.txt"))
          result <- skill.invoke(ctx, "telegram.send_file", args).either
        } yield assertTrue(result.isRight)
      },
      test("send_file invoke fails with invalid base64") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          args = Json
            .Obj("chatId" -> Json.Str("42"), "file" -> Json.Str("!!!invalid!!!"), "filename" -> Json.Str("f.txt"))
          result <- skill.invoke(ctx, "telegram.send_file", args).either
        } yield assertTrue(result.isLeft)
      },
      test("filterUpdates drops messages from non-allowlisted chats") {
        for {
          fakeClient <- FakeTelegramApiClient.make(List(makeUpdate("msg-for-42")))
          ingress    <- NoOpIngress.make
          restrictedConfig = config.copy(allowedChatIds = Set("99999"))
          skill <- TelegramConnectorSkill.make(restrictedConfig, instanceId, fakeClient, ingress)
          ready <- Promise.make[Nothing, Unit]
          _     <- FakeTelegramApiClient.make(List(makeUpdate("msg-for-42")), Some(ready)).flatMap { client =>
            TelegramConnectorSkill.make(restrictedConfig, instanceId, client, ingress).flatMap { s =>
              s.start *> ready.await *> ZIO.sleep(50.millis) *> s.stop
            }
          }
          received <- ingress.received.get
        } yield assertTrue(received.isEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("send_message invoke fails when text is missing") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "telegram.send_message", Json.Obj("chatId" -> Json.Str("42"))).either
        } yield assertTrue(result.isLeft)
      },
      test("send_photo invoke fails when chatId is missing") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          validB64 = java.util.Base64.getEncoder.encodeToString("img".getBytes("UTF-8"))
          result <- skill.invoke(ctx, "telegram.send_photo", Json.Obj("photo" -> Json.Str(validB64))).either
        } yield assertTrue(result.isLeft)
      },
      test("send_photo invoke fails when photo is missing") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "telegram.send_photo", Json.Obj("chatId" -> Json.Str("42"))).either
        } yield assertTrue(result.isLeft)
      },
      test("send_photo with caption calls apiClient.sendPhoto") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          validB64 = java.util.Base64.getEncoder.encodeToString("img".getBytes("UTF-8"))
          args = Json.Obj("chatId" -> Json.Str("42"), "photo" -> Json.Str(validB64), "caption" -> Json.Str("my pic"))
          result <- skill.invoke(ctx, "telegram.send_photo", args).either
        } yield assertTrue(result.isRight)
      },
      test("send_file invoke fails when chatId is missing") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          validB64 = java.util.Base64.getEncoder.encodeToString("data".getBytes("UTF-8"))
          result <- skill
            .invoke(
              ctx,
              "telegram.send_file",
              Json.Obj("file" -> Json.Str(validB64), "filename" -> Json.Str("f.txt")),
            ).either
        } yield assertTrue(result.isLeft)
      },
      test("send_file invoke fails when file is missing") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill
            .invoke(
              ctx,
              "telegram.send_file",
              Json.Obj("chatId" -> Json.Str("42"), "filename" -> Json.Str("f.txt")),
            ).either
        } yield assertTrue(result.isLeft)
      },
      test("send_file invoke fails when filename is missing") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          validB64 = java.util.Base64.getEncoder.encodeToString("data".getBytes("UTF-8"))
          result <- skill
            .invoke(
              ctx,
              "telegram.send_file",
              Json.Obj("chatId" -> Json.Str("42"), "file" -> Json.Str(validB64)),
            ).either
        } yield assertTrue(result.isLeft)
      },
      test("filterUpdates drops messages from non-allowlisted users") {
        for {
          ready   <- Promise.make[Nothing, Unit]
          ingress <- NoOpIngress.make
          restrictedConfig = config.copy(allowedUserIds = Set("99999"))
          _ <- FakeTelegramApiClient.make(List(makeUpdate("msg")), Some(ready)).flatMap { client =>
            TelegramConnectorSkill.make(restrictedConfig, instanceId, client, ingress).flatMap { s =>
              s.start *> ready.await *> ZIO.sleep(50.millis) *> s.stop
            }
          }
          received <- ingress.received.get
        } yield assertTrue(received.isEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("poll loop skips updates without text") {
        for {
          ready <- Promise.make[Nothing, Unit]
          noTextUpdate = makeUpdate("").copy(
            message = Some(
              makeUpdate("").message.get.copy(text = None),
            ),
          )
          fakeClient <- FakeTelegramApiClient.make(List(noTextUpdate), Some(ready))
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          _          <- skill.start
          _          <- ready.await
          _          <- ZIO.sleep(50.millis)
          _          <- skill.stop
          received   <- ingress.received.get
        } yield assertTrue(received.isEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("poll offset advances to lastUpdateId + 1 after first batch") {
        for {
          offsets    <- Ref.make(List.empty[Long])
          updatesRef <- Ref.make(List(makeUpdate("u1").copy(updateId = 10), makeUpdate("u2").copy(updateId = 20)))
          sentRef    <- Ref.make(List.empty[(String, String)])
          fakeClient = new FakeTelegramApiClient(updatesRef, sentRef) {
            override def getUpdates(
              offset:         Long,
              timeoutSeconds: Int,
            ): IO[JorlanError, List[Update]] =
              offsets.update(_ :+ offset) *> super.getUpdates(offset, timeoutSeconds)
          }
          ingress <- NoOpIngress.make
          skill   <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress)
          _       <- skill.start
          _       <- ZIO.sleep(150.millis)
          _       <- skill.stop
          seen    <- offsets.get
        } yield assertTrue(seen.contains(0L), seen.contains(21L))
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
    )

}
