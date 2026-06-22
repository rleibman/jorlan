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

import jorlan.*
import jorlan.connector.*
import jorlan.*
import just.semver.SemVer
import telegramium.bots.Update
import telegramium.bots.Message as TgMessage
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Normalizes a telegramium [[Update]] into a connector-neutral [[InboundMessage]].
  *
  * Handles regular messages (`update.message`) and channel posts (`update.channelPost`). Maps `chat.type` →
  * [[ChatKind]], `from.id` → `channelUserId`, and `chat.id` → `chatRef`. Channel posts use the chat id as sender
  * identity since they have no `from` user.
  */
object TelegramMessageNormalizer {

  def normalize(
    update:     Update,
    receivedAt: java.time.Instant,
  ): Option[InboundMessage] =
    (update.message orElse update.channelPost).flatMap(normalizeMessage(_, receivedAt))

  private def normalizeMessage(
    msg:        TgMessage,
    receivedAt: java.time.Instant,
  ): Option[InboundMessage] =
    msg.text.map { text =>
      val channelUserId = msg.from.map(_.id.toString).getOrElse(msg.chat.id.toString)
      val chatRef = msg.chat.id.toString
      val chatKind = msg.chat.`type` match {
        case "private"    => ChatKind.Private
        case "group"      => ChatKind.Group
        case "channel"    => ChatKind.Channel
        case "supergroup" => ChatKind.Supergroup
        case _            => ChatKind.Private
      }
      InboundMessage(
        channelType = ChannelType.Telegram,
        channelUserId = channelUserId,
        chatRef = chatRef,
        chatKind = chatKind,
        content = text,
        receivedAt = receivedAt,
      )
    }

}

/** Tier-0 [[ConnectorSkill]] for Telegram.
  *
  * Ingress: a long-poll loop calls [[TelegramApiClient.getUpdates]], normalizes each [[Update]] via
  * [[TelegramMessageNormalizer]], and passes it to [[MessageIngress.receive]]. Handles both private messages and
  * channel posts. The loop runs until [[stop]] is called.
  *
  * Egress tools (each gated by capability `telegram.send`):
  *   - `telegram.send_message`
  *   - `telegram.send_photo`
  *   - `telegram.send_file`
  *
  * Note: the reply path (subscribe to session stream → forward to `send_message`) is deferred to Phase 12
  * `NotificationRouter`. See P11-001 in the phase review.
  */
class TelegramConnectorSkill(
  config:         TelegramConfig,
  val instanceId: ConnectorInstanceId,
  apiClient:      TelegramApiClient,
  ingress:        MessageIngress,
  pollingFiber:   Ref[Option[Fiber[Nothing, Unit]]],
) extends ConnectorSkill {

  override val connectorType:       ConnectorType = ConnectorType.Telegram
  override val sendMessageToolName: Option[String] = Some("telegram.send_message")

  private val sendCapability = CapabilityName("telegram.send")

  private val sendMessageSchema =
    """{"type":"object","properties":{"chatId":{"type":"string"},"text":{"type":"string"}},"required":["chatId","text"]}"""
  private val sendPhotoSchema =
    """{"type":"object","properties":{"chatId":{"type":"string"},"photo":{"type":"string","contentEncoding":"base64"},"caption":{"type":"string"}},"required":["chatId","photo"]}"""
  private val sendFileSchema =
    """{"type":"object","properties":{"chatId":{"type":"string"},"file":{"type":"string","contentEncoding":"base64"},"filename":{"type":"string"}},"required":["chatId","file","filename"]}"""
  private val emptyOutputSchema = """{"type":"object","properties":{}}"""

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "telegram",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    configKey = Some("skill.telegram"),
    configJsModule = Some("jorlan-telegram"),
    tools = List(
      ToolDescriptor(
        name = "telegram.send_message",
        description = "Send a text message to a Telegram chat",
        inputSchema = Json.decoder.decodeJson(sendMessageSchema).getOrElse(Json.Null),
        outputSchema = Json.decoder.decodeJson(emptyOutputSchema).getOrElse(Json.Null),
        requiredCapabilities = List(sendCapability),
        examplePrompts = List(
          "Send a Telegram message to chat 123456 saying hello",
          "Text Roberto on Telegram that the build is done",
        ),
      ),
      ToolDescriptor(
        name = "telegram.send_photo",
        description = "Send a photo to a Telegram chat",
        inputSchema = Json.decoder.decodeJson(sendPhotoSchema).getOrElse(Json.Null),
        outputSchema = Json.decoder.decodeJson(emptyOutputSchema).getOrElse(Json.Null),
        requiredCapabilities = List(sendCapability),
        examplePrompts = List(
          "Send this screenshot to Telegram chat 123456",
          "Share the graph image with the team on Telegram",
        ),
      ),
      ToolDescriptor(
        name = "telegram.send_file",
        description = "Send a file/document to a Telegram chat",
        inputSchema = Json.decoder.decodeJson(sendFileSchema).getOrElse(Json.Null),
        outputSchema = Json.decoder.decodeJson(emptyOutputSchema).getOrElse(Json.Null),
        requiredCapabilities = List(sendCapability),
        examplePrompts = List(
          "Send the report PDF to Telegram chat 123456",
          "Upload the log file to the Telegram group",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    val obj = args.asObject.getOrElse(Json.Obj())
    tool match {
      case "telegram.send_message" =>
        val chatId = obj.get("chatId").flatMap(_.asString)
        val text = obj.get("text").flatMap(_.asString)
        (chatId, text) match {
          case (Some(cid), Some(t)) => apiClient.sendMessage(cid, t).as(Json.Obj())
          case (None, _)            => ZIO.fail(JorlanError("chatId is required for telegram.send_message"))
          case (_, None)            => ZIO.fail(JorlanError("text is required for telegram.send_message"))
        }

      case "telegram.send_photo" =>
        val chatId = obj.get("chatId").flatMap(_.asString)
        val photoB64 = obj.get("photo").flatMap(_.asString)
        val caption = obj.get("caption").flatMap(_.asString)
        (chatId, photoB64) match {
          case (Some(cid), Some(b64)) =>
            ZIO
              .attempt(java.util.Base64.getDecoder.decode(b64))
              .mapError(e => JorlanError(s"Invalid base64 for photo: ${e.getMessage}"))
              .flatMap(bytes => apiClient.sendPhoto(cid, bytes, caption).as(Json.Obj()))
          case (None, _) => ZIO.fail(JorlanError("chatId is required for telegram.send_photo"))
          case (_, None) => ZIO.fail(JorlanError("photo (base64) is required for telegram.send_photo"))
        }

      case "telegram.send_file" =>
        val chatId = obj.get("chatId").flatMap(_.asString)
        val fileB64 = obj.get("file").flatMap(_.asString)
        val filename = obj.get("filename").flatMap(_.asString)
        (chatId, fileB64, filename) match {
          case (Some(cid), Some(b64), Some(fname)) =>
            ZIO
              .attempt(java.util.Base64.getDecoder.decode(b64))
              .mapError(e => JorlanError(s"Invalid base64 for file: ${e.getMessage}"))
              .flatMap(bytes => apiClient.sendDocument(cid, bytes, fname).as(Json.Obj()))
          case (None, _, _) => ZIO.fail(JorlanError("chatId is required for telegram.send_file"))
          case (_, None, _) => ZIO.fail(JorlanError("file (base64) is required for telegram.send_file"))
          case (_, _, None) => ZIO.fail(JorlanError("filename is required for telegram.send_file"))
        }

      case other => ZIO.fail(JorlanError(s"Unknown telegram tool: $other"))
    }
  }

  override def start: IO[JorlanError, Unit] =
    pollingFiber.get.flatMap {
      case Some(_) => ZIO.unit
      case None    =>
        ZIO.when(config.useWebhook)(
          ZIO.logWarning(
            "[telegram] useWebhook=true is not yet implemented — falling back to long-polling. " +
              "Webhook ingress requires a publicly reachable HTTPS route wired into the server.",
          ),
        ) *>
          ZIO.logInfo("[telegram] deleting any active webhook before starting long-poll loop") *>
          apiClient.deleteWebhook
            .tapError(e => ZIO.logWarning(s"[telegram] deleteWebhook failed (continuing): ${e.msg}")).ignore *>
          pollLoop(offset = 0L).forkDaemon
            .flatMap(f => pollingFiber.set(Some(f)))
    }
  // ZIO 2 fiber scheduling trampolines recursive flatMap chains — this is stack-safe.
  // sandbox converts both typed failures and defects into a unified Cause, catchAll handles both.
  private def pollLoop(offset: Long): UIO[Unit] =
    pollStep(offset).sandbox
      .catchAll(cause =>
        ZIO
          .logWarning(
            s"[telegram] polling error: ${Option(cause.squash.getMessage).getOrElse(cause.squash.toString)}",
          ).as(offset),
      )
      .flatMap(pollLoop)

  private def pollStep(offset: Long): IO[JorlanError, Long] =
    apiClient
      .getUpdates(offset, timeoutSeconds = 30).flatMap { updates =>
        val filtered = filterUpdates(updates)
        ZIO
          .foreachParDiscard(filtered) { update =>
            Clock.instant.flatMap { now =>
              TelegramMessageNormalizer.normalize(update, now) match {
                case None      => ZIO.unit
                case Some(msg) =>
                  val chatId = msg.chatRef
                  ingress
                    .receive(
                      msg,
                      config.unrecognizedPolicy,
                      onResponse = Some(text =>
                        apiClient
                          .sendMessage(chatId, text)
                          .tapError(e => ZIO.logWarning(s"[telegram] reply failed for chat $chatId: ${e.msg}"))
                          .ignore,
                      ),
                    )
                    .tapError(e => ZIO.logWarning(s"[telegram] ingress error for update ${update.updateId}: ${e.msg}"))
                    .ignore
              }
            }
          }
          .as(updates.maxByOption(_.updateId).map(_.updateId.toLong + 1L).getOrElse(offset))
      }

  override def stop: IO[JorlanError, Unit] =
    pollingFiber.get.flatMap {
      case Some(f) => f.interrupt.unit *> pollingFiber.set(None)
      case None    => ZIO.unit
    }

  private def filterUpdates(updates: List[Update]): List[Update] = {
    val chatFiltered =
      if (config.allowedChatIds.isEmpty) updates
      else
        updates.filter { u =>
          val chatId = (u.message orElse u.channelPost).map(_.chat.id.toString)
          chatId.exists(config.allowedChatIds.contains)
        }
    if (config.allowedUserIds.isEmpty) chatFiltered
    else
      chatFiltered.filter { u =>
        val userId = (u.message orElse u.channelPost).flatMap(_.from).map(_.id.toString)
        userId.exists(config.allowedUserIds.contains)
      }
  }

}

object TelegramConnectorSkill {

  /** Build a [[TelegramConnectorSkill]] instance.
    *
    * @param config
    *   parsed [[TelegramConfig]] from the bound [[ConnectorInstance]]
    * @param instanceId
    *   the connector instance identifier
    * @param apiClient
    *   the Telegram Bot API client (live or fake)
    * @param ingress
    *   the connector-agnostic ingress pipeline
    * @return
    *   a ready-to-start [[TelegramConnectorSkill]]
    */
  def make(
    config:     TelegramConfig,
    instanceId: ConnectorInstanceId,
    apiClient:  TelegramApiClient,
    ingress:    MessageIngress,
  ): UIO[TelegramConnectorSkill] =
    Ref.make(Option.empty[Fiber[Nothing, Unit]]).map { fiberRef =>
      TelegramConnectorSkill(config, instanceId, apiClient, ingress, fiberRef)
    }

}
