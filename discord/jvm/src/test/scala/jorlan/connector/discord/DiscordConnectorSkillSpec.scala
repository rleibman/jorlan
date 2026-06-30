/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.connector.discord

import jorlan.*
import jorlan.connector.*
import jorlan.discord.{DiscordConfig, DiscordConnectorSkill, DiscordRawMessage}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object DiscordConnectorSkillSpec extends ZIOSpecDefault {

  private val instanceId = ConnectorInstanceId(1L)

  private val config = DiscordConfig(
    botToken = "test-token",
    allowedGuildIds = Set.empty,
    allowedUserIds = Set.empty,
    mentionOnly = false,
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

  private def rawMsg(
    guildId:   Option[String] = None,
    authorId:  String = "user-42",
    isBot:     Boolean = false,
    isMention: Boolean = false,
  ): DiscordRawMessage =
    DiscordRawMessage(
      messageId = "msg-1",
      authorId = authorId,
      authorName = "Bob",
      channelId = "channel-99",
      guildId = guildId,
      isBot = isBot,
      content = "test message",
      isMention = isMention,
      receivedAt = Instant.parse("2026-01-01T12:00:00Z"),
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DiscordConnectorSkill")(
      test("descriptor has discord namespace with 4 tools") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
        } yield assertTrue(
          skill.descriptor.name == "discord",
          skill.descriptor.tools.map(_.name) == List(
            "discord.send_message",
            "discord.send_dm",
            "discord.get_history",
            "discord.get_channel_info",
          ),
          skill.connectorType == ConnectorType.Discord,
          skill.sendMessageToolName == Some("discord.send_message"),
        )
      },
      test("send_message invoke calls apiClient.sendToChannel") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          args = Json.Obj("channelId" -> Json.Str("channel-1"), "content" -> Json.Str("hello"))
          _    <- skill.invoke(ctx, "discord.send_message", args)
          sent <- fakeClient.sentMessages.get
        } yield assertTrue(sent == List(("channel-1", "hello")))
      },
      test("send_dm invoke calls apiClient.sendToDm") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          args = Json.Obj("userId" -> Json.Str("user-99"), "content" -> Json.Str("hi"))
          _    <- skill.invoke(ctx, "discord.send_dm", args)
          sent <- fakeClient.sentDms.get
        } yield assertTrue(sent == List(("user-99", "hi")))
      },
      test("get_channel_info invoke returns channel metadata") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          args = Json.Obj("channelId" -> Json.Str("channel-99"))
          result <- skill.invoke(ctx, "discord.get_channel_info", args)
        } yield assertTrue(result.asObject.isDefined)
      },
      test("get_history invoke returns list") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          args = Json.Obj("channelId" -> Json.Str("channel-99"), "limit" -> Json.Num(10))
          result <- skill.invoke(ctx, "discord.get_history", args)
        } yield assertTrue(result.asArray.isDefined)
      },
      test("invoke unknown tool returns error") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "discord.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("send_message fails when channelId is missing") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "discord.send_message", Json.Obj("content" -> Json.Str("hi"))).either
        } yield assertTrue(result.isLeft)
      },
      test("send_message fails when content is missing") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "discord.send_message", Json.Obj("channelId" -> Json.Str("c1"))).either
        } yield assertTrue(result.isLeft)
      },
      test("send_dm fails when userId is missing") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          ctx = InvocationContext(UserId(1L), None, None)
          result <- skill.invoke(ctx, "discord.send_dm", Json.Obj("content" -> Json.Str("hi"))).either
        } yield assertTrue(result.isLeft)
      },
      test("start forks event loop and stop interrupts it") {
        for {
          fakeClient <- FakeDiscordApiClient.make()
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          _          <- skill.start
          _          <- ZIO.sleep(50.millis)
          _          <- skill.stop
        } yield assertTrue(true)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("event loop normalizes DM and passes to ingress") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeDiscordApiClient.make(List(rawMsg()), Some(ready))
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          _          <- skill.start
          _          <- ready.await
          _          <- ZIO.sleep(50.millis)
          _          <- skill.stop
          received   <- ingress.received.get
        } yield assertTrue(received.nonEmpty, received.head.content == "test message")
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("event loop skips bot messages") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeDiscordApiClient.make(List(rawMsg(isBot = true)), Some(ready))
          ingress    <- NoOpIngress.make
          skill      <- DiscordConnectorSkill.make(config, instanceId, fakeClient, ingress)
          _          <- skill.start
          _          <- ready.await
          _          <- ZIO.sleep(50.millis)
          _          <- skill.stop
          received   <- ingress.received.get
        } yield assertTrue(received.isEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("event loop drops messages from unlisted guilds") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeDiscordApiClient.make(List(rawMsg(guildId = Some("guild-X"))), Some(ready))
          ingress    <- NoOpIngress.make
          restricted = config.copy(allowedGuildIds = Set("guild-allowed"))
          skill    <- DiscordConnectorSkill.make(restricted, instanceId, fakeClient, ingress)
          _        <- skill.start
          _        <- ready.await
          _        <- ZIO.sleep(50.millis)
          _        <- skill.stop
          received <- ingress.received.get
        } yield assertTrue(received.isEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("event loop drops messages from unlisted users") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeDiscordApiClient.make(List(rawMsg(authorId = "user-99")), Some(ready))
          ingress    <- NoOpIngress.make
          restricted = config.copy(allowedUserIds = Set("user-allowed"))
          skill    <- DiscordConnectorSkill.make(restricted, instanceId, fakeClient, ingress)
          _        <- skill.start
          _        <- ready.await
          _        <- ZIO.sleep(50.millis)
          _        <- skill.stop
          received <- ingress.received.get
        } yield assertTrue(received.isEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("mentionOnly config drops guild messages without bot mention") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeDiscordApiClient
            .make(List(rawMsg(guildId = Some("guild-1"), isMention = false)), Some(ready))
          ingress <- NoOpIngress.make
          mentionCfg = config.copy(mentionOnly = true)
          skill    <- DiscordConnectorSkill.make(mentionCfg, instanceId, fakeClient, ingress)
          _        <- skill.start
          _        <- ready.await
          _        <- ZIO.sleep(50.millis)
          _        <- skill.stop
          received <- ingress.received.get
        } yield assertTrue(received.isEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("mentionOnly config passes guild messages with bot mention") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeDiscordApiClient
            .make(List(rawMsg(guildId = Some("guild-1"), isMention = true)), Some(ready))
          ingress <- NoOpIngress.make
          mentionCfg = config.copy(mentionOnly = true)
          skill    <- DiscordConnectorSkill.make(mentionCfg, instanceId, fakeClient, ingress)
          _        <- skill.start
          _        <- ready.await
          _        <- ZIO.sleep(50.millis)
          _        <- skill.stop
          received <- ingress.received.get
        } yield assertTrue(received.nonEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
      test("mentionOnly does not apply to DMs") {
        for {
          ready      <- Promise.make[Nothing, Unit]
          fakeClient <- FakeDiscordApiClient.make(List(rawMsg(guildId = None, isMention = false)), Some(ready))
          ingress    <- NoOpIngress.make
          mentionCfg = config.copy(mentionOnly = true)
          skill    <- DiscordConnectorSkill.make(mentionCfg, instanceId, fakeClient, ingress)
          _        <- skill.start
          _        <- ready.await
          _        <- ZIO.sleep(50.millis)
          _        <- skill.stop
          received <- ingress.received.get
        } yield assertTrue(received.nonEmpty)
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
    )

}
