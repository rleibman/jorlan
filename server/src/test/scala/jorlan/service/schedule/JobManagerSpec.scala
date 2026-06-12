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

import jorlan.*
import jorlan.db.repository.ZIORepositories
import jorlan
.*
import jorlan.service.JobManager
import jorlan.service.schedule.JobManagerImpl
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

/** Each test creates its own isolated in-memory repo instance to prevent cross-test contamination. */
object JobManagerSpec extends ZIOSpecDefault {

  private val agentId = AgentId(1L)
  private val agentId2 = AgentId(2L)
  private val userId = UserId(1L)

  private def mkJob(
    name:        String,
    maxRetries:  Int = 0,
    backoffSecs: Int = 60,
    backoffPol:  RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedPol:   MissedRunPolicy = MissedRunPolicy.Skip,
  )(
    mgr: JobManager,
  ): IO[JorlanError, SchedulerJob] =
    mgr.createJob(agentId, userId, name, None, maxRetries, backoffSecs, backoffPol, missedPol)

  private def makeManager = ZIO.serviceWith[ZIORepositories](JobManagerImpl(_))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("JobManager")(
      suite("job lifecycle")(
        test("createJob returns a job with Pending status and the given name") {
          for {
            mgr <- makeManager
            job <- mkJob("test-job")(mgr)
          } yield assertTrue(
            job.name == "test-job",
            job.status == JobStatus.Pending,
            job.agentId == agentId,
            job.userId == userId,
            job.maxRetries == 0,
          )
        },
        test("createJob propagates retry and backoff settings") {
          for {
            mgr <- makeManager
            job <- mgr.createJob(
              agentId,
              userId,
              "retry-job",
              Some("""{"key":"value"}"""),
              maxRetries = 3,
              backoffSeconds = 120,
              backoffPolicy = RetryBackoffPolicy.Exponential,
              missedRunPolicy = MissedRunPolicy.RunOnce,
            )
          } yield assertTrue(
            job.maxRetries == 3,
            job.backoffSeconds == 120,
            job.backoffPolicy == RetryBackoffPolicy.Exponential,
            job.missedRunPolicy == MissedRunPolicy.RunOnce,
            job.inputJson.contains("""{"key":"value"}"""),
          )
        },
        test("pauseJob sets status to Paused") {
          for {
            mgr    <- makeManager
            job    <- mkJob("pause-me")(mgr)
            _      <- mgr.pauseJob(job.id)
            result <- mgr.getJob(job.id)
          } yield assertTrue(result.status == JobStatus.Paused)
        },
        test("pauseJob fails on Cancelled job") {
          for {
            mgr    <- makeManager
            job    <- mkJob("pause-cancelled")(mgr)
            _      <- mgr.cancelJob(job.id)
            result <- mgr.pauseJob(job.id).either
          } yield assertTrue(result.isLeft)
        },
        test("pauseJob fails on Succeeded job") {
          for {
            mgr  <- makeManager
            repo <- ZIO.service[ZIORepositories]
            mgr2 = JobManagerImpl(repo)
            job    <- mkJob("pause-succeeded")(mgr2)
            _      <- repo.scheduler.releaseJob(job.id, JobStatus.Succeeded, None, java.time.Instant.now()).orDie
            result <- mgr2.pauseJob(job.id).either
          } yield assertTrue(result.isLeft)
        },
        test("resumeJob sets status back to Pending") {
          for {
            mgr    <- makeManager
            job    <- mkJob("resume-me")(mgr)
            _      <- mgr.pauseJob(job.id)
            _      <- mgr.resumeJob(job.id)
            result <- mgr.getJob(job.id)
          } yield assertTrue(result.status == JobStatus.Pending)
        },
        test("resumeJob fails on a non-Paused job") {
          for {
            mgr    <- makeManager
            job    <- mkJob("resume-running")(mgr)
            result <- mgr.resumeJob(job.id).either
          } yield assertTrue(result.isLeft)
        },
        test("cancelJob sets status to Cancelled") {
          for {
            mgr    <- makeManager
            job    <- mkJob("cancel-me")(mgr)
            _      <- mgr.cancelJob(job.id)
            result <- mgr.getJob(job.id)
          } yield assertTrue(result.status == JobStatus.Cancelled)
        },
        test("cancelJob is idempotent — calling on already-Cancelled job succeeds without error") {
          for {
            mgr    <- makeManager
            job    <- mkJob("cancel-twice")(mgr)
            _      <- mgr.cancelJob(job.id)
            result <- mgr.cancelJob(job.id).either
          } yield assertTrue(result.isRight)
        },
        test("triggerNow resets scheduledAt to now and sets Pending") {
          for {
            mgr    <- makeManager
            before <- Clock.instant
            job    <- mkJob("trigger-me")(mgr)
            _      <- mgr.pauseJob(job.id)
            _      <- mgr.triggerNow(job.id)
            result <- mgr.getJob(job.id)
          } yield assertTrue(
            result.status == JobStatus.Pending,
            !result.scheduledAt.isBefore(before),
            result.leasedAt.isEmpty,
          )
        },
        test("deleteJob removes the job — getJob fails afterward") {
          for {
            mgr    <- makeManager
            job    <- mkJob("delete-me")(mgr)
            _      <- mgr.deleteJob(job.id)
            result <- mgr.getJob(job.id).either
          } yield assertTrue(result.isLeft)
        },
        test("getJob fails with JorlanError for unknown id") {
          for {
            mgr    <- makeManager
            result <- mgr.getJob(SchedulerJobId(999L)).either
          } yield assertTrue(result.isLeft)
        },
        test("addTrigger attaches a trigger to the job") {
          for {
            mgr     <- makeManager
            job     <- mkJob("trigger-job")(mgr)
            now     <- Clock.instant
            trigger <- mgr.addTrigger(
              job.id,
              SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Cron, "0 9 * * 1-5", true, now),
            )
          } yield assertTrue(trigger.id.value > 0L, trigger.expression == "0 9 * * 1-5")
        },
      ).provideShared(InMemoryRepositories.live()),
      test("listJobs(None) returns exactly the created jobs") {
        for {
          mgr <- makeManager
          _   <- mkJob("job-a")(mgr)
          _   <- mkJob("job-b")(mgr)
          all <- mgr.listJobs(None)
        } yield assertTrue(all.size == 2, all.exists(_.name == "job-a"), all.exists(_.name == "job-b"))
      }.provide(InMemoryRepositories.live()),
      test("listJobs(Some(agentId)) returns only jobs for that agent") {
        for {
          mgr  <- makeManager
          repo <- ZIO.service[ZIORepositories]
          mgr2 = JobManagerImpl(repo)
          _ <- mgr2
            .createJob(agentId, userId, "agent1-job", None, 0, 60, RetryBackoffPolicy.Fixed, MissedRunPolicy.Skip)
          _ <- mgr2
            .createJob(agentId2, userId, "agent2-job", None, 0, 60, RetryBackoffPolicy.Fixed, MissedRunPolicy.Skip)
          all      <- mgr2.listJobs(None)
          filtered <- mgr2.listJobs(Some(agentId))
        } yield assertTrue(
          all.size == 2,
          filtered.size == 1,
          filtered.head.name == "agent1-job",
        )
      }.provide(InMemoryRepositories.live()),
    )

}
