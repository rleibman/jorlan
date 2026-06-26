/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.connector.discord

import jorlan.*
import jorlan.connector.ChatKind
import jorlan.discord.{DiscordMessageNormalizer, DiscordRawMessage}
import zio.*
import zio.test.*

import java.time.Instant

object DiscordMessageNormalizerSpec extends ZIOSpecDefault {

  private val now = Instant.parse("2026-01-01T12:00:00Z")

  private def rawMsg(
    guildId:   Option[String] = None,
    isBot:     Boolean = false,
    content:   String = "Hello",
    isMention: Boolean = false,
  ) =
    DiscordRawMessage(
      messageId = "msg-1",
      authorId = "user-42",
      authorName = "Bob",
      channelId = "channel-99",
      guildId = guildId,
      isBot = isBot,
      content = content,
      isMention = isMention,
      receivedAt = now,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DiscordMessageNormalizer")(
      test("normalizes a DM message to Private ChatKind") {
        val result = DiscordMessageNormalizer.normalize(rawMsg(guildId = None))
        assertTrue(
          result.isDefined,
          result.get.chatKind == ChatKind.Private,
          result.get.channelUserId == "user-42",
          result.get.chatRef == "channel-99",
          result.get.content == "Hello",
          result.get.channelType == ChannelType.Discord,
        )
      },
      test("normalizes a guild message to Group ChatKind") {
        val result = DiscordMessageNormalizer.normalize(rawMsg(guildId = Some("guild-1")))
        assertTrue(
          result.isDefined,
          result.get.chatKind == ChatKind.Group,
        )
      },
      test("drops bot messages") {
        val result = DiscordMessageNormalizer.normalize(rawMsg(isBot = true))
        assertTrue(result.isEmpty)
      },
      test("preserves content exactly") {
        val msg = rawMsg(content = "Hello, world! 🎉")
        val result = DiscordMessageNormalizer.normalize(msg)
        assertTrue(result.isDefined, result.get.content == "Hello, world! 🎉")
      },
      test("uses channelId as chatRef") {
        val result = DiscordMessageNormalizer.normalize(rawMsg())
        assertTrue(result.isDefined, result.get.chatRef == "channel-99")
      },
      test("uses authorId as channelUserId") {
        val result = DiscordMessageNormalizer.normalize(rawMsg())
        assertTrue(result.isDefined, result.get.channelUserId == "user-42")
      },
      test("preserves receivedAt") {
        val result = DiscordMessageNormalizer.normalize(rawMsg())
        assertTrue(result.isDefined, result.get.receivedAt == now)
      },
    )

}
