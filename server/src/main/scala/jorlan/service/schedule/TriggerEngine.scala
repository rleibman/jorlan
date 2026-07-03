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
import jorlan.connector.InvocationContext
import jorlan.db.repository.*
import jorlan.service.{AgentRunner, AgentSessionManager, NotificationRouter}
import zio.*
import zio.json.*

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
  repo:               ZIORepositories,
  sessionManager:     AgentSessionManager,
  agentRunner:        AgentRunner,
  notificationRouter: NotificationRouter,
  pollInterval:       Duration = Duration.ofSeconds(10),
  leaseTtl:           Int = 300,
  jobTimeout:         Duration = Duration.ofSeconds(300),
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
            agentId = job.agentId,
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

  /** Run one agent turn (subscribe → send → collect). Returns `(output, isError)` or `None` on timeout. */
  private def runAgentTurn(
    sessionId: AgentSessionId,
    userId:    UserId,
    send:      AgentSessionId => IO[JorlanError, Unit],
  ): IO[JorlanError, Option[(String, Boolean)]] =
    for {
      connId <- ConnectionId.randomZIO
      stream <- agentRunner.subscribeToSession(sessionId, connId)
      _      <- send(sessionId)
        .tapError(err => ZIO.logWarning(s"[TriggerEngine] agent turn soft error: ${err.msg}"))
        .ignore
      result <- stream
        .takeUntil(_.finished)
        .runFold(("", false)) { case ((acc, _), chunk) =>
          if (chunk.finished) {
            val out = if (chunk.isError && chunk.content.nonEmpty) acc + chunk.content else acc
            (out, chunk.isError)
          } else (acc + chunk.content, false)
        }
        .timeout(zio.Duration.fromJava(jobTimeout))
    } yield result

  private def notifyPipelineFailure(
    job: SchedulerJob,
    run: PipelineRun,
    err: JorlanError,
  ): UIO[Unit] = {
    val stepInfo = run.failedStep.fold("")(s => s"\nFailed at step: $s")
    val msg =
      s"Pipeline job '${job.name}' failed (run #${run.id.value}).$stepInfo\nError: ${err.msg}\n\nSee Event Log for details."
    val ctx = InvocationContext(actorId = job.userId, agentId = job.agentId, sessionId = None)
    notificationRouter.notifyUser(job.userId, msg, ctx).ignore
  }

  /** Execute a pipeline step with per-step retry. Returns the step output on success or fails with [[JorlanError]]. */
  private def executeStep(
    job:         SchedulerJob,
    session:     AgentSession,
    step:        PipelineStep,
    pipeline:    Pipeline,
    stepContext: Map[String, String],
    runId:       PipelineRunId,
    runContext:  Option[String],
  ): IO[JorlanError, String] = {
    def attempt(attemptsLeft: Int): IO[JorlanError, String] = {
      for {
        now   <- Clock.instant
        agent <- repo.agent
          .getById(job.agentId.getOrElse(AgentId.empty))
          .mapError(JorlanError(_))
          .map(_.getOrElse(Agent(AgentId.empty, "", None, None, createdAt = now)))
        mergedInvariants = agent.invariants ++ pipeline.invariants
        userPrompt = TemplateEngine.renderUserPrompt(step, mergedInvariants, stepContext, runId, runContext, now)
        systemPrompt = step.systemPrompt.nonEmpty match {
          case true  => step.systemPrompt
          case false => TemplateEngine.accuracySystemPrompt
        }
        result <- runAgentTurn(
          session.id,
          job.userId,
          sid =>
            step.mode match {
              case StepMode.SingleCall =>
                agentRunner.processMessageSingleCall(sid, systemPrompt, userPrompt, Some(job.userId))
              case StepMode.ReactLoop =>
                agentRunner.processMessage(sid, userPrompt, Some(job.userId), withMemory = false)
            },
        )
        output <- result match {
          case Some((text, false))   => ZIO.succeed(text)
          case Some((errText, true)) =>
            if (attemptsLeft > 0)
              ZIO.logWarning(
                s"[TriggerEngine] Step '${step.name}' failed, retrying ($attemptsLeft left): $errText",
              ) *> attempt(attemptsLeft - 1)
            else ZIO.fail(JorlanError(s"Step '${step.name}' failed after retries: $errText"))
          case None =>
            if (attemptsLeft > 0)
              ZIO.logWarning(
                s"[TriggerEngine] Step '${step.name}' timed out, retrying ($attemptsLeft left)",
              ) *> attempt(attemptsLeft - 1)
            else ZIO.fail(JorlanError(s"Step '${step.name}' timed out after retries"))
        }
      } yield output
    }
    attempt(step.retryOnFail)
  }

  /** Execute a pipeline job: run each step in sequence, collecting outputs into the shared context map. */
  private def executePipelineJob(
    job:       SchedulerJob,
    pipeline:  Pipeline,
    session:   AgentSession,
    run:       PipelineRun,
    cronCache: Ref[Map[SchedulerTriggerId, CronExpr]],
  ): IO[JorlanError, Unit] = {
    import zio.json.*

    def loop(
      remaining:   List[PipelineStep],
      stepContext: Map[String, String],
    ): IO[JorlanError, Unit] =
      remaining match {
        case Nil          => ZIO.unit
        case step :: rest =>
          executeStep(job, session, step, pipeline, stepContext, run.id, run.runContext)
            .foldZIO(
              err =>
                for {
                  now <- Clock.instant
                  failedRun = run.copy(
                    status = PipelineRunStatus.FailedAtStep,
                    failedStep = Some(step.name),
                    contextJson = Some(stepContext.toJson),
                    finishedAt = Some(now),
                  )
                  _ <- repo.scheduler.updatePipelineRun(failedRun).mapError(JorlanError(_))
                  _ <- ZIO.fail(err)
                } yield (),
              output => {
                val newCtx = stepContext + (step.outputVar -> output)
                repo.scheduler
                  .updatePipelineRun(run.copy(contextJson = Some(newCtx.toJson))).mapError(JorlanError(_)) *>
                  loop(rest, newCtx)
              },
            )
      }

    loop(pipeline.steps, Map.empty).foldZIO(
      err =>
        for {
          now       <- Clock.instant
          _         <- ZIO.logWarning(s"[TriggerEngine] Pipeline job ${job.id.value} failed: ${err.msg}")
          latestRun <- repo.scheduler
            .listPipelineRuns(job.id).mapError(JorlanError(_))
            .map(_.find(_.id == run.id).getOrElse(run))
          _ <- notifyPipelineFailure(job, latestRun, err)
          _ <- scheduleRetryOrFail(job, now)
          _ <- logJobEvent(EventType.SchedulerJobFailed, job)
        } yield (),
      _ =>
        for {
          now <- Clock.instant
          _   <- repo.scheduler
            .updatePipelineRun(run.copy(status = PipelineRunStatus.Succeeded, finishedAt = Some(now)))
            .mapError(JorlanError(_))
          _ <- repo.scheduler
            .releaseJob(job.id, JobStatus.Succeeded, None, now)
            .mapError(JorlanError(_))
          _ <- logJobEvent(EventType.SchedulerJobCompleted, job)
          _ <- advanceTriggers(job, cronCache, now)
            .tapError(e =>
              ZIO.logWarning(
                s"[TriggerEngine] Job ${job.id.value} succeeded but trigger advance failed: ${e.msg}",
              ),
            ).ignore
        } yield (),
    )
  }

  /** Execute a single claimed job: create a session, run its pipeline, collect the result, then terminate the session.
    */
  private def executeJob(
    job:       SchedulerJob,
    cronCache: Ref[Map[SchedulerTriggerId, CronExpr]],
    workerId:  String,
  ): IO[JorlanError, Unit] = {
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
        // Find the most recent Running PipelineRun or create one for scheduled auto-runs.
        for {
          now  <- Clock.instant
          runs <- repo.scheduler.listPipelineRuns(job.id).mapError(JorlanError(_))
          run  <- runs.find(_.status == PipelineRunStatus.Running) match {
            case Some(r) => ZIO.succeed(r)
            case None    =>
              repo.scheduler
                .insertPipelineRun(
                  PipelineRun(
                    id = PipelineRunId.empty,
                    jobId = job.id,
                    status = PipelineRunStatus.Running,
                    runContext = None,
                    contextJson = None,
                    failedStep = None,
                    startedAt = now,
                    finishedAt = None,
                  ),
                )
                .mapError(JorlanError(_))
          }
          _ <- executePipelineJob(job, job.pipeline, session, run, cronCache)
        } yield ()
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
    ConfigurationService & AgentRunner & AgentSessionManager & ZIORepositories & NotificationRouter,
    ConfigurationError,
    TriggerEngineImpl,
  ] = ZLayer.fromZIO {
    for {
      repo   <- ZIO.service[ZIORepositories]
      sm     <- ZIO.service[AgentSessionManager]
      runner <- ZIO.service[AgentRunner]
      notify <- ZIO.service[NotificationRouter]
      config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      s = config.jorlan.scheduler
    } yield TriggerEngineImpl(
      repo,
      sm,
      runner,
      notify,
      Duration.ofSeconds(s.pollIntervalSeconds.toLong),
      s.leaseTtlSeconds,
      Duration.ofSeconds(s.jobTimeoutSeconds.toLong),
    )
  }

}
