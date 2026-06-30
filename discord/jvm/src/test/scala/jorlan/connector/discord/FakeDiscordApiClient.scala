/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.connector.discord

import jorlan.*
import jorlan.discord.{DiscordApiClient, DiscordRawMessage}
import zio.*
import zio.json.ast.Json

/** Test double for [[DiscordApiClient]].
  *
  * Canned events are drained in order via the internal queue. Sent channel messages and DMs are recorded for assertion.
  * Intended for use only in test scope.
  */
class FakeDiscordApiClient(
  events:           Ref[List[DiscordRawMessage]],
  val sentMessages: Ref[List[(String, String)]],
  val sentDms:      Ref[List[(String, String)]],
  firstCallPromise: Option[Promise[Nothing, Unit]] = None,
) extends DiscordApiClient {

  override def connect(): IO[JorlanError, Unit] = ZIO.unit

  override def disconnect(): UIO[Unit] = ZIO.unit

  override def nextEvent(): IO[JorlanError, Option[DiscordRawMessage]] =
    firstCallPromise.fold(ZIO.unit)(_.succeed(()).unit) *>
      events
        .modify { all =>
          all match {
            case head :: tail => (Some(head), tail)
            case Nil          => (None, Nil)
          }
        }.flatMap {
          case Some(msg) => ZIO.succeed(Some(msg))
          case None      =>
            // Block indefinitely until interrupted — simulates waiting for gateway events
            ZIO.never
        }

  override def sendToChannel(
    channelId: String,
    content:   String,
  ): IO[JorlanError, Unit] =
    sentMessages.update(_ :+ (channelId, content))

  override def sendToDm(
    userId:  String,
    content: String,
  ): IO[JorlanError, Unit] =
    sentDms.update(_ :+ (userId, content))

  override def getChannelHistory(
    channelId: String,
    limit:     Int,
  ): IO[JorlanError, List[Json]] =
    ZIO.succeed(List.empty)

  override def getChannelInfo(channelId: String): IO[JorlanError, Json] =
    ZIO.succeed(
      Json.Obj(
        "id"        -> Json.Str(channelId),
        "name"      -> Json.Str("test-channel"),
        "guildId"   -> Json.Str("guild-1"),
        "guildName" -> Json.Str("Test Guild"),
        "type"      -> Json.Str("TEXT"),
      ),
    )

}

object FakeDiscordApiClient {

  def make(
    events:           List[DiscordRawMessage] = List.empty,
    firstCallPromise: Option[Promise[Nothing, Unit]] = None,
  ): UIO[FakeDiscordApiClient] =
    for {
      eventsRef <- Ref.make(events)
      sentRef   <- Ref.make(List.empty[(String, String)])
      dmsRef    <- Ref.make(List.empty[(String, String)])
    } yield FakeDiscordApiClient(eventsRef, sentRef, dmsRef, firstCallPromise)

}
