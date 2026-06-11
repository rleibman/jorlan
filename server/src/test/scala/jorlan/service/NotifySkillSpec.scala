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
import jorlan.connector.InvocationContext
import jorlan.domain.*
import jorlan.service.skills.NotifySkill
import zio.*
import zio.json.ast.Json
import zio.test.*

object NotifySkillSpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)

  private def mkArgs(pairs: (String, String)*): Json =
    Json.Obj(pairs.map { case (k, v) => k -> Json.Str(v) }*)

  /** A stub [[NotificationRouter]] that captures calls and returns a fixed result. */
  private def stubRouter(
    userCalls:    Ref[List[(UserId, String)]],
    channelCalls: Ref[List[(String, ChannelType, String)]],
    result:       Json = Json.Str("ok"),
  ): NotificationRouter =
    new NotificationRouter {
      override def notifyUser(
        userId:  UserId,
        message: String,
        ctx:     InvocationContext,
      ): UIO[Json] =
        userCalls.update(_ :+ (userId, message)).as(result)
      override def notifyChannel(
        channelUserId: String,
        channelType:   ChannelType,
        message:       String,
        ctx:           InvocationContext,
      ): UIO[Json] =
        channelCalls.update(_ :+ (channelUserId, channelType, message)).as(result)
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("NotifySkill")(
      // ─── notify.user ─────────────────────────────────────────────────────────
      test("notify.user invokes router.notifyUser with correct userId and message") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          router = stubRouter(userCalls, channelCalls)
          skill = new NotifySkill(router)
          result <- skill.invoke(ctx, "notify.user", mkArgs("userId" -> "42", "message" -> "hello"))
          calls  <- userCalls.get
        } yield assertTrue(
          result == Json.Str("ok"),
          calls.length == 1,
          calls.head._1 == UserId(42L),
          calls.head._2 == "hello",
        )
      },
      test("notify.user fails when userId is missing") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill.invoke(ctx, "notify.user", mkArgs("message" -> "hello")).either
        } yield assertTrue(result.isLeft)
      },
      test("notify.user fails when message is missing") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill.invoke(ctx, "notify.user", mkArgs("userId" -> "1")).either
        } yield assertTrue(result.isLeft)
      },
      test("notify.user fails when userId is non-numeric") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill.invoke(ctx, "notify.user", mkArgs("userId" -> "notanumber", "message" -> "hi")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── notify.channel ───────────────────────────────────────────────────────
      test("notify.channel invokes router.notifyChannel with correct args") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill.invoke(
            ctx,
            "notify.channel",
            mkArgs("channelUserId" -> "tg-99", "channelType" -> "telegram", "message" -> "direct"),
          )
          calls <- channelCalls.get
        } yield assertTrue(
          result == Json.Str("ok"),
          calls.length == 1,
          calls.head._1 == "tg-99",
          calls.head._2 == ChannelType.Telegram,
          calls.head._3 == "direct",
        )
      },
      test("notify.channel fails when channelUserId is missing") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill
            .invoke(ctx, "notify.channel", mkArgs("channelType" -> "telegram", "message" -> "hi"))
            .either
        } yield assertTrue(result.isLeft)
      },
      test("notify.channel fails when channelType is missing") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill
            .invoke(ctx, "notify.channel", mkArgs("channelUserId" -> "tg-1", "message" -> "hi"))
            .either
        } yield assertTrue(result.isLeft)
      },
      test("notify.channel fails when message is missing") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill
            .invoke(ctx, "notify.channel", mkArgs("channelUserId" -> "tg-1", "channelType" -> "telegram"))
            .either
        } yield assertTrue(result.isLeft)
      },
      test("notify.channel fails for unknown channelType") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill
            .invoke(
              ctx,
              "notify.channel",
              mkArgs("channelUserId" -> "x", "channelType" -> "carrier-pigeon", "message" -> "hi"),
            )
            .either
        } yield assertTrue(result.isLeft)
      },
      test("notify.channel accepts case-insensitive channelType (Slack)") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill.invoke(
            ctx,
            "notify.channel",
            mkArgs("channelUserId" -> "slack-1", "channelType" -> "SLACK", "message" -> "msg"),
          )
          calls <- channelCalls.get
        } yield assertTrue(
          result == Json.Str("ok"),
          calls.head._2 == ChannelType.Slack,
        )
      },
      // ─── unknown tool ─────────────────────────────────────────────────────────
      test("invoke unknown tool returns JorlanError") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
          result <- skill.invoke(ctx, "notify.broadcast", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      // ─── descriptor ──────────────────────────────────────────────────────────
      test("descriptor has 2 tools with correct names") {
        for {
          userCalls    <- Ref.make(List.empty[(UserId, String)])
          channelCalls <- Ref.make(List.empty[(String, ChannelType, String)])
          skill = new NotifySkill(stubRouter(userCalls, channelCalls))
        } yield assertTrue(
          skill.descriptor.tools.map(_.name).toSet == Set("notify.user", "notify.channel"),
        )
      },
    )

}
