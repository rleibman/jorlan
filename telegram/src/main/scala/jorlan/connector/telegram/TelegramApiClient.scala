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

import io.circe.parser as circeParser
import jorlan.*
import jorlan.connector.UnrecognizedIdentityPolicy
import telegramium.bots.CirceImplicits.given
import telegramium.bots.Update
import zio.*
import zio.http.*
import zio.json.*

import java.io.ByteArrayOutputStream

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
  *   Reserved for future use — long-polling is always used in Phase 11. Setting this to `true` has no effect.
  *   // TODO Phase 12: implement webhook ingress path when useWebhook = true
  */
case class TelegramConfig(
  botToken:           String,
  allowedChatIds:     Set[String] = Set.empty,
  allowedUserIds:     Set[String] = Set.empty,
  unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
  useWebhook:         Boolean = false,
  apiBaseUrl:         String = "https://api.telegram.org",
) derives JsonEncoder, JsonDecoder

// ─── Trait ───────────────────────────────────────────────────────────────────

/** Minimal Telegram Bot API client used by [[TelegramConnectorSkill]].
  *
  * Uses telegramium-core's generated types (`telegramium.bots.Update`) for the parsed update payloads. The HTTP
  * transport uses zio-http; JSON decoding uses the circe codecs bundled with telegramium-core.
  */
trait TelegramApiClient {

  /** Long-poll for new updates from the Telegram Bot API.
    *
    * @param offset
    *   identifier of the first update to return; pass `lastUpdateId + 1` to confirm prior updates
    * @param timeoutSeconds
    *   long-poll timeout in seconds; 0 for short-polling
    * @return
    *   list of updates (may be empty); on API error returns an empty list after logging a warning
    */
  def getUpdates(
    offset:         Long,
    timeoutSeconds: Int,
  ): IO[JorlanError, List[Update]]

  /** Send a text message to a chat.
    *
    * @param chatId
    *   Telegram chat id (numeric id as string, or `@username` for channels)
    * @param text
    *   message text (UTF-8)
    */
  def sendMessage(
    chatId: String,
    text:   String,
  ): IO[JorlanError, Unit]

  /** Send a photo to a chat.
    *
    * @param chatId
    *   Telegram chat id
    * @param photo
    *   raw bytes of the image (JPEG, PNG, etc.)
    * @param caption
    *   optional caption displayed below the photo
    */
  def sendPhoto(
    chatId:  String,
    photo:   Array[Byte],
    caption: Option[String],
  ): IO[JorlanError, Unit]

  /** Send a file/document to a chat.
    *
    * @param chatId
    *   Telegram chat id
    * @param file
    *   raw bytes of the file
    * @param filename
    *   filename shown to the recipient
    */
  def sendDocument(
    chatId:   String,
    file:     Array[Byte],
    filename: String,
  ): IO[JorlanError, Unit]

}

// ─── Live implementation ──────────────────────────────────────────────────────

/** [[TelegramApiClient]] backed by a real Telegram Bot API endpoint via zio-http.
  *
  * Uses `Client.batched` (no streaming) for all requests. Responses are decoded using circe with the codecs from
  * telegramium-core.
  */
class TelegramApiClientLive(
  config: TelegramConfig,
  client: Client,
) extends TelegramApiClient {

  private val base = s"${config.apiBaseUrl}/bot${config.botToken}"
  private val MultipartBoundary = "JorlanBoundary"

  private def run[A](effect: ZIO[Client, JorlanError, A]): IO[JorlanError, A] =
    effect.provideEnvironment(ZEnvironment(client))

  private def checkSuccess(response: Response): IO[JorlanError, Unit] =
    if (response.status.isSuccess) ZIO.unit
    else
      response.body.asString
        .mapError(e => JorlanError(s"Failed to read error body: $e"))
        .flatMap(body => ZIO.fail(JorlanError(s"Telegram API error: $body")))

  private def buildMultipartBody(
    chatId:    String,
    fieldName: String,
    fileName:  String,
    fileData:  Array[Byte],
    mimeType:  String,
    caption:   Option[String] = None,
  ): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    def write(s: String): Unit = out.write(s.getBytes("UTF-8"))
    write(s"--$MultipartBoundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n")
    caption.foreach { c =>
      write(s"--$MultipartBoundary\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n$c\r\n")
    }
    write(
      s"--$MultipartBoundary\r\nContent-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\nContent-Type: $mimeType\r\n\r\n",
    )
    out.write(fileData)
    write(s"\r\n--$MultipartBoundary--")
    out.toByteArray
  }

  override def getUpdates(
    offset:         Long,
    timeoutSeconds: Int,
  ): IO[JorlanError, List[Update]] = {
    val url = s"$base/getUpdates?offset=$offset&timeout=$timeoutSeconds"
    run {
      Client
        .batched(Request.get(url))
        .mapError(e => JorlanError(s"Telegram getUpdates failed: $e"))
        .flatMap { resp =>
          resp.body.asString
            .mapError(e => JorlanError(s"Telegram getUpdates failed: $e"))
            .flatMap { body =>
              if (!resp.status.isSuccess)
                ZIO.logWarning(s"[telegram] getUpdates non-2xx ${resp.status.code}: $body") *> ZIO.succeed(Nil)
              else
                ZIO
                  .fromEither {
                    for {
                      json    <- circeParser.parse(body).left.map(e => JorlanError(s"Telegram JSON parse error: $e"))
                      updates <- json.hcursor
                        .downField("result").as[List[Update]].left.map(e =>
                          JorlanError(s"Telegram getUpdates decode failed: $e"),
                        )
                    } yield updates
                  }.catchAll {
                    case _ if body.contains("\"ok\":false") =>
                      ZIO.logWarning(s"[telegram] API returned error: $body") *> ZIO.succeed(Nil)
                    case err => ZIO.fail(err)
                  }
            }
        }
    }
  }

  override def sendMessage(
    chatId: String,
    text:   String,
  ): IO[JorlanError, Unit] = {
    val bodyStr = s"""{"chat_id":"$chatId","text":${io.circe.Json.fromString(text).noSpaces}}"""
    run {
      Client
        .batched(
          Request
            .post(s"$base/sendMessage", Body.fromString(bodyStr))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        .mapError(e => JorlanError(s"Telegram sendMessage failed: $e"))
        .flatMap(checkSuccess)
    }
  }

  override def sendPhoto(
    chatId:  String,
    photo:   Array[Byte],
    caption: Option[String],
  ): IO[JorlanError, Unit] = {
    val bodyBytes = buildMultipartBody(chatId, "photo", "photo.jpg", photo, "image/jpeg", caption)
    run {
      Client
        .batched(
          Request
            .post(s"$base/sendPhoto", Body.fromChunk(zio.Chunk.fromArray(bodyBytes)))
            .addHeader(
              Header.ContentType(
                MediaType("multipart", "form-data").copy(parameters = Map("boundary" -> MultipartBoundary)),
              ),
            ),
        )
        .mapError(e => JorlanError(s"Telegram sendPhoto failed: $e"))
        .flatMap(checkSuccess)
    }
  }

  override def sendDocument(
    chatId:   String,
    file:     Array[Byte],
    filename: String,
  ): IO[JorlanError, Unit] = {
    val bodyBytes = buildMultipartBody(chatId, "document", filename, file, "application/octet-stream")
    run {
      Client
        .batched(
          Request
            .post(s"$base/sendDocument", Body.fromChunk(zio.Chunk.fromArray(bodyBytes)))
            .addHeader(
              Header.ContentType(
                MediaType("multipart", "form-data").copy(parameters = Map("boundary" -> MultipartBoundary)),
              ),
            ),
        )
        .mapError(e => JorlanError(s"Telegram sendDocument failed: $e"))
        .flatMap(checkSuccess)
    }
  }

}

object TelegramApiClientLive {

  /** Create a [[TelegramApiClientLive]] using the given [[TelegramConfig]] and the zio-http [[Client]] from the
    * environment.
    *
    * @param config
    *   bot configuration including the bot token
    * @return
    *   a live [[TelegramApiClient]] wired to the Telegram Bot API
    */
  def make(config: TelegramConfig): URIO[Client, TelegramApiClient] =
    ZIO.service[Client].map(TelegramApiClientLive(config, _))

}
