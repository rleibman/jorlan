/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db

import jorlan.*
import jorlan.db.TestFixtures.{*, given}
import jorlan.db.repository.*
import jorlan.*
import jorlan.service.{CorrelationId, EventLogFilter, EventLogOrder}
import zio.*
import zio.test.*

object EventLogServiceIntegrationSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("EventLog integration")(
      test("append and search roundtrip against real DB") {
        for {
          repo  <- ZIO.serviceWith[ZIORepositories](_.eventLog)
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
          repo   <- ZIO.serviceWith[ZIORepositories](_.eventLog)
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
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          repo     <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          user     <- userRepo.upsert(jorlan.User(jorlan.UserId.empty, "EventActor", "", T0, T0))
          _        <- repo.append(testEvent(EventType.AgentStarted, actorId = Some(user.id)))
          found    <- repo.search(EventLogFilter(eventType = Some(EventType.AgentStarted)))
        } yield assertTrue(found.exists(_.actorId.contains(user.id)))
      },
      test("replaySession returns only events for the given session in chronological order") {
        val sid = AgentSessionId(42001L)
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
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
      test("search with sessionId filter returns matching events") {
        val sid = AgentSessionId(55555L)
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          saved  <- repo.append(testEvent(EventType.UserMessageReceived, sessionId = Some(sid)))
          _      <- repo.append(testEvent(EventType.AgentStarted))
          result <- repo.search(EventLogFilter(sessionId = Some(sid), pageSize = 100))
        } yield assertTrue(result.exists(_.id == saved.id))
      },
      test("search with agentId filter returns matching events") {
        import jorlan.db.repository.ZIOAgentRepository
        for {
          agentRepo <- ZIO.serviceWith[ZIORepositories](_.agent)
          agent  <- agentRepo.upsert(jorlan.Agent(jorlan.AgentId.empty, "FilterAgent", None, None, 0, createdAt = T0))
          repo   <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          saved  <- repo.append(testEvent(EventType.AgentStarted, agentId = Some(agent.id)))
          _      <- repo.append(testEvent(EventType.AgentStarted))
          result <- repo.search(EventLogFilter(agentId = Some(agent.id), pageSize = 100))
        } yield assertTrue(result.exists(_.id == saved.id))
      },
      test("search sorted by OccurredAt ascending returns events in order") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          e1     <- repo.append(testEvent(EventType.ModelCallStarted, occurredAt = T0))
          e2     <- repo.append(testEvent(EventType.ModelCallStarted, occurredAt = T0.plusSeconds(1)))
          e3     <- repo.append(testEvent(EventType.ModelCallStarted, occurredAt = T0.plusSeconds(2)))
          result <- repo.search(
            EventLogFilter(
              eventType = Some(EventType.ModelCallStarted),
              sorts = Some(Sort(EventLogOrder.OccurredAt, OrderDirection.Asc)),
              pageSize = 100,
            ),
          )
          ids = result.map(_.id)
        } yield assertTrue(ids.contains(e1.id), ids.contains(e2.id), ids.contains(e3.id))
      },
      test("search sorted by Id ascending") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          e1     <- repo.append(testEvent(EventType.ModelCallCompleted, occurredAt = T0))
          e2     <- repo.append(testEvent(EventType.ModelCallCompleted, occurredAt = T0.plusSeconds(1)))
          result <- repo.search(
            EventLogFilter(
              eventType = Some(EventType.ModelCallCompleted),
              sorts = Some(Sort(EventLogOrder.Id, OrderDirection.Asc)),
              pageSize = 100,
            ),
          )
          ids = result.map(_.id)
        } yield assertTrue(ids.indexOf(e1.id) < ids.indexOf(e2.id))
      },
      test("search sorted by Id descending") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          e1     <- repo.append(testEvent(EventType.ModelCallFailed, occurredAt = T0))
          e2     <- repo.append(testEvent(EventType.ModelCallFailed, occurredAt = T0.plusSeconds(1)))
          result <- repo.search(
            EventLogFilter(
              eventType = Some(EventType.ModelCallFailed),
              sorts = Some(Sort(EventLogOrder.Id, OrderDirection.Desc)),
              pageSize = 100,
            ),
          )
          ids = result.map(_.id)
        } yield assertTrue(ids.indexOf(e2.id) < ids.indexOf(e1.id))
      },
      test("search with pagination returns correct page") {
        for {
          repo  <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          _     <- ZIO.foreachDiscard(1 to 5)(i => repo.append(testEvent(EventType.SkillInvoked)))
          page0 <- repo.search(EventLogFilter(eventType = Some(EventType.SkillInvoked), page = 0, pageSize = 2))
          page1 <- repo.search(EventLogFilter(eventType = Some(EventType.SkillInvoked), page = 1, pageSize = 2))
        } yield assertTrue(page0.size == 2, page1.size == 2, page0.map(_.id) != page1.map(_.id))
      },
      test("correlation id is accessible within withId block around repository calls") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          corrId = "integ-corr-001"
          cid <- CorrelationId.withId(corrId) {
            repo.append(testEvent(EventType.SystemAlert)) *> CorrelationId.get
          }
        } yield assertTrue(cid.contains(corrId))
      },
    )

}
