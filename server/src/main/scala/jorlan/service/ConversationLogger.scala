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

import jorlan.domain.{AgentSessionId, UserId}
import zio.*

/** Logs complete user↔agent exchanges via ZIO's structured logging.
  *
  * Log entries carry two annotations that flow through the ZIO fiber context:
  *
  *   - `sessionId` — used by Logback's `SiftingAppender` (via the SLF4J bridge MDC) to route output to
  *     `logs/conversations/session-<id>.log`.
  *   - logger name `"jorlan.conversation"` — maps to the SLF4J logger name so logback.xml can set its level and
  *     appender independently of the main `jorlan` logger.
  *
  * Because ZIO propagates annotations through the fiber context (not thread-locals), both methods are fully fiber-safe
  * and require no blocking I/O — the SLF4J bridge is invoked synchronously on the ZIO thread pool under the
  * `SLF4J.slf4j` backend, which is already configured in `Jorlan.bootstrap`.
  *
  * Enable or disable by configuring the `jorlan.conversation` logger level in logback.xml.
  */
object ConversationLogger {

  /** Logs an inbound user message. `actorId = None` is logged as `"unknown"`. */
  def logUserMessage(
    sessionId: AgentSessionId,
    actorId:   Option[UserId],
    content:   String,
  ): UIO[Unit] = {
    val who = actorId.map(id => s"actor=${id.value}").getOrElse("unknown")
    ZIO.logInfo(s"USER [$who]: $content") @@
      ZIOAspect.annotated("sessionId", sessionId.value.toString) @@
      zio.logging.loggerName("jorlan.conversation")
  }

  /** Logs a completed agent response. When `isError = true` the entry is emitted at ERROR level, routing it to
    * error-specific appenders in addition to the session conversation log.
    */
  def logAgentResponse(
    sessionId: AgentSessionId,
    response:  String,
    isError:   Boolean,
  ): UIO[Unit] = {
    val log = if (isError) ZIO.logError(s"AGENT ERROR: $response") else ZIO.logInfo(s"AGENT: $response")
    log @@
      ZIOAspect.annotated("sessionId", sessionId.value.toString) @@
      zio.logging.loggerName("jorlan.conversation")
  }

}
