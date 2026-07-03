/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

/** Whether a [[PipelineStep]] runs the full ReAct tool-calling loop or makes a single LLM call. */
enum StepMode derives JsonCodec {

  /** Full ReAct loop: model may call tools in multiple rounds before producing output. */
  case ReactLoop

  /** Single LLM call: no tools, used for pure-reasoning steps. Cheaper and faster. */
  case SingleCall

}

/** One step in an ordered [[Pipeline]] execution.
  *
  * @param name
  *   Human-readable label used as the output variable key (e.g. `"gather-context"`).
  * @param systemPrompt
  *   Instructions specific to this step's responsibility.
  * @param userPrompt
  *   The message sent to the LLM; may reference `{{invariants.KEY}}`, `{{steps.NAME.output}}`, `{{now}}`, etc.
  * @param tools
  *   Tool namespace allowlist (e.g. `List("calendar", "weather")`). Empty means no tools.
  * @param mode
  *   `ReactLoop` for tool-using steps; `SingleCall` for pure-reasoning steps.
  * @param outputVar
  *   Key under which this step's final output is stored in the pipeline context map.
  * @param retryOnFail
  *   Number of automatic retries before halting the pipeline. 0 = no retry.
  */
case class PipelineStep(
  name:         String,
  systemPrompt: String,
  userPrompt:   String,
  tools:        List[String] = List.empty,
  mode:         StepMode = StepMode.ReactLoop,
  outputVar:    String,
  retryOnFail:  Int = 0,
) derives JsonCodec

/** An ordered sequence of [[PipelineStep]]s that constitutes a scheduled job.
  *
  * @param steps
  *   Ordered list of steps; executed sequentially.
  * @param invariants
  *   Pipeline-level key-value facts injected into every step. Override agent-level invariants with the same key.
  * @param personality
  *   Optional named accuracy personality. `None` activates accuracy mode (minimal, instruction-following system
  *   prompt).
  */
case class Pipeline(
  steps:       List[PipelineStep],
  invariants:  Map[String, String] = Map.empty,
  personality: Option[String] = None,
) derives JsonCodec

/** Lifecycle status of a [[PipelineRun]]. */
enum PipelineRunStatus derives JsonCodec {

  /** The pipeline is currently executing. */
  case Running

  /** All steps completed successfully. */
  case Succeeded

  /** A step failed and retries were exhausted; subsequent steps did not run. */
  case FailedAtStep

  /** The run was explicitly cancelled. */
  case Cancelled

}

/** A single execution instance of a pipeline job.
  *
  * @param id
  *   Auto-assigned on insert; use [[PipelineRunId.empty]] for new records.
  * @param jobId
  *   The [[SchedulerJob]] that owns this run.
  * @param status
  *   Lifecycle state of the run.
  * @param runContext
  *   Optional free-form text provided at manual trigger time. Available as `{{run.context}}` in every step.
  * @param contextJson
  *   JSON snapshot of the accumulated step-output context map at the time of last update.
  * @param failedStep
  *   Name of the step that caused the run to fail, if `status == FailedAtStep`.
  * @param startedAt
  *   Wall-clock time the run began.
  * @param finishedAt
  *   Populated when the run reaches a terminal state.
  */
case class PipelineRun(
  id:          PipelineRunId,
  jobId:       SchedulerJobId,
  status:      PipelineRunStatus,
  runContext:  Option[String],
  contextJson: Option[String],
  failedStep:  Option[String],
  startedAt:   Instant,
  finishedAt:  Option[Instant],
) derives JsonCodec

/** Lifecycle state of a [[SchedulerJob]] execution run. */
enum JobStatus derives JsonCodec {

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
enum TriggerType derives JsonCodec {

  case Cron, Interval, OneShot

  /** Fired externally via the event bus; the TriggerEngine does not advance Event-type triggers automatically. */
  case Event

}

/** Determines what to do when a scheduled job run was missed (e.g. server was down). */
enum MissedRunPolicy derives JsonCodec {

  /** Silently skip missed executions and advance to the next scheduled time. */
  case Skip

  /** Run exactly once for all missed executions, then advance. */
  case RunOnce

  /** Queue one execution per missed window (capped at 10 to prevent storms). */
  case RunAllMissed

}

/** Backoff strategy when retrying a failed job. */
enum RetryBackoffPolicy derives JsonCodec {

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
  *   Human-readable unique label for this job.
  * @param pipeline
  *   The ordered sequence of steps this job executes on each trigger. Every job is a pipeline; a single-step pipeline
  *   is the equivalent of a traditional single-prompt job.
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
  agentId:         Option[AgentId],
  userId:          UserId,
  skillId:         Option[SkillId],
  name:            String,
  pipeline:        Pipeline,
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
) derives JsonCodec

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
) derives JsonCodec
