/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.{ZIOAgentRepository, ZIOEventLogRepository, ZIORepositories}
import jorlan.*
import jorlan.service.llm.FakeModelGateway
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

object AgentSessionManagerSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)

  // Seeds the "Jorlan Interactive" default agent so createSession can find it
  // (mirrors what V016 Flyway migration does against a real DB).
  private val seededAgentRepoLayer: ULayer[ZIOAgentRepository] =
    ZLayer.fromZIO {
      for {
        now  <- Clock.instant
        repo <- InMemoryRepositories.InMemoryAgentRepo.make
        _    <- repo
          .upsert(
            Agent(
              id = AgentId.empty,
              name = "Jorlan Interactive",
              description = Some("Default interactive agent for shell sessions"),
              defaultModel = None,
              createdAt = now,
            ),
          )
          .orDie
      } yield repo: ZIOAgentRepository
    }

  private val sharedLayer: ULayer[AgentSessionManager & SessionHub & ZIORepositories] =
    ZLayer.make[AgentSessionManager & SessionHub & ZIORepositories](
      InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(agentRepoOpt =
        Some(seededAgentRepoLayer),
      ),
      SessionHub.live,
      EventLogHub.live,
      FakeModelGateway.layer(List.empty),
      AgentSessionManagerImpl.live,
    )

  override def spec =
    suite("AgentSessionManager")(
      test("createSession returns a session with Active status") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          session <- mgr.createSession(userId, Some(ModelId("test-model")))
        } yield assertTrue(
          session.id.value > 0L,
          session.userId == userId,
          session.status == SessionStatus.Active,
          session.modelId.contains(ModelId("test-model")),
        )
      },
      test("createSession logs SessionCreated event") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          session <- mgr.createSession(userId, None)
          events  <- ZIO.serviceWithZIO[ZIORepositories](
            _.eventLog.search(EventLogFilter(eventType = Some(EventType.SessionCreated))),
          )
        } yield assertTrue(
          events.nonEmpty,
          events.exists(_.sessionId.contains(session.id)),
          events.exists(_.actorId.contains(userId)),
        )
      },
      test("getSession returns the created session") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          created <- mgr.createSession(userId, None)
          found   <- mgr.getSession(created.id)
        } yield assertTrue(found.contains(created))
      },
      test("getSession returns None for unknown id") {
        for {
          mgr    <- ZIO.service[AgentSessionManager]
          result <- mgr.getSession(AgentSessionId(999L))
        } yield assertTrue(result.isEmpty)
      },
      test("suspendSession sets status to Paused") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          created <- mgr.createSession(userId, None)
          paused  <- mgr.suspendSession(created.id)
        } yield assertTrue(paused.status == SessionStatus.Paused)
      },
      test("terminateSession sets status to Completed") {
        for {
          mgr       <- ZIO.service[AgentSessionManager]
          created   <- mgr.createSession(userId, None)
          completed <- mgr.terminateSession(created.id)
        } yield assertTrue(completed.status == SessionStatus.Completed)
      },
      test("createSession reuses existing Jorlan Interactive agent across calls") {
        for {
          mgr <- ZIO.service[AgentSessionManager]
          s1  <- mgr.createSession(userId, None)
          s2  <- mgr.createSession(userId, None)
        } yield assertTrue(s1.agentId == s2.agentId)
      },
      test("suspendSession with unknown session ID returns JorlanError") {
        for {
          mgr    <- ZIO.service[AgentSessionManager]
          result <- mgr.suspendSession(AgentSessionId(999L)).flip
        } yield assertTrue(result.isInstanceOf[JorlanError])
      },
      test("terminateSession transitions session status to Completed") {
        for {
          mgr        <- ZIO.service[AgentSessionManager]
          created    <- mgr.createSession(userId, None)
          terminated <- mgr.terminateSession(created.id)
        } yield assertTrue(terminated.status == SessionStatus.Completed)
      },
      suite("service methods")(
        test("createSession works") {
          ZIO.serviceWithZIO[AgentSessionManager](_.createSession(userId, None)).as(assertCompletes)
        },
        test("getSession works") {
          ZIO.serviceWithZIO[AgentSessionManager](_.getSession(AgentSessionId(999L))).as(assertCompletes)
        },
        test("suspendSession round-trip") {
          for {
            created <- ZIO.serviceWithZIO[AgentSessionManager](_.createSession(userId, None))
            _       <- ZIO.serviceWithZIO[AgentSessionManager](_.suspendSession(created.id))
          } yield assertCompletes
        },
        test("terminateSession round-trip") {
          for {
            created <- ZIO.serviceWithZIO[AgentSessionManager](_.createSession(userId, None))
            _       <- ZIO.serviceWithZIO[AgentSessionManager](_.terminateSession(created.id))
          } yield assertCompletes
        },
        test("listSessions covers impl") {
          for {
            created <- ZIO.serviceWithZIO[AgentSessionManager](_.createSession(userId, None))
            results <- ZIO.serviceWithZIO[AgentSessionManager](_.listSessions(userId, 0, 10))
          } yield assertTrue(results.exists(_.id == created.id))
        },
      ),
    ).provide(sharedLayer)

}
