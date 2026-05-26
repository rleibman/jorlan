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

import jorlan.Codecs.given
import zio.json.{JsonDecoder, JsonEncoder}
import zio.json.ast.Json

import java.time.Instant

/** A named bundle of permissions that can be assigned to users. */
case class Role(
  id:          RoleId,
  name:        String,
  description: Option[String],
) derives JsonEncoder, JsonDecoder

/** A direct permission granting a specific action on a resource, attached to either a role or a user.
  *
  * @param scope
  *   Optional JSON scope constraint (e.g. restricts to a specific workspace or agent).
  */
case class Permission(
  id:       PermissionId,
  roleId:   Option[RoleId],
  userId:   Option[UserId],
  resource: String,
  action:   String,
  scope:    Option[Json],
) derives JsonEncoder, JsonDecoder

/** Controls how strictly an agent must seek user approval before using a capability.
  *
  *   - `Denied` — permanently disallowed; no approval path.
  *   - `PerInvocation` — must ask the user every single time.
  *   - `Once` — user approves once and the grant persists indefinitely.
  *   - `Session` — approval holds for the duration of the current agent session.
  *   - `Timed` — approval holds until `CapabilityGrant.expiresAt`.
  *   - `Persistent` — granted without any approval required (highest trust).
  */
enum ApprovalMode derives JsonEncoder, JsonDecoder {

  case Denied, PerInvocation, Once, Session, Timed, Persistent

}

/** Records that a user has been granted a named capability, possibly scoped and time-limited.
  *
  * @param capability
  *   Dot-separated capability name, e.g. `shell.execute` or `memory.write`.
  * @param scopeJson
  *   Optional JSON blob further restricting what the grant covers (e.g. specific workspace).
  * @param grantorId
  *   The user who made the grant; `None` for system-issued grants.
  * @param resourceConstraints
  *   Optional JSON describing which specific resources the grant applies to.
  */
case class CapabilityGrant(
  id:                  CapabilityGrantId,
  capability:          String,
  scopeJson:           Option[String],
  granteeId:           UserId,
  grantorId:           Option[UserId],
  approvalMode:        ApprovalMode,
  expiresAt:           Option[Instant],
  resourceConstraints: Option[String],
  createdAt:           Instant,
) derives JsonEncoder, JsonDecoder

/** Lifecycle state of an [[ApprovalRequest]]. */
enum ApprovalStatus derives JsonEncoder, JsonDecoder {

  case Pending, Approved, Rejected, Expired, Cancelled

}

/** An in-flight request for a user to approve use of a capability. Created by the capability evaluator when
  * `ApprovalMode` is not `Persistent` or `Denied`.
  *
  * @param riskClass
  *   0–5 risk classification; higher means more dangerous. Influences required approval mode.
  * @param status
  *   Mutable; transitions through `Pending → Approved | Denied | Expired | Cancelled`.
  */
case class ApprovalRequest(
  id:              ApprovalRequestId,
  capability:      String,
  scopeJson:       Option[String],
  agentId:         Option[AgentId],
  requestorUserId: UserId,
  sessionId:       Option[AgentSessionId],
  riskClass:       Int,
  status:          ApprovalStatus,
  createdAt:       Instant,
  expiresAt:       Option[Instant],
) derives JsonEncoder, JsonDecoder

/** Records a user's response to an [[ApprovalRequest]].
  *
  * @param scopeOverride
  *   If the approving user narrows or redirects the scope, the override JSON is stored here and used instead of the
  *   original `scopeJson` when enforcing the grant.
  */
case class ApprovalDecision(
  id:                ApprovalDecisionId,
  approvalRequestId: ApprovalRequestId,
  decidedBy:         UserId,
  decision:          ApprovalStatus,
  scopeOverride:     Option[String],
  decidedAt:         Instant,
) derives JsonEncoder, JsonDecoder
