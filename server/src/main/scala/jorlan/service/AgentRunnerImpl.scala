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
import zio.stream.ZStream

class AgentRunnerImpl(
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
      _ <- ZIO.scoped {
        Ref.make(Option.empty[String]).flatMap { errorRef =>
          modelGateway
            .streamedResponse(sessionId, content)
            .mapError(e => JorlanError(e.msg, Some(e)))
            .tapError(e => errorRef.set(Some(e.getMessage)))
            .foreach { chunk =>
              sessionHub.publish(ResponseChunk(sessionId, chunk, finished = false))
            }
            .ensuring(
              errorRef.get.flatMap { errMsg =>
                val sentinel = errMsg match {
                  case Some(msg) => ResponseChunk(sessionId, msg, finished = true, isError = true)
                  case None      => ResponseChunk(sessionId, "", finished = true)
                }
                sessionHub.publish(sentinel) *>
                  Clock.instant.flatMap { completedAt =>
                    eventLog
                      .log(
                        EventLog(
                          id = EventLogId.empty,
                          eventType = EventType.AgentResponseCompleted,
                          actorId = actorId,
                          agentId = None,
                          sessionId = Some(sessionId),
                          resource = Some(sessionId),
                          payloadJson = None,
                          occurredAt = completedAt,
                        ),
                      )
                      .ignore
                  }
              },
            )
        }
      }
    } yield ()
  }

  override def subscribeToSession(sessionId: AgentSessionId): ZStream[Any, Nothing, ResponseChunk] =
    sessionHub.subscribe(sessionId)

}

object AgentRunnerImpl {

  val live: URLayer[ModelGateway & SessionHub & EventLogService, AgentRunner] =
    ZLayer.fromFunction(new AgentRunnerImpl(_, _, _))

}
