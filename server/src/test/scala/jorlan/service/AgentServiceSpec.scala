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
import jorlan.db.repository.{AgentZIORepository, EventLogZIORepository, RepositoryTask}
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object AgentServiceSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  private class InMemoryAgentRepo(
    agentIdGen:   Ref[Long],
    agentStore:   Ref[Map[Long, Agent]],
    sessionIdGen: Ref[Long],
    sessionStore: Ref[Map[Long, AgentSession]],
  ) extends AgentZIORepository {

    override def getById(id: AgentId): RepositoryTask[Option[Agent]] =
      agentStore.get.map(_.get(id.value))

    override def search(s: AgentSearch): RepositoryTask[List[Agent]] =
      agentStore.get.map(_.values.toList)

    override def upsert(agent: Agent): RepositoryTask[Agent] =
      for {
        id <- if (agent.id == AgentId.empty) agentIdGen.updateAndGet(_ + 1) else ZIO.succeed(agent.id.value)
        saved = agent.copy(id = AgentId(id))
        _ <- agentStore.update(_.updated(id, saved))
      } yield saved

    override def delete(id: AgentId): RepositoryTask[Long] =
      agentStore.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value))
        else (0L, m)
      }

    override def getSession(id: AgentSessionId): RepositoryTask[Option[AgentSession]] =
      sessionStore.get.map(_.get(id.value))

    override def searchSessions(s: AgentSessionSearch): RepositoryTask[List[AgentSession]] =
      sessionStore.get.map { sessions =>
        sessions.values.toList.filter(ss => s.agentId.forall(_ == ss.agentId))
      }

    override def upsertSession(session: AgentSession): RepositoryTask[AgentSession] =
      for {
        id <-
          if (session.id == AgentSessionId.empty) sessionIdGen.updateAndGet(_ + 1)
          else ZIO.succeed(session.id.value)
        saved = session.copy(id = AgentSessionId(id))
        _ <- sessionStore.update(_.updated(id, saved))
      } yield saved

  }

  private object InMemoryAgentRepo {

    def make: UIO[InMemoryAgentRepo] =
      for {
        agentIdGen   <- Ref.make(0L)
        agentStore   <- Ref.make(Map.empty[Long, Agent])
        sessionIdGen <- Ref.make(0L)
        sessionStore <- Ref.make(Map.empty[Long, AgentSession])
      } yield new InMemoryAgentRepo(agentIdGen, agentStore, sessionIdGen, sessionStore)

  }

  private class InMemoryEventLogRepo(
    idGen: Ref[Long],
    store: Ref[List[EventLog[Json]]],
  ) extends EventLogZIORepository {

    override def append[R: zio.json.JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] =
      for {
        nextId <- idGen.updateAndGet(_ + 1)
        saved = event.copy(id = EventLogId(nextId))
        generic = EventLog[Json](
          id = saved.id,
          eventType = saved.eventType,
          actorId = saved.actorId,
          agentId = saved.agentId,
          sessionId = saved.sessionId,
          resource = None,
          payloadJson = saved.payloadJson,
          occurredAt = saved.occurredAt,
        )
        _ <- store.update(generic :: _)
      } yield saved

    override def search(filter: EventLogFilter): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(e => filter.eventType.forall(_ == e.eventType)))

    override def replaySession(
      sessionId: AgentSessionId,
      limit:     Int,
    ): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(_.sessionId.contains(sessionId)).reverse)

  }

  private object InMemoryEventLogRepo {

    def make: UIO[InMemoryEventLogRepo] =
      for {
        idGen <- Ref.make(0L)
        store <- Ref.make(List.empty[EventLog[Json]])
      } yield new InMemoryEventLogRepo(idGen, store)

  }

  private def freshLayers: ULayer[AgentService & EventLogService] = {
    val agentRepoLayer: ULayer[AgentZIORepository] =
      ZLayer(InMemoryAgentRepo.make.map(r => r: AgentZIORepository))
    val logRepoLayer: ULayer[EventLogZIORepository] =
      ZLayer(InMemoryEventLogRepo.make.map(r => r: EventLogZIORepository))
    val eventLogLayer: ULayer[EventLogService] = logRepoLayer >>> EventLogServiceImpl.live
    (agentRepoLayer ++ eventLogLayer) >>> AgentServiceImpl.live ++ eventLogLayer
  }

  private val templateAgent: Agent = Agent(AgentId.empty, "MyAgent", None, None, 0, T0)

  private val templateSession: AgentSession = AgentSession(
    id = AgentSessionId.empty,
    agentId = AgentId(1L),
    userId = UserId(1L),
    workspaceId = None,
    status = SessionStatus.Created,
    modelId = None,
    createdAt = T0,
    updatedAt = T0,
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AgentService")(
      test("upsertAgent assigns a generated id") {
        for {
          svc   <- ZIO.service[AgentService]
          saved <- svc.upsertAgent(templateAgent, None)
        } yield assertTrue(saved.id.value > 0L, saved.name == "MyAgent")
      }.provide(freshLayers),
      test("getAgent returns None for unknown id") {
        for {
          svc    <- ZIO.service[AgentService]
          result <- svc.getAgent(AgentId(999L))
        } yield assertTrue(result.isEmpty)
      }.provide(freshLayers),
      test("getAgent returns the agent after upsert") {
        for {
          svc   <- ZIO.service[AgentService]
          saved <- svc.upsertAgent(templateAgent, None)
          found <- svc.getAgent(saved.id)
        } yield assertTrue(found.contains(saved))
      }.provide(freshLayers),
      test("deleteAgent removes the agent and returns count 1") {
        for {
          svc     <- ZIO.service[AgentService]
          saved   <- svc.upsertAgent(templateAgent, None)
          count   <- svc.deleteAgent(saved.id, None)
          missing <- svc.getAgent(saved.id)
        } yield assertTrue(count == 1L, missing.isEmpty)
      }.provide(freshLayers),
      test("searchAgents returns all upserted agents") {
        for {
          svc     <- ZIO.service[AgentService]
          _       <- svc.upsertAgent(templateAgent, None)
          _       <- svc.upsertAgent(templateAgent.copy(name = "Agent2"), None)
          results <- svc.searchAgents(AgentSearch())
        } yield assertTrue(results.length == 2)
      }.provide(freshLayers),
      test("startSession saves session and logs AgentStarted") {
        for {
          svc    <- ZIO.service[AgentService]
          log    <- ZIO.service[EventLogService]
          saved  <- svc.startSession(templateSession, actorId = Some(UserId(1L)))
          events <- log.query(EventLogFilter(eventType = Some(EventType.AgentStarted)))
        } yield assertTrue(
          saved.id.value > 0L,
          events.nonEmpty,
          events.exists(_.agentId.contains(saved.agentId)),
        )
      }.provide(freshLayers),
      test("completeSession logs AgentCompleted") {
        for {
          svc    <- ZIO.service[AgentService]
          log    <- ZIO.service[EventLogService]
          s1     <- svc.startSession(templateSession, None)
          _      <- svc.completeSession(s1.copy(status = SessionStatus.Completed), None)
          events <- log.query(EventLogFilter(eventType = Some(EventType.AgentCompleted)))
        } yield assertTrue(events.nonEmpty)
      }.provide(freshLayers),
      test("failSession logs AgentFailed with error payload") {
        for {
          svc    <- ZIO.service[AgentService]
          log    <- ZIO.service[EventLogService]
          s1     <- svc.startSession(templateSession, None)
          _      <- svc.failSession(s1.copy(status = SessionStatus.Failed), Some(Json.Str("timeout")), None)
          events <- log.query(EventLogFilter(eventType = Some(EventType.AgentFailed)))
        } yield assertTrue(
          events.nonEmpty,
          events.head.payloadJson.contains(Json.Str("timeout")),
        )
      }.provide(freshLayers),
      test("getSession returns None for unknown session") {
        for {
          svc    <- ZIO.service[AgentService]
          result <- svc.getSession(AgentSessionId(999L))
        } yield assertTrue(result.isEmpty)
      }.provide(freshLayers),
      test("searchSessions filters by agentId") {
        for {
          svc <- ZIO.service[AgentService]
          aid1 = AgentId(1L)
          aid2 = AgentId(2L)
          _       <- svc.startSession(templateSession.copy(agentId = aid1), None)
          _       <- svc.startSession(templateSession.copy(agentId = aid2), None)
          results <- svc.searchSessions(AgentSessionSearch(agentId = Some(aid1)))
        } yield assertTrue(results.length == 1, results.head.agentId == aid1)
      }.provide(freshLayers),
    )

}
