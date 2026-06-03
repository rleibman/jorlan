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
    for {
      errorRef  <- Ref.make(Option.empty[String])
      tokensRef <- Ref.make(new StringBuilder)
      work =
        for {
          _           <- ZIO.logDebug(s"[session:$sessionId] incoming message (${content.length} chars)")
          _           <- ConversationLogger.logUserMessage(sessionId, actorId, content)
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
                tokensRef.update(_.append(chunk)) *>
                  sessionHub.publish(ResponseChunk(sessionId, chunk, finished = false))
              }
          }
        } yield ()
      _ <- work
        .tapError(e => errorRef.update(_.orElse(Some(e.getMessage))))
        .ensuring {
          errorRef.get.flatMap { errMsg =>
            tokensRef.get.flatMap { sb =>
              val fullResponse = sb.toString
              val sentinel = errMsg match {
                case Some(msg) => ResponseChunk(sessionId, msg, finished = true, isError = true)
                case None      => ResponseChunk(sessionId, "", finished = true)
              }
              ZIO.logDebug(
                s"[session:$sessionId] response finished${errMsg.map(e => s" with error: $e").getOrElse("")}",
              ) *>
                sessionHub.publish(sentinel) *>
                ConversationLogger.logAgentResponse(sessionId, fullResponse, isError = errMsg.isDefined) *>
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
    } yield ()

  override def subscribeToSession(
    sessionId:    AgentSessionId,
    connectionId: ConnectionId,
  ): UIO[ZStream[Any, Nothing, ResponseChunk]] =
    sessionHub.subscribe(sessionId, connectionId)

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
