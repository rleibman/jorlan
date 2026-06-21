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

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/** Numeric risk level for a capability invocation request, 0 = safest, 5 = most dangerous.
  *
  * Mirrors the shell command risk table in the design document and generalises it to all capabilities.
  */
enum RiskClass(val level: Int) derives JsonCodec {

  /** Read-only inspection — no side effects (e.g. `filesystem.read`, `memory.search`). */
  case ReadOnly extends RiskClass(0)

  /** Workspace-local write — reversible, contained (e.g. `filesystem.write`, `memory.write`). */
  case WorkspaceWrite extends RiskClass(1)

  /** Destructive local operation — hard to reverse (e.g. `filesystem.delete`, `memory.forget`). */
  case Destructive extends RiskClass(2)

  /** External side effect — affects systems outside the workspace (e.g. `network.post`, `shell.execute`). */
  case ExternalEffect extends RiskClass(3)

  /** Privileged / system operation (e.g. `shell.interactive.start`, `role.assign`). */
  case Privileged extends RiskClass(4)

  /** Credential / payment / security-sensitive (e.g. `shell.sudo.execute`, `capability.grant`). */
  case SecuritySensitive extends RiskClass(5)

}

object RiskClass {

  def fromLevel(level: Int): Option[RiskClass] = values.find(_.level == level)

}

/** Describes a runtime request to use a named capability. Produced by the agent orchestrator before every tool
  * invocation; consumed by [[jorlan.service.ApprovalService]].
  */
case class CapabilityRequest(
  capability:          CapabilityName,
  requestorId:         UserId,
  agentId:             Option[AgentId],
  sessionId:           Option[AgentSessionId],
  resourceConstraints: Option[String],
)

/** The specific policy source that determined the outcome of a [[jorlan.service.CapabilityEvaluator]] check.
  *
  * Evaluation follows a strict priority order (explicit deny first, default deny last); the first matching source wins.
  */
enum EvaluationResult {

  /** A [[CapabilityGrant]] row with [[ApprovalMode.Denied]] exists for this user + capability. */
  case ExplicitDeny

  /** A direct [[Permission]] row (user-scoped) allows this resource + action. */
  case ResourcePermissionAllows

  /** A role-based [[Permission]] row (via `userRole` join) allows this resource + action. */
  case RolePermissionAllows

  /** A [[CapabilityGrant]] exists; `grant.approvalMode` determines whether a human must approve. */
  case CapabilityGrantAllows(grant: CapabilityGrant)

  /** A connector-level policy allows the action (stub; not yet implemented). */
  case ConnectorPolicyAllows

  /** A skill-level policy allows the action (stub; not yet implemented). */
  case SkillPolicyAllows

  /** No policy source granted the capability — deny by default. */
  case DefaultDeny

}

/** The final decision produced by [[jorlan.service.ApprovalPolicyEngine]] for a capability request. */
enum AuthorizationResult {

  /** The capability is unconditionally granted; execution may proceed. */
  case Allowed

  /** A human must approve before execution. An [[ApprovalRequest]] has been persisted with `status = Pending`; the
    * caller must wait for a decision via [[jorlan.service.ApprovalService.recordDecision]].
    */
  case PendingApproval(
    request: ApprovalRequest,
    mode:    ApprovalMode,
  )

  /** The capability is denied; execution must not proceed. */
  case Denied(reason: String)

}
