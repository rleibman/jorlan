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

import jorlan.ChannelType
import jorlan.connector.*
import jorlan.*
import jorlan.telegram.TelegramMessageNormalizer
import telegramium.bots.{Chat, Update}
import telegramium.bots.{Message as TgMessage, User as TgUser}
import zio.test.*

import java.time.Instant

object TelegramMessageNormalizerSpec extends ZIOSpecDefault {

  private def makeUpdate(
    chatType: String,
    fromId:   Long = 42L,
    chatId:   Long = 100L,
    text:     String = "hello",
  ): Update =
    Update(
      updateId = 1,
      message = Some(
        TgMessage(
          messageId = 1,
          date = 0,
          chat = Chat(id = chatId, `type` = chatType),
          from = Some(TgUser(id = fromId, isBot = false, firstName = "Test")),
          text = Some(text),
        ),
      ),
    )

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("TelegramMessageNormalizer")(
      test("normalizes private chat") {
        val update = makeUpdate("private", fromId = 42L, chatId = 42L)
        val result = TelegramMessageNormalizer.normalize(update, Instant.EPOCH)
        assertTrue(
          result.isDefined,
          result.exists(_.chatKind == ChatKind.Private),
          result.exists(_.channelUserId == "42"),
          result.exists(_.chatRef == "42"),
          result.exists(_.content == "hello"),
          result.exists(_.channelType == ChannelType.Telegram),
        )
      },
      test("normalizes group chat") {
        val update = makeUpdate("group", fromId = 42L, chatId = -100L)
        val result = TelegramMessageNormalizer.normalize(update, Instant.EPOCH)
        assertTrue(
          result.exists(_.chatKind == ChatKind.Group),
          result.exists(_.channelUserId == "42"),
          result.exists(_.chatRef == "-100"),
        )
      },
      test("normalizes channel post — uses chat id as sender when no from") {
        val update = Update(
          updateId = 2,
          channelPost = Some(
            TgMessage(
              messageId = 2,
              date = 0,
              chat = Chat(id = -200L, `type` = "channel"),
              from = None,
              text = Some("announcement"),
            ),
          ),
        )
        val result = TelegramMessageNormalizer.normalize(update, Instant.EPOCH)
        assertTrue(
          result.exists(_.chatKind == ChatKind.Channel),
          result.exists(_.channelUserId == "-200"),
          result.exists(_.chatRef == "-200"),
        )
      },
      test("normalizes supergroup") {
        val update = makeUpdate("supergroup", fromId = 99L, chatId = -300L)
        val result = TelegramMessageNormalizer.normalize(update, Instant.EPOCH)
        assertTrue(result.exists(_.chatKind == ChatKind.Supergroup))
      },
      test("unknown chat type falls back to Private") {
        val update = makeUpdate("unknown_type", fromId = 42L, chatId = 42L)
        val result = TelegramMessageNormalizer.normalize(update, Instant.EPOCH)
        assertTrue(result.exists(_.chatKind == ChatKind.Private))
      },
      test("returns None for update without message or channelPost") {
        val update = Update(updateId = 3)
        assertTrue(TelegramMessageNormalizer.normalize(update, Instant.EPOCH).isEmpty)
      },
      test("returns None for message without text") {
        val update = Update(
          updateId = 4,
          message = Some(
            TgMessage(
              messageId = 4,
              date = 0,
              chat = Chat(id = 1L, `type` = "private"),
              from = Some(TgUser(id = 1L, isBot = false, firstName = "Bot")),
              text = None,
            ),
          ),
        )
        assertTrue(TelegramMessageNormalizer.normalize(update, Instant.EPOCH).isEmpty)
      },
    )

}
