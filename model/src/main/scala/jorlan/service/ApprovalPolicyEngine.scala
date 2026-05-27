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

/** Combines an [[EvaluationResult]] and a [[RiskClass]] into a final [[AuthorizationResult]].
  *
  * This is a pure decision function — all DB lookups are performed by the caller ([[ApprovalService]]) before invoking
  * `decide`. `existingApprovals` contains any already-approved [[ApprovalRequest]] rows for this capability + user (
  * and optionally session), pre-loaded so this method stays side-effect free.
  *
  * `now` is passed explicitly so callers in non-ZIO contexts can supply a fixed instant (test friendliness).
  */
trait ApprovalPolicyEngine {

  def decide(
    request:           CapabilityRequest,
    evaluation:        EvaluationResult,
    riskClass:         RiskClass,
    existingApprovals: List[ApprovalRequest],
    now:               Instant,
  ): AuthorizationResult

}

object ApprovalPolicyEngine {

  def decide(
    request:           CapabilityRequest,
    evaluation:        EvaluationResult,
    riskClass:         RiskClass,
    existingApprovals: List[ApprovalRequest],
    now:               Instant,
  ): URIO[ApprovalPolicyEngine, AuthorizationResult] =
    ZIO.serviceWith[ApprovalPolicyEngine](_.decide(request, evaluation, riskClass, existingApprovals, now))

}
