/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.*
import jorlan.service.NotificationRouter
import zio.*
import zio.json.ast.Json

/** Built-in skill for sending notifications through connector channels.
  *
  * Provides two tools:
  *   - `notify.user` — resolve user → preferred channel → send
  *   - `notify.channel` — send directly to a known channelUserId + channelType pair
  */
class NotifySkill(router: NotificationRouter) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "notify",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "notify.user",
        description = "Send a text message to a user's preferred communication channel (Telegram preferred).",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID"},"message":{"type":"string","description":"Message text to send"}},"required":["userId","message"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("notify.send")),
      ),
      ToolDescriptor(
        name = "notify.channel",
        description = "Send a text message to a specific channel identity (channelUserId + channelType such as Telegram, Slack, Email).",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"channelUserId":{"type":"string","description":"Channel-native user identifier (e.g. Telegram chat ID)"},"channelType":{"type":"string","description":"Channel type: Telegram, Slack, Email, etc."},"message":{"type":"string","description":"Message text to send"}},"required":["channelUserId","channelType","message"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("notify.send")),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "notify.user"    => notifyUser(ctx, args)
      case "notify.channel" => notifyChannel(ctx, args)
      case other            => ZIO.fail(JorlanError(s"NotifySkill: unknown tool '$other'"))
    }

  private def notifyUser(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val userIdRaw = SkillArgs.str(args, "userId")
    val message = SkillArgs.str(args, "message")
    (userIdRaw, message) match {
      case (None, _)              => ZIO.fail(JorlanError("notify.user: userId is required"))
      case (_, None)              => ZIO.fail(JorlanError("notify.user: message is required"))
      case (Some(uid), Some(msg)) =>
        uid.toLongOption match {
          case None     => ZIO.fail(JorlanError(s"notify.user: userId must be numeric, got '$uid'"))
          case Some(id) => router.notifyUser(UserId(id), msg, ctx)
        }
    }
  }

  private def notifyChannel(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val channelUserId = SkillArgs.str(args, "channelUserId")
    val channelTypeStr = SkillArgs.str(args, "channelType")
    val message = SkillArgs.str(args, "message")
    (channelUserId, channelTypeStr, message) match {
      case (None, _, _)                      => ZIO.fail(JorlanError("notify.channel: channelUserId is required"))
      case (_, None, _)                      => ZIO.fail(JorlanError("notify.channel: channelType is required"))
      case (_, _, None)                      => ZIO.fail(JorlanError("notify.channel: message is required"))
      case (Some(cuid), Some(ct), Some(msg)) =>
        SkillArgs.parseChannelType(ct) match {
          case None         => ZIO.fail(JorlanError(s"notify.channel: unknown channelType '$ct'"))
          case Some(chType) => router.notifyChannel(cuid, chType, msg, ctx)
        }
    }
  }

}

object NotifySkill
