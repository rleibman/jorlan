/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** Lifecycle state of a [[SchedulerJob]] execution run. */
enum JobStatus derives JsonEncoder, JsonDecoder {

  case Pending, Running, Succeeded, Failed, Cancelled

}

/** Determines how a [[SchedulerTrigger]] fires its associated [[SchedulerJob]]. */
enum TriggerType derives JsonEncoder, JsonDecoder {

  case Cron, Interval, OneShot, Event

}

/** A deferred or recurring agent invocation managed by the scheduler.
  *
  * @param inputJson
  *   JSON payload passed to the skill when the job fires; `None` means no input.
  * @param scheduledAt
  *   The next (or only) intended execution time as of the last scheduler tick.
  * @param resultJson
  *   JSON output of the last run; populated after `status` reaches `Succeeded` or `Failed`.
  */
case class SchedulerJob(
  id:          SchedulerJobId,
  agentId:     AgentId,
  skillId:     Option[SkillId],
  name:        String,
  inputJson:   Option[String],
  status:      JobStatus,
  scheduledAt: Instant,
  startedAt:   Option[Instant],
  finishedAt:  Option[Instant],
  resultJson:  Option[String],
  createdAt:   Instant,
) derives JsonEncoder, JsonDecoder

/** A schedule rule that fires a [[SchedulerJob]].
  *
  * @param expression
  *   Interpretation depends on `triggerType`: a cron expression (e.g. `"0 9 * * 1-5"`), an ISO 8601 duration (e.g.
  *   `"PT15M"`), or an event name for `Event`-type triggers.
  */
case class SchedulerTrigger(
  id:          SchedulerTriggerId,
  jobId:       SchedulerJobId,
  triggerType: TriggerType,
  expression: String, // cron expression, ISO duration, or event name. Is there a library or type we can use for this instead of a raw string?
  enabled:   Boolean = true,
  createdAt: Instant,
) derives JsonEncoder, JsonDecoder
