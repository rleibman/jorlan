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

import jorlan.domain.*
import jorlan.service.{CorrelationId, EventLogFilter, EventLogService, EventLogServiceImpl}
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object EventLogServiceIntegrationSpec extends ZIOSpecDefault {

  private val now = Instant.now()

  private val serviceLayer =
    JorlanContainer.repositoryLayer >>> EventLogServiceImpl.live

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EventLogService integration")(
      test("log and query roundtrip against real DB") {
        for {
          svc <- ZIO.service[EventLogService]
          event = EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, None, None, None, now)
          saved <- svc.log(event)
          found <- svc.query(EventLogFilter(eventType = Some(EventType.AgentStarted), limit = 10))
        } yield assertTrue(
          saved.id.value > 0L,
          found.exists(_.id == saved.id),
        )
      },
      test("replay returns only events for the given session in order") {
        for {
          svc <- ZIO.service[EventLogService]
          sid = AgentSessionId(42000L)
          e1 <- svc.log(
            EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, Some(sid), None, None, now),
          )
          e2 <- svc.log(
            EventLog[SkillId](
              EventLogId.empty,
              EventType.SkillInvoked,
              None,
              None,
              Some(sid),
              None,
              None,
              now.plusSeconds(1),
            ),
          )
          e3 <- svc.log(
            EventLog[AgentId](
              EventLogId.empty,
              EventType.AgentCompleted,
              None,
              None,
              Some(sid),
              None,
              None,
              now.plusSeconds(2),
            ),
          )
          _ <- svc.log(
            EventLog[AgentId](
              EventLogId.empty,
              EventType.AgentStarted,
              None,
              None,
              Some(AgentSessionId(99999L)),
              None,
              None,
              now,
            ),
          )
          replayed <- svc.replay(sid)
        } yield assertTrue(
          replayed.map(_.id) == List(e1.id, e2.id, e3.id),
          replayed.map(_.eventType) == List(EventType.AgentStarted, EventType.SkillInvoked, EventType.AgentCompleted),
        )
      },
      test("correlation id propagates through log call") {
        for {
          svc <- ZIO.service[EventLogService]
          corrId = "integ-corr-001"
          inside <- CorrelationId.withId(corrId) {
            for {
              _ <- svc.log(
                EventLog[AgentId](EventLogId.empty, EventType.SystemAlert, None, None, None, None, None, now),
              )
              cid <- CorrelationId.get
            } yield cid
          }
        } yield assertTrue(inside.contains(corrId))
      },
    ).provideLayerShared(serviceLayer)

}
