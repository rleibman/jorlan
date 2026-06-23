/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.{ZIOAgentRepository, ZIOEventLogRepository, ZIORepositories}
import jorlan.*
import zio.*

/** Concrete [[AgentSessionManager]] implementation. Session lifecycle is managed via DB CRUD and event-log writes.
  *
  * The default agent is resolved from the V016 migration seed ("Jorlan Interactive") and cached after the first lookup.
  * [[terminateSession]] also calls [[ModelGateway.invalidateSession]] to release any in-memory model state.
  */
class AgentSessionManagerImpl(
  repo:                 ZIORepositories,
  modelGateway:         ModelGateway,
  cachedDefaultAgentId: Ref[Option[AgentId]],
  eventLogHub:          EventLogHub,
) extends AgentSessionManager {

  private val defaultAgentName = "Jorlan Interactive"

  /** Returns the seeded default agent, using a cached ID after the first DB lookup. The agent is guaranteed to exist
    * because V016 migration seeds it.
    */
  private def getDefaultAgent: IO[JorlanError, Agent] =
    cachedDefaultAgentId.get.flatMap {
      case Some(id) =>
        repo.agent
          .getById(id)
          .mapError(JorlanError(_))
          .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"Default agent $id not found; check V016 migration")))
      case None =>
        repo.agent
          .search(AgentSearch())
          .mapError(JorlanError(_))
          .flatMap { agents =>
            ZIO
              .fromOption(agents.find(_.name == defaultAgentName))
              .orElseFail(JorlanError(s"Default agent '$defaultAgentName' not found — ensure V016 migration ran"))
          }
          .tap(a => cachedDefaultAgentId.set(Some(a.id)))
    }

  override def createSession(
    userId:  UserId,
    modelId: Option[ModelId],
  ): IO[JorlanError, AgentSession] =
    for {
      agent <- getDefaultAgent
      now   <- Clock.instant
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
      saved    <- repo.agent.upsertSession(session).mapError(JorlanError(_))
      logEntry <- repo.eventLog.append(
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
      _ <- eventLogHub.publishTyped(logEntry)
    } yield saved

  override def getSession(id: AgentSessionId): IO[JorlanError, Option[AgentSession]] =
    repo.agent.getSession(id).mapError(JorlanError(_))

  private def updateStatus(
    id:        AgentSessionId,
    status:    SessionStatus,
    eventType: EventType,
  ): IO[JorlanError, AgentSession] =
    for {
      session <- repo.agent
        .getSession(id)
        .mapError(JorlanError(_))
        .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"Session $id not found")))
      now   <- Clock.instant
      saved <- repo.agent
        .upsertSession(session.copy(status = status, updatedAt = now))
        .mapError(JorlanError(_))
      logEntry <- repo.eventLog.append(
        EventLog(
          id = EventLogId.empty,
          eventType = eventType,
          actorId = None,
          agentId = Some(session.agentId),
          sessionId = Some(id),
          resource = Some(id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
      _ <- eventLogHub.publishTyped(logEntry)
    } yield saved

  override def suspendSession(id: AgentSessionId): IO[JorlanError, AgentSession] =
    updateStatus(id, SessionStatus.Paused, EventType.SessionSuspended)

  override def terminateSession(id: AgentSessionId): IO[JorlanError, AgentSession] =
    updateStatus(id, SessionStatus.Completed, EventType.SessionTerminated) <*
      modelGateway.invalidateSession(id)

  override def listSessions(
    userId:   UserId,
    page:     Int,
    pageSize: Int,
  ): IO[JorlanError, List[AgentSession]] = {
    val cutoff = java.time.Instant.now().minus(1L, java.time.temporal.ChronoUnit.DAYS)
    repo.agent
      .searchSessions(
        AgentSessionSearch(
          userId = Some(userId),
          page = page,
          pageSize = pageSize,
          hideOldTerminatedBefore = Some(cutoff),
        ),
      )
      .mapError(JorlanError(_))
  }

}

object AgentSessionManagerImpl {

  /** Constructs the layer. Allocates a [[Ref]] for the cached default agent ID. */
  val live: URLayer[ZIORepositories & ModelGateway & EventLogHub, AgentSessionManager] =
    ZLayer.fromZIO(
      for {
        repo         <- ZIO.service[ZIORepositories]
        modelGateway <- ZIO.service[ModelGateway]
        hub          <- ZIO.service[EventLogHub]
        cached       <- Ref.make(Option.empty[AgentId])
      } yield AgentSessionManagerImpl(repo, modelGateway, cached, hub),
    )

}
