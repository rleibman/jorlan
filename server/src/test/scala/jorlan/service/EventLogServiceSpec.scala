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

import jorlan.db.repository.{EventLogZIORepository, RepositoryError, RepositoryTask}
import jorlan.domain.*
import zio.*
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

object EventLogServiceSpec extends ZIOSpecDefault {

  private val now = Instant.now()

  /** In-memory event log repository for unit testing — no DB required. */
  private class InMemoryEventLogRepo extends EventLogZIORepository {

    private val idGen = AtomicLong(0)
    private val store = scala.collection.mutable.ArrayBuffer.empty[EventLog[Json]]

    override def append[R: JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] = {
      val id = EventLogId(idGen.incrementAndGet())
      val saved = event.copy(id = id)
      val asJson = saved.copy(resource = saved.resource.flatMap(r => JsonEncoder[R].toJsonAST(r).toOption))
      ZIO.succeed {
        store.synchronized(store += asJson)
        saved
      }
    }

    override def search(
      eventType: Option[EventType],
      agentId:   Option[AgentId],
      sessionId: Option[AgentSessionId],
      from:      Option[Instant],
      to:        Option[Instant],
      limit:     Int,
    ): RepositoryTask[List[EventLog[Json]]] =
      ZIO.succeed {
        store.synchronized {
          store.toList
            .filter(e => eventType.forall(_ == e.eventType))
            .filter(e => agentId.forall(id => e.agentId.contains(id)))
            .filter(e => sessionId.forall(sid => e.sessionId.contains(sid)))
            .filter(e => from.forall(f => !e.occurredAt.isBefore(f)))
            .filter(e => to.forall(t => !e.occurredAt.isAfter(t)))
            .sortBy(_.occurredAt)(Ordering[Instant].reverse)
            .take(limit)
        }
      }

  }

  private val repoLayer: ULayer[EventLogZIORepository] =
    ZLayer.succeed(new InMemoryEventLogRepo())

  private val serviceLayer: ULayer[EventLogService] =
    repoLayer >>> EventLogServiceImpl.live

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EventLogService")(
      test("log stores an event and returns it with a generated id") {
        for {
          svc <- ZIO.service[EventLogService]
          event = EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, None, None, None, now)
          saved <- svc.log(event)
        } yield assertTrue(saved.id.value > 0L, saved.eventType == EventType.AgentStarted)
      },
      test("query filters by event type") {
        for {
          svc <- ZIO.service[EventLogService]
          e1 = EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, None, None, None, now)
          e2 = EventLog[SkillId](EventLogId.empty, EventType.SkillInvoked, None, None, None, None, None, now)
          _           <- svc.log(e1)
          _           <- svc.log(e2)
          agentEvents <- svc.query(EventLogFilter(eventType = Some(EventType.AgentStarted)))
          skillEvents <- svc.query(EventLogFilter(eventType = Some(EventType.SkillInvoked)))
          allEvents   <- svc.query(EventLogFilter())
        } yield assertTrue(
          agentEvents.forall(_.eventType == EventType.AgentStarted),
          skillEvents.forall(_.eventType == EventType.SkillInvoked),
          allEvents.length >= 2,
        )
      },
      test("query filters by agent id") {
        for {
          svc <- ZIO.service[EventLogService]
          aid = AgentId(99L)
          withAid = EventLog[AgentId](
            EventLogId.empty,
            EventType.AgentStarted,
            None,
            Some(aid),
            None,
            None,
            None,
            now,
          )
          withoutAid = EventLog[AgentId](
            EventLogId.empty,
            EventType.AgentCompleted,
            None,
            None,
            None,
            None,
            None,
            now,
          )
          _        <- svc.log(withAid)
          _        <- svc.log(withoutAid)
          filtered <- svc.query(EventLogFilter(agentId = Some(aid)))
        } yield assertTrue(
          filtered.nonEmpty,
          filtered.forall(_.agentId.contains(aid)),
        )
      },
      test("replay returns session events in ascending order") {
        for {
          svc <- ZIO.service[EventLogService]
          sid = AgentSessionId(42L)
          e1 = EventLog[AgentId](
            EventLogId.empty,
            EventType.AgentStarted,
            None,
            None,
            Some(sid),
            None,
            None,
            now,
          )
          e2 = EventLog[SkillId](
            EventLogId.empty,
            EventType.SkillInvoked,
            None,
            None,
            Some(sid),
            None,
            None,
            now.plusSeconds(1),
          )
          e3 = EventLog[AgentId](
            EventLogId.empty,
            EventType.AgentCompleted,
            None,
            None,
            Some(sid),
            None,
            None,
            now.plusSeconds(2),
          )
          _        <- svc.log(e1) *> svc.log(e2) *> svc.log(e3)
          replayed <- svc.replay(sid)
        } yield assertTrue(
          replayed.length == 3,
          replayed.map(_.eventType) == List(EventType.AgentStarted, EventType.SkillInvoked, EventType.AgentCompleted),
        )
      },
      test("replay excludes events from other sessions") {
        for {
          svc <- ZIO.service[EventLogService]
          sid = AgentSessionId(100L)
          othSid = AgentSessionId(200L)
          _ <- svc.log(
            EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, Some(sid), None, None, now),
          )
          _ <- svc.log(
            EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, Some(othSid), None, None, now),
          )
          result <- svc.replay(sid)
        } yield assertTrue(result.forall(_.sessionId.contains(sid)))
      },
      test("correlationId is accessible within withNew block") {
        for {
          before <- CorrelationId.get
          inside <- CorrelationId.withNew(CorrelationId.get)
        } yield assertTrue(before.isEmpty, inside.isDefined)
      },
      test("withId sets explicit correlation id") {
        for {
          inside <- CorrelationId.withId("test-123")(CorrelationId.get)
        } yield assertTrue(inside.contains("test-123"))
      },
    ).provideLayer(serviceLayer)

}
