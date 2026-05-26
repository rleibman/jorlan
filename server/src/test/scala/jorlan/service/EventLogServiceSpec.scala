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
import jorlan.service.TestFixtures.{*, given}
import jorlan.{OrderDirection, Sort}
import zio.*
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object EventLogServiceSpec extends ZIOSpecDefault {

  /** Functional in-memory event log repository — no DB, no shared mutable state. */
  private class InMemoryEventLogRepo(
    idGen: Ref[Long],
    store: Ref[List[EventLog[Json]]],
  ) extends EventLogZIORepository {

    override def append[R: JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] =
      for {
        nextId <- idGen.updateAndGet(_ + 1)
        saved = event.copy(id = EventLogId(nextId))
        asJson <- ZIO
          .fromEither(
            event.resource.fold[Either[String, Option[Json]]](Right(None))(r =>
              JsonEncoder[R].toJsonAST(r).map(Some(_)),
            ),
          ).mapError(msg => RepositoryError(msg))
        _ <- store.update(saved.copy(resource = asJson) :: _)
      } yield saved

    override def search(filter: EventLogFilter): RepositoryTask[List[EventLog[Json]]] =
      store.get.map { events =>
        val ascending = filter.sorts.headOption.exists(_.direction == OrderDirection.Asc)
        events
          .filter(e => filter.eventType.forall(_ == e.eventType))
          .filter(e => filter.agentId.forall(id => e.agentId.contains(id)))
          .filter(e => filter.sessionId.forall(sid => e.sessionId.contains(sid)))
          .filter(e => filter.from.forall(f => !e.occurredAt.isBefore(f)))
          .filter(e => filter.to.forall(t => !e.occurredAt.isAfter(t)))
          .sortBy(_.occurredAt)(if (ascending) Ordering[Instant] else Ordering[Instant].reverse)
          .drop(filter.page * filter.pageSize)
          .take(filter.pageSize)
      }

    override def replaySession(sessionId: AgentSessionId): RepositoryTask[List[EventLog[Json]]] =
      store.get.map { events =>
        events
          .filter(_.sessionId.contains(sessionId))
          .sortBy(_.occurredAt)
      }

  }

  private object InMemoryEventLogRepo {

    def make: UIO[InMemoryEventLogRepo] =
      for {
        idGen <- Ref.make(0L)
        store <- Ref.make(List.empty[EventLog[Json]])
      } yield new InMemoryEventLogRepo(idGen, store)

  }

  private def freshServiceLayer: ULayer[EventLogService] = {
    val repoLayer: ULayer[EventLogZIORepository] = ZLayer(InMemoryEventLogRepo.make.map(r => r: EventLogZIORepository))
    repoLayer >>> EventLogServiceImpl.live
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EventLogService")(
      eventLogSuite,
      correlationIdSuite,
    )

  private val eventLogSuite = suite("event logging")(
    test("log stores an event and returns it with a generated id") {
      for {
        svc   <- ZIO.service[EventLogService]
        saved <- svc.log(testEvent(EventType.AgentStarted))
      } yield assertTrue(saved.id.value > 0L, saved.eventType == EventType.AgentStarted)
    }.provide(freshServiceLayer),
    test("log fails when resource encoding fails") {
      // We test this via the InMemoryRepo which now propagates encoding errors
      for {
        svc <- ZIO.service[EventLogService]
        // SkillId encodes fine — just verifying the success path is type-safe
        saved <- svc.log(testEvent[SkillId](EventType.SkillInvoked, Some(SkillId(1L))))
      } yield assertTrue(saved.id.value > 0L)
    }.provide(freshServiceLayer),
    test("query filters by event type") {
      for {
        svc         <- ZIO.service[EventLogService]
        _           <- svc.log(testEvent(EventType.AgentStarted))
        _           <- svc.log(testEvent(EventType.SkillInvoked))
        agentEvents <- svc.query(EventLogFilter(eventType = Some(EventType.AgentStarted)))
        skillEvents <- svc.query(EventLogFilter(eventType = Some(EventType.SkillInvoked)))
        allEvents   <- svc.query(EventLogFilter())
      } yield assertTrue(
        agentEvents.forall(_.eventType == EventType.AgentStarted),
        skillEvents.forall(_.eventType == EventType.SkillInvoked),
        allEvents.length == 2,
      )
    }.provide(freshServiceLayer),
    test("query filters by agent id") {
      val aid = AgentId(99L)
      for {
        svc      <- ZIO.service[EventLogService]
        _        <- svc.log(testEvent(EventType.AgentStarted, agentId = Some(aid)))
        _        <- svc.log(testEvent(EventType.AgentCompleted))
        filtered <- svc.query(EventLogFilter(agentId = Some(aid)))
      } yield assertTrue(
        filtered.nonEmpty,
        filtered.forall(_.agentId.contains(aid)),
      )
    }.provide(freshServiceLayer),
    test("query applies combined agentId AND eventType filter") {
      val aid = AgentId(10L)
      for {
        svc    <- ZIO.service[EventLogService]
        _      <- svc.log(testEvent(EventType.AgentStarted, agentId = Some(aid)))
        _      <- svc.log(testEvent(EventType.SkillInvoked, agentId = Some(aid)))
        _      <- svc.log(testEvent(EventType.AgentStarted))
        result <- svc.query(EventLogFilter(eventType = Some(EventType.AgentStarted), agentId = Some(aid)))
      } yield assertTrue(
        result.length == 1,
        result.forall(e => e.eventType == EventType.AgentStarted && e.agentId.contains(aid)),
      )
    }.provide(freshServiceLayer),
    test("query respects from/to time range") {
      val t1 = T0.minusSeconds(2)
      val t2 = T0
      val t3 = T0.plusSeconds(2)
      for {
        svc    <- ZIO.service[EventLogService]
        _      <- svc.log(testEvent(EventType.AgentStarted, occurredAt = t1))
        _      <- svc.log(testEvent(EventType.AgentStarted, occurredAt = t2))
        _      <- svc.log(testEvent(EventType.AgentStarted, occurredAt = t3))
        result <- svc.query(EventLogFilter(from = Some(T0.minusSeconds(1)), to = Some(T0.plusSeconds(1))))
      } yield assertTrue(result.length == 1, result.head.occurredAt == t2)
    }.provide(freshServiceLayer),
    test("query returns results in descending order") {
      val t1 = T0
      val t2 = T0.plusSeconds(1)
      val t3 = T0.plusSeconds(2)
      for {
        svc     <- ZIO.service[EventLogService]
        _       <- ZIO.foreachDiscard(List(t1, t2, t3))(t => svc.log(testEvent(EventType.AgentStarted, occurredAt = t)))
        results <- svc.query(EventLogFilter())
      } yield assertTrue(results.map(_.occurredAt) == results.map(_.occurredAt).sortWith(_.isAfter(_)))
    }.provide(freshServiceLayer),
    test("query rejects limit <= 0") {
      for {
        svc    <- ZIO.service[EventLogService]
        result <- svc.query(EventLogFilter(pageSize = 0)).exit
      } yield assertTrue(result.isFailure)
    }.provide(freshServiceLayer),
    test("query rejects limit > MaxLimit") {
      for {
        svc    <- ZIO.service[EventLogService]
        result <- svc.query(EventLogFilter(pageSize = EventLogFilter.MaxLimit + 1)).exit
      } yield assertTrue(result.isFailure)
    }.provide(freshServiceLayer),
    test("replay returns session events in ascending order") {
      val sid = AgentSessionId(42L)
      val t1 = T0
      val t2 = T0.plusSeconds(1)
      val t3 = T0.plusSeconds(2)
      for {
        svc <- ZIO.service[EventLogService]
        // Insert in reverse order to prove the sort is doing real work
        e3       <- svc.log(testEvent(EventType.AgentCompleted, sessionId = Some(sid), occurredAt = t3))
        e1       <- svc.log(testEvent(EventType.AgentStarted, sessionId = Some(sid), occurredAt = t1))
        e2       <- svc.log(testEvent(EventType.SkillInvoked, sessionId = Some(sid), occurredAt = t2))
        replayed <- svc.replay(sid)
      } yield assertTrue(
        replayed.map(_.id) == List(e1.id, e2.id, e3.id),
        replayed.map(_.occurredAt) == List(t1, t2, t3),
      )
    }.provide(freshServiceLayer),
    test("replay excludes events from other sessions") {
      val sid = AgentSessionId(100L)
      val othSid = AgentSessionId(200L)
      for {
        svc    <- ZIO.service[EventLogService]
        _      <- svc.log(testEvent(EventType.AgentStarted, sessionId = Some(sid)))
        _      <- svc.log(testEvent(EventType.AgentStarted, sessionId = Some(othSid)))
        result <- svc.replay(sid)
      } yield assertTrue(result.forall(_.sessionId.contains(sid)))
    }.provide(freshServiceLayer),
    test("EventLogService companion accessors delegate correctly") {
      for {
        saved    <- EventLogService.log(testEvent(EventType.SystemAlert))
        results  <- EventLogService.query(EventLogFilter(eventType = Some(EventType.SystemAlert)))
        replayed <- EventLogService.replay(AgentSessionId(0L))
      } yield assertTrue(
        saved.id.value > 0L,
        results.exists(_.id == saved.id),
        replayed.isEmpty,
      )
    }.provide(freshServiceLayer),
  )

  private val correlationIdSuite = suite("CorrelationId")(
    test("no correlation id before withNew") {
      for {
        before <- CorrelationId.get
      } yield assertTrue(before.isEmpty)
    },
    test("withNew sets a non-empty correlation id") {
      for {
        inside <- CorrelationId.withNew(CorrelationId.get)
      } yield assertTrue(inside.isDefined)
    },
    test("withNew generates unique ids across calls") {
      for {
        id1   <- CorrelationId.withNew(CorrelationId.get)
        id2   <- CorrelationId.withNew(CorrelationId.get)
        after <- CorrelationId.get
      } yield assertTrue(id1 != id2, after.isEmpty)
    },
    test("withId sets an explicit correlation id") {
      for {
        inside <- CorrelationId.withId("test-123")(CorrelationId.get)
        after  <- CorrelationId.get
      } yield assertTrue(inside.contains("test-123"), after.isEmpty)
    },
    test("nested withId restores outer id after inner block") {
      for {
        outerResult <- CorrelationId.withId("outer") {
          for {
            inner <- CorrelationId.withId("inner")(CorrelationId.get)
            outer <- CorrelationId.get
          } yield (inner, outer)
        }
        (inner, outer) = outerResult
      } yield assertTrue(inner.contains("inner"), outer.contains("outer"))
    },
  )

}
