/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.ZIORepositories
import zio.*
import zio.stream.ZStream

import java.time.Instant

/** Orchestrates the full capability authorization pipeline and owns the in-process approval pub-sub.
  *
  * `authorize` pipeline:
  *   1. [[RiskClassifier.classify]] — pure
  *   2. [[CapabilityEvaluator.evaluate]] — queries DB
  *   3. Pre-load existing approvals when the grant mode is `Once` or `Session`
  *   4. [[ApprovalPolicyEngine.decide]] — pure
  *   5. If `PendingApproval`: persist the [[ApprovalRequest]] and write `ApprovalRequested` event
  *   6. For direct `Allowed`/`Denied` results: write a `CapabilityAllowed`/`CapabilityDenied` audit event
  *
  * Hub state: [[awaitDecision]] / [[completeDecision]] / [[subscribeToNewRequests]] are entirely in-memory (no DB) and
  * are race-safe: the Promise is registered before checking `preDecisions`, so a concurrent [[completeDecision]] is
  * never missed.
  */
private class ApprovalServiceImpl(
  evaluator:       CapabilityEvaluator,
  repo:            ZIORepositories,
  eventLogHub:     EventLogHub,
  pendingPromises: Ref[Map[ApprovalRequestId, Promise[Nothing, Boolean]]],
  preDecisions:    Ref[Map[ApprovalRequestId, (Boolean, Instant)]],
  broadcastHub:    KeyedPubSubHub[Unit, ApprovalRequest],
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
      _         <- completeDecision(saved.approvalRequestId, saved.decision == ApprovalStatus.Approved)
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

  override def expireStaleRequests(): IO[JorlanError, Long] =
    purgeExpiredPreDecisions() *> repo.permission.expireAllStaleApprovalRequests()

  override def awaitDecision(
    id:      ApprovalRequestId,
    timeout: Duration,
  ): UIO[Option[Boolean]] =
    for {
      promise <- Promise.make[Nothing, Boolean]
      _       <- pendingPromises.update(_.updated(id, promise))
      _       <- preDecisions
        .modify { pre =>
          pre.get(id) match {
            case Some((result, _)) => (Some(result), pre - id)
            case None              => (None, pre)
          }
        }.flatMap {
          case Some(result) => promise.succeed(result).unit
          case None         => ZIO.unit
        }
      result <- promise.await.timeout(timeout).ensuring(pendingPromises.update(_ - id))
    } yield result

  override def completeDecision(
    id:       ApprovalRequestId,
    approved: Boolean,
  ): UIO[Unit] =
    pendingPromises.get.flatMap { map =>
      map.get(id) match {
        case Some(promise) => promise.succeed(approved).unit
        case None          =>
          Clock.instant.flatMap { now =>
            preDecisions.update(_.updated(id, (approved, now.plusSeconds(600))))
          }
      }
    }

  override def notifyNewRequest(req: ApprovalRequest): UIO[Unit] =
    broadcastHub.publish((), req)

  override def purgeExpiredPreDecisions(): UIO[Long] =
    Clock.instant.flatMap { now =>
      preDecisions.modify { pre =>
        val (stale, fresh) = pre.partition { case (_, (_, expiry)) => expiry.isBefore(now) }
        (stale.size.toLong, fresh)
      }
    }

  override def subscribeToNewRequests: UIO[ZStream[Any, Nothing, ApprovalRequest]] =
    broadcastHub.subscribe(())

  private def requestApproval(
    req:     ApprovalRequest,
    actorId: Option[UserId],
  ): IO[JorlanError, ApprovalRequest] =
    for {
      now      <- Clock.instant
      saved    <- repo.permission.createApprovalRequest(req)
      _        <- notifyNewRequest(saved)
      logEntry <- repo.eventLog.append(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.ApprovalRequested,
          actorId = actorId,
          agentId = saved.agentId,
          sessionId = saved.sessionId,
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
    ZLayer.fromZIO(
      for {
        evaluator    <- ZIO.service[CapabilityEvaluator]
        repo         <- ZIO.service[ZIORepositories]
        eventLogHub  <- ZIO.service[EventLogHub]
        pending      <- Ref.make(Map.empty[ApprovalRequestId, Promise[Nothing, Boolean]])
        pre          <- Ref.make(Map.empty[ApprovalRequestId, (Boolean, Instant)])
        broadcastHub <- KeyedPubSubHub.make[Unit, ApprovalRequest]
      } yield new ApprovalServiceImpl(evaluator, repo, eventLogHub, pending, pre, broadcastHub): ApprovalService,
    )

}
