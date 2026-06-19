/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.connector.telegram

import jorlan.*
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
