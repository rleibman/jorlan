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
import jorlan.*
import jorlan.service.JobManager
import zio.*

/** Service-layer implementation of [[JobManager]] backed by [[ZIOSchedulerRepository]].
  *
  * All state transitions validate the current job status before writing. `upsertJob` only updates runtime-state fields
  * (status, timestamps, lease, result); configuration fields are immutable after creation.
  */
class JobManagerImpl(
  repo:          ZIORepositories,
  listJobsLimit: Int = 200,
) extends JobManager {

  override def createJob(
    agentId:         AgentId,
    userId:          UserId,
    name:            String,
    prompt:          String,
    inputJson:       Option[String],
    maxRetries:      Int,
    backoffSeconds:  Int,
    backoffPolicy:   RetryBackoffPolicy,
    missedRunPolicy: MissedRunPolicy,
  ): IO[JorlanError, SchedulerJob] =
    for {
      now <- Clock.instant
      job <- repo.scheduler
        .upsertJob(
          SchedulerJob(
            id = SchedulerJobId.empty,
            agentId = agentId,
            userId = userId,
            skillId = None,
            name = name,
            prompt = prompt,
            inputJson = inputJson,
            status = JobStatus.Pending,
            scheduledAt = now,
            startedAt = None,
            finishedAt = None,
            resultJson = None,
            maxRetries = maxRetries,
            retryCount = 0,
            backoffSeconds = backoffSeconds,
            backoffPolicy = backoffPolicy,
            missedRunPolicy = missedRunPolicy,
            leasedAt = None,
            leasedBy = None,
            createdAt = now,
          ),
        )
        .mapError(JorlanError(_))
    } yield job

  override def addTrigger(
    jobId:   SchedulerJobId,
    trigger: SchedulerTrigger,
  ): IO[JorlanError, SchedulerTrigger] =
    repo.scheduler.upsertTrigger(trigger.copy(jobId = jobId)).mapError(JorlanError(_))

  override def listTriggers(jobId: SchedulerJobId): IO[JorlanError, List[SchedulerTrigger]] =
    repo.scheduler.searchTriggers(TriggerSearch(jobId = jobId, pageSize = 1000)).mapError(JorlanError(_))

  override def listJobs(agentId: Option[AgentId]): UIO[List[SchedulerJob]] =
    repo.scheduler.listJobs(agentId, listJobsLimit).orDie

  override def getJob(id: SchedulerJobId): IO[JorlanError, SchedulerJob] =
    repo.scheduler
      .getJob(id)
      .mapError(JorlanError(_))
      .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"Job ${id.value} not found")))

  override def pauseJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    getJob(id).flatMap { job =>
      val terminalStatuses = Set(JobStatus.Succeeded, JobStatus.Failed, JobStatus.Cancelled)
      if (terminalStatuses.contains(job.status)) {
        ZIO.fail(JorlanError(s"Cannot pause a ${job.status} job"))
      } else {
        repo.scheduler.upsertJob(job.copy(status = JobStatus.Paused)).mapError(JorlanError(_)).unit
      }
    }

  override def resumeJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    getJob(id).flatMap { job =>
      if (job.status != JobStatus.Paused) {
        ZIO.fail(JorlanError(s"Cannot resume a job with status ${job.status}; expected Paused"))
      } else {
        repo.scheduler.upsertJob(job.copy(status = JobStatus.Pending)).mapError(JorlanError(_)).unit
      }
    }

  override def cancelJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    for {
      now <- Clock.instant
      job <- getJob(id)
      _   <- repo.scheduler
        .releaseJob(id, JobStatus.Cancelled, None, now)
        .mapError(JorlanError(_))
        .unless(job.status == JobStatus.Cancelled)
    } yield ()

  override def triggerNow(id: SchedulerJobId): IO[JorlanError, Unit] =
    for {
      now <- Clock.instant
      job <- getJob(id)
      _   <- repo.scheduler
        .upsertJob(job.released(JobStatus.Pending, now))
        .mapError(JorlanError(_))
    } yield ()

  override def deleteJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    repo.scheduler.deleteJob(id).mapError(JorlanError(_)).unit

}

object JobManagerImpl {

  val live: URLayer[ZIORepositories & ConfigurationService, JobManagerImpl] =
    ZLayer.fromZIO {
      for {
        repo   <- ZIO.service[ZIORepositories]
        config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie
      } yield JobManagerImpl(repo, config.jorlan.scheduler.listJobsLimit)
    }

}
