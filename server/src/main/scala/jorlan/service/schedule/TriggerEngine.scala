/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.schedule

import cron4s.Cron
import cron4s.expr.CronExpr
import cron4s.lib.javatime.*
import cron4s.syntax.all.*
import jorlan.*
import jorlan.SchedulerJob.*
import jorlan.db.repository.*
import jorlan.service.{AgentRunner, AgentSessionManager}
import zio.*

/** Daemon that drives the durable scheduler; polls for pending jobs, claims them, executes them via [[AgentRunner]],
  * and advances trigger schedules.
  */
trait TriggerEngine {

  def start: IO[JorlanError, Unit]

}

import java.net.InetAddress
import java.time.{Duration, Instant, ZoneOffset, ZonedDateTime}

/** Daemon fiber that drives the durable scheduler: polls for pending jobs, claims them with DB-level locking, executes
  * them via [[AgentRunner]], and advances trigger schedules.
  *
  * Lease TTL controls the recovery window for crashed workers: a job whose lease is older than `leaseTtl` seconds is
  * released back to `Pending` on the next tick. Poll interval is configurable (default 10 seconds).
  *
  * @param repo
  *   Scheduler repository for job/trigger persistence.
  * @param eventLog
  *   Event log repository for audit writes.
  * @param sessionManager
  *   Used to create and terminate agent sessions for each job execution.
  * @param agentRunner
  *   Used to submit messages and subscribe to the response stream.
  * @param pollInterval
  *   How often the engine polls for pending jobs. Unit: [[java.time.Duration]].
  * @param leaseTtl
  *   Lease timeout in seconds. Jobs whose lease is older than this are released on the next tick.
  * @param jobTimeout
  *   Maximum wall-clock time allowed for a single job execution before it is treated as failed.
  *
  * Note: graceful-shutdown support (releasing active leases on SIGTERM) is deferred to a future phase. On process exit,
  * claimed jobs remain `Running` until the next startup's `expireLeases` call reclaims them.
  */
class TriggerEngineImpl(
  repo:           ZIORepositories,
  sessionManager: AgentSessionManager,
  agentRunner:    AgentRunner,
  pollInterval:   Duration = Duration.ofSeconds(10),
  leaseTtl:       Int = 300,
  jobTimeout:     Duration = Duration.ofSeconds(300),
) extends TriggerEngine {

  private val workerIdIO: IO[JorlanError, String] =
    ZIO
      .attemptBlocking {
        val host = InetAddress.getLocalHost.nn.getHostName.nn
        s"$host:${ProcessHandle.current().pid()}"
      }
      .orElse(ZIO.succeed(s"unknown:${ProcessHandle.current().pid()}"))

  private def logJobEvent(
    eventType: EventType,
    job:       SchedulerJob,
  ): IO[JorlanError, Unit] =
    Clock.instant.flatMap { now =>
      repo.eventLog
        .append(
          EventLog.entry(
            eventType = eventType,
            actorId = Some(job.userId),
            agentId = Some(job.agentId),
            sessionId = None,
            resource = Some(s"schedulerJob:${job.id.value}"),
            now = now,
          ),
        )
        .unit.mapError(JorlanError.apply)

    }

  private def advanceCronTrigger(
    job:      SchedulerJob,
    trigger:  SchedulerTrigger,
    cronExpr: CronExpr,
    now:      Instant,
  ): IO[JorlanError, Unit] = {
    val zdtNow = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
    cronExpr.next(zdtNow) match {
      case Some(nextZdt) =>
        repo.scheduler.upsertJob(job.released(JobStatus.Pending, nextZdt.toInstant)).mapError(JorlanError(_)).unit
      case None =>
        ZIO.logWarning(
          s"[TriggerEngine] Cron trigger ${trigger.id.value} has no future occurrence — job will not re-queue",
        )
    }
  }

  private def advanceIntervalTrigger(
    job:     SchedulerJob,
    trigger: SchedulerTrigger,
    now:     Instant,
  ): IO[JorlanError, Unit] =
    ZIO
      .attempt(Duration.parse(trigger.expression))
      .mapError(e => JorlanError(s"Invalid interval '${trigger.expression}': ${e.getMessage}"))
      .flatMap { duration =>
        repo.scheduler.upsertJob(job.released(JobStatus.Pending, now.plus(duration))).mapError(JorlanError(_)).unit
      }

  /** After a successful run, advance recurring triggers to their next fire time. */
  private def advanceTriggers(
    job:       SchedulerJob,
    cronCache: Ref[Map[SchedulerTriggerId, CronExpr]],
    now:       Instant,
  ): IO[JorlanError, Unit] =
    for {
      triggers <- repo.scheduler.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 100)).mapError(JorlanError(_))
      _        <- ZIO.foreachDiscard(triggers.filter(_.enabled)) { trigger =>
        trigger.triggerType match {
          case TriggerType.OneShot =>
            repo.scheduler.upsertTrigger(trigger.copy(enabled = false)).mapError(JorlanError(_)).unit
          case TriggerType.Cron =>
            for {
              cronExpr <- cronCache.get.flatMap { cache =>
                cache.get(trigger.id) match {
                  case Some(expr) => ZIO.succeed(expr)
                  case None       =>
                    ZIO
                      .fromEither(Cron.parse(trigger.expression))
                      .mapError(e => JorlanError(e.toString))
                      .tap(expr => cronCache.update(_ + (trigger.id -> expr)))
                }
              }
              _ <- advanceCronTrigger(job, trigger, cronExpr, now)
            } yield ()
          case TriggerType.Interval => advanceIntervalTrigger(job, trigger, now)
          case TriggerType.Event    => ZIO.unit
        }
      }
    } yield ()

  /** On failure: retry with backoff if retries remain, otherwise mark as Failed. */
  private def scheduleRetryOrFail(
    job: SchedulerJob,
    now: Instant,
  ): IO[JorlanError, Unit] =
    if (job.retryCount < job.maxRetries) {
      val backoff = job.backoffPolicy match {
        case RetryBackoffPolicy.Fixed       => job.backoffSeconds.toLong
        case RetryBackoffPolicy.Exponential =>
          // Use bit-shift to avoid floating-point precision loss; cap exponent at 62 to prevent overflow.
          job.backoffSeconds.toLong * (1L << math.min(job.retryCount, 62))
      }
      repo.scheduler
        .upsertJob(job.released(JobStatus.Pending, now.plusSeconds(backoff)).copy(retryCount = job.retryCount + 1))
        .orElseSucceed(())
        .unit
    } else {
      repo.scheduler.releaseJob(job.id, JobStatus.Failed, None, now).orElseSucceed(())
    }

  /** Execute a single claimed job: create a session, run the message, collect the result, then terminate the session.
    */
  private def executeJob(
    job:       SchedulerJob,
    cronCache: Ref[Map[SchedulerTriggerId, CronExpr]],
    workerId:  String,
  ): IO[JorlanError, Unit] = {
    val content = if (job.prompt.nonEmpty) job.prompt else job.inputJson.getOrElse("")
    ZIO
      .acquireReleaseWith(
        // Acquire: create session and record startedAt while preserving the Running lease state.
        for {
          _   <- logJobEvent(EventType.SchedulerJobStarted, job)
          now <- Clock.instant
          _   <- repo.scheduler
            .upsertJob(
              job.copy(
                status = JobStatus.Running,
                startedAt = Some(now),
                leasedAt = Some(now),
                leasedBy = Some(workerId),
              ),
            )

          session <- sessionManager.createSession(job.userId, None)
        } yield session,
      )(
        // Release: always terminate the session regardless of outcome
        session => sessionManager.terminateSession(session.id).ignore,
      ) { session =>
        (for {
          connId <- ConnectionId.randomZIO
          stream <- agentRunner.subscribeToSession(session.id, connId)
          _      <- agentRunner.processMessage(session.id, content, Some(job.userId))
          result <- stream
            .takeWhile(!_.finished)
            .runFold("") { case (acc, chunk) => acc + chunk.content }
            .timeout(jobTimeout)
            .map(_.getOrElse(""))
          now <- Clock.instant
          _   <- repo.scheduler
            .releaseJob(job.id, JobStatus.Succeeded, Some(result), now)
            .mapError(JorlanError(_))
          _ <- logJobEvent(EventType.SchedulerJobCompleted, job)
          _ <- advanceTriggers(job, cronCache, now)
        } yield ()).catchAll { err =>
          for {
            now <- Clock.instant
            _   <- ZIO.logWarning(s"[TriggerEngine] Job ${job.id.value} failed: ${err.msg}")
            _   <- scheduleRetryOrFail(job, now)
            _   <- logJobEvent(EventType.SchedulerJobFailed, job)
          } yield ()
        }
      }.catchAll { err =>
        // Session creation or other setup failure: treat the job as failed
        for {
          now <- Clock.instant
          _   <- ZIO.logWarning(s"[TriggerEngine] Job ${job.id.value} failed during setup: ${err.msg}")
          _   <- scheduleRetryOrFail(job, now)
          _   <- logJobEvent(EventType.SchedulerJobFailed, job)
        } yield ()
      }.mapError(JorlanError.apply)
  }

  /** Recompute `scheduledAt` for all Pending jobs with Cron or Interval triggers that are already in the past, to
    * prevent a thundering herd on restart.
    */
  private def recomputeStaleTriggers: IO[JorlanError, Unit] =
    for {
      now  <- Clock.instant
      jobs <- repo.scheduler.getPendingJobs
      staleness = pollInterval.multipliedBy(2L)
      stale = jobs.filter(j => j.scheduledAt.plusMillis(staleness.toMillis).isBefore(now))
      _ <- ZIO.foreachDiscard(stale) { job =>
        (for {
          triggers <- repo.scheduler.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 100))
          _        <- ZIO.foreachDiscard(triggers.filter(_.enabled)) { trigger =>
            trigger.triggerType match {
              case TriggerType.Cron =>
                ZIO
                  .fromEither(Cron.parse(trigger.expression))
                  .mapError(e => JorlanError(e.toString))
                  .flatMap { cronExpr =>
                    val zdtNow = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                    cronExpr.next(zdtNow) match {
                      case Some(next) =>
                        job.missedRunPolicy match {
                          case MissedRunPolicy.Skip =>
                            repo.scheduler
                              .upsertJob(job.released(JobStatus.Pending, next.toInstant)).mapError(JorlanError(_)).unit
                          case _ => ZIO.unit
                        }
                      case None => ZIO.unit
                    }
                  }
              case TriggerType.Interval =>
                job.missedRunPolicy match {
                  case MissedRunPolicy.Skip =>
                    ZIO
                      .attempt(Duration.parse(trigger.expression))
                      .mapError(e => JorlanError(e.getMessage))
                      .flatMap { dur =>
                        repo.scheduler
                          .upsertJob(job.released(JobStatus.Pending, now.plus(dur))).mapError(JorlanError(_)).unit
                      }
                  case _ => ZIO.unit
                }
              case _ => ZIO.unit
            }
          }
        } yield ()).orElse(ZIO.unit)
      }
    } yield ()

  /** One poll tick: expire stale leases, then claim and fork each pending job.
    *
    * `private[service]` visibility is intentional — exposed for `TriggerEngineSpec` test access without making it part
    * of the public API.
    */
  private[service] def tick(
    workerId:  String,
    cronCache: Ref[Map[SchedulerTriggerId, CronExpr]],
  ): IO[JorlanError, Unit] = {
    for {
      now  <- Clock.instant
      _    <- repo.scheduler.expireLeases(now.minusSeconds(leaseTtl.toLong))
      jobs <- repo.scheduler.getPendingJobs
      _    <- ZIO.foreachDiscard(jobs) { job =>
        repo.scheduler
          .claimJob(job.id, workerId, now, leaseTtl)
          .flatMap { claimed =>
            executeJob(job, cronCache, workerId).forkDaemon.unit.when(claimed)
          }
      }
    } yield ()
  }

  /** Run the scheduler loop: recompute stale triggers on startup, then poll indefinitely.
    *
    * This method runs until interrupted. Callers should `forkDaemon` the returned effect and retain the fiber for
    * potential cancellation.
    */
  override def start: IO[JorlanError, Unit] =
    for {
      workerId  <- workerIdIO
      cronCache <- Ref.make(Map.empty[SchedulerTriggerId, CronExpr])
      _         <- ZIO.logInfo(s"[TriggerEngine] Starting (worker=$workerId, poll=${pollInterval.getSeconds}s)")
      _         <- recomputeStaleTriggers
      _         <- tick(workerId, cronCache).repeat(Schedule.spaced(pollInterval)).unit
    } yield ()

}

object TriggerEngine {

  val live: ZLayer[
    ConfigurationService & AgentRunner & AgentSessionManager & ZIORepositories,
    ConfigurationError,
    TriggerEngineImpl,
  ] = ZLayer.fromZIO {
    for {
      repo   <- ZIO.service[ZIORepositories]
      sm     <- ZIO.service[AgentSessionManager]
      runner <- ZIO.service[AgentRunner]
      config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      s = config.jorlan.scheduler
    } yield TriggerEngineImpl(
      repo,
      sm,
      runner,
      Duration.ofSeconds(s.pollIntervalSeconds.toLong),
      s.leaseTtlSeconds,
      Duration.ofSeconds(s.jobTimeoutSeconds.toLong),
    )
  }

}
