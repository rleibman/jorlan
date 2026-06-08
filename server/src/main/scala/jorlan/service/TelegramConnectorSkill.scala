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
import jorlan.domain.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** Normalizes a raw [[TelegramUpdate]] into a connector-neutral [[InboundMessage]].
  *
  * Maps Telegram `chat.type` → [[ChatKind]], `from.id` → `channelUserId`, and `chat.id` → `chatRef`. Messages without
  * a `from` field (e.g. channel posts) use the chat id as the sender identity.
  */
object TelegramMessageNormalizer {

  def normalize(update: TelegramUpdate): Option[InboundMessage] =
    update.message.flatMap { msg =>
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
          receivedAt = java.time.Instant.now(),
        )
      }
    }

}

/** Tier-0 [[ConnectorSkill]] for Telegram.
  *
  * Ingress: a long-poll loop calls [[TelegramApiClient.getUpdates]], normalizes each update via
  * [[TelegramMessageNormalizer]], and passes it to [[MessageIngress.receive]]. The loop runs until [[stop]] is called.
  *
  * Egress tools (each gated by capability `telegram.send`):
  *   - `telegram.send_message`
  *   - `telegram.send_photo`
  *   - `telegram.send_file`
  *
  * Reply routing: after dispatching a message, the skill subscribes to the session stream and sends the assembled reply
  * back via `sendMessage` to the originating chat.
  */
class TelegramConnectorSkill(
  config:         TelegramConfig,
  val instanceId: ConnectorInstanceId,
  apiClient:      TelegramApiClient,
  ingress:        MessageIngress,
  agentRunner:    AgentRunner,
  pollingFiber:   Ref[Option[Fiber[Nothing, Unit]]],
) extends ConnectorSkill {

  override val connectorType: ConnectorType = ConnectorType.Telegram

  private val sendCapability = CapabilityName("telegram.send")

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "telegram",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "telegram.send_message",
        description = "Send a text message to a Telegram chat",
        inputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{"chatId":{"type":"string"},"text":{"type":"string"}},"required":["chatId","text"]}""").getOrElse(Json.Null),
        outputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{}}""").getOrElse(Json.Null),
        requiredCapabilities = List(sendCapability),
      ),
      ToolDescriptor(
        name = "telegram.send_photo",
        description = "Send a photo to a Telegram chat",
        inputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{"chatId":{"type":"string"},"photo":{"type":"string","contentEncoding":"base64"},"caption":{"type":"string"}},"required":["chatId","photo"]}""").getOrElse(Json.Null),
        outputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{}}""").getOrElse(Json.Null),
        requiredCapabilities = List(sendCapability),
      ),
      ToolDescriptor(
        name = "telegram.send_file",
        description = "Send a file/document to a Telegram chat",
        inputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{"chatId":{"type":"string"},"file":{"type":"string","contentEncoding":"base64"},"filename":{"type":"string"}},"required":["chatId","file","filename"]}""").getOrElse(Json.Null),
        outputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{}}""").getOrElse(Json.Null),
        requiredCapabilities = List(sendCapability),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    val obj = args.asObject.getOrElse(Json.Obj().asObject.get)
    tool match {
      case "telegram.send_message" =>
        val chatId = obj.get("chatId").flatMap(_.asString).getOrElse("")
        val text = obj.get("text").flatMap(_.asString).getOrElse("")
        apiClient.sendMessage(chatId, text).as(Json.Obj())

      case "telegram.send_photo" =>
        val chatId = obj.get("chatId").flatMap(_.asString).getOrElse("")
        val photoB64 = obj.get("photo").flatMap(_.asString).getOrElse("")
        val caption = obj.get("caption").flatMap(_.asString)
        val photoBytes = java.util.Base64.getDecoder.decode(photoB64)
        apiClient.sendPhoto(chatId, photoBytes, caption).as(Json.Obj())

      case "telegram.send_file" =>
        val chatId = obj.get("chatId").flatMap(_.asString).getOrElse("")
        val fileB64 = obj.get("file").flatMap(_.asString).getOrElse("")
        val filename = obj.get("filename").flatMap(_.asString).getOrElse("file")
        val fileBytes = java.util.Base64.getDecoder.decode(fileB64)
        apiClient.sendDocument(chatId, fileBytes, filename).as(Json.Obj())

      case other => ZIO.fail(JorlanError(s"Unknown telegram tool: $other"))
    }
  }

  override def start: IO[JorlanError, Unit] =
    pollLoop(offset = 0L)
      .catchAll(e => ZIO.logWarning(s"[telegram] polling loop error: ${e.msg}"))
      .forkDaemon
      .flatMap(f => pollingFiber.set(Some(f)))

  override def stop: IO[JorlanError, Unit] =
    pollingFiber.get.flatMap {
      case Some(f) => f.interrupt.unit *> pollingFiber.set(None)
      case None    => ZIO.unit
    }

  private def pollLoop(offset: Long): IO[JorlanError, Unit] = {
    apiClient.getUpdates(offset, timeoutSeconds = 30).flatMap { updates =>
      val filtered = filterUpdates(updates)
      ZIO
        .foreach(filtered) { update =>
          TelegramMessageNormalizer.normalize(update) match {
            case None      => ZIO.unit
            case Some(msg) =>
              ingress
                .receive(msg)
                .tapError(e => ZIO.logWarning(s"[telegram] ingress error for update ${update.update_id}: ${e.msg}"))
                .ignore
          }
        }
        .as(updates.map(_.update_id).maxOption.map(_ + 1).getOrElse(offset))
    }.flatMap(nextOffset => pollLoop(nextOffset))
  }

  private def filterUpdates(updates: List[TelegramUpdate]): List[TelegramUpdate] = {
    val chatFiltered =
      if (config.allowedChatIds.isEmpty) updates
      else updates.filter(u => u.message.exists(m => config.allowedChatIds.contains(m.chat.id.toString)))
    val userFiltered =
      if (config.allowedUserIds.isEmpty) chatFiltered
      else chatFiltered.filter(u => u.message.exists(m => m.from.exists(f => config.allowedUserIds.contains(f.id.toString))))
    userFiltered
  }

}

object TelegramConnectorSkill {

  def make(
    config:     TelegramConfig,
    instanceId: ConnectorInstanceId,
    apiClient:  TelegramApiClient,
    ingress:    MessageIngress,
    agentRunner: AgentRunner,
  ): UIO[TelegramConnectorSkill] =
    Ref.make(Option.empty[Fiber[Nothing, Unit]]).map { fiberRef =>
      TelegramConnectorSkill(config, instanceId, apiClient, ingress, agentRunner, fiberRef)
    }

}
