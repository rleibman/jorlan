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
import jorlan.db.repository.{EventLogZIORepository, ServerSettingsRepository}
import jorlan.domain.*
import zio.*
import zio.json.*
import zio.stream.ZStream

/** [[AgentRunner]] implementation. On each [[processMessage]] call it reads the current personality from
  * [[ServerSettingsRepository]], builds the system prompt, and streams the model response token-by-token through
  * [[SessionHub]].
  *
  * @param settingsRepo
  *   Read on every `processMessage` call so personality changes take effect on the next message without a restart.
  */
class AgentRunnerImpl(
  modelGateway: ModelGateway,
  sessionHub:   SessionHub,
  eventLogRepo: EventLogZIORepository,
  settingsRepo: ServerSettingsRepository,
) extends AgentRunner {

  override def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): IO[JorlanError, Unit] =
    Ref.make(Option.empty[String]).flatMap { errorRef =>
      val work = for {
        personality <- loadPersonality
        systemPrompt = Personality.buildSystemPrompt(personality)
        now <- Clock.instant
        _   <- eventLogRepo.append(
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
                eventLogRepo
                  .append(
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

  private val loadPersonality: UIO[Personality] =
    settingsRepo.get(ServerSettingsRepository.PersonalityKey).flatMap {
      case Some(json) =>
        json.as[Personality] match {
          case Right(p)  => ZIO.succeed(p)
          case Left(err) =>
            ZIO.logWarning(s"Corrupt personality in server_settings: $err. Using default.") *>
              ZIO.succeed(Personality.default)
        }
      case None => ZIO.succeed(Personality.default)
    }

}

object AgentRunnerImpl {

  val live: URLayer[ModelGateway & SessionHub & EventLogZIORepository & ServerSettingsRepository, AgentRunner] =
    ZLayer.fromFunction(new AgentRunnerImpl(_, _, _, _))

}
