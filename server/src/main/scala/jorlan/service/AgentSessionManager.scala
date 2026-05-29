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
import jorlan.db.repository.AgentZIORepository
import jorlan.domain.*
import zio.*

/** High-level session lifecycle service. Delegates persistence to [[AgentService]]; also initialises the in-process
  * [[SessionHub]] slot so the GraphQL subscription is ready before the first message arrives.
  */
trait AgentSessionManager {

  /** Create a new [[AgentSession]] for `userId`.
    *
    * Auto-creates a "Jorlan Interactive" default agent if none exists. Writes a [[EventType.SessionCreated]] event and
    * pre-allocates the [[SessionHub]] slot so the subscription is ready before the first message arrives.
    *
    * @param userId
    *   The authenticated user starting the session.
    * @param modelId
    *   Optional model override; if `None` the agent's `defaultModel` is used at inference time.
    */
  def createSession(
    userId:  UserId,
    modelId: Option[ModelId],
  ): IO[JorlanError, AgentSession]

  /** Look up a session by ID. Returns `None` if the session does not exist. */
  def getSession(id: AgentSessionId): IO[JorlanError, Option[AgentSession]]

  /** Transition the session to [[SessionStatus.Paused]]. The [[SessionHub]] slot is retained. */
  def suspendSession(id: AgentSessionId): IO[JorlanError, AgentSession]

  /** Transition the session to [[SessionStatus.Completed]] and release its [[SessionHub]] slot. */
  def terminateSession(id: AgentSessionId): IO[JorlanError, AgentSession]

}

object AgentSessionManager {

  def createSession(
    userId:  UserId,
    modelId: Option[ModelId],
  ): ZIO[AgentSessionManager, JorlanError, AgentSession] =
    ZIO.serviceWithZIO[AgentSessionManager](_.createSession(userId, modelId))

  def getSession(id: AgentSessionId): ZIO[AgentSessionManager, JorlanError, Option[AgentSession]] =
    ZIO.serviceWithZIO[AgentSessionManager](_.getSession(id))

  def suspendSession(id: AgentSessionId): ZIO[AgentSessionManager, JorlanError, AgentSession] =
    ZIO.serviceWithZIO[AgentSessionManager](_.suspendSession(id))

  def terminateSession(id: AgentSessionId): ZIO[AgentSessionManager, JorlanError, AgentSession] =
    ZIO.serviceWithZIO[AgentSessionManager](_.terminateSession(id))

}

private class AgentSessionManagerImpl(
  agentRepo:  AgentZIORepository,
  sessionHub: SessionHub,
  eventLog:   EventLogService,
) extends AgentSessionManager {

  private val defaultAgentName = "Jorlan Interactive"

  private def ensureDefaultAgent: IO[JorlanError, Agent] =
    agentRepo
      .search(AgentSearch())
      .mapError(JorlanError(_))
      .flatMap { agents =>
        agents.find(_.name == defaultAgentName) match {
          case Some(a) => ZIO.succeed(a)
          case None    =>
            Clock.instant.flatMap { now =>
              agentRepo
                .upsert(
                  Agent(
                    id = AgentId.empty,
                    name = defaultAgentName,
                    description = Some("Default interactive agent"),
                    defaultModel = None,
                    trustLevel = 0,
                    createdAt = now,
                  ),
                )
                .mapError(JorlanError(_))
            }
        }
      }

  override def createSession(
    userId:  UserId,
    modelId: Option[ModelId],
  ): IO[JorlanError, AgentSession] =
    for {
      agent <- ensureDefaultAgent
      now   <- Clock.instant
      _     <- sessionHub.getOrCreate(AgentSessionId.empty).unit
      session = AgentSession(
        id = AgentSessionId.empty,
        agentId = agent.id,
        userId = userId,
        workspaceId = None,
        status = SessionStatus.Active,
        modelId = modelId,
        createdAt = now,
        updatedAt = now,
      )
      saved <- agentRepo.upsertSession(session).mapError(JorlanError(_))
      _     <- sessionHub.getOrCreate(saved.id).unit
      _     <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.SessionCreated,
          actorId = Some(userId),
          agentId = Some(agent.id),
          sessionId = Some(saved.id),
          resource = Some(saved.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield saved

  override def getSession(id: AgentSessionId): IO[JorlanError, Option[AgentSession]] =
    agentRepo.getSession(id).mapError(JorlanError(_))

  private def updateStatus(
    id:     AgentSessionId,
    status: SessionStatus,
  ): IO[JorlanError, AgentSession] =
    for {
      session <- agentRepo
        .getSession(id)
        .mapError(JorlanError(_))
        .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"Session $id not found")))
      now   <- Clock.instant
      saved <- agentRepo
        .upsertSession(session.copy(status = status, updatedAt = now))
        .mapError(JorlanError(_))
    } yield saved

  override def suspendSession(id: AgentSessionId): IO[JorlanError, AgentSession] =
    updateStatus(id, SessionStatus.Paused)

  override def terminateSession(id: AgentSessionId): IO[JorlanError, AgentSession] =
    updateStatus(id, SessionStatus.Completed) <* sessionHub.remove(id)

}

object AgentSessionManagerImpl {

  val live: URLayer[AgentZIORepository & SessionHub & EventLogService, AgentSessionManager] =
    ZLayer.fromFunction(
      (
        agentRepo:  AgentZIORepository,
        sessionHub: SessionHub,
        eventLog:   EventLogService,
      ) => new AgentSessionManagerImpl(agentRepo, sessionHub, eventLog),
    )

}
