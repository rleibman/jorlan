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
import zio.json.ast.Json

// TODO, this is not necessary, we can move what little functionality we have here to the AgentRepository
/** Application service for agent and session lifecycle.
  *
  * All session-state mutations (start, complete, fail) write to the event log so every agent execution is auditable.
  * Callers supply `actorId` explicitly — never call `Instant.now` or `Clock.instant` here; use the ZIO `Clock` service
  * instead.
  */
trait AgentService {

  def getAgent(id:    AgentId):     IO[JorlanError, Option[Agent]]
  def searchAgents(s: AgentSearch): IO[JorlanError, List[Agent]]
  def upsertAgent(
    agent:   Agent,
    actorId: Option[UserId],
  ): IO[JorlanError, Agent]
  def deleteAgent(
    id:      AgentId,
    actorId: Option[UserId],
  ): IO[JorlanError, Long]

  def getSession(id:    AgentSessionId):     IO[JorlanError, Option[AgentSession]]
  def searchSessions(s: AgentSessionSearch): IO[JorlanError, List[AgentSession]]

  /** Start a new agent session. Writes an [[EventType.AgentStarted]] event. */
  def startSession(
    session: AgentSession,
    actorId: Option[UserId],
  ): IO[JorlanError, AgentSession]

  /** Mark a session as completed successfully. Writes an [[EventType.AgentCompleted]] event. */
  def completeSession(
    session: AgentSession,
    actorId: Option[UserId],
  ): IO[JorlanError, AgentSession]

  /** Mark a session as failed. Writes an [[EventType.AgentFailed]] event with the error detail. */
  def failSession(
    session:     AgentSession,
    errorDetail: Option[Json],
    actorId:     Option[UserId],
  ): IO[JorlanError, AgentSession]

}

object AgentService {

  def getAgent(id: AgentId): ZIO[AgentService, JorlanError, Option[Agent]] =
    ZIO.serviceWithZIO[AgentService](_.getAgent(id))

  def searchAgents(s: AgentSearch): ZIO[AgentService, JorlanError, List[Agent]] =
    ZIO.serviceWithZIO[AgentService](_.searchAgents(s))

  def upsertAgent(
    agent:   Agent,
    actorId: Option[UserId],
  ): ZIO[AgentService, JorlanError, Agent] = ZIO.serviceWithZIO[AgentService](_.upsertAgent(agent, actorId))

  def deleteAgent(
    id:      AgentId,
    actorId: Option[UserId],
  ): ZIO[AgentService, JorlanError, Long] = ZIO.serviceWithZIO[AgentService](_.deleteAgent(id, actorId))

  def getSession(id: AgentSessionId): ZIO[AgentService, JorlanError, Option[AgentSession]] =
    ZIO.serviceWithZIO[AgentService](_.getSession(id))

  def searchSessions(s: AgentSessionSearch): ZIO[AgentService, JorlanError, List[AgentSession]] =
    ZIO.serviceWithZIO[AgentService](_.searchSessions(s))

  def startSession(
    session: AgentSession,
    actorId: Option[UserId],
  ): ZIO[AgentService, JorlanError, AgentSession] = ZIO.serviceWithZIO[AgentService](_.startSession(session, actorId))

  def completeSession(
    session: AgentSession,
    actorId: Option[UserId],
  ): ZIO[AgentService, JorlanError, AgentSession] =
    ZIO.serviceWithZIO[AgentService](_.completeSession(session, actorId))

  def failSession(
    session:     AgentSession,
    errorDetail: Option[Json],
    actorId:     Option[UserId],
  ): ZIO[AgentService, JorlanError, AgentSession] =
    ZIO.serviceWithZIO[AgentService](_.failSession(session, errorDetail, actorId))

}
