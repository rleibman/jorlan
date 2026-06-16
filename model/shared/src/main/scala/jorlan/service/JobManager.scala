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

import jorlan.{
  AgentId,
  JorlanError,
  MissedRunPolicy,
  RetryBackoffPolicy,
  SchedulerJob,
  SchedulerJobId,
  SchedulerTrigger,
  UserId,
}
import jorlan.*
import zio.*

/** Manages the lifecycle of scheduled jobs and their triggers.
  *
  * All repository writes go through this trait so that the GraphQL layer never calls the scheduler repository directly.
  */
trait JobManager {

  /** Create a new [[SchedulerJob]] in `Pending` state.
    *
    * @param agentId
    *   The agent that will run this job.
    * @param userId
    *   The user on whose behalf the job runs (used to create the execution session).
    * @param name
    *   Human-readable label; not required to be unique.
    * @param inputJson
    *   Optional JSON payload passed verbatim to the agent when the job fires. Callers are responsible for ensuring this
    *   is valid JSON and within a reasonable size (recommend ≤ 64 KB).
    * @param maxRetries
    *   Number of additional attempts on failure (0 = no retry).
    * @param backoffSeconds
    *   Base delay in seconds between retries. Must be > 0 when `maxRetries > 0`.
    * @param backoffPolicy
    *   Fixed or exponential retry delay.
    * @param missedRunPolicy
    *   How to handle runs that were missed while the server was offline.
    */
  def createJob(
    agentId:         AgentId,
    userId:          UserId,
    name:            String,
    inputJson:       Option[String],
    maxRetries:      Int,
    backoffSeconds:  Int,
    backoffPolicy:   RetryBackoffPolicy,
    missedRunPolicy: MissedRunPolicy,
  ): IO[JorlanError, SchedulerJob]

  /** Attach a trigger to an existing job.
    *
    * Note: `trigger.jobId` is overwritten with `jobId` — pass `SchedulerJobId.empty` on the trigger or the correct
    * `jobId` value; either will be replaced. The explicit `jobId` parameter is authoritative.
    */
  def addTrigger(
    jobId:   SchedulerJobId,
    trigger: SchedulerTrigger,
  ): IO[JorlanError, SchedulerTrigger]

  /** List all triggers attached to the given job.
    *
    * @param jobId
    *   The job whose triggers to return.
    */
  def listTriggers(jobId: SchedulerJobId): IO[JorlanError, List[SchedulerTrigger]]

  /** List jobs, optionally filtered by owning agent.
    *
    * @param agentId
    *   `Some(id)` to return only jobs for that agent; `None` to return all jobs (unfiltered, up to repository limit).
    */
  def listJobs(agentId: Option[AgentId]): UIO[List[SchedulerJob]]

  /** Retrieve a single job by ID.
    *
    * @return
    *   The job if found.
    * @throws JorlanError
    *   if no job with the given ID exists.
    */
  def getJob(id: SchedulerJobId): IO[JorlanError, SchedulerJob]

  /** Set the job's status to `Paused`.
    *
    * Only valid from `Pending` or `Running` status. Fails with [[JorlanError]] if the job is already in a terminal
    * state (`Succeeded`, `Failed`, `Cancelled`).
    */
  def pauseJob(id: SchedulerJobId): IO[JorlanError, Unit]

  /** Set the job's status back to `Pending` from `Paused`. Fails if the job is not currently `Paused`. */
  def resumeJob(id: SchedulerJobId): IO[JorlanError, Unit]

  /** Cancel the job. Idempotent: calling on an already-`Cancelled` job succeeds without re-writing the row. */
  def cancelJob(id: SchedulerJobId): IO[JorlanError, Unit]

  /** Force a job to run immediately by setting its `scheduledAt` to now and status to `Pending`. */
  def triggerNow(id: SchedulerJobId): IO[JorlanError, Unit]

  /** Permanently delete the job and all of its triggers. */
  def deleteJob(id: SchedulerJobId): IO[JorlanError, Unit]

}

object JobManager {

  def createJob(
    agentId:         AgentId,
    userId:          UserId,
    name:            String,
    inputJson:       Option[String],
    maxRetries:      Int = 0,
    backoffSeconds:  Int = 60,
    backoffPolicy:   RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedRunPolicy: MissedRunPolicy = MissedRunPolicy.Skip,
  ): ZIO[JobManager, JorlanError, SchedulerJob] =
    ZIO.serviceWithZIO[JobManager](
      _.createJob(agentId, userId, name, inputJson, maxRetries, backoffSeconds, backoffPolicy, missedRunPolicy),
    )

  def addTrigger(
    jobId:   SchedulerJobId,
    trigger: SchedulerTrigger,
  ): ZIO[JobManager, JorlanError, SchedulerTrigger] =
    ZIO.serviceWithZIO[JobManager](_.addTrigger(jobId, trigger))

  def listTriggers(jobId: SchedulerJobId): ZIO[JobManager, JorlanError, List[SchedulerTrigger]] =
    ZIO.serviceWithZIO[JobManager](_.listTriggers(jobId))

  def listJobs(agentId: Option[AgentId]): URIO[JobManager, List[SchedulerJob]] =
    ZIO.serviceWithZIO[JobManager](_.listJobs(agentId))

  def getJob(id: SchedulerJobId): ZIO[JobManager, JorlanError, SchedulerJob] =
    ZIO.serviceWithZIO[JobManager](_.getJob(id))

  def pauseJob(id: SchedulerJobId): ZIO[JobManager, JorlanError, Unit] =
    ZIO.serviceWithZIO[JobManager](_.pauseJob(id))

  def resumeJob(id: SchedulerJobId): ZIO[JobManager, JorlanError, Unit] =
    ZIO.serviceWithZIO[JobManager](_.resumeJob(id))

  def cancelJob(id: SchedulerJobId): ZIO[JobManager, JorlanError, Unit] =
    ZIO.serviceWithZIO[JobManager](_.cancelJob(id))

  def triggerNow(id: SchedulerJobId): ZIO[JobManager, JorlanError, Unit] =
    ZIO.serviceWithZIO[JobManager](_.triggerNow(id))

  def deleteJob(id: SchedulerJobId): ZIO[JobManager, JorlanError, Unit] =
    ZIO.serviceWithZIO[JobManager](_.deleteJob(id))

}
