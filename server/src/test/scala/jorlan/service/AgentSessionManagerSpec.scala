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
import jorlan.db.repository.AgentZIORepository
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

object AgentSessionManagerSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)

  // Seeds the "Jorlan Interactive" default agent so createSession can find it
  // (mirrors what V016 Flyway migration does against a real DB).
  private val seededAgentRepoLayer: ULayer[AgentZIORepository] =
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
      } yield repo: AgentZIORepository
    }

  private val freshLayers: ULayer[AgentSessionManager & SessionHub & EventLogService] = {
    val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
    val eventLogLayer = eventLogRepo >>> EventLogServiceImpl.live
    val hubLayer = SessionHub.live
    val fakeGateway = FakeModelGateway.layer(Nil)
    (seededAgentRepoLayer ++ hubLayer ++ fakeGateway ++ eventLogLayer) >>> AgentSessionManagerImpl.live ++ hubLayer ++ eventLogLayer
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
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
      }.provide(freshLayers),
      test("createSession logs SessionCreated event") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          log     <- ZIO.service[EventLogService]
          session <- mgr.createSession(userId, None)
          events  <- log.query(EventLogFilter(eventType = Some(EventType.SessionCreated)))
        } yield assertTrue(
          events.nonEmpty,
          events.head.sessionId.contains(session.id),
          events.head.actorId.contains(userId),
        )
      }.provide(freshLayers),
      test("getSession returns the created session") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          created <- mgr.createSession(userId, None)
          found   <- mgr.getSession(created.id)
        } yield assertTrue(found.contains(created))
      }.provide(freshLayers),
      test("getSession returns None for unknown id") {
        for {
          mgr    <- ZIO.service[AgentSessionManager]
          result <- mgr.getSession(AgentSessionId(999L))
        } yield assertTrue(result.isEmpty)
      }.provide(freshLayers),
      test("suspendSession sets status to Paused") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          created <- mgr.createSession(userId, None)
          paused  <- mgr.suspendSession(created.id)
        } yield assertTrue(paused.status == SessionStatus.Paused)
      }.provide(freshLayers),
      test("terminateSession sets status to Completed") {
        for {
          mgr       <- ZIO.service[AgentSessionManager]
          created   <- mgr.createSession(userId, None)
          completed <- mgr.terminateSession(created.id)
        } yield assertTrue(completed.status == SessionStatus.Completed)
      }.provide(freshLayers),
      test("createSession reuses existing Jorlan Interactive agent across calls") {
        for {
          mgr <- ZIO.service[AgentSessionManager]
          s1  <- mgr.createSession(userId, None)
          s2  <- mgr.createSession(userId, None)
        } yield assertTrue(s1.agentId == s2.agentId)
      }.provide(freshLayers),
      test("suspendSession with unknown session ID returns JorlanError") {
        for {
          mgr    <- ZIO.service[AgentSessionManager]
          result <- mgr.suspendSession(AgentSessionId(999L)).flip
        } yield assertTrue(result.isInstanceOf[JorlanError])
      }.provide(freshLayers),
      test("terminateSession removes session hub entry (fresh hub after termination)") {
        for {
          mgr     <- ZIO.service[AgentSessionManager]
          hub     <- ZIO.service[SessionHub]
          created <- mgr.createSession(userId, None)
          h1      <- hub.getOrCreate(created.id)
          _       <- mgr.terminateSession(created.id)
          h2      <- hub.getOrCreate(created.id)
          // After remove the Ref is cleared, so a fresh Hub is allocated on next getOrCreate
        } yield assertTrue(!(h1 eq h2))
      }.provide(freshLayers),
      suite("companion accessors")(
        test("createSession companion accessor") {
          AgentSessionManager.createSession(userId, None).as(assertCompletes)
        }.provide(freshLayers),
        test("getSession companion accessor") {
          AgentSessionManager.getSession(AgentSessionId(999L)).as(assertCompletes)
        }.provide(freshLayers),
        test("suspendSession companion accessor") {
          for {
            created <- AgentSessionManager.createSession(userId, None)
            _       <- AgentSessionManager.suspendSession(created.id)
          } yield assertCompletes
        }.provide(freshLayers),
        test("terminateSession companion accessor") {
          for {
            created <- AgentSessionManager.createSession(userId, None)
            _       <- AgentSessionManager.terminateSession(created.id)
          } yield assertCompletes
        }.provide(freshLayers),
        test("listSessions companion accessor covers impl and companion") {
          for {
            created <- AgentSessionManager.createSession(userId, None)
            results <- AgentSessionManager.listSessions(userId, 0, 10)
          } yield assertTrue(results.exists(_.id == created.id))
        }.provide(freshLayers),
      ),
    )

}
