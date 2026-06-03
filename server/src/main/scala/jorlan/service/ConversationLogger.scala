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
import org.slf4j.{LoggerFactory, MDC}
import zio.*

import scala.language.unsafeNulls

/** Logs complete user↔agent exchanges to a dedicated SLF4J logger (`jorlan.conversation`).
  *
  * Logback routes this logger to a `SiftingAppender` keyed on the `sessionId` MDC entry, producing one log file per
  * [[AgentSessionId]] under `logs/conversations/`. The MDC key is set and restored within a single `ZIO.succeed` block
  * so it never leaks across fiber hops.
  *
  * Enable or disable by configuring the `jorlan.conversation` logger level in logback.xml.
  */
object ConversationLogger {

  private val logger = LoggerFactory.getLogger("jorlan.conversation")

  def logUserMessage(
    sessionId: AgentSessionId,
    actorId:   Option[UserId],
    content:   String,
  ): UIO[Unit] =
    ZIO.succeed {
      withMdc(sessionId) {
        val who = actorId.map(id => s"actor=${id.value}").getOrElse("unknown")
        logger.info(s"USER [$who]: $content")
      }
    }

  def logAgentResponse(
    sessionId: AgentSessionId,
    response:  String,
    isError:   Boolean,
  ): UIO[Unit] =
    ZIO.succeed {
      withMdc(sessionId) {
        if (isError) logger.error(s"AGENT ERROR: $response")
        else logger.info(s"AGENT: $response")
      }
    }

  private def withMdc(sessionId: AgentSessionId)(block: => Unit): Unit = {
    val prev = Option(MDC.getCopyOfContextMap).getOrElse(java.util.Collections.emptyMap[String, String]())
    MDC.put("sessionId", sessionId.value.toString)
    try block
    finally MDC.setContextMap(prev)
  }

}
