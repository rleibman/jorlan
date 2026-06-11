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
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.schedule.JobManagerImpl
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

import java.time.Instant

/** Covers [[JobManagerImpl]] `.mapError(JorlanError(_))` lambdas by injecting a scheduler repository that fails on
  * specific operations.
  */
object JobManagerFailingRepoSpec extends ZIOSpecDefault {

  private val agentId = AgentId(1L)
  private val userId = UserId(1L)

  private val repoError: RepositoryError = RepositoryError("simulated failure")

  private val anyJob: SchedulerJob = SchedulerJob(
    id = SchedulerJobId(1L),
    agentId = agentId,
    userId = userId,
    skillId = None,
    name = "test-job",
    inputJson = None,
    status = JobStatus.Pending,
    scheduledAt = Instant.EPOCH,
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
    createdAt = Instant.EPOCH,
  )

  private val pausedJob: SchedulerJob = anyJob.copy(status = JobStatus.Paused)

  private def alwaysFail[A]: RepositoryTask[A] = ZIO.fail(repoError)

  private def makeRepo(
    getJobFn:        SchedulerJobId => RepositoryTask[Option[SchedulerJob]] = _ => alwaysFail,
    upsertJobFn:     SchedulerJob => RepositoryTask[SchedulerJob] = _ => alwaysFail,
    deleteJobFn:     SchedulerJobId => RepositoryTask[Long] = _ => alwaysFail,
    searchTriggerFn: TriggerSearch => RepositoryTask[List[SchedulerTrigger]] = _ => alwaysFail,
    upsertTriggerFn: SchedulerTrigger => RepositoryTask[SchedulerTrigger] = _ => alwaysFail,
    releaseJobFn:    (SchedulerJobId, JobStatus, Option[String], Instant) => RepositoryTask[Unit] = (
      _,
      _,
      _,
      _,
    ) => alwaysFail,
  ): ZIOSchedulerRepository =
    new ZIOSchedulerRepository {
      override def getJob(id:             SchedulerJobId):   RepositoryTask[Option[SchedulerJob]] = getJobFn(id)
      override def upsertJob(job:         SchedulerJob):     RepositoryTask[SchedulerJob] = upsertJobFn(job)
      override def deleteJob(id:          SchedulerJobId):   RepositoryTask[Long] = deleteJobFn(id)
      override def searchTriggers(s:      TriggerSearch):    RepositoryTask[List[SchedulerTrigger]] = searchTriggerFn(s)
      override def upsertTrigger(trigger: SchedulerTrigger): RepositoryTask[SchedulerTrigger] = upsertTriggerFn(trigger)
      override def listJobs(
        agentId: Option[AgentId],
        limit:   Int = 200,
      ):                                                  RepositoryTask[List[SchedulerJob]] = alwaysFail
      override def getPendingJobs:                        RepositoryTask[List[SchedulerJob]] = alwaysFail
      override def deleteTrigger(id: SchedulerTriggerId): RepositoryTask[Long] = alwaysFail
      override def claimJob(
        id:              SchedulerJobId,
        workerId:        String,
        now:             Instant,
        leaseTtlSeconds: Int,
      ): RepositoryTask[Boolean] = alwaysFail
      override def releaseJob(
        id:         SchedulerJobId,
        status:     JobStatus,
        resultJson: Option[String],
        finishedAt: Instant,
      ): RepositoryTask[Unit] = releaseJobFn(id, status, resultJson, finishedAt)
      override def expireLeases(olderThan: Instant): RepositoryTask[Long] = alwaysFail
    }

  private def managerLayer(schedulerRepo: ZIOSchedulerRepository): ULayer[JobManagerImpl] =
    InMemoryRepositories.live(schedulerRepoOpt = Some(schedulerRepo)) >>> ZLayer.fromFunction(JobManagerImpl(_))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("JobManagerImpl — failing repo mapError coverage")(
      test("createJob: upsertJob failure covers line-61 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr
            .createJob(agentId, userId, "j", None, 0, 60, RetryBackoffPolicy.Fixed, MissedRunPolicy.Skip).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo())),
      test("addTrigger: upsertTrigger failure covers line-68 lambda") {
        val trigger = SchedulerTrigger(
          id = SchedulerTriggerId.empty,
          jobId = SchedulerJobId(1L),
          triggerType = TriggerType.OneShot,
          expression = "",
          enabled = true,
          createdAt = Instant.EPOCH,
        )
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.addTrigger(SchedulerJobId(1L), trigger).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo())),
      test("listTriggers: searchTriggers failure covers line-71 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.listTriggers(SchedulerJobId(1L)).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo())),
      test("getJob: repo.getJob failure covers line-79 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.getJob(SchedulerJobId(1L)).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo())),
      test("pauseJob: upsertJob failure after getJob success covers line-88 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.pauseJob(anyJob.id).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo(getJobFn = _ => ZIO.succeed(Some(anyJob))))),
      test("resumeJob: upsertJob failure after getJob success covers line-97 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.resumeJob(pausedJob.id).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo(getJobFn = _ => ZIO.succeed(Some(pausedJob))))),
      test("cancelJob: releaseJob failure after getJob success covers line-107 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.cancelJob(anyJob.id).either
        } yield assertTrue(result.isLeft)
      }.provide(
        managerLayer(
          makeRepo(
            getJobFn = _ => ZIO.succeed(Some(anyJob)),
            releaseJobFn = (
              _,
              _,
              _,
              _,
            ) => ZIO.fail(repoError),
          ),
        ),
      ),
      test("triggerNow: upsertJob failure after getJob success covers line-117 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.triggerNow(anyJob.id).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo(getJobFn = _ => ZIO.succeed(Some(anyJob))))),
      test("deleteJob: repo.deleteJob failure covers line-121 lambda") {
        for {
          mgr    <- ZIO.service[JobManagerImpl]
          result <- mgr.deleteJob(SchedulerJobId(1L)).either
        } yield assertTrue(result.isLeft)
      }.provide(managerLayer(makeRepo())),
    )

}
