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

import zio.json.ast.Json
import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** All observable platform events written to the append-only event log. Every significant action by an agent, user, or
  * system component emits at least one of these.
  */
enum EventType derives JsonEncoder, JsonDecoder {

  case AgentStarted, AgentCompleted, AgentFailed
  case SessionCreated
  case UserMessageReceived, AgentResponseCompleted
  case ModelCallStarted, ModelCallCompleted, ModelCallFailed
  case SkillInvoked, SkillSucceeded, SkillFailed
  case ApprovalRequested, ApprovalGranted, ApprovalDenied
  case UserCreated, UserUpdated
  case UserConnected, UserDisconnected
  case MemoryWritten, MemoryExpired
  case RoleAssigned, RoleRevoked
  case CapabilityAllowed, CapabilityDenied
  case CapabilityGranted, CapabilityRevoked
  case PermissionGranted, PermissionRevoked
  case SystemAlert

}

/** An append-only audit record. Rows are never updated or deleted by application code.
  *
  * @param resource
  *   The typed domain entity affected by this event. Callers provide the concrete ID type (e.g. `AgentId`,
  *   `SkillVersionId`) when appending; the repository stores it as JSON and returns it as raw `Json` on reads.
  * @param payloadJson
  *   Event-specific detail (e.g. input/output summary, error message, diff).
  */
case class EventLog[R](
  id:          EventLogId,
  eventType:   EventType,
  actorId:     Option[UserId],
  agentId:     Option[AgentId],
  sessionId:   Option[AgentSessionId],
  resource:    Option[R],
  payloadJson: Option[Json],
  occurredAt:  Instant,
) derives JsonEncoder, JsonDecoder
