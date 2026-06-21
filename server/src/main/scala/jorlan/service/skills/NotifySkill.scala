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
import just.semver.SemVer
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
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    tools = List(
      ToolDescriptor(
        name = "notify.user",
        description = "Send a text message to a user's preferred communication channel (Telegram preferred). If userId is omitted the message is sent to the currently authenticated user. Use contacts.find to get a numeric userId for other users.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID — omit to send to the current user"},"message":{"type":"string","description":"Message text to send"}},"required":["message"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("notify.send")),
        examplePrompts = List(
          "Send a message to Alice saying the report is ready",
          "Notify me when the task is done",
          "Tell user 5 that their order has shipped",
        ),
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
        examplePrompts = List(
          "Send a Telegram message to chat ID 123456",
          "Notify Telegram user 987654 that the build succeeded",
        ),
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
    val userIdRaw = str(args, "userId")
    val message = str(args, "message")
    val resolvedUserId: Either[String, UserId] = userIdRaw match {
      case None      => Right(ctx.actorId)
      case Some(uid) => uid.toLongOption.map(UserId(_)).toRight(s"notify.user: userId must be numeric, got '$uid'")
    }
    (resolvedUserId, message) match {
      case (_, None)               => ZIO.fail(JorlanError("notify.user: message is required"))
      case (Left(err), _)          => ZIO.fail(JorlanError(err))
      case (Right(uid), Some(msg)) => router.notifyUser(uid, msg, ctx)
    }
  }

  private def notifyChannel(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val channelUserId = str(args, "channelUserId")
    val channelTypeStr = str(args, "channelType")
    val message = str(args, "message")
    (channelUserId, channelTypeStr, message) match {
      case (None, _, _)                      => ZIO.fail(JorlanError("notify.channel: channelUserId is required"))
      case (_, None, _)                      => ZIO.fail(JorlanError("notify.channel: channelType is required"))
      case (_, _, None)                      => ZIO.fail(JorlanError("notify.channel: message is required"))
      case (Some(cuid), Some(ct), Some(msg)) =>
        parseChannelType(ct) match {
          case None         => ZIO.fail(JorlanError(s"notify.channel: unknown channelType '$ct'"))
          case Some(chType) => router.notifyChannel(cuid, chType, msg, ctx)
        }
    }
  }

}

object NotifySkill
