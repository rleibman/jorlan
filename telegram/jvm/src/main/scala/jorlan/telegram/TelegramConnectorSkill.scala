/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
import zio.json.literal.*

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
/** Resolves a human-readable name to a Telegram numeric chatId (the channel identity `channelUserId`). Returns
  * `Some(chatId)` if found, `None` if the name is not linked to any Telegram account. Injected at server startup so the
  * connector module stays free of DB dependencies.
  */
type TelegramNameResolver = String => IO[JorlanError, Option[String]]

class TelegramConnectorSkill(
  config:         TelegramConfig,
  val instanceId: ConnectorInstanceId,
  apiClient:      TelegramApiClient,
  ingress:        MessageIngress,
  pollingFiber:   Ref[Option[Fiber[Nothing, Unit]]],
  nameResolver:   TelegramNameResolver = _ => ZIO.none,
) extends ConnectorSkill {

  override val connectorType:       ConnectorType = ConnectorType.Telegram
  override val sendMessageToolName: Option[String] = Some("telegram.send_message")

  private val sendCapability = CapabilityName("telegram.send")

  private val sendMessageSchema: Json =
    json"""{"type":"object","properties":{"chatId":{"type":"string"},"text":{"type":"string"}},"required":["chatId","text"]}"""
  private val sendPhotoSchema: Json =
    json"""{"type":"object","properties":{"chatId":{"type":"string"},"photo":{"type":"string","contentEncoding":"base64"},"caption":{"type":"string"}},"required":["chatId","photo"]}"""
  private val sendFileSchema: Json =
    json"""{"type":"object","properties":{"chatId":{"type":"string"},"file":{"type":"string","contentEncoding":"base64"},"filename":{"type":"string"}},"required":["chatId","file","filename"]}"""
  private val emptyOutputSchema: Json = json"""{"type":"object","properties":{}}"""

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "telegram",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "telegram",
      "message",
      "send",
      "chat",
      "text",
      "notify",
      "notification",
      "messenger",
      "remind",
      "alert",
    ),
    configKey = Some("skill.telegram"),
    configJsModule = Some("jorlan-telegram"),
    doc = Some(
      """|## Telegram Skill
         |
         |Sends messages and media via Telegram bots.
         |
         |### Tools
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `telegram.send_message` | Send a text message | `telegram.send` |
         || `telegram.send_photo` | Send a photo image | `telegram.send` |
         || `telegram.send_file` | Send a binary file | `telegram.send` |
         |
         |### Setup
         |1. Create a Telegram bot via @BotFather (https://t.me/botfather) and copy the bot token.
         |2. In Admin → Connectors, add a Telegram connector instance:
         |   - `botToken`: your Telegram bot token
         |   - `botUsername`: your bot's username (without @)
         |3. Grant the `telegram.send` capability to agents.
         |
         |### Notes
         |IMPORTANT: Always call `user_mgmt.find` first to look up the person and get their real Telegram `channelUserId`.
         |Never guess, fabricate, or use placeholder chatIds — an invented ID will always fail with "chat not found".
         |The `chatId` field must be the exact numeric Telegram chat or user ID returned by `user_mgmt.find`.""".stripMargin,
    ),
    tools = List(
      ToolDescriptor(
        name = "telegram.send_message",
        description = "Send a text message via Telegram to a chat. 'chatId' MUST be a real Telegram numeric chat or user ID — NEVER guess or fabricate one. Always call user_mgmt.find first to get the identity, then extract the Telegram channelUserId from the result. Do not proceed without a confirmed chatId.",
        inputSchema = sendMessageSchema,
        outputSchema = emptyOutputSchema,
        requiredCapabilities = List(sendCapability),
        keywords = List("telegram", "message", "send", "text", "chat", "notify", "remind"),
        examplePrompts = List(
          "Send a Telegram message to Roberto saying hello",
          "Text Dominique on Telegram reminding her to call me",
          "Notify the team on Telegram that the build is done",
          "Send a Telegram reminder to user Roberto",
        ),
      ),
      ToolDescriptor(
        name = "telegram.send_photo",
        description = "Send a photo image via Telegram to a chat.",
        inputSchema = sendPhotoSchema,
        outputSchema = emptyOutputSchema,
        requiredCapabilities = List(sendCapability),
        keywords = List("telegram", "photo", "image", "picture", "send"),
        examplePrompts = List(
          "Send this screenshot to Roberto on Telegram",
          "Share the graph image with the team on Telegram",
        ),
      ),
      ToolDescriptor(
        name = "telegram.send_file",
        description = "Send a file or document via Telegram to a chat.",
        inputSchema = sendFileSchema,
        outputSchema = emptyOutputSchema,
        requiredCapabilities = List(sendCapability),
        keywords = List("telegram", "file", "document", "send", "upload"),
        examplePrompts = List(
          "Send the report PDF to Roberto on Telegram",
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
          case (Some(cid), Some(t)) =>
            resolveChatId(cid).flatMap(id => apiClient.sendMessage(id, t).as(Json.Obj()))
          case (None, _) => ZIO.fail(JorlanError("chatId is required for telegram.send_message"))
          case (_, None) => ZIO.fail(JorlanError("text is required for telegram.send_message"))
        }

      case "telegram.send_photo" =>
        val chatId = obj.get("chatId").flatMap(_.asString)
        val photoB64 = obj.get("photo").flatMap(_.asString)
        val caption = obj.get("caption").flatMap(_.asString)
        (chatId, photoB64) match {
          case (Some(cid), Some(b64)) =>
            for {
              id    <- resolveChatId(cid)
              bytes <- ZIO
                .attempt(java.util.Base64.getDecoder.decode(b64))
                .mapError(e => JorlanError(s"Invalid base64 for photo: ${e.getMessage}"))
              _ <- apiClient.sendPhoto(id, bytes, caption)
            } yield Json.Obj()
          case (None, _) => ZIO.fail(JorlanError("chatId is required for telegram.send_photo"))
          case (_, None) => ZIO.fail(JorlanError("photo (base64) is required for telegram.send_photo"))
        }

      case "telegram.send_file" =>
        val chatId = obj.get("chatId").flatMap(_.asString)
        val fileB64 = obj.get("file").flatMap(_.asString)
        val filename = obj.get("filename").flatMap(_.asString)
        (chatId, fileB64, filename) match {
          case (Some(cid), Some(b64), Some(fname)) =>
            for {
              id    <- resolveChatId(cid)
              bytes <- ZIO
                .attempt(java.util.Base64.getDecoder.decode(b64))
                .mapError(e => JorlanError(s"Invalid base64 for file: ${e.getMessage}"))
              _ <- apiClient.sendDocument(id, bytes, fname)
            } yield Json.Obj()
          case (None, _, _) => ZIO.fail(JorlanError("chatId is required for telegram.send_file"))
          case (_, None, _) => ZIO.fail(JorlanError("file (base64) is required for telegram.send_file"))
          case (_, _, None) => ZIO.fail(JorlanError("filename is required for telegram.send_file"))
        }

      case other => ZIO.fail(JorlanError(s"Unknown telegram tool: $other"))
    }
  }

  /** Resolves `chatId` to a real Telegram numeric ID. If it already looks numeric, pass through. Otherwise, treat it as
    * a user display name and look up via the injected [[nameResolver]].
    */
  private def resolveChatId(chatId: String): IO[JorlanError, String] =
    if (chatId.toLongOption.isDefined) {
      ZIO.succeed(chatId)
    } else {
      nameResolver(chatId).flatMap {
        case Some(id) => ZIO.succeed(id)
        case None     =>
          ZIO.fail(
            JorlanError(
              s"telegram: no Telegram account linked for '$chatId'. " +
                s"Use user_mgmt.find to look up the user and get their chatId from identities.",
            ),
          )
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
          pollLoop(offset = 0L).fork
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
      .getUpdates(offset, timeoutSeconds = config.longPollTimeoutSeconds).flatMap { updates =>
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
    config:       TelegramConfig,
    instanceId:   ConnectorInstanceId,
    apiClient:    TelegramApiClient,
    ingress:      MessageIngress,
    nameResolver: TelegramNameResolver = _ => ZIO.none,
  ): UIO[TelegramConnectorSkill] =
    Ref.make(Option.empty[Fiber[Nothing, Unit]]).map { fiberRef =>
      TelegramConnectorSkill(config, instanceId, apiClient, ingress, fiberRef, nameResolver)
    }

}
