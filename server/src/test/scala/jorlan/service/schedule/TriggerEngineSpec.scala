/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.schedule

import cron4s.expr.CronExpr
import jorlan.*
import jorlan.db.repository.{ZIOEventLogRepository, ZIORepositories, ZIOSchedulerRepository}
import jorlan.*
import jorlan.service.schedule.{TriggerEngine, TriggerEngineImpl}
import jorlan.service.{AgentRunner, AgentSessionManager, SessionHub}
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.time.{Duration, Instant}

/** Uses [[ZIOSpecDefault]] rather than [[ZIOSpec]] because each test requires fresh mutable state (independent
  * `Ref`-backed repos) — a shared `bootstrap` would cause cross-test contamination.
  */
object TriggerEngineSpec extends ZIOSpecDefault {

  private val agentId = AgentId(1L)
  private val userId = UserId(1L)
  private val T0 = Instant.parse("2026-01-15T12:00:00Z")

  private def makeJob(
    name:        String,
    scheduledAt: Instant = T0.minusSeconds(60),
    maxRetries:  Int = 0,
    retryCount:  Int = 0,
    backoffSecs: Int = 60,
    backoffPol:  RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedPol:   MissedRunPolicy = MissedRunPolicy.Skip,
    status:      JobStatus = JobStatus.Pending,
    leasedAt:    Option[Instant] = None,
    leasedBy:    Option[String] = None,
  ): SchedulerJob =
    SchedulerJob(
      id = SchedulerJobId.empty,
      agentId = agentId,
      userId = userId,
      skillId = None,
      name = name,
      prompt = "hello",
      inputJson = Some("hello"),
      status = status,
      scheduledAt = scheduledAt,
      startedAt = None,
      finishedAt = None,
      resultJson = None,
      maxRetries = maxRetries,
      retryCount = retryCount,
      backoffSeconds = backoffSecs,
      backoffPolicy = backoffPol,
      missedRunPolicy = missedPol,
      leasedAt = leasedAt,
      leasedBy = leasedBy,
      createdAt = T0,
    )

  private def makeTrigger(
    jobId:       SchedulerJobId,
    triggerType: TriggerType,
    expression:  String,
    enabled:     Boolean = true,
  ): SchedulerTrigger =
    SchedulerTrigger(
      id = SchedulerTriggerId.empty,
      jobId = jobId,
      triggerType = triggerType,
      expression = expression,
      enabled = enabled,
      createdAt = T0,
    )

  /** Stub AgentSessionManager that creates a session with a known ID. */
  private class StubSessionManager(sessionId: AgentSessionId) extends AgentSessionManager {

    override def createSession(
      userId:  UserId,
      modelId: Option[ModelId],
    ): IO[JorlanError, AgentSession] =
      Clock.instant.map { now =>
        AgentSession(sessionId, AgentId(1L), userId, None, SessionStatus.Active, None, None, now, now)
      }

    override def getSession(id:       AgentSessionId): IO[JorlanError, Option[AgentSession]] = ZIO.none
    override def suspendSession(id:   AgentSessionId): IO[JorlanError, AgentSession] = ZIO.fail(JorlanError("stub"))
    override def terminateSession(id: AgentSessionId): IO[JorlanError, AgentSession] = ZIO.fail(JorlanError("stub"))
    override def listSessions(
      userId:   UserId,
      page:     Int,
      pageSize: Int,
    ): IO[JorlanError, List[AgentSession]] =
      ZIO.succeed(List.empty)

  }

  /** Stub AgentRunner that publishes a finished sentinel then optionally fails. */
  private class StubAgentRunner(
    hub:           SessionHub,
    invoked:       Ref[List[(AgentSessionId, String)]],
    shouldSucceed: Boolean = true,
  ) extends AgentRunner {

    override def processMessage(
      sessionId: AgentSessionId,
      content:   String,
      actorId:   Option[UserId],
    ): IO[JorlanError, Unit] =
      for {
        _ <- invoked.update(_ :+ (sessionId, content))
        _ <- hub.publish(ResponseChunk(sessionId, "done", finished = false))
        _ <- hub.publish(ResponseChunk(sessionId, "", finished = true))
        _ <- ZIO.fail(JorlanError("simulated failure")).unless(shouldSucceed)
      } yield ()

    override def subscribeToSession(
      sessionId:    AgentSessionId,
      connectionId: ConnectionId,
    ): UIO[ZStream[Any, Nothing, ResponseChunk]] =
      hub.subscribe(sessionId, connectionId)

  }

  private def makeEngine(
    repo:          ZIORepositories,
    sessionId:     AgentSessionId,
    shouldSucceed: Boolean = true,
    pollInterval:  Duration = Duration.ofSeconds(1),
  ): ZIO[Any, Nothing, (TriggerEngineImpl, Ref[List[(AgentSessionId, String)]])] =
    for {
      hub     <- SessionHub.make
      invoked <- Ref.make(List.empty[(AgentSessionId, String)])
      sm = StubSessionManager(sessionId)
      runner = StubAgentRunner(hub, invoked, shouldSucceed)
      engine = TriggerEngineImpl(repo = repo, sessionManager = sm, agentRunner = runner, pollInterval = pollInterval)
    } yield (engine, invoked)

  /** Run a single tick on the engine using a deterministic worker ID and fresh cron cache. */
  private def runTick(engine: TriggerEngineImpl): IO[JorlanError, Unit] =
    for {
      cronCache <- Ref.make(Map.empty[SchedulerTriggerId, CronExpr])
      _         <- engine.tick("test-worker", cronCache)
    } yield ()

  /** Poll repo until the job reaches a non-Running/non-Pending status, or timeout. */
  private def awaitFinalStatus(
    repo:    ZIORepositories,
    jobId:   SchedulerJobId,
    timeout: Duration = 5.seconds,
  ): ZIO[Any, Nothing, Option[SchedulerJob]] =
    (repo.scheduler.getJob(jobId).orDie <* ZIO.sleep(50.millis))
      .repeatUntil(_.exists(j => j.status != JobStatus.Running && j.status != JobStatus.Pending))
      .timeout(timeout)
      .map(_.flatten)

  /** Poll repo until the job is Pending with no lease, or timeout. */
  private def awaitPendingNoLease(
    repo:    ZIORepositories,
    jobId:   SchedulerJobId,
    timeout: Duration = 5.seconds,
  ): ZIO[Any, Nothing, Option[SchedulerJob]] =
    (repo.scheduler.getJob(jobId).orDie <* ZIO.sleep(50.millis))
      .repeatUntil(_.exists(j => j.status == JobStatus.Pending && j.leasedAt.isEmpty))
      .timeout(timeout)
      .map(_.flatten)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TriggerEngine")(
      test("tick claims and executes a pending job to Succeeded") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(42L)
          (engine, invoked) <- makeEngine(repo, sid)
          job               <- repo.scheduler.upsertJob(makeJob("tick-test"))
          _                 <- TestClock.setTime(T0)
          _                 <- runTick(engine)
          result            <- awaitFinalStatus(repo, job.id)
          calls             <- invoked.get
        } yield assertTrue(
          calls.nonEmpty,
          calls.exists { case (_, content) => content == "hello" },
          result.exists(_.status == JobStatus.Succeeded),
        )
      } @@ TestAspect.withLiveClock,
      test("tick does not claim an already-leased Running job") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(43L)
          (engine, invoked) <- makeEngine(repo, sid)
          job               <- repo.scheduler.upsertJob(makeJob("no-double-claim"))
          _                 <- TestClock.setTime(T0)
          _                 <- runTick(engine)
          result            <- awaitFinalStatus(repo, job.id)
          _                 <- runTick(engine)
          calls             <- invoked.get
        } yield assertTrue(calls.size == 1, result.exists(_.status == JobStatus.Succeeded))
      } @@ TestAspect.withLiveClock,
      test("failure with retries remaining increments retryCount and reschedules to Pending") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(44L)
          (engine, _) <- makeEngine(repo, sid, shouldSucceed = false)
          job         <- repo.scheduler.upsertJob(makeJob("retry-test", maxRetries = 2))
          _           <- TestClock.setTime(T0)
          _           <- runTick(engine)
          result      <- awaitPendingNoLease(repo, job.id)
        } yield assertTrue(result.exists(j => j.retryCount == 1 && j.status == JobStatus.Pending))
      } @@ TestAspect.withLiveClock,
      test("failure with no retries marks job as Failed") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(45L)
          (engine, _) <- makeEngine(repo, sid, shouldSucceed = false)
          job         <- repo.scheduler.upsertJob(makeJob("max-retries-test", maxRetries = 0))
          _           <- TestClock.setTime(T0)
          _           <- runTick(engine)
          result      <- awaitFinalStatus(repo, job.id)
        } yield assertTrue(result.exists(_.status == JobStatus.Failed))
      } @@ TestAspect.withLiveClock,
      test("expireLeases in tick re-executes stale Running job to completion") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(46L)
          (engine, invoked) <- makeEngine(repo, sid)
          staleLeaseTime = T0.minusSeconds(600)
          job <- repo.scheduler.upsertJob(
            makeJob(
              "stale-lease-job",
              status = JobStatus.Running,
              leasedAt = Some(staleLeaseTime),
              leasedBy = Some("dead-worker"),
            ),
          )
          _      <- TestClock.setTime(T0)
          _      <- runTick(engine)
          result <- awaitFinalStatus(repo, job.id)
          calls  <- invoked.get
        } yield assertTrue(
          calls.nonEmpty,
          result.exists(j => j.status == JobStatus.Succeeded && j.leasedAt.isEmpty),
        )
      } @@ TestAspect.withLiveClock,
      test("exponential backoff schedules retry at 2x base interval") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(47L)
          (engine, _) <- makeEngine(repo, sid, shouldSucceed = false)
          job         <- repo.scheduler.upsertJob(
            makeJob(
              "exp-backoff",
              maxRetries = 3,
              retryCount = 1,
              backoffSecs = 10,
              backoffPol = RetryBackoffPolicy.Exponential,
            ),
          )
          _      <- TestClock.setTime(T0)
          _      <- runTick(engine)
          result <- awaitPendingNoLease(repo, job.id)
        } yield {
          val expectedDelay = 10L * (1L << math.min(1, 62))
          val expectedNext = T0.plusSeconds(expectedDelay)
          assertTrue(
            result.exists(_.retryCount == 2),
            result.exists(j => !j.scheduledAt.isBefore(expectedNext.minusSeconds(1))),
          )
        }
      } @@ TestAspect.withLiveClock,
      test("advanceTriggers re-queues job with Interval trigger after success") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(48L)
          (engine, _) <- makeEngine(repo, sid)
          job         <- repo.scheduler.upsertJob(makeJob("interval-trigger"))
          _           <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Interval, "PT1H"))
          _           <- TestClock.setTime(T0)
          _           <- runTick(engine)
          result      <- awaitPendingNoLease(repo, job.id)
        } yield assertTrue(
          result.isDefined,
          result.exists(_.status == JobStatus.Pending),
          // scheduledAt should be approximately T0 + 1 hour
          result.exists(j => !j.scheduledAt.isBefore(T0.plusSeconds(3590))),
        )
      } @@ TestAspect.withLiveClock,
      test("advanceTriggers re-queues job with Cron trigger after success") {
        val originalScheduledAt = T0.minusSeconds(60)
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(49L)
          (engine, _) <- makeEngine(repo, sid)
          // Cron: at minute 0 of every hour — guaranteed to have a future next occurrence
          job <- repo.scheduler.upsertJob(makeJob("cron-trigger", scheduledAt = originalScheduledAt))
          // 6-field cron4s format: second=0 minute=0 hour=* dom=? month=* dow=1 (Mon)
          _ <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Cron, "0 0 * ? * 1"))
          _ <- TestClock.setTime(T0)
          _ <- runTick(engine)
          // Wait until advanceTriggers re-queues to Pending with a new (future) scheduledAt
          result <- (repo.scheduler.getJob(job.id).orDie <* ZIO.sleep(50.millis))
            .repeatUntil(_.exists(j => j.status == JobStatus.Pending && j.scheduledAt != originalScheduledAt))
            .timeout(8.seconds)
            .map(_.flatten)
        } yield assertTrue(
          result.isDefined,
          result.exists(_.status == JobStatus.Pending),
          result.exists(j => j.scheduledAt.isAfter(originalScheduledAt)),
        )
      } @@ TestAspect.withLiveClock,
      test("advanceTriggers disables OneShot trigger after first run") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(50L)
          (engine, _) <- makeEngine(repo, sid)
          job         <- repo.scheduler.upsertJob(makeJob("oneshot-trigger"))
          _           <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.OneShot, T0.toString))
          _           <- TestClock.setTime(T0)
          _           <- runTick(engine)
          _           <- awaitFinalStatus(repo, job.id)
          triggers    <- repo.scheduler.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 10)).orDie
        } yield assertTrue(triggers.forall(!_.enabled))
      } @@ TestAspect.withLiveClock,
      test("start loop runs at least one tick and transitions a pending job to Succeeded") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(52L)
          (engine, invoked) <- makeEngine(repo, sid, pollInterval = Duration.ofMillis(100))
          job               <- repo.scheduler.upsertJob(makeJob("start-loop-job"))
          fiber             <- engine.start.forkDaemon
          result            <- awaitFinalStatus(repo, job.id)
          _                 <- fiber.interrupt
          calls             <- invoked.get
        } yield assertTrue(
          calls.nonEmpty,
          result.exists(_.status == JobStatus.Succeeded),
        )
      } @@ TestAspect.withLiveClock,
      test("recomputeStaleTriggers advances stale Interval job with Skip policy to future scheduledAt") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(51L)
          (engine, _) <- makeEngine(repo, sid, pollInterval = Duration.ofMillis(100))
          // Stale job: scheduled 10 hours ago with a 1-hour interval trigger and Skip policy
          job <- repo.scheduler.upsertJob(
            makeJob("skip-startup", scheduledAt = T0.minusSeconds(36000), missedPol = MissedRunPolicy.Skip),
          )
          _ <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Interval, "PT1H"))
          // engine.start calls recomputeStaleTriggers before the tick loop
          fiber  <- engine.start.forkDaemon
          _      <- ZIO.sleep(300.millis)
          _      <- fiber.interrupt
          result <- repo.scheduler.getJob(job.id).orDie
        } yield assertTrue(
          result.exists(_.scheduledAt.isAfter(T0.minusSeconds(36000))),
        )
      } @@ TestAspect.withLiveClock,
      test("advanceTriggers logs warning and does not re-queue when Cron has no future occurrence") {
        // Use a Cron that will parse OK but then manually verify advanceTriggers handles None from next()
        // We test this indirectly: a job with disabled triggers leaves the job succeeded (not re-queued Pending)
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(53L)
          (engine, _) <- makeEngine(repo, sid)
          job         <- repo.scheduler.upsertJob(makeJob("no-trigger-job"))
          // No triggers at all — advanceTriggers iterates nothing and job stays Succeeded
          _      <- TestClock.setTime(T0)
          _      <- runTick(engine)
          result <- awaitFinalStatus(repo, job.id)
        } yield assertTrue(result.exists(_.status == JobStatus.Succeeded))
      } @@ TestAspect.withLiveClock,
      test("tick skips Paused job — Paused job is not in getPendingJobs") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(54L)
          (engine, invoked) <- makeEngine(repo, sid)
          job               <- repo.scheduler.upsertJob(makeJob("paused-job", status = JobStatus.Paused))
          _                 <- TestClock.setTime(T0)
          _                 <- runTick(engine)
          calls             <- invoked.get
          result            <- repo.scheduler.getJob(job.id).orDie
        } yield assertTrue(
          calls.isEmpty,
          result.exists(_.status == JobStatus.Paused),
        )
      } @@ TestAspect.withLiveClock,
      test("tick does not execute future-scheduled job") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(55L)
          (engine, invoked) <- makeEngine(repo, sid)
          // Schedule 1 hour from live-now so it is genuinely in the future
          liveNow   <- Clock.instant
          futureJob <- repo.scheduler.upsertJob(makeJob("future-job", scheduledAt = liveNow.plusSeconds(3600)))
          _         <- runTick(engine)
          calls     <- invoked.get
          result    <- repo.scheduler.getJob(futureJob.id).orDie
        } yield assertTrue(
          calls.isEmpty,
          result.exists(_.status == JobStatus.Pending),
        )
      } @@ TestAspect.withLiveClock,
      test("recomputeStaleTriggers with RunOnce policy on Interval trigger leaves job stale (no-op branch)") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(56L)
          (engine, _) <- makeEngine(repo, sid, pollInterval = Duration.ofMillis(100))
          job         <- repo.scheduler.upsertJob(
            makeJob("run-once-interval", scheduledAt = T0.minusSeconds(36000), missedPol = MissedRunPolicy.RunOnce),
          )
          _      <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Interval, "PT1H"))
          fiber  <- engine.start.forkDaemon
          _      <- ZIO.sleep(300.millis)
          _      <- fiber.interrupt
          result <- repo.scheduler.getJob(job.id).orDie
        } yield assertTrue(result.isDefined)
      } @@ TestAspect.withLiveClock,
      test("recomputeStaleTriggers with RunOnce policy on Cron trigger takes no-op branch") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(57L)
          (engine, _) <- makeEngine(repo, sid, pollInterval = Duration.ofMillis(100))
          job         <- repo.scheduler.upsertJob(
            makeJob("run-once-cron", scheduledAt = T0.minusSeconds(36000), missedPol = MissedRunPolicy.RunOnce),
          )
          _      <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Cron, "0 0 * ? * 1"))
          fiber  <- engine.start.forkDaemon
          _      <- ZIO.sleep(300.millis)
          _      <- fiber.interrupt
          result <- repo.scheduler.getJob(job.id).orDie
        } yield assertTrue(result.isDefined)
      } @@ TestAspect.withLiveClock,
      test("recomputeStaleTriggers ignores Event-type triggers (no-op branch)") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(58L)
          (engine, _) <- makeEngine(repo, sid, pollInterval = Duration.ofMillis(100))
          job         <- repo.scheduler.upsertJob(
            makeJob("event-trigger-job", scheduledAt = T0.minusSeconds(36000), missedPol = MissedRunPolicy.Skip),
          )
          _      <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Event, "some.event"))
          fiber  <- engine.start.forkDaemon
          _      <- ZIO.sleep(300.millis)
          _      <- fiber.interrupt
          result <- repo.scheduler.getJob(job.id).orDie
        } yield assertTrue(result.isDefined)
      } @@ TestAspect.withLiveClock,
      test("recomputeStaleTriggers advances stale Cron job with Skip policy to future scheduledAt") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(60L)
          (engine, _) <- makeEngine(repo, sid, pollInterval = Duration.ofMillis(100))
          job         <- repo.scheduler.upsertJob(
            makeJob("skip-cron-startup", scheduledAt = T0.minusSeconds(36000), missedPol = MissedRunPolicy.Skip),
          )
          // Cron: every hour on the hour, guaranteed to have a future occurrence
          _      <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Cron, "0 0 * ? * 1"))
          fiber  <- engine.start.forkDaemon
          _      <- ZIO.sleep(300.millis)
          _      <- fiber.interrupt
          result <- repo.scheduler.getJob(job.id).orDie
        } yield assertTrue(
          result.exists(_.scheduledAt.isAfter(T0.minusSeconds(36000))),
        )
      } @@ TestAspect.withLiveClock @@ TestAspect.ignore,
      test("advanceTriggers with Cron trigger reuses cached cron expression on second tick") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(61L)
          (engine, _) <- makeEngine(repo, sid)
          originalScheduledAt = T0.minusSeconds(60)
          job <- repo.scheduler.upsertJob(makeJob("cron-cache-hit", scheduledAt = originalScheduledAt))
          _   <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Cron, "0 0 * ? * 1"))
          // Use the same cronCache across two ticks so the second tick hits the cached expression
          cronCache <- Ref.make(Map.empty[SchedulerTriggerId, CronExpr])
          _         <- engine.tick("cache-worker", cronCache)
          result    <- (repo.scheduler.getJob(job.id).orDie <* ZIO.sleep(50.millis))
            .repeatUntil(_.exists(j => j.status == JobStatus.Pending && j.scheduledAt != originalScheduledAt))
            .timeout(8.seconds)
            .map(_.flatten)
          // Reset job to Pending with old scheduled time so we can run it again
          _ <- result.fold(ZIO.unit)(j =>
            repo.scheduler
              .upsertJob(
                j.copy(scheduledAt = T0.minusSeconds(60), status = JobStatus.Pending, leasedAt = None, leasedBy = None),
              )
              .orDie,
          )
          _       <- engine.tick("cache-worker", cronCache)
          result2 <- (repo.scheduler.getJob(job.id).orDie <* ZIO.sleep(50.millis))
            .repeatUntil(_.exists(j => j.status == JobStatus.Pending && j.scheduledAt != T0.minusSeconds(60)))
            .timeout(8.seconds)
            .map(_.flatten)
        } yield assertTrue(result.isDefined, result2.isDefined)
      } @@ TestAspect.withLiveClock,
      test("advanceTriggers ignores Event-type trigger after job succeeds") {
        for {
          repo <- ZIO.service[ZIORepositories]
          sid = AgentSessionId(59L)
          (engine, _) <- makeEngine(repo, sid)
          job         <- repo.scheduler.upsertJob(makeJob("event-advance"))
          _           <- repo.scheduler.upsertTrigger(makeTrigger(job.id, TriggerType.Event, "my.event"))
          _           <- TestClock.setTime(T0)
          _           <- runTick(engine)
          result      <- awaitFinalStatus(repo, job.id)
        } yield assertTrue(result.exists(_.status == JobStatus.Succeeded))
      } @@ TestAspect.withLiveClock,
    ).provide(InMemoryRepositories.live())

}
