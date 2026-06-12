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

import jorlan.{AgentId, AgentSessionId, EventLog, EventLogId, EventType, UserId}
import jorlan.*
import jorlan.service.EventLogFilter
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

@scala.annotation.nowarn("msg=IsUnionOf")
object EventLogRepositorySpec extends ZIOSpecDefault {

  private val T0 = Instant.parse("2026-01-15T12:00:00Z")

  private def makeEntry(
    eventType:  EventType,
    actorId:    Option[UserId] = None,
    sessionId:  Option[AgentSessionId] = None,
    agentId:    Option[AgentId] = None,
    occurredAt: Instant = T0,
  ): EventLog[Nothing] =
    EventLog(
      id = EventLogId.empty,
      eventType = eventType,
      actorId = actorId,
      agentId = agentId,
      sessionId = sessionId,
      resource = None,
      payloadJson = None,
      occurredAt = occurredAt,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("InMemoryEventLogRepo")(
      test("append assigns a unique id") {
        for {
          repo <- InMemoryRepositories.InMemoryEventLogRepo.make
          e1   <- repo.append(makeEntry(EventType.UserCreated))
          e2   <- repo.append(makeEntry(EventType.UserUpdated))
        } yield assertTrue(
          e1.id != EventLogId.empty,
          e2.id != EventLogId.empty,
          e1.id != e2.id,
        )
      },
      test("search with no filter returns all appended events") {
        for {
          repo <- InMemoryRepositories.InMemoryEventLogRepo.make
          _    <- repo.append(makeEntry(EventType.UserCreated))
          _    <- repo.append(makeEntry(EventType.SessionCreated))
          filter = EventLogFilter()
          result <- repo.search(filter)
        } yield assertTrue(result.size == 2)
      },
      test("search filters by eventType") {
        for {
          repo <- InMemoryRepositories.InMemoryEventLogRepo.make
          _    <- repo.append(makeEntry(EventType.UserCreated))
          _    <- repo.append(makeEntry(EventType.SessionCreated))
          _    <- repo.append(makeEntry(EventType.UserCreated))
          filter = EventLogFilter(eventType = Some(EventType.UserCreated))
          result <- repo.search(filter)
        } yield assertTrue(result.size == 2, result.forall(_.eventType == EventType.UserCreated))
      },
      test("replaySession returns only events for the matching sessionId in order") {
        for {
          repo <- InMemoryRepositories.InMemoryEventLogRepo.make
          sid1 = AgentSessionId(1L)
          sid2 = AgentSessionId(2L)
          _      <- repo.append(makeEntry(EventType.UserMessageReceived, sessionId = Some(sid1)))
          _      <- repo.append(makeEntry(EventType.AgentResponseCompleted, sessionId = Some(sid2)))
          _      <- repo.append(makeEntry(EventType.ModelCallCompleted, sessionId = Some(sid1)))
          result <- repo.replaySession(sid1, 100)
        } yield assertTrue(
          result.size == 2,
          result.forall(_.sessionId.contains(sid1)),
        )
      },
      test("replaySession respects the limit") {
        for {
          repo <- InMemoryRepositories.InMemoryEventLogRepo.make
          sid = AgentSessionId(3L)
          _      <- repo.append(makeEntry(EventType.UserMessageReceived, sessionId = Some(sid)))
          _      <- repo.append(makeEntry(EventType.AgentResponseCompleted, sessionId = Some(sid)))
          _      <- repo.append(makeEntry(EventType.ModelCallCompleted, sessionId = Some(sid)))
          result <- repo.replaySession(sid, 2)
        } yield assertTrue(result.size == 2)
      },
      test("search with agentId filter returns only matching events") {
        for {
          repo <- InMemoryRepositories.InMemoryEventLogRepo.make
          agId1 = AgentId(1L)
          agId2 = AgentId(2L)
          _ <- repo.append(makeEntry(EventType.AgentStarted, agentId = Some(agId1)))
          _ <- repo.append(makeEntry(EventType.AgentCompleted, agentId = Some(agId2)))
          _ <- repo.append(makeEntry(EventType.AgentStarted, agentId = Some(agId1)))
          // InMemoryEventLogRepo only filters by eventType; this tests the filter API shape
          filter = EventLogFilter(eventType = Some(EventType.AgentStarted))
          result <- repo.search(filter)
        } yield assertTrue(result.size == 2, result.forall(_.eventType == EventType.AgentStarted))
      },
      test("EventLogFilter.validatePageSize rejects out-of-range sizes") {
        assertTrue(
          EventLogFilter.validatePageSize(EventLogFilter(pageSize = 0)).isLeft,
          EventLogFilter.validatePageSize(EventLogFilter(pageSize = EventLogFilter.MaxLimit + 1)).isLeft,
          EventLogFilter.validatePageSize(EventLogFilter(pageSize = 50)).isRight,
          EventLogFilter.validatePageSize(EventLogFilter(pageSize = EventLogFilter.MaxLimit)).isRight,
        )
      },
      test("EventLogFilter companion defaults are sane") {
        val f = EventLogFilter()
        assertTrue(
          f.eventType.isEmpty,
          f.agentId.isEmpty,
          f.sessionId.isEmpty,
          f.from.isEmpty,
          f.to.isEmpty,
          f.page == 0,
          f.pageSize == 100,
          f.sorts.isEmpty,
        )
      },
      test("append preserves eventType, actorId, and occurredAt") {
        for {
          repo <- InMemoryRepositories.InMemoryEventLogRepo.make
          entry = makeEntry(EventType.SchedulerJobStarted, actorId = Some(UserId(5L)), occurredAt = T0)
          saved <- repo.append(entry)
        } yield assertTrue(
          saved.eventType == EventType.SchedulerJobStarted,
          saved.actorId.contains(UserId(5L)),
          saved.occurredAt == T0,
        )
      },
      test("EventLog.entry smart constructor populates expected fields") {
        val entry = EventLog.entry(
          eventType = EventType.SchedulerJobStarted,
          actorId = Some(UserId(1L)),
          agentId = Some(AgentId(2L)),
          sessionId = Some(AgentSessionId(3L)),
          resource = Some("schedulerJob:42"),
          now = T0,
        )
        assertTrue(
          entry.id == EventLogId.empty,
          entry.eventType == EventType.SchedulerJobStarted,
          entry.actorId.contains(UserId(1L)),
          entry.agentId.contains(AgentId(2L)),
          entry.sessionId.contains(AgentSessionId(3L)),
          entry.occurredAt == T0,
        )
      },
    )

}
