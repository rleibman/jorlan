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

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.Instant

enum JobStatus {

  case Pending, Running, Succeeded, Failed, Cancelled

}
object JobStatus {

  given JsonEncoder[JobStatus] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[JobStatus] =
    JsonDecoder[String].mapOrFail { s =>
      JobStatus.values.find(_.toString == s).toRight(s"Unknown JobStatus: $s")
    }

}

enum TriggerType {

  case Cron, Interval, OneShot, Event

}
object TriggerType {

  given JsonEncoder[TriggerType] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[TriggerType] =
    JsonDecoder[String].mapOrFail { s =>
      TriggerType.values.find(_.toString == s).toRight(s"Unknown TriggerType: $s")
    }

}

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
)
object SchedulerJob {

  given JsonEncoder[SchedulerJob] = DeriveJsonEncoder.gen[SchedulerJob]
  given JsonDecoder[SchedulerJob] = DeriveJsonDecoder.gen[SchedulerJob]

}

case class SchedulerTrigger(
  id:          SchedulerTriggerId,
  jobId:       SchedulerJobId,
  triggerType: TriggerType,
  expression:  String, // cron expression, ISO duration, or event name
  enabled:     Boolean = true,
  createdAt:   Instant,
)
object SchedulerTrigger {

  given JsonEncoder[SchedulerTrigger] = DeriveJsonEncoder.gen[SchedulerTrigger]
  given JsonDecoder[SchedulerTrigger] = DeriveJsonDecoder.gen[SchedulerTrigger]

}
