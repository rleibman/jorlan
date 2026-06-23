/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.ZIORepositories
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

import jorlan.service.TestFixtures.given

object DashboardServiceSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.now()

  private val agentId = AgentId(1L)
  private val userId = UserId(1L)

  private def makeJob(
    name:   String,
    status: JobStatus,
  ): SchedulerJob =
    SchedulerJob(
      id = SchedulerJobId.empty,
      agentId = agentId,
      userId = userId,
      skillId = None,
      name = name,
      prompt = "test",
      inputJson = None,
      status = status,
      scheduledAt = T0.minusSeconds(60),
      startedAt = None,
      finishedAt = None,
      resultJson = None,
      maxRetries = 0,
      retryCount = 0,
      backoffSeconds = 60,
      backoffPolicy = RetryBackoffPolicy.Fixed,
      missedRunPolicy = MissedRunPolicy.Skip,
      leasedAt = None,
      leasedBy = None,
      createdAt = T0,
    )

  private def makeSession(status: SessionStatus): AgentSession =
    AgentSession(
      id = AgentSessionId.empty,
      agentId = agentId,
      userId = userId,
      workspaceId = None,
      status = status,
      modelId = None,
      createdAt = T0,
      updatedAt = T0,
    )

  private def makeEvent(
    eventType:   EventType,
    payloadJson: Option[Json] = None,
    occurredAt:  Instant = T0,
  ): EventLog[Unit] =
    EventLog(
      id = EventLogId.empty,
      eventType = eventType,
      actorId = Some(userId),
      agentId = Some(agentId),
      sessionId = None,
      resource = None,
      payloadJson = payloadJson,
      occurredAt = occurredAt,
    )

  private val reposLayer: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val serviceLayer: ULayer[DashboardService] =
    reposLayer >>> DashboardService.live

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DashboardServiceSpec")(
      test("globalStats returns zeros for empty repositories") {
        for {
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield assertTrue(
          stats.activeSessionCount == 0,
          stats.eventCountToday == 0,
          stats.skillInvocationCount == 0,
          stats.schedulerSuccessRate == 1.0,
          stats.eventVolumeSeries.isEmpty,
          stats.skillInvocationsByName.isEmpty,
          stats.sessionStatusCounts.isEmpty,
          stats.jobOutcomeCounts.isEmpty,
        )
      }.provide(serviceLayer),
      test("globalStats counts active sessions correctly") {
        for {
          repos <- ZIO.service[ZIORepositories]
          _     <- repos.agent.upsertSession(makeSession(SessionStatus.Active))
          _     <- repos.agent.upsertSession(makeSession(SessionStatus.Created))
          _     <- repos.agent.upsertSession(makeSession(SessionStatus.Blocked))
          _     <- repos.agent.upsertSession(makeSession(SessionStatus.Completed))
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield assertTrue(
          stats.activeSessionCount == 3,
          stats.sessionStatusCounts.map(_.name).contains(SessionStatus.Active.toString),
        )
      }.provide(reposLayer, DashboardService.live),
      test("globalStats counts skill invocations from event log") {
        for {
          repos <- ZIO.service[ZIORepositories]
          _     <- repos.eventLog.append(
            makeEvent(
              EventType.SkillInvoked,
              Some(Json.Obj("toolName" -> Json.Str("units.convert"))),
            ),
          )
          _ <- repos.eventLog.append(
            makeEvent(
              EventType.SkillInvoked,
              Some(Json.Obj("toolName" -> Json.Str("units.convert"))),
            ),
          )
          _ <- repos.eventLog.append(
            makeEvent(
              EventType.SkillInvoked,
              Some(Json.Obj("toolName" -> Json.Str("calendar.listEvents"))),
            ),
          )
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield {
          val byName = stats.skillInvocationsByName.map(nc => nc.name -> nc.count).toMap
          assertTrue(
            stats.skillInvocationCount == 3,
            byName.getOrElse("units.convert", 0) == 2,
            byName.getOrElse("calendar.listEvents", 0) == 1,
          )
        }
      }.provide(reposLayer, DashboardService.live),
      test("globalStats computes schedulerSuccessRate correctly") {
        for {
          repos <- ZIO.service[ZIORepositories]
          _     <- repos.scheduler.upsertJob(makeJob("j1", JobStatus.Succeeded))
          _     <- repos.scheduler.upsertJob(makeJob("j2", JobStatus.Succeeded))
          _     <- repos.scheduler.upsertJob(makeJob("j3", JobStatus.Failed))
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield assertTrue(
          math.abs(stats.schedulerSuccessRate - 2.0 / 3.0) < 0.001,
        )
      }.provide(reposLayer, DashboardService.live),
      test("globalStats has 1.0 success rate when no finished jobs") {
        for {
          repos <- ZIO.service[ZIORepositories]
          _     <- repos.scheduler.upsertJob(makeJob("running", JobStatus.Running))
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield assertTrue(stats.schedulerSuccessRate == 1.0)
      }.provide(reposLayer, DashboardService.live),
      test("globalStats jobOutcomeCounts excludes zero-count statuses") {
        for {
          repos <- ZIO.service[ZIORepositories]
          _     <- repos.scheduler.upsertJob(makeJob("s1", JobStatus.Succeeded))
          _     <- repos.scheduler.upsertJob(makeJob("f1", JobStatus.Failed))
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield {
          val names = stats.jobOutcomeCounts.map(_.name)
          assertTrue(
            names.contains("Succeeded"),
            names.contains("Failed"),
            !names.contains("Running"),
            !names.contains("Pending"),
          )
        }
      }.provide(reposLayer, DashboardService.live),
      test("globalStats builds eventVolumeSeries bucketed by hour") {
        for {
          repos <- ZIO.service[ZIORepositories]
          t1 = Instant.parse("2026-06-20T10:00:00Z")
          t2 = Instant.parse("2026-06-20T10:30:00Z")
          t3 = Instant.parse("2026-06-20T11:15:00Z")
          _     <- repos.eventLog.append(makeEvent(EventType.UserMessageReceived, occurredAt = t1))
          _     <- repos.eventLog.append(makeEvent(EventType.UserMessageReceived, occurredAt = t2))
          _     <- repos.eventLog.append(makeEvent(EventType.UserMessageReceived, occurredAt = t3))
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield {
          // 2 events at 10:xx → one bucket, 1 event at 11:xx → another bucket
          val series = stats.eventVolumeSeries
          assertTrue(
            series.exists(_.count == 2),
            series.exists(_.count == 1),
          )
        }
      }.provide(reposLayer, DashboardService.live),
      test("globalStats skillInvocations ignores events without toolName in payload") {
        for {
          repos <- ZIO.service[ZIORepositories]
          _     <- repos.eventLog.append(
            makeEvent(EventType.SkillInvoked, Some(Json.Obj("other" -> Json.Str("field")))),
          )
          _     <- repos.eventLog.append(makeEvent(EventType.SkillInvoked, payloadJson = None))
          svc   <- ZIO.service[DashboardService]
          stats <- svc.globalStats
        } yield assertTrue(stats.skillInvocationCount == 2 && stats.skillInvocationsByName.isEmpty)
      }.provide(reposLayer, DashboardService.live),
    )

}
