/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.telegram

import jorlan.*
import jorlan.telegram.TelegramApiClient
import telegramium.bots.Update
import zio.*

/** Test double for [[TelegramApiClient]] using telegramium's [[Update]] type.
  *
  * Canned updates are drained in order; sent messages are recorded for assertion. Intended for use only in test scope.
  */
class FakeTelegramApiClient(
  updates:          Ref[List[Update]],
  val sentMessages: Ref[List[(String, String)]],
  firstCallPromise: Option[Promise[Nothing, Unit]] = None,
) extends TelegramApiClient {

  override def getUpdates(
    offset:         Long,
    timeoutSeconds: Int,
  ): IO[JorlanError, List[Update]] =
    firstCallPromise.fold(ZIO.unit)(_.succeed(()).unit) *>
      updates.modify { all =>
        val (batch, rest) = all.splitAt(10)
        (batch, rest)
      }

  override def sendMessage(
    chatId: String,
    text:   String,
  ): IO[JorlanError, Unit] =
    sentMessages.update(_ :+ (chatId, text))

  override def sendPhoto(
    chatId:  String,
    photo:   Array[Byte],
    caption: Option[String],
  ): IO[JorlanError, Unit] = ZIO.unit

  override def sendDocument(
    chatId:   String,
    file:     Array[Byte],
    filename: String,
  ): IO[JorlanError, Unit] = ZIO.unit

  override def deleteWebhook: IO[JorlanError, Unit] = ZIO.unit

}

object FakeTelegramApiClient {

  def make(
    updates:          List[Update] = List.empty,
    firstCallPromise: Option[Promise[Nothing, Unit]] = None,
  ): UIO[FakeTelegramApiClient] =
    (Ref.make(updates) <*> Ref.make(List.empty[(String, String)])).map { case (u, s) =>
      FakeTelegramApiClient(u, s, firstCallPromise)
    }

  def layer(updates: List[Update] = List.empty): ULayer[TelegramApiClient] =
    ZLayer(make(updates).map(f => f: TelegramApiClient))

}
