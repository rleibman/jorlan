/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.{
  ApprovalMode,
  ApprovalRequest,
  ApprovalRequestId,
  ApprovalStatus,
  AuthorizationResult,
  CapabilityGrant,
  CapabilityRequest,
  EvaluationResult,
  RiskClass,
}
import jorlan.*

import java.time.Instant

/** Combines an [[EvaluationResult]] and a [[RiskClass]] into a final [[AuthorizationResult]].
  *
  * This is a pure decision function — all DB lookups are performed by the caller ([[ApprovalService]]) before invoking
  * `decide`. `existingApprovals` contains any already-approved [[ApprovalRequest]] rows for this capability + user (
  * and optionally session), pre-loaded so this method stays side-effect free.
  *
  * `now` is passed explicitly so callers in non-ZIO contexts can supply a fixed instant (test friendliness).
  *
  * Decision rules:
  *   - `ExplicitDeny` / `DefaultDeny` → `Denied`
  *   - `ResourcePermissionAllows` / `RolePermissionAllows` → `Allowed`
  *   - `ConnectorPolicyAllows` / `SkillPolicyAllows` → `Allowed` (stubs are permissive by design)
  *   - `CapabilityGrantAllows(grant)` — resolved by the grant's [[ApprovalMode]]
  */
object ApprovalPolicyEngine {

  def decide(
    request:           CapabilityRequest,
    evaluation:        EvaluationResult,
    riskClass:         RiskClass,
    existingApprovals: List[ApprovalRequest],
    now:               Instant,
  ): AuthorizationResult =
    evaluation match {
      case EvaluationResult.ExplicitDeny => AuthorizationResult.Denied("explicitly denied by capability grant")
      case EvaluationResult.DefaultDeny  => AuthorizationResult.Denied("no matching policy — default deny")

      case EvaluationResult.ResourcePermissionAllows | EvaluationResult.RolePermissionAllows |
          EvaluationResult.ConnectorPolicyAllows | EvaluationResult.SkillPolicyAllows =>
        AuthorizationResult.Allowed

      case EvaluationResult.CapabilityGrantAllows(grant) =>
        grant.approvalMode match {
          case ApprovalMode.Denied        => AuthorizationResult.Denied("capability grant has ApprovalMode.Denied")
          case ApprovalMode.Persistent    => AuthorizationResult.Allowed
          case ApprovalMode.PerInvocation =>
            AuthorizationResult.PendingApproval(
              buildRequest(request, grant, riskClass, now),
              ApprovalMode.PerInvocation,
            )
          case ApprovalMode.Timed =>
            grant.expiresAt match {
              case Some(exp) if exp.isAfter(now) => AuthorizationResult.Allowed
              case _                             =>
                AuthorizationResult.PendingApproval(
                  buildRequest(request, grant, riskClass, now, expiresAt = grant.expiresAt),
                  ApprovalMode.Timed,
                )
            }
          case ApprovalMode.Once =>
            val hasApproved = existingApprovals.exists(_.status == ApprovalStatus.Approved)
            if (hasApproved) AuthorizationResult.Allowed
            else AuthorizationResult.PendingApproval(buildRequest(request, grant, riskClass, now), ApprovalMode.Once)
          case ApprovalMode.Session =>
            val sessionMatch = request.sessionId.isDefined &&
              existingApprovals.exists(r => r.sessionId == request.sessionId && r.status == ApprovalStatus.Approved)
            if (sessionMatch) AuthorizationResult.Allowed
            else
              AuthorizationResult.PendingApproval(buildRequest(request, grant, riskClass, now), ApprovalMode.Session)
        }
    }

  private def buildRequest(
    request:   CapabilityRequest,
    grant:     CapabilityGrant,
    riskClass: RiskClass,
    now:       Instant,
    expiresAt: Option[Instant] = None,
  ): ApprovalRequest =
    ApprovalRequest(
      id = ApprovalRequestId.empty,
      capability = request.capability,
      scopeJson = grant.scopeJson,
      agentId = request.agentId,
      requestorUserId = request.requestorId,
      sessionId = request.sessionId,
      riskClass = riskClass,
      status = ApprovalStatus.Pending,
      createdAt = now,
      expiresAt = expiresAt,
    )

}
