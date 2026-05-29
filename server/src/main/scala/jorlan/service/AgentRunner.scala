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

/** The per-session agent execution loop for Phase 8.
  *
  * Receives a user message, streams the model response token-by-token through [[SessionHub]], and records all
  * significant steps in the event log. The full ReAct planning loop (multi-step tool dispatch) is deferred to Phase 12.
  */
trait AgentRunner {

  def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): IO[JorlanError, Unit]

}

object AgentRunner {

  def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): ZIO[AgentRunner, JorlanError, Unit] =
    ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, content, actorId))

}

private class AgentRunnerImpl(
  modelGateway: ModelGateway,
  sessionHub:   SessionHub,
  eventLog:     EventLogService,
) extends AgentRunner {

  override def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): IO[JorlanError, Unit] = {
    for {
      now <- Clock.instant
      _   <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.UserMessageReceived,
          actorId = actorId,
          agentId = None,
          sessionId = Some(sessionId),
          resource = Some(sessionId),
          payloadJson = None,
          occurredAt = now,
        ),
      )
      _ <- modelGateway
        .streamedResponse(sessionId, content)
        .mapError(e => JorlanError(e.msg, Some(e)))
        .foreach { chunk =>
          sessionHub.publish(ResponseChunk(sessionId, chunk, finished = false))
        }
        .ensuring(
          sessionHub.publish(ResponseChunk(sessionId, "", finished = true)),
        )
      now2 <- Clock.instant
      _    <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.AgentResponseCompleted,
          actorId = actorId,
          agentId = None,
          sessionId = Some(sessionId),
          resource = Some(sessionId),
          payloadJson = None,
          occurredAt = now2,
        ),
      )
    } yield ()
  }

}

object AgentRunnerImpl {

  val live: URLayer[ModelGateway & SessionHub & EventLogService, AgentRunner] =
    ZLayer.fromFunction(
      (
        modelGateway: ModelGateway,
        sessionHub:   SessionHub,
        eventLog:     EventLogService,
      ) => new AgentRunnerImpl(modelGateway, sessionHub, eventLog),
    )

}
