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

import jorlan.domain.*
import zio.test.*

object TelegramMessageNormalizerSpec extends ZIOSpecDefault {

  private def makeUpdate(
    chatType: String,
    fromId:   Long = 42L,
    chatId:   Long = 100L,
    text:     String = "hello",
  ): TelegramUpdate =
    TelegramUpdate(
      update_id = 1L,
      message = Some(
        TelegramMessage(
          message_id = 1L,
          from = Some(TelegramUser(id = fromId, is_bot = false, first_name = "Test", username = None)),
          chat = TelegramChat(id = chatId, `type` = chatType, title = None),
          text = Some(text),
        ),
      ),
    )

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("TelegramMessageNormalizer")(
      test("normalizes private chat") {
        val update = makeUpdate("private", fromId = 42L, chatId = 42L)
        val result = TelegramMessageNormalizer.normalize(update)
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
        val result = TelegramMessageNormalizer.normalize(update)
        assertTrue(
          result.exists(_.chatKind == ChatKind.Group),
          result.exists(_.channelUserId == "42"),
          result.exists(_.chatRef == "-100"),
        )
      },
      test("normalizes channel — uses chat id as sender when no from") {
        val update = TelegramUpdate(
          update_id = 2L,
          message = Some(
            TelegramMessage(
              message_id = 2L,
              from = None,
              chat = TelegramChat(id = -200L, `type` = "channel", title = Some("My Channel")),
              text = Some("announcement"),
            ),
          ),
        )
        val result = TelegramMessageNormalizer.normalize(update)
        assertTrue(
          result.exists(_.chatKind == ChatKind.Channel),
          result.exists(_.channelUserId == "-200"),
          result.exists(_.chatRef == "-200"),
        )
      },
      test("normalizes supergroup") {
        val update = makeUpdate("supergroup", fromId = 99L, chatId = -300L)
        val result = TelegramMessageNormalizer.normalize(update)
        assertTrue(result.exists(_.chatKind == ChatKind.Supergroup))
      },
      test("returns None for update without message") {
        val update = TelegramUpdate(update_id = 3L, message = None)
        assertTrue(TelegramMessageNormalizer.normalize(update).isEmpty)
      },
      test("returns None for message without text") {
        val update = TelegramUpdate(
          update_id = 4L,
          message = Some(
            TelegramMessage(
              message_id = 4L,
              from = Some(TelegramUser(id = 1L, is_bot = false, first_name = "Bot", username = None)),
              chat = TelegramChat(id = 1L, `type` = "private", title = None),
              text = None,
            ),
          ),
        )
        assertTrue(TelegramMessageNormalizer.normalize(update).isEmpty)
      },
    )

}
