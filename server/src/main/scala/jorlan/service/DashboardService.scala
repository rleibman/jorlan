/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.ZIORepositories
import zio.*
import zio.json.ast.Json

import java.time.{Instant, ZoneOffset}

case class TimeSeriesPoint(
  timestampMs: Long,
  count:       Int,
)
case class NamedCount(
  name:  String,
  count: Int,
)

case class DashboardStats(
  activeSessionCount:     Int,
  eventCountToday:        Int,
  skillInvocationCount:   Int,
  schedulerSuccessRate:   Double,
  eventVolumeSeries:      List[TimeSeriesPoint],
  skillInvocationsByName: List[NamedCount],
  sessionStatusCounts:    List[NamedCount],
  jobOutcomeCounts:       List[NamedCount],
)

trait DashboardService {

  def globalStats: IO[JorlanError, DashboardStats]

}

object DashboardService {

  val live: URLayer[ZIORepositories, DashboardService] =
    ZLayer.fromFunction(DashboardServiceLive(_))

}

private class DashboardServiceLive(repos: ZIORepositories) extends DashboardService {

  override def globalStats: IO[JorlanError, DashboardStats] = {
    for {
      now <- Clock.instant
      startOfToday = now.atZone(ZoneOffset.UTC).toLocalDate.atStartOfDay(ZoneOffset.UTC).toInstant
      events   <- repos.eventLog.search(EventLogFilter(from = Some(startOfToday), pageSize = EventLogFilter.MaxLimit))
      sessions <- repos.agent.searchSessions(AgentSessionSearch(pageSize = 1000)).mapError(JorlanError(_))
      jobs     <- repos.scheduler.listJobs(None, 1000)
    } yield buildStats(now, startOfToday, events, sessions, jobs)
  }

  private def buildStats(
    now:          Instant,
    startOfToday: Instant,
    events:       List[EventLog[Json]],
    sessions:     List[AgentSession],
    jobs:         List[SchedulerJob],
  ): DashboardStats = {
    val activeSessionCount = sessions.count(s =>
      s.status == SessionStatus.Active || s.status == SessionStatus.Created || s.status == SessionStatus.Blocked,
    )

    val sessionStatusCounts = sessions
      .groupBy(_.status.toString)
      .map(
        (
          k,
          v,
        ) => NamedCount(k, v.size),
      )
      .toList
      .sortBy(_.name)

    val skillEvents = events.filter(e =>
      e.eventType == EventType.SkillInvoked || e.eventType == EventType.SkillSucceeded ||
        e.eventType == EventType.SkillFailed,
    )

    val skillInvocations = events.filter(_.eventType == EventType.SkillInvoked)

    val skillInvocationsByName = skillInvocations
      .flatMap { e =>
        e.payloadJson.flatMap {
          case zio.json.ast.Json.Obj(fields) =>
            fields.collectFirst { case ("toolName", zio.json.ast.Json.Str(name)) => name }
          case _ => None
        }
      }
      .groupBy(identity)
      .map(
        (
          k,
          v,
        ) => NamedCount(k, v.size),
      )
      .toList
      .sortBy(-_.count)
      .take(20)

    val eventVolumeSeries = buildHourlySeries(events, startOfToday, now)

    val succeededJobs = jobs.count(_.status == JobStatus.Succeeded)
    val failedJobs = jobs.count(_.status == JobStatus.Failed)
    val totalFinished = succeededJobs + failedJobs
    val successRate = if (totalFinished == 0) 1.0 else succeededJobs.toDouble / totalFinished.toDouble

    val jobOutcomeCounts = List(
      NamedCount("Succeeded", succeededJobs),
      NamedCount("Failed", failedJobs),
      NamedCount("Running", jobs.count(_.status == JobStatus.Running)),
      NamedCount("Pending", jobs.count(_.status == JobStatus.Pending)),
    ).filter(_.count > 0)

    DashboardStats(
      activeSessionCount = activeSessionCount,
      eventCountToday = events.size,
      skillInvocationCount = skillInvocations.size,
      schedulerSuccessRate = successRate,
      eventVolumeSeries = eventVolumeSeries,
      skillInvocationsByName = skillInvocationsByName,
      sessionStatusCounts = sessionStatusCounts,
      jobOutcomeCounts = jobOutcomeCounts,
    )
  }

  private def buildHourlySeries(
    events:       List[EventLog[Json]],
    startOfToday: Instant,
    now:          Instant,
  ): List[TimeSeriesPoint] = {
    val hourBuckets = events
      .groupBy { e =>
        val zdt = e.occurredAt.atZone(ZoneOffset.UTC)
        val epoch = zdt.toLocalDate.atTime(zdt.getHour, 0).toInstant(ZoneOffset.UTC)
        epoch.toEpochMilli
      }
      .map(
        (
          k,
          v,
        ) => TimeSeriesPoint(k, v.size),
      )
      .toList
      .sortBy(_.timestampMs)
    hourBuckets
  }

}
