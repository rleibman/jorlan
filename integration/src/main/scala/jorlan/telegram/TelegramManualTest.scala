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

import jorlan.connector.telegram.*
import zio.*
import zio.http.Client

import scala.annotation.tailrec

/** Manual smoke test for the Telegram connector.
  *
  * Not part of the automated test suite — run explicitly with:
  * {{{
  *   TELEGRAM_BOT_TOKEN=<token> TELEGRAM_CHAT_ID=<chat_id> sbt "integration/runMain jorlan.telegram.TelegramManualTest"
  * }}}
  *
  * What it does:
  *   1. Sends a single text message to [[chatId]] via the live bot.
  *   2. Long-polls for [[pollSeconds]] seconds, printing each received message to stdout.
  *
  * Fill in [[botToken]] and [[chatId]] below, or supply them through the environment variables `TELEGRAM_BOT_TOKEN` and
  * `TELEGRAM_CHAT_ID`.
  */
object TelegramManualTest extends ZIOAppDefault {

  // How many seconds to poll for incoming messages after sending.
  private val pollSeconds: Int = 10

  // ─── App ─────────────────────────────────────────────────────────────────────

  override def run: ZIO[ZIOAppArgs & Scope, Throwable, Unit] =
    ZIO
      .scoped {
        for {
          botToken <- ZIO.attempt(sys.env.getOrElse("TELEGRAM_BOT_TOKEN", "YOUR_BOT_TOKEN_HERE"))
          chatId   <- ZIO.attempt(sys.env.getOrElse("TELEGRAM_CHAT_ID", "YOUR_CHAT_ID_HERE"))
          groupChatId = sys.env.getOrElse("TELEGRAM_GROUP_CHAT_ID", "")
          config = TelegramConfig(botToken = botToken)
          _ <- ZIO.logInfo(s"TelegramManualTest starting — chat_id=$chatId, poll=${pollSeconds}s")
          _ <- ZIO.when(botToken == "YOUR_BOT_TOKEN_HERE" || chatId == "YOUR_CHAT_ID_HERE") {
            ZIO.die(
              new IllegalStateException(
                "Set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID environment variables before running this test.",
              ),
            )
          }
          client <- ZIO.service[Client]
          apiClient = TelegramApiClientLive(config, client)
          _ <- ZIO.logInfo("Sending test message to private chat…")
          _ <- apiClient.sendMessage(chatId, "Hello from Jorlan! (TelegramManualTest - private)")
          _ <- ZIO.when(groupChatId.nonEmpty) {
            ZIO.logInfo(s"Sending test message to group chat $groupChatId…") *>
              apiClient.sendMessage(groupChatId, "Hello from Jorlan! (TelegramManualTest - group)")
          }
          _ <- ZIO.logInfo("Messages sent. Polling for incoming messages…")
          _ <- pollAndPrint(apiClient, offset = 0L, remaining = pollSeconds)
          _ <- ZIO.logInfo("Done.")
        } yield ()
      }.provide(Client.default)

  private def pollAndPrint(
    apiClient: TelegramApiClient,
    offset:    Long,
    remaining: Int,
  ): Task[Unit] =
    apiClient
      .getUpdates(offset, timeoutSeconds = 5).flatMap { updates =>
        ZIO.foreachDiscard(updates) { u =>
          val text = (u.message orElse u.channelPost)
            .flatMap(_.text)
            .getOrElse("<no text>")
          val from = (u.message orElse u.channelPost)
            .flatMap(_.from)
            .map(u => s"${u.firstName} (id=${u.id})")
            .getOrElse("channel")
          ZIO.logInfo(s"  [update ${u.updateId}] from=$from text=$text")
        } *> {
          val nextOffset = updates.map(_.updateId.toLong).maxOption.map(_ + 1L).getOrElse(offset)
          pollAndPrint(apiClient, nextOffset, remaining - 5)
        }
      }.mapError(e => new RuntimeException(e.getMessage, e)).unless(remaining <= 0).unit

}
