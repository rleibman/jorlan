/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
    *   Unique human-readable label for this job.
    * @param pipeline
    *   The ordered sequence of steps this job executes on each trigger. Must have at least one step.
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
    agentId:         Option[AgentId],
    userId:          UserId,
    name:            String,
    pipeline:        Pipeline,
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
  def listJobs(agentId: Option[AgentId]): IO[JorlanError, List[SchedulerJob]]

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

  /** Update the mutable metadata of a job (name, retry settings). Status, timestamps, and pipeline content are
    * unchanged — see `updateJobPipeline` (server repository layer) for pipeline content updates.
    */
  def updateJob(
    id:              SchedulerJobId,
    name:            String,
    maxRetries:      Int,
    backoffSeconds:  Int,
    backoffPolicy:   RetryBackoffPolicy,
    missedRunPolicy: MissedRunPolicy,
  ): IO[JorlanError, SchedulerJob]

  /** Remove a trigger from its job. */
  def deleteTrigger(id: SchedulerTriggerId): IO[JorlanError, Unit]

  /** Manually trigger a pipeline job with an optional run context.
    *
    * Creates a [[PipelineRun]] record, marks the job `Pending`, and stores the run context so the TriggerEngine can
    * inject it into template substitution.
    *
    * @param jobId
    *   The job to trigger.
    * @param runContext
    *   Optional free-form instructions for this specific run (available as `{{run.context}}` in every step).
    * @return
    *   The ID of the newly created [[PipelineRun]] record.
    */
  def triggerPipeline(
    jobId:      SchedulerJobId,
    runContext: Option[String],
  ): IO[JorlanError, PipelineRunId]

  /** List all [[PipelineRun]]s for the given job, ordered by `startedAt` descending. */
  def pipelineRuns(jobId: SchedulerJobId): IO[JorlanError, List[PipelineRun]]

}

object JobManager
