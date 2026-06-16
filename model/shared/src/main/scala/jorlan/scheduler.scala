/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** Lifecycle state of a [[SchedulerJob]] execution run. */
enum JobStatus derives JsonEncoder, JsonDecoder {

  /** Waiting to be claimed by the TriggerEngine on the next poll. */
  case Pending

  /** Claimed by a worker; a lease is held. */
  case Running

  /** Execution completed without error. */
  case Succeeded

  /** Execution failed and retries are exhausted. */
  case Failed

  /** Manually cancelled; will not be re-queued. */
  case Cancelled

  /** Manually paused; skipped by the TriggerEngine until resumed. */
  case Paused

}

/** Determines how a [[SchedulerTrigger]] fires its associated [[SchedulerJob]]. */
enum TriggerType derives JsonEncoder, JsonDecoder {

  case Cron, Interval, OneShot

  /** Fired externally via the event bus; the TriggerEngine does not advance Event-type triggers automatically. */
  case Event

}

/** Determines what to do when a scheduled job run was missed (e.g. server was down). */
enum MissedRunPolicy derives JsonEncoder, JsonDecoder {

  /** Silently skip missed executions and advance to the next scheduled time. */
  case Skip

  /** Run exactly once for all missed executions, then advance. */
  case RunOnce

  /** Queue one execution per missed window (capped at 10 to prevent storms). */
  case RunAllMissed

}

/** Backoff strategy when retrying a failed job. */
enum RetryBackoffPolicy derives JsonEncoder, JsonDecoder {

  /** Wait exactly `backoffSeconds` between retries. */
  case Fixed

  /** Double the wait time on each retry: `backoffSeconds * 2^retryCount`. */
  case Exponential

}

/** A deferred or recurring agent invocation managed by the scheduler.
  *
  * @param id
  *   Auto-assigned by the repository on insert; use [[SchedulerJobId.empty]] when constructing new records.
  * @param agentId
  *   The agent that owns this job and will be used to create the execution session.
  * @param userId
  *   The user on whose behalf the job runs. Used to create the agent session at execution time.
  * @param skillId
  *   Reserved for Phase 12 skill-registry integration; always `None` for now.
  * @param name
  *   Human-readable label. Not a unique key.
  * @param inputJson
  *   JSON payload passed to the agent when the job fires; `None` means no input.
  * @param status
  *   Current lifecycle state; see [[JobStatus]].
  * @param scheduledAt
  *   The next (or only) intended execution time as of the last scheduler tick.
  * @param startedAt
  *   Populated when a worker begins execution (after claiming the lease).
  * @param finishedAt
  *   Populated when execution reaches a terminal state (`Succeeded`, `Failed`, or `Cancelled`).
  * @param resultJson
  *   JSON output of the last run; populated after `status` reaches `Succeeded` or `Failed`.
  * @param maxRetries
  *   Maximum number of retry attempts after failure. 0 = no retry.
  * @param retryCount
  *   Number of retry attempts made so far.
  * @param backoffSeconds
  *   Base wait in seconds between retries (actual wait depends on `backoffPolicy`).
  * @param backoffPolicy
  *   Whether retries use a fixed or exponential delay; see [[RetryBackoffPolicy]].
  * @param missedRunPolicy
  *   What to do when the engine detects a missed run window; see [[MissedRunPolicy]].
  * @param leasedAt
  *   When the current worker claimed this job to execute; `None` if unclaimed.
  * @param leasedBy
  *   Worker identifier (hostname:pid) that holds the current lease; `None` if unclaimed.
  * @param createdAt
  *   Wall-clock time the job was first persisted.
  */
case class SchedulerJob(
  id:              SchedulerJobId,
  agentId:         AgentId,
  userId:          UserId,
  skillId:         Option[SkillId],
  name:            String,
  inputJson:       Option[String],
  status:          JobStatus,
  scheduledAt:     Instant,
  startedAt:       Option[Instant],
  finishedAt:      Option[Instant],
  resultJson:      Option[String],
  maxRetries:      Int,
  retryCount:      Int,
  backoffSeconds:  Int,
  backoffPolicy:   RetryBackoffPolicy,
  missedRunPolicy: MissedRunPolicy,
  leasedAt:        Option[Instant],
  leasedBy:        Option[String],
  createdAt:       Instant,
) derives JsonEncoder, JsonDecoder

object SchedulerJob {

  extension (job: SchedulerJob) {

    /** Return a copy of this job cleared for re-queuing: sets the given status and scheduled time, and erases the lease
      * fields. Use this whenever re-queuing to ensure `leasedAt`/`leasedBy` are always cleared together.
      */
    def released(
      newStatus:   JobStatus,
      scheduledAt: Instant,
    ): SchedulerJob =
      job.copy(status = newStatus, scheduledAt = scheduledAt, leasedAt = None, leasedBy = None)

    /** Validate domain invariants. Returns Left with a descriptive message if any constraint is violated. */
    def validate: Either[String, SchedulerJob] = {
      if (job.maxRetries < 0) Left(s"maxRetries must be >= 0, got ${job.maxRetries}")
      else if (job.maxRetries > 0 && job.backoffSeconds <= 0)
        Left(s"backoffSeconds must be > 0 when maxRetries > 0, got ${job.backoffSeconds}")
      else Right(job)
    }

  }

}

/** A schedule rule that fires a [[SchedulerJob]].
  *
  * @param id
  *   Auto-assigned by the repository on insert; use [[SchedulerTriggerId.empty]] when constructing new records.
  * @param jobId
  *   The parent job this trigger fires.
  * @param triggerType
  *   Determines how `expression` is interpreted; see [[TriggerType]].
  * @param expression
  *   Interpretation depends on `triggerType`: a cron expression (e.g. `"0 9 * * 1-5"`), an ISO 8601 duration (e.g.
  *   `"PT15M"`), an ISO 8601 instant for OneShot (e.g. `"2026-12-01T09:00:00Z"`), or an event name for `Event`-type
  *   triggers.
  * @param enabled
  *   When `false`, this trigger is skipped by the TriggerEngine. OneShot triggers are disabled after their first run.
  * @param createdAt
  *   Wall-clock time the trigger was first persisted.
  */
case class SchedulerTrigger(
  id:          SchedulerTriggerId,
  jobId:       SchedulerJobId,
  triggerType: TriggerType,
  expression:  String, // Should this be some sort of CronExpression or something less Stringy?
  enabled:     Boolean = true,
  createdAt:   Instant,
) derives JsonEncoder, JsonDecoder
