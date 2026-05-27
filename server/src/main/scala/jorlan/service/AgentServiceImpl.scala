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
import jorlan.db.repository.{AgentZIORepository, RepositoryTask}
import jorlan.domain.*
import zio.*
import zio.json.ast.Json

private class AgentServiceImpl(
  repo:     AgentZIORepository,
  eventLog: EventLogService,
) extends AgentService {

  override def getAgent(id: AgentId): IO[JorlanError, Option[Agent]] = repo.getById(id)

  override def searchAgents(s: AgentSearch): IO[JorlanError, List[Agent]] = repo.search(s)

  override def upsertAgent(
    agent:   Agent,
    actorId: Option[UserId],
  ): IO[JorlanError, Agent] = repo.upsert(agent)

  override def deleteAgent(
    id:      AgentId,
    actorId: Option[UserId],
  ): IO[JorlanError, Long] = repo.delete(id)

  override def getSession(id: AgentSessionId): IO[JorlanError, Option[AgentSession]] = repo.getSession(id)

  override def searchSessions(s: AgentSessionSearch): IO[JorlanError, List[AgentSession]] = repo.searchSessions(s)

  override def startSession(
    session: AgentSession,
    actorId: Option[UserId],
  ): IO[JorlanError, AgentSession] =
    for {
      now   <- Clock.instant
      saved <- repo.upsertSession(session)
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.AgentStarted,
          actorId = actorId,
          agentId = Some(saved.agentId),
          sessionId = Some(saved.id),
          resource = Some(saved.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield saved

  override def completeSession(
    session: AgentSession,
    actorId: Option[UserId],
  ): IO[JorlanError, AgentSession] =
    for {
      now   <- Clock.instant
      saved <- repo.upsertSession(session)
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.AgentCompleted,
          actorId = actorId,
          agentId = Some(saved.agentId),
          sessionId = Some(saved.id),
          resource = Some(saved.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield saved

  override def failSession(
    session:     AgentSession,
    errorDetail: Option[Json],
    actorId:     Option[UserId],
  ): IO[JorlanError, AgentSession] =
    for {
      now   <- Clock.instant
      saved <- repo.upsertSession(session)
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.AgentFailed,
          actorId = actorId,
          agentId = Some(saved.agentId),
          sessionId = Some(saved.id),
          resource = Some(saved.id),
          payloadJson = errorDetail,
          occurredAt = now,
        ),
      )
    } yield saved

}

object AgentServiceImpl {

  val live: URLayer[AgentZIORepository & EventLogService, AgentService] =
    ZLayer.fromFunction(
      (
        repo:     AgentZIORepository,
        eventLog: EventLogService,
      ) => new AgentServiceImpl(repo, eventLog),
    )

}
