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

import jorlan.*
import jorlan.domain.*
import zio.*

import java.time.Instant

/** Orchestrates the full capability authorization pipeline.
  *
  * `authorize` pipeline:
  *   1. [[RiskClassifier.classify]] — pure
  *   2. [[CapabilityEvaluator.evaluate]] — queries DB
  *   3. Pre-load existing approvals when the grant mode is `Once` or `Session`
  *   4. [[ApprovalPolicyEngine.decide]] — pure
  *   5. If `PendingApproval`: persist the [[ApprovalRequest]] via [[PermissionService.requestApproval]], which also
  *      writes the `ApprovalRequested` event
  *   6. For direct `Allowed`/`Denied` results: write a `CapabilityAllowed`/`CapabilityDenied` audit event
  */
private class ApprovalServiceImpl(
  riskClassifier:    RiskClassifier,
  evaluator:         CapabilityEvaluator,
  policyEngine:      ApprovalPolicyEngine,
  permissionService: PermissionService,
  eventLog:          EventLogService,
) extends ApprovalService {

  override def authorize(request: CapabilityRequest): IO[JorlanError, AuthorizationResult] =
    for {
      now <- Clock.instant
      riskClass = riskClassifier.classify(request.capability)
      evaluation <- evaluator.evaluate(request)
      // Pre-load existing approvals needed for Once / Session modes.
      existingApprovals <- loadExistingApprovals(request, evaluation)
      rawResult = policyEngine.decide(request, evaluation, riskClass, existingApprovals, now)
      // Persist the approval request and rewrite PendingApproval with the saved record.
      result <- rawResult match {
        case AuthorizationResult.PendingApproval(template, mode) =>
          permissionService
            .requestApproval(template, Some(request.requestorId))
            .map(saved => AuthorizationResult.PendingApproval(saved, mode))
        case AuthorizationResult.Allowed =>
          logDecision(request, EventType.CapabilityAllowed, now).as(AuthorizationResult.Allowed)
        case denied @ AuthorizationResult.Denied(_) =>
          logDecision(request, EventType.CapabilityDenied, now).as(denied)
      }
    } yield result

  override def recordDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision] =
    permissionService.recordApprovalDecision(decision)

  override def expireStaleRequests(): IO[JorlanError, Long] = permissionService.expireAllStaleApprovalRequests()

  private def loadExistingApprovals(
    request:    CapabilityRequest,
    evaluation: EvaluationResult,
  ): IO[JorlanError, List[ApprovalRequest]] =
    evaluation match {
      case EvaluationResult.CapabilityGrantAllows(grant) if grant.approvalMode == ApprovalMode.Once =>
        permissionService
          .findApprovedRequest(request.capability, request.requestorId, None)
          .map(_.toList)
      case EvaluationResult.CapabilityGrantAllows(grant)
          if grant.approvalMode == ApprovalMode.Session && request.sessionId.isDefined =>
        permissionService
          .findApprovedRequest(request.capability, request.requestorId, request.sessionId)
          .map(_.toList)
      // Session mode with no sessionId: policy engine will deny anyway; skip the DB round-trip.
      case _ => ZIO.succeed(Nil)
    }

  private def logDecision(
    request:   CapabilityRequest,
    eventType: EventType,
    now:       Instant,
  ): IO[JorlanError, Unit] =
    eventLog
      .log(
        EventLog(
          id = EventLogId.empty,
          eventType = eventType,
          actorId = Some(request.requestorId),
          agentId = request.agentId,
          sessionId = request.sessionId,
          resource = Some(request.capability),
          payloadJson = None,
          occurredAt = now,
        ),
      ).unit

}

object ApprovalServiceImpl {

  val live: URLayer[
    RiskClassifier & CapabilityEvaluator & ApprovalPolicyEngine & PermissionService & EventLogService,
    ApprovalService,
  ] =
    ZLayer.fromFunction(
      (
        rc: RiskClassifier,
        ev: CapabilityEvaluator,
        pe: ApprovalPolicyEngine,
        ps: PermissionService,
        el: EventLogService,
      ) => new ApprovalServiceImpl(rc, ev, pe, ps, el),
    )

}
