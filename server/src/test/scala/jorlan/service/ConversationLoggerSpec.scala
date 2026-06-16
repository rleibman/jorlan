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

import jorlan.{AgentSessionId, UserId}
import zio.*
import zio.test.*

object ConversationLoggerSpec extends ZIOSpecDefault {

  private val sid = AgentSessionId(42L)
  private val uid = UserId(7L)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ConversationLogger")(
      test("logUserMessage with Some(actorId) logs 'actor=<id>'") {
        for {
          _      <- ConversationLogger.logUserMessage(sid, Some(uid), "hello")
          output <- ZTestLogger.logOutput
        } yield {
          val entry = output.head
          assertTrue(
            output.size == 1,
            entry.message().contains(s"actor=${uid.value}"),
            entry.message().contains("hello"),
            entry.logLevel == LogLevel.Info,
          )
        }
      },
      test("logUserMessage with None actorId logs 'unknown'") {
        for {
          _      <- ConversationLogger.logUserMessage(sid, None, "anon message")
          output <- ZTestLogger.logOutput
        } yield {
          val entry = output.head
          assertTrue(
            output.size == 1,
            entry.message().contains("unknown"),
            entry.logLevel == LogLevel.Info,
          )
        }
      },
      test("logUserMessage annotates sessionId") {
        for {
          _      <- ConversationLogger.logUserMessage(sid, Some(uid), "hello")
          output <- ZTestLogger.logOutput
        } yield assertTrue(
          output.head.annotations.get("sessionId").contains(sid.value.toString),
        )
      },
      test("logAgentResponse with isError=false logs at INFO level") {
        for {
          _      <- ConversationLogger.logAgentResponse(sid, "normal response", isError = false)
          output <- ZTestLogger.logOutput
        } yield {
          val entry = output.head
          assertTrue(
            output.size == 1,
            entry.message().contains("normal response"),
            entry.logLevel == LogLevel.Info,
          )
        }
      },
      test("logAgentResponse with isError=true logs at ERROR level") {
        for {
          _      <- ConversationLogger.logAgentResponse(sid, "something failed", isError = true)
          output <- ZTestLogger.logOutput
        } yield {
          val entry = output.head
          assertTrue(
            output.size == 1,
            entry.message().contains("something failed"),
            entry.logLevel == LogLevel.Error,
          )
        }
      },
      test("logAgentResponse annotates sessionId") {
        for {
          _      <- ConversationLogger.logAgentResponse(sid, "resp", isError = false)
          output <- ZTestLogger.logOutput
        } yield assertTrue(
          output.head.annotations.get("sessionId").contains(sid.value.toString),
        )
      },
      test("logUserMessage and logAgentResponse are infallible — never fail") {
        for {
          r1 <- ConversationLogger.logUserMessage(sid, Some(uid), "x").exit
          r2 <- ConversationLogger.logAgentResponse(sid, "y", isError = true).exit
        } yield assertTrue(r1.isSuccess, r2.isSuccess)
      },
    ) @@ TestAspect.withLiveClock

}
