/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.domain.*
import zio.*

import java.time.Instant

/** Pure [[ApprovalPolicyEngine]] that maps [[EvaluationResult]] + [[RiskClass]] → [[AuthorizationResult]].
  *
  * Decision rules:
  *
  *   - `ExplicitDeny` / `DefaultDeny` → `Denied`
  *   - `ResourcePermissionAllows` / `RolePermissionAllows` → `Allowed` (direct permissions bypass approval flow)
  *   - `ConnectorPolicyAllows` / `SkillPolicyAllows` → `Allowed` (stub policies are permissive by design)
  *   - `CapabilityGrantAllows(grant)` — resolved by the grant's [[ApprovalMode]]:
  *     - `Denied` → `Denied`
  *     - `Persistent` → `Allowed`
  *     - `PerInvocation` → `NeedsApproval`
  *     - `Timed` → `Allowed` if grant has not expired; `NeedsApproval` otherwise
  *     - `Once` → `Allowed` if `existingApprovals` contains an approved request; `NeedsApproval` otherwise
  *     - `Session` → `Allowed` if `existingApprovals` contains an approved request for the same session;
  *       `NeedsApproval` otherwise
  */
class ApprovalPolicyEngineImpl extends ApprovalPolicyEngine {

  override def decide(
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
            // None == None would incorrectly allow sessionless requests; guard with isDefined.
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

object ApprovalPolicyEngineImpl {

  val live: ULayer[ApprovalPolicyEngine] = ZLayer.succeed(new ApprovalPolicyEngineImpl)

}
