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

enum EventType {

  case AgentStarted, AgentCompleted, AgentFailed
  case SkillInvoked, SkillSucceeded, SkillFailed
  case ApprovalRequested, ApprovalGranted, ApprovalDenied
  case UserConnected, UserDisconnected
  case MemoryWritten, MemoryExpired
  case SystemAlert

}
object EventType {

  given JsonEncoder[EventType] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[EventType] =
    JsonDecoder[String].mapOrFail { s =>
      EventType.values.find(_.toString == s).toRight(s"Unknown EventType: $s")
    }

}

case class EventLog(
  id:           EventLogId,
  eventType:    EventType,
  actorId:      Option[UserId],
  agentId:      Option[AgentId],
  sessionId:    Option[AgentSessionId],
  resourceType: Option[String],
  resourceId:   Option[Long],
  payloadJson:  Option[String],
  occurredAt:   Instant,
)
object EventLog {

  given JsonEncoder[EventLog] = DeriveJsonEncoder.gen[EventLog]
  given JsonDecoder[EventLog] = DeriveJsonDecoder.gen[EventLog]

}
