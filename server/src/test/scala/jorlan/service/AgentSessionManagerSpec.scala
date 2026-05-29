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
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*
import zio.test.Assertion.*

object AgentSessionManagerSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)

  private val freshLayers: ULayer[AgentSessionManager & EventLogService] = {
    val agentRepoLayer = InMemoryRepositories.InMemoryAgentRepo.layer
    val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
    val eventLogLayer = eventLogRepo >>> EventLogServiceImpl.live
    val hubLayer = SessionHub.live
    (agentRepoLayer ++ hubLayer ++ eventLogLayer) >>> AgentSessionManagerImpl.live ++ eventLogLayer
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
    )

}
