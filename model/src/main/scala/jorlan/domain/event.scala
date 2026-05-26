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

/** All observable platform events written to the append-only event log. Every significant action by an agent, user, or
  * system component emits at least one of these.
  */
enum EventType derives JsonEncoder, JsonDecoder {

  case AgentStarted, AgentCompleted, AgentFailed
  case SkillInvoked, SkillSucceeded, SkillFailed
  case ApprovalRequested, ApprovalGranted, ApprovalDenied
  case UserConnected, UserDisconnected
  case MemoryWritten, MemoryExpired
  case SystemAlert

}

/** An append-only audit record. Rows are never updated or deleted by application code.
  *
  * @param resourceType
  *   Name of the domain entity affected (e.g. `"Skill"`, `"MemoryRecord"`), if applicable.
  * @param resourceId
  *   Raw `Long` PK of the affected entity, independent of which opaque ID type it belongs to.
  * @param payloadJson
  *   Event-specific detail JSON (e.g. input/output summary, error message, diff).
  */
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
) derives JsonEncoder, JsonDecoder
