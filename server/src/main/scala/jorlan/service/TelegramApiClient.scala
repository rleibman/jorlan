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
import jorlan.domain.UnrecognizedIdentityPolicy
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

// ─── Config ──────────────────────────────────────────────────────────────────

/** Runtime configuration for a single Telegram bot connector instance.
  *
  * @param botToken
  *   Telegram Bot API token. Never logged or returned to unprivileged callers.
  * @param allowedChatIds
  *   When non-empty, messages from chats not in this set are dropped before identity resolution.
  * @param allowedUserIds
  *   When non-empty, messages from users not in this set are dropped before identity resolution.
  * @param unrecognizedPolicy
  *   What to do when the sender does not resolve to a known Jorlan user.
  * @param useWebhook
  *   When `true`, the connector expects Telegram to push updates via webhook rather than long-polling.
  *   Long-poll first; webhook is a later toggle.
  */
case class TelegramConfig(
  botToken:              String,
  allowedChatIds:        Set[String] = Set.empty,
  allowedUserIds:        Set[String] = Set.empty,
  unrecognizedPolicy:    UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
  useWebhook:            Boolean = false,
) derives JsonEncoder, JsonDecoder

// ─── Wire types (Telegram Bot API) ───────────────────────────────────────────

/** A Telegram user object returned inside updates. */
case class TelegramUser(
  id:         Long,
  is_bot:     Boolean,
  first_name: String,
  username:   Option[String],
) derives JsonEncoder, JsonDecoder

/** A Telegram chat object. */
case class TelegramChat(
  id:    Long,
  `type`: String,
  title: Option[String],
) derives JsonEncoder, JsonDecoder

/** A single Telegram message inside an update. */
case class TelegramMessage(
  message_id: Long,
  from:       Option[TelegramUser],
  chat:       TelegramChat,
  text:       Option[String],
) derives JsonEncoder, JsonDecoder

/** One update item as returned by `getUpdates`. */
case class TelegramUpdate(
  update_id: Long,
  message:   Option[TelegramMessage],
) derives JsonEncoder, JsonDecoder

/** Wrapper around a Telegram Bot API response. */
case class TelegramResponse[A](
  ok:     Boolean,
  result: Option[A],
  description: Option[String],
)

object TelegramResponse {

  given [A: JsonDecoder]: JsonDecoder[TelegramResponse[A]] =
    DeriveJsonDecoder.gen[TelegramResponse[A]]

}

// ─── Trait ───────────────────────────────────────────────────────────────────

/** Minimal Telegram Bot API client used by [[TelegramConnectorSkill]].
  *
  * The trait is kept narrow so tests can inject a [[FakeTelegramApiClient]] without a live bot token.
  */
trait TelegramApiClient {

  def getUpdates(
    offset:         Long,
    timeoutSeconds: Int,
  ): IO[JorlanError, List[TelegramUpdate]]

  def sendMessage(
    chatId: String,
    text:   String,
  ): IO[JorlanError, Unit]

  def sendPhoto(
    chatId:  String,
    photo:   Array[Byte],
    caption: Option[String],
  ): IO[JorlanError, Unit]

  def sendDocument(
    chatId:   String,
    file:     Array[Byte],
    filename: String,
  ): IO[JorlanError, Unit]

}

// ─── Live implementation ──────────────────────────────────────────────────────

/** [[TelegramApiClient]] backed by a real Telegram Bot API endpoint via zio-http. */
class TelegramApiClientLive(
  config: TelegramConfig,
  client: Client,
  scope:  Scope,
) extends TelegramApiClient {

  private val base = s"https://api.telegram.org/bot${config.botToken}"

  override def getUpdates(
    offset:         Long,
    timeoutSeconds: Int,
  ): IO[JorlanError, List[TelegramUpdate]] = {
    val url = s"$base/getUpdates?offset=$offset&timeout=$timeoutSeconds"
    Client
      .request(Request.get(url))
      .flatMap(_.body.asString)
      .mapError(e => JorlanError(s"Telegram getUpdates failed: $e"))
      .flatMap { body =>
        ZIO
          .fromEither(body.fromJson[TelegramResponse[List[TelegramUpdate]]])
          .mapError(e => JorlanError(s"Telegram getUpdates decode failed: $e"))
          .flatMap {
            case TelegramResponse(true, Some(updates), _) => ZIO.succeed(updates)
            case TelegramResponse(_, _, Some(desc))       => ZIO.fail(JorlanError(s"Telegram API error: $desc"))
            case _                                        => ZIO.succeed(Nil)
          }
      }
      .provideSome[Any](ZLayer.succeed(client), ZLayer.succeed(scope))
  }

  override def sendMessage(
    chatId: String,
    text:   String,
  ): IO[JorlanError, Unit] = {
    val body = Json.Obj(
      "chat_id" -> Json.Str(chatId),
      "text"    -> Json.Str(text),
    )
    Client
      .request(
        Request
          .post(s"$base/sendMessage", Body.fromString(body.toJson))
          .addHeader(Header.ContentType(MediaType.application.json)),
      )
      .flatMap(r => if (r.status.isSuccess) ZIO.unit else r.body.asString.flatMap(b => ZIO.fail(new Exception(b))))
      .mapError(e => JorlanError(s"Telegram sendMessage failed: $e"))
      .provideSome[Any](ZLayer.succeed(client), ZLayer.succeed(scope))
  }

  override def sendPhoto(
    chatId:  String,
    photo:   Array[Byte],
    caption: Option[String],
  ): IO[JorlanError, Unit] = {
    val boundary = "ZioBoundary"
    val captionPart = caption.fold("")(c => s"--$boundary\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n$c\r\n")
    val header = s"--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n$captionPart--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
    val footer = s"\r\n--$boundary--"
    val bodyBytes = header.getBytes("UTF-8") ++ photo ++ footer.getBytes("UTF-8")
    Client
      .request(
        Request
          .post(s"$base/sendPhoto", Body.fromChunk(zio.Chunk.fromArray(bodyBytes)))
          .addHeader(Header.ContentType(MediaType("multipart", "form-data").copy(parameters = Map("boundary" -> boundary)))),
      )
      .flatMap(r => if (r.status.isSuccess) ZIO.unit else r.body.asString.flatMap(b => ZIO.fail(new Exception(b))))
      .mapError(e => JorlanError(s"Telegram sendPhoto failed: $e"))
      .provideSome[Any](ZLayer.succeed(client), ZLayer.succeed(scope))
  }

  override def sendDocument(
    chatId:   String,
    file:     Array[Byte],
    filename: String,
  ): IO[JorlanError, Unit] = {
    val boundary = "ZioBoundary"
    val header = s"--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n"
    val footer = s"\r\n--$boundary--"
    val bodyBytes = header.getBytes("UTF-8") ++ file ++ footer.getBytes("UTF-8")
    Client
      .request(
        Request
          .post(s"$base/sendDocument", Body.fromChunk(zio.Chunk.fromArray(bodyBytes)))
          .addHeader(Header.ContentType(MediaType("multipart", "form-data").copy(parameters = Map("boundary" -> boundary)))),
      )
      .flatMap(r => if (r.status.isSuccess) ZIO.unit else r.body.asString.flatMap(b => ZIO.fail(new Exception(b))))
      .mapError(e => JorlanError(s"Telegram sendDocument failed: $e"))
      .provideSome[Any](ZLayer.succeed(client), ZLayer.succeed(scope))
  }

}

object TelegramApiClientLive {

  val live: URLayer[TelegramConfig & Client & Scope, TelegramApiClient] =
    ZLayer.fromFunction(TelegramApiClientLive(_, _, _))

}

// ─── Fake (for tests) ─────────────────────────────────────────────────────────

/** Test double for [[TelegramApiClient]].
  *
  * Canned updates are provided at construction time; each call to [[getUpdates]] drains them in order (returning
  * `Nil` once exhausted). Sent messages are accumulated in [[sentMessages]] for assertion.
  */
class FakeTelegramApiClient(
  updates:      Ref[List[TelegramUpdate]],
  val sentMessages: Ref[List[(String, String)]],
) extends TelegramApiClient {

  override def getUpdates(
    offset:         Long,
    timeoutSeconds: Int,
  ): IO[JorlanError, List[TelegramUpdate]] =
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

}

object FakeTelegramApiClient {

  def make(updates: List[TelegramUpdate] = Nil): UIO[FakeTelegramApiClient] =
    (Ref.make(updates) <*> Ref.make(List.empty[(String, String)])).map { case (u, s) =>
      FakeTelegramApiClient(u, s)
    }

  def layer(updates: List[TelegramUpdate] = Nil): ULayer[TelegramApiClient] =
    ZLayer(make(updates).map(f => f: TelegramApiClient))

}
