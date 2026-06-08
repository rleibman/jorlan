/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.domain.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import java.time.Instant

object TelegramConnectorSkillSpec extends ZIOSpecDefault {

  private val instanceId = ConnectorInstanceId(1L)

  private val config = TelegramConfig(
    botToken = "test-token",
    allowedChatIds = Set.empty,
    allowedUserIds = Set.empty,
    unrecognizedPolicy = UnrecognizedIdentityPolicy.Reject,
  )

  private class NoOpIngress(val received: Ref[List[InboundMessage]]) extends MessageIngress {
    override def receive(msg: InboundMessage): IO[JorlanError, Unit] = received.update(_ :+ msg)
  }

  private object NoOpIngress {
    def make: UIO[NoOpIngress] = Ref.make(List.empty[InboundMessage]).map(NoOpIngress(_))
  }

  private class NoOpAgentRunner extends AgentRunner {
    override def processMessage(sessionId: AgentSessionId, content: String, actorId: Option[UserId]): IO[JorlanError, Unit] = ZIO.unit
    override def subscribeToSession(sessionId: AgentSessionId, connectionId: ConnectionId): UIO[ZStream[Any, Nothing, ResponseChunk]] =
      ZIO.succeed(ZStream.empty)
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TelegramConnectorSkill")(
      test("descriptor has telegram namespace with 3 tools") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress, new NoOpAgentRunner)
        } yield assertTrue(
          skill.descriptor.name == "telegram",
          skill.descriptor.tools.map(_.name) == List("telegram.send_message", "telegram.send_photo", "telegram.send_file"),
          skill.connectorType == ConnectorType.Telegram,
        )
      },
      test("send_message invoke calls apiClient.sendMessage") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress, new NoOpAgentRunner)
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
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress, new NoOpAgentRunner)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "telegram.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("start forks polling and stop interrupts it") {
        for {
          fakeClient <- FakeTelegramApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress, new NoOpAgentRunner)
          _          <- skill.start
          _          <- ZIO.sleep(50.millis)
          _          <- skill.stop
        } yield assertTrue(true)
      },
      test("long-poll loop normalizes updates and passes to ingress") {
        val update = TelegramUpdate(
          update_id = 1L,
          message = Some(
            TelegramMessage(
              message_id = 1L,
              from = Some(TelegramUser(id = 42L, is_bot = false, first_name = "Bob", username = None)),
              chat = TelegramChat(id = 42L, `type` = "private", title = None),
              text = Some("test message"),
            ),
          ),
        )
        for {
          fakeClient <- FakeTelegramApiClient.make(List(update))
          ingress    <- NoOpIngress.make
          skill      <- TelegramConnectorSkill.make(config, instanceId, fakeClient, ingress, new NoOpAgentRunner)
          _          <- skill.start
          _          <- ZIO.sleep(100.millis)
          _          <- skill.stop
          received   <- ingress.received.get
        } yield assertTrue(received.nonEmpty, received.head.content == "test message")
      },
    )

}
