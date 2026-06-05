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

import jorlan.*
import jorlan.db.TestFixtures.{*, given}
import jorlan.db.repository.EventLogZIORepository
import jorlan.domain.*
import jorlan.service.{CorrelationId, EventLogFilter}
import zio.*
import zio.test.*

object EventLogServiceIntegrationSpec extends ZIOSpecDefault {

  private val appLayer = JorlanContainer.repositoryLayer

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EventLog integration")(
      test("append and search roundtrip against real DB") {
        for {
          repo  <- ZIO.service[EventLogZIORepository]
          saved <- repo.append(testEvent(EventType.AgentStarted))
          found <- repo.search(EventLogFilter(eventType = Some(EventType.AgentStarted), pageSize = 10))
        } yield assertTrue(
          saved.id.value > 0L,
          found.exists(_.id == saved.id),
        )
      },
      test("search filters by time range") {
        val t1 = T0.minusSeconds(5)
        val t2 = T0
        val t3 = T0.plusSeconds(5)
        for {
          repo   <- ZIO.service[EventLogZIORepository]
          _      <- repo.append(testEvent(EventType.SystemAlert, occurredAt = t1))
          _      <- repo.append(testEvent(EventType.SystemAlert, occurredAt = t2))
          _      <- repo.append(testEvent(EventType.SystemAlert, occurredAt = t3))
          result <- repo.search(
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
          repo     <- ZIO.service[EventLogZIORepository]
          user     <- userRepo.upsert(jorlan.domain.User(jorlan.domain.UserId.empty, "EventActor", "", T0, T0))
          _        <- repo.append(testEvent(EventType.AgentStarted, actorId = Some(user.id)))
          found    <- repo.search(EventLogFilter(eventType = Some(EventType.AgentStarted)))
        } yield assertTrue(found.exists(_.actorId.contains(user.id)))
      },
      test("replaySession returns only events for the given session in chronological order") {
        val sid = AgentSessionId(42001L)
        for {
          repo <- ZIO.service[EventLogZIORepository]
          e1   <- repo.append(testEvent(EventType.AgentStarted, sessionId = Some(sid), occurredAt = T0))
          e2   <- repo.append(testEvent(EventType.SkillInvoked, sessionId = Some(sid), occurredAt = T0.plusSeconds(1)))
          e3 <- repo.append(testEvent(EventType.AgentCompleted, sessionId = Some(sid), occurredAt = T0.plusSeconds(2)))
          _  <- repo.append(testEvent(EventType.AgentStarted, sessionId = Some(AgentSessionId(99999L))))
          replayed <- repo.replaySession(sid, 100)
        } yield assertTrue(
          replayed.map(_.id) == List(e1.id, e2.id, e3.id),
          replayed.map(_.occurredAt) == List(T0, T0.plusSeconds(1), T0.plusSeconds(2)),
        )
      },
      test("correlation id is accessible within withId block around repository calls") {
        for {
          repo <- ZIO.service[EventLogZIORepository]
          corrId = "integ-corr-001"
          cid <- CorrelationId.withId(corrId) {
            repo.append(testEvent(EventType.SystemAlert)) *> CorrelationId.get
          }
        } yield assertTrue(cid.contains(corrId))
      },
    ).provideLayerShared(appLayer)

}
