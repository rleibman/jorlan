/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import jorlan.db.TestFixtures.*
import jorlan.*
import jorlan.db.TestFixtures.given
import jorlan.domain.*
import jorlan.service.{CorrelationId, EventLogFilter, EventLogService, EventLogServiceImpl}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object EventLogServiceIntegrationSpec extends ZIOSpecDefault {

  private val serviceLayer =
    JorlanContainer.repositoryLayer >>> EventLogServiceImpl.live

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EventLogService integration")(
      test("log and query roundtrip against real DB") {
        for {
          svc   <- ZIO.service[EventLogService]
          saved <- svc.log(testEvent(EventType.AgentStarted))
          found <- svc.query(EventLogFilter(eventType = Some(EventType.AgentStarted), pageSize = 10))
        } yield assertTrue(
          saved.id.value > 0L,
          found.exists(_.id == saved.id),
        )
      },
      test("query filters by time range") {
        val t1 = T0.minusSeconds(5)
        val t2 = T0
        val t3 = T0.plusSeconds(5)
        for {
          svc <- ZIO.service[EventLogService]
          _   <- svc.log(testEvent(EventType.SystemAlert, occurredAt = t1))
          _   <- svc.log(testEvent(EventType.SystemAlert, occurredAt = t2))
          _   <- svc.log(testEvent(EventType.SystemAlert, occurredAt = t3))
          result <- svc.query(
            EventLogFilter(
              eventType = Some(EventType.SystemAlert),
              from = Some(T0.minusSeconds(1)),
              to = Some(T0.plusSeconds(1)),
              pageSize = 100,
            ),
          )
        } yield assertTrue(
          result.exists(_.occurredAt == t2),
          result.forall(e => !e.occurredAt.isBefore(T0.minusSeconds(1)) && !e.occurredAt.isAfter(T0.plusSeconds(1))),
        )
      },
      test("actorId is persisted and returned on search") {
        for {
          userRepo <- ZIO.service[jorlan.db.repository.UserZIORepository]
          svc      <- ZIO.service[EventLogService]
          user     <- userRepo.upsert(jorlan.domain.User(jorlan.domain.UserId.empty, "EventActor", T0, T0))
          _        <- svc.log(testEvent(EventType.AgentStarted, actorId = Some(user.id)))
          found    <- svc.query(EventLogFilter(eventType = Some(EventType.AgentStarted)))
        } yield assertTrue(found.exists(_.actorId.contains(user.id)))
      },
      test("replay returns only events for the given session in chronological order") {
        val sid = AgentSessionId(42001L)
        for {
          svc <- ZIO.service[EventLogService]
          e1  <- svc.log(testEvent(EventType.AgentStarted, sessionId = Some(sid), occurredAt = T0))
          e2  <- svc.log(testEvent(EventType.SkillInvoked, sessionId = Some(sid), occurredAt = T0.plusSeconds(1)))
          e3  <- svc.log(testEvent(EventType.AgentCompleted, sessionId = Some(sid), occurredAt = T0.plusSeconds(2)))
          _   <- svc.log(testEvent(EventType.AgentStarted, sessionId = Some(AgentSessionId(99999L))))
          replayed <- svc.replay(sid)
        } yield assertTrue(
          replayed.map(_.id) == List(e1.id, e2.id, e3.id),
          replayed.map(_.occurredAt) == List(T0, T0.plusSeconds(1), T0.plusSeconds(2)),
        )
      },
      test("correlation id is accessible within withId block around service calls") {
        for {
          svc <- ZIO.service[EventLogService]
          corrId = "integ-corr-001"
          cid <- CorrelationId.withId(corrId) {
            svc.log(testEvent(EventType.SystemAlert)) *> CorrelationId.get
          }
        } yield assertTrue(cid.contains(corrId))
      },
    ).provideLayerShared(serviceLayer ++ JorlanContainer.repositoryLayer)

}
