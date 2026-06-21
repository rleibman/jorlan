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

import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

/** All observable platform events written to the append-only event log. Every significant action by an agent, user, or
  * system component emits at least one of these.
  */
enum EventType derives JsonCodec {

  // ─── Agent / session lifecycle ───────────────────────────────────────────────
  case AgentStarted, AgentCompleted, AgentFailed
  case SessionCreated, SessionSuspended, SessionTerminated

  // ─── Message and model call cycle ────────────────────────────────────────────
  case UserMessageReceived, AgentResponseCompleted
  case ModelCallStarted, ModelCallCompleted, ModelCallFailed

  // ─── Skill invocations ───────────────────────────────────────────────────────
  case SkillInvoked, SkillSucceeded, SkillFailed, ToolLoopExceeded

  // ─── Shell command lifecycle ─────────────────────────────────────────────────
  case ShellCommandInvoked, ShellCommandCompleted

  // ─── Approval lifecycle ──────────────────────────────────────────────────────
  case ApprovalRequested, ApprovalGranted, ApprovalDenied

  // ─── Identity and connectivity ───────────────────────────────────────────────
  case UserCreated, UserUpdated
  case UserConnected, UserDisconnected

  // ─── Memory ──────────────────────────────────────────────────────────────────
  case MemoryWritten, MemoryDeleted, MemoryRescoped, MemoryCheckpointed, MemoryExpired

  // ─── RBAC and capabilities ───────────────────────────────────────────────────
  case RoleAssigned, RoleRevoked
  case CapabilityAllowed, CapabilityDenied
  case CapabilityGranted, CapabilityRevoked
  case PermissionGranted, PermissionRevoked

  // ─── Scheduler ───────────────────────────────────────────────────────────────
  case SchedulerJobQueued, SchedulerJobStarted, SchedulerJobCompleted, SchedulerJobFailed, SchedulerJobCancelled
  case SchedulerJobPaused, SchedulerJobResumed, SchedulerJobTriggered, SchedulerJobDeleted
  case SchedulerTriggerAdded

  // ─── Email ───────────────────────────────────────────────────────────────────
  case EmailMessageRead, EmailMessageSent, EmailDraftCreated, EmailMessageArchived, EmailMessageDeleted

  // ─── Calendar ────────────────────────────────────────────────────────────────
  case CalendarEventRead, CalendarEventCreated, CalendarEventUpdated, CalendarEventDeleted

  // ─── Drive ───────────────────────────────────────────────────────────────────
  case DriveFileRead, DriveFileListed

  // ─── System ──────────────────────────────────────────────────────────────────
  case SystemAlert
  case ServerInitialized

}

object EventLog {

  // Future: consider removing this constructor, I don't see why it's needed
  /** Smart constructor that supplies the invariant defaults (`id = EventLogId.empty`, `agentId = None`,
    * `payloadJson = None`) so callers do not have to repeat them.
    */
  def entry[R](
    eventType: EventType,
    actorId:   Option[UserId],
    agentId:   Option[AgentId],
    sessionId: Option[AgentSessionId],
    resource:  Option[R],
    now:       Instant,
  ): EventLog[R] =
    EventLog(
      id = EventLogId.empty,
      eventType = eventType,
      actorId = actorId,
      agentId = agentId,
      sessionId = sessionId,
      resource = resource,
      payloadJson = None,
      occurredAt = now,
    )

}

/** An append-only audit record. Rows are never updated or deleted by application code.
  *
  * @param id
  *   Auto-assigned by the repository on insert; use [[EventLogId.empty]] when constructing new records.
  * @param eventType
  *   The kind of event; determines how `resource` and `payloadJson` should be interpreted.
  * @param actorId
  *   The human user who triggered this event, if applicable.
  * @param agentId
  *   The agent involved in this event, if applicable.
  * @param sessionId
  *   The session context, if the event occurred within a session.
  * @param resource
  *   The typed domain entity affected by this event. Callers provide the concrete ID type (e.g. `AgentId`,
  *   `SkillVersionId`) when appending; the repository stores it as JSON and returns it as raw `Json` on reads.
  * @param payloadJson
  *   Event-specific detail (e.g. input/output summary, error message, diff).
  * @param occurredAt
  *   Wall-clock time of the event, sourced from ZIO `Clock` (never `Instant.now()`).
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
) derives JsonCodec
