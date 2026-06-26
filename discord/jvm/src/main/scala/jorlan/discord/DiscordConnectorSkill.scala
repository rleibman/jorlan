/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.discord

import jorlan.*
import jorlan.connector.*
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Tier-0 [[ConnectorSkill]] for Discord.
  *
  * Ingress: a Gateway event loop calls [[DiscordApiClient.nextEvent]], normalizes each [[DiscordRawMessage]] via
  * [[DiscordMessageNormalizer]], and passes it to [[MessageIngress.receive]]. The loop runs until [[stop]] is called.
  *
  * Filtering:
  *   - Bot messages are always dropped (handled in normalizer and double-checked here).
  *   - If `allowedGuildIds` is non-empty and the message comes from a guild, messages from unlisted guilds are dropped.
  *   - If `allowedUserIds` is non-empty, messages from unlisted users are dropped.
  *   - If `mentionOnly` is `true`, guild channel messages that do not mention the bot are dropped.
  *
  * Egress tools (each gated by capability):
  *   - `discord.send_message` (requires `discord.send`)
  *   - `discord.send_dm` (requires `discord.send`)
  *   - `discord.get_history` (requires `discord.read`)
  *   - `discord.get_channel_info` (requires `discord.read`)
  */
class DiscordConnectorSkill(
  config:         DiscordConfig,
  val instanceId: ConnectorInstanceId,
  apiClient:      DiscordApiClient,
  ingress:        MessageIngress,
  pollingFiber:   Ref[Option[Fiber[Nothing, Unit]]],
) extends ConnectorSkill {

  override val connectorType:       ConnectorType = ConnectorType.Discord
  override val sendMessageToolName: Option[String] = Some("discord.send_message")

  private val sendCapability = CapabilityName("discord.send")
  private val readCapability = CapabilityName("discord.read")

  private val sendMessageSchema: Json =
    json"""{"type":"object","properties":{"channelId":{"type":"string"},"content":{"type":"string"}},"required":["channelId","content"]}"""
  private val sendDmSchema: Json =
    json"""{"type":"object","properties":{"userId":{"type":"string"},"content":{"type":"string"}},"required":["userId","content"]}"""
  private val getHistorySchema: Json =
    json"""{"type":"object","properties":{"channelId":{"type":"string"},"limit":{"type":"integer","default":50}},"required":["channelId"]}"""
  private val getChannelInfoSchema: Json =
    json"""{"type":"object","properties":{"channelId":{"type":"string"}},"required":["channelId"]}"""
  private val emptyOutputSchema:   Json = json"""{"type":"object","properties":{}}"""
  private val historyOutputSchema: Json =
    json"""{"type":"array","items":{"type":"object"}}"""
  private val channelInfoOutputSchema: Json = json"""{"type":"object","properties":{}}"""

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "discord",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "discord",
      "message",
      "send",
      "chat",
      "guild",
      "server",
      "notify",
      "DM",
      "direct message",
      "bot",
    ),
    configKey = Some("skill.discord"),
    configJsModule = Some("jorlan-discord"),
    tools = List(
      ToolDescriptor(
        name = "discord.send_message",
        description =
          "Send a text message to a Discord guild text channel. 'channelId' is the Snowflake ID of the target channel.",
        inputSchema = sendMessageSchema,
        outputSchema = emptyOutputSchema,
        requiredCapabilities = List(sendCapability),
        keywords = List("discord", "message", "send", "channel", "guild", "notify"),
        examplePrompts = List(
          "Send a Discord message to the general channel",
          "Post a notification to the Discord announcements channel",
          "Notify the team on Discord that the build is done",
        ),
      ),
      ToolDescriptor(
        name = "discord.send_dm",
        description = "Send a direct message to a Discord user by their Snowflake user ID.",
        inputSchema = sendDmSchema,
        outputSchema = emptyOutputSchema,
        requiredCapabilities = List(sendCapability),
        keywords = List("discord", "dm", "direct message", "send", "user"),
        examplePrompts = List(
          "Send a DM to Roberto on Discord",
          "Send a direct message on Discord to user 123456789",
        ),
      ),
      ToolDescriptor(
        name = "discord.get_history",
        description = "Retrieve recent messages from a Discord text channel. 'channelId' is the Snowflake ID; 'limit' defaults to 50.",
        inputSchema = getHistorySchema,
        outputSchema = historyOutputSchema,
        requiredCapabilities = List(readCapability),
        keywords = List("discord", "history", "messages", "channel", "read"),
        examplePrompts = List(
          "Get the last 20 messages from the Discord general channel",
          "Show me recent Discord messages in channel 123456789",
        ),
      ),
      ToolDescriptor(
        name = "discord.get_channel_info",
        description = "Get metadata about a Discord text channel (name, guild, type) by Snowflake channel ID.",
        inputSchema = getChannelInfoSchema,
        outputSchema = channelInfoOutputSchema,
        requiredCapabilities = List(readCapability),
        keywords = List("discord", "channel", "info", "metadata", "guild"),
        examplePrompts = List(
          "What is the name of Discord channel 123456789?",
          "Get info about this Discord channel",
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
      case "discord.send_message" =>
        val channelId = obj.get("channelId").flatMap(_.asString)
        val content = obj.get("content").flatMap(_.asString)
        (channelId, content) match {
          case (Some(cid), Some(c)) => apiClient.sendToChannel(cid, c).as(Json.Obj())
          case (None, _)            => ZIO.fail(JorlanError("channelId is required for discord.send_message"))
          case (_, None)            => ZIO.fail(JorlanError("content is required for discord.send_message"))
        }

      case "discord.send_dm" =>
        val userId = obj.get("userId").flatMap(_.asString)
        val content = obj.get("content").flatMap(_.asString)
        (userId, content) match {
          case (Some(uid), Some(c)) => apiClient.sendToDm(uid, c).as(Json.Obj())
          case (None, _)            => ZIO.fail(JorlanError("userId is required for discord.send_dm"))
          case (_, None)            => ZIO.fail(JorlanError("content is required for discord.send_dm"))
        }

      case "discord.get_history" =>
        val channelId = obj.get("channelId").flatMap(_.asString)
        val limit = obj.get("limit").flatMap(_.asNumber).map(n => BigDecimal(n.value).toInt).getOrElse(50).max(1).min(100)
        channelId match {
          case Some(cid) =>
            apiClient.getChannelHistory(cid, limit).map(msgs => Json.Arr(msgs*))
          case None => ZIO.fail(JorlanError("channelId is required for discord.get_history"))
        }

      case "discord.get_channel_info" =>
        val channelId = obj.get("channelId").flatMap(_.asString)
        channelId match {
          case Some(cid) => apiClient.getChannelInfo(cid)
          case None      => ZIO.fail(JorlanError("channelId is required for discord.get_channel_info"))
        }

      case other => ZIO.fail(JorlanError(s"Unknown discord tool: $other"))
    }
  }

  override def start: IO[JorlanError, Unit] =
    pollingFiber.get.flatMap {
      case Some(_) => ZIO.unit
      case None    =>
        ZIO.logInfo("[discord] connecting to Discord gateway") *>
          apiClient.connect() *>
          eventLoop.fork.flatMap(f => pollingFiber.set(Some(f)))
    }

  override def stop: IO[JorlanError, Unit] =
    pollingFiber.get.flatMap {
      case Some(f) => f.interrupt.unit *> pollingFiber.set(None) *> apiClient.disconnect()
      case None    => ZIO.unit
    }

  // ZIO 2 fiber scheduling trampolines recursive flatMap chains — this is stack-safe.
  private def eventLoop: UIO[Unit] =
    apiClient
      .nextEvent()
      .foldZIO(
        err => ZIO.logWarning(s"[discord] event error: ${err.msg}") *> eventLoop,
        {
          case None =>
            ZIO.logInfo("[discord] event loop received shutdown sentinel, stopping")
          case Some(msg) =>
            processMessage(msg) *> eventLoop
        },
      )

  private def processMessage(msg: DiscordRawMessage): UIO[Unit] = {
    // Skip bots
    if (msg.isBot) {
      ZIO.unit
    } else if (
      config.allowedGuildIds.nonEmpty && msg.guildId.isDefined && !config.allowedGuildIds.contains(msg.guildId.get)
    ) {
      ZIO.logDebug(s"[discord] dropping message from unlisted guild ${msg.guildId.get}")
    } else if (config.allowedUserIds.nonEmpty && !config.allowedUserIds.contains(msg.authorId)) {
      ZIO.logDebug(s"[discord] dropping message from unlisted user ${msg.authorId}")
    } else if (config.mentionOnly && msg.guildId.isDefined && !msg.isMention) {
      ZIO.logDebug(s"[discord] dropping guild message with no bot mention from ${msg.authorId}")
    } else {
      DiscordMessageNormalizer.normalize(msg) match {
        case None          => ZIO.unit
        case Some(inbound) =>
          val channelId = msg.channelId
          ingress
            .receive(
              inbound,
              config.unrecognizedPolicy,
              onResponse = Some(text =>
                (if (msg.guildId.isDefined) apiClient.sendToChannel(channelId, text)
                 else apiClient.sendToDm(msg.authorId, text))
                  .tapError(e => ZIO.logWarning(s"[discord] reply failed for channel $channelId: ${e.msg}"))
                  .ignore,
              ),
            )
            .tapError(e => ZIO.logWarning(s"[discord] ingress error for message ${msg.messageId}: ${e.msg}"))
            .ignore
      }
    }
  }

}

object DiscordConnectorSkill {

  /** Build a [[DiscordConnectorSkill]] instance.
    *
    * @param config
    *   parsed [[DiscordConfig]] from the bound [[ConnectorInstance]]
    * @param instanceId
    *   the connector instance identifier
    * @param apiClient
    *   the Discord API client (live or fake)
    * @param ingress
    *   the connector-agnostic ingress pipeline
    * @return
    *   a ready-to-start [[DiscordConnectorSkill]]
    */
  def make(
    config:     DiscordConfig,
    instanceId: ConnectorInstanceId,
    apiClient:  DiscordApiClient,
    ingress:    MessageIngress,
  ): UIO[DiscordConnectorSkill] =
    Ref.make(Option.empty[Fiber[Nothing, Unit]]).map { fiberRef =>
      DiscordConnectorSkill(config, instanceId, apiClient, ingress, fiberRef)
    }

}
