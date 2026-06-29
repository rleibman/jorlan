/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.ZIORepositories
import zio.*

import java.time.Instant

/** Orchestrates the full capability authorization pipeline.
  *
  * `authorize` pipeline:
  *   1. [[RiskClassifier.classify]] — pure
  *   2. [[CapabilityEvaluator.evaluate]] — queries DB
  *   3. Pre-load existing approvals when the grant mode is `Once` or `Session`
  *   4. [[ApprovalPolicyEngine.decide]] — pure
  *   5. If `PendingApproval`: persist the [[ApprovalRequest]] and write `ApprovalRequested` event
  *   6. For direct `Allowed`/`Denied` results: write a `CapabilityAllowed`/`CapabilityDenied` audit event
  */
private class ApprovalServiceImpl(
  evaluator:   CapabilityEvaluator,
  repo:        ZIORepositories,
  eventLogHub: EventLogHub,
) extends ApprovalService {

  override def authorize(request: CapabilityRequest): IO[JorlanError, AuthorizationResult] =
    for {
      now <- Clock.instant
      riskClass = RiskClassifier.classify(request.capability)
      evaluation        <- evaluator.evaluate(request)
      existingApprovals <- loadExistingApprovals(request, evaluation)
      rawResult = ApprovalPolicyEngine.decide(request, evaluation, riskClass, existingApprovals, now)
      result <- rawResult match {
        case AuthorizationResult.PendingApproval(template, mode) =>
          requestApproval(template, Some(request.requestorId))
            .map(saved => AuthorizationResult.PendingApproval(saved, mode))
        case AuthorizationResult.Allowed =>
          logDecision(request, EventType.CapabilityAllowed, now).as(AuthorizationResult.Allowed)
        case denied @ AuthorizationResult.Denied(_) =>
          logDecision(request, EventType.CapabilityDenied, now).as(denied)
      }
    } yield result

  override def recordDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision] =
    for {
      now       <- Clock.instant
      saved     <- repo.permission.recordApprovalDecision(decision)
      eventType <- saved.decision match {
        case ApprovalStatus.Approved => ZIO.succeed(EventType.ApprovalGranted)
        case ApprovalStatus.Rejected | ApprovalStatus.Expired | ApprovalStatus.Cancelled =>
          ZIO.succeed(EventType.ApprovalDenied)
        case ApprovalStatus.Pending =>
          ZIO.fail(JorlanError("recordApprovalDecision called with Pending status — invariant violated"))
      }
      logEntry <- repo.eventLog.append(
        EventLog(
          id = EventLogId.empty,
          eventType = eventType,
          actorId = Some(saved.decidedBy),
          agentId = None,
          sessionId = None,
          resource = Some(saved.approvalRequestId),
          payloadJson = None,
          occurredAt = now,
        ),
      )
      _ <- eventLogHub.publishTyped(logEntry)
    } yield saved

  override def expireStaleRequests(): IO[JorlanError, Long] = repo.permission.expireAllStaleApprovalRequests()

  private def requestApproval(
    req:     ApprovalRequest,
    actorId: Option[UserId],
  ): IO[JorlanError, ApprovalRequest] =
    for {
      now      <- Clock.instant
      saved    <- repo.permission.createApprovalRequest(req)
      logEntry <- repo.eventLog.append(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.ApprovalRequested,
          actorId = actorId,
          agentId = None,
          sessionId = None,
          resource = Some(saved.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
      _ <- eventLogHub.publishTyped(logEntry)
    } yield saved

  private def loadExistingApprovals(
    request:    CapabilityRequest,
    evaluation: EvaluationResult,
  ): IO[JorlanError, List[ApprovalRequest]] =
    evaluation match {
      case EvaluationResult.CapabilityGrantAllows(grant) if grant.approvalMode == ApprovalMode.Once =>
        repo.permission.findApprovedRequest(request.capability, request.requestorId, None).map(_.toList)
      case EvaluationResult.CapabilityGrantAllows(grant)
          if grant.approvalMode == ApprovalMode.Session && request.sessionId.isDefined =>
        repo.permission.findApprovedRequest(request.capability, request.requestorId, request.sessionId).map(_.toList)
      case _ => ZIO.succeed(List.empty)
    }

  private def logDecision(
    request:   CapabilityRequest,
    eventType: EventType,
    now:       Instant,
  ): IO[JorlanError, Unit] =
    for {
      logEntry <- repo.eventLog.append(
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
      )
      _ <- eventLogHub.publishTyped(logEntry)
    } yield ()

}

object ApprovalServiceImpl {

  val live: URLayer[CapabilityEvaluator & ZIORepositories & EventLogHub, ApprovalService] =
    ZLayer.fromFunction(ApprovalServiceImpl(_, _, _))

}
