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

/** [[AgentRunner]] implementation. On each [[processMessage]] call it reads the current [[PersonalityService]]
  * snapshot, builds the system prompt, and streams the model response token-by-token through [[SessionHub]].
  *
  * @param personalityService
  *   Read on every `processMessage` call so personality changes take effect on the next message without requiring a
  *   server restart.
  */
class AgentRunnerImpl(
  modelGateway:       ModelGateway,
  sessionHub:         SessionHub,
  eventLog:           EventLogService,
  personalityService: PersonalityService,
) extends AgentRunner {

  override def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): IO[JorlanError, Unit] =
    // errorRef is hoisted above the entire for-comprehension so that failures at any
    // step (event log write, stream processing) are captured and reflected in the sentinel.
    Ref.make(Option.empty[String]).flatMap { errorRef =>
      val work = for {
        personality <- personalityService.get()
        systemPrompt = Personality.buildSystemPrompt(personality)
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
          modelGateway
            .streamedResponse(sessionId, content, systemPrompt)
            .mapError(e => JorlanError(e.msg, Some(e)))
            .tapError(e => errorRef.set(Some(e.getMessage)))
            .foreach { chunk =>
              sessionHub.publish(ResponseChunk(sessionId, chunk, finished = false))
            }
        }
      } yield ()

      // Capture any failure not already recorded by tapError (e.g. event log write failure).
      work
        .tapError(e => errorRef.update(_.orElse(Some(e.getMessage))))
        .ensuring {
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
          }
        }
    }

  override def subscribeToSession(sessionId: AgentSessionId): ZStream[Any, Nothing, ResponseChunk] =
    sessionHub.subscribe(sessionId)

}

object AgentRunnerImpl {

  val live: URLayer[ModelGateway & SessionHub & EventLogService & PersonalityService, AgentRunner] =
    ZLayer.fromFunction(new AgentRunnerImpl(_, _, _, _))

}
