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
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

import java.time.Instant

object ApprovalServiceSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  // ─── Layer helpers ────────────────────────────────────────────────────────────

  /** Build a fresh set of layers for each test. All service layers share the same in-memory repositories so that
    * objects created via PermissionService are visible to CapabilityEvaluator and vice-versa.
    */
  private def freshLayers: ULayer[ApprovalService & PermissionService & EventLogService] = {
    val permRepoLayer = InMemoryRepositories.InMemoryPermissionRepo.layer
    val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
    val eventLogLayer = eventLogRepo >>> EventLogServiceImpl.live
    val permSvcLayer = (permRepoLayer ++ eventLogLayer) >>> PermissionServiceImpl.live
    val evaluatorLayer = permRepoLayer >>> CapabilityEvaluatorImpl.live
    val approvalLayer =
      (RiskClassifierImpl.live ++ evaluatorLayer ++ ApprovalPolicyEngineImpl.live ++ permSvcLayer ++ eventLogLayer) >>>
        ApprovalServiceImpl.live
    permSvcLayer ++ approvalLayer ++ eventLogLayer
  }

  // ─── Fixtures ─────────────────────────────────────────────────────────────────

  private val sessionId1: AgentSessionId = AgentSessionId(42L)

  private val sessionGrant: CapabilityGrant = CapabilityGrant(
    id = CapabilityGrantId.empty,
    capability = CapabilityName("shell.execute"),
    scopeJson = None,
    granteeId = UserId(1L),
    grantorId = Some(UserId(99L)),
    approvalMode = ApprovalMode.Session,
    expiresAt = None,
    resourceConstraints = None,
    createdAt = T0,
  )

  private val persistentGrant: CapabilityGrant = CapabilityGrant(
    id = CapabilityGrantId.empty,
    capability = CapabilityName("memory.read"),
    scopeJson = None,
    granteeId = UserId(1L),
    grantorId = Some(UserId(99L)),
    approvalMode = ApprovalMode.Persistent,
    expiresAt = None,
    resourceConstraints = None,
    createdAt = T0,
  )

  private def capReq(
    cap:       String,
    userId:    UserId = UserId(1L),
    sessionId: Option[AgentSessionId] = None,
  ): CapabilityRequest =
    CapabilityRequest(
      capability = CapabilityName(cap),
      requestorId = userId,
      agentId = None,
      sessionId = sessionId,
      resourceConstraints = None,
    )

  // ─── Tests ───────────────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ApprovalService")(
      recordDecisionSuite,
      sessionBranchSuite,
      companionAccessorSuite,
    )

  private val recordDecisionSuite = suite("recordDecision")(
    test("recordDecision delegates to PermissionService and returns the saved decision") {
      for {
        permSvc <- ZIO.service[PermissionService]
        svc     <- ZIO.service[ApprovalService]
        // Create a pending approval request to decide on
        req <- permSvc.requestApproval(
          ApprovalRequest(
            id = ApprovalRequestId.empty,
            capability = CapabilityName("shell.execute"),
            scopeJson = None,
            agentId = None,
            requestorUserId = UserId(1L),
            sessionId = None,
            riskClass = RiskClass.ExternalEffect,
            status = ApprovalStatus.Pending,
            createdAt = T0,
            expiresAt = None,
          ),
          actorId = Some(UserId(1L)),
        )
        decision = ApprovalDecision(
          id = ApprovalDecisionId.empty,
          approvalRequestId = req.id,
          decidedBy = UserId(99L),
          decision = ApprovalStatus.Approved,
          scopeOverride = None,
          decidedAt = T0,
        )
        saved <- svc.recordDecision(decision)
      } yield assertTrue(
        saved.id != ApprovalDecisionId.empty,
        saved.decision == ApprovalStatus.Approved,
        saved.decidedBy == UserId(99L),
      )
    },
    test("recordDecision with Rejected status is delegated correctly") {
      for {
        permSvc <- ZIO.service[PermissionService]
        svc     <- ZIO.service[ApprovalService]
        req     <- permSvc.requestApproval(
          ApprovalRequest(
            id = ApprovalRequestId.empty,
            capability = CapabilityName("filesystem.write"),
            scopeJson = None,
            agentId = None,
            requestorUserId = UserId(1L),
            sessionId = None,
            riskClass = RiskClass.WorkspaceWrite,
            status = ApprovalStatus.Pending,
            createdAt = T0,
            expiresAt = None,
          ),
          actorId = None,
        )
        decision = ApprovalDecision(
          id = ApprovalDecisionId.empty,
          approvalRequestId = req.id,
          decidedBy = UserId(99L),
          decision = ApprovalStatus.Rejected,
          scopeOverride = None,
          decidedAt = T0,
        )
        saved <- svc.recordDecision(decision)
      } yield assertTrue(
        saved.decision == ApprovalStatus.Rejected,
      )
    },
  ).provide(freshLayers)

  private val sessionBranchSuite = suite("authorize with sessionId branch")(
    test("Session grant with sessionId in request triggers findApprovedRequest with sessionId") {
      for {
        permSvc <- ZIO.service[PermissionService]
        svc     <- ZIO.service[ApprovalService]
        // Add a Session-mode grant for the user
        _ <- permSvc.upsertCapabilityGrant(sessionGrant)
        // First call: no existing approval, should return PendingApproval
        result1 <- svc.authorize(capReq("shell.execute", sessionId = Some(sessionId1)))
        // Manually create an approved request for the same session
        req <- permSvc.requestApproval(
          ApprovalRequest(
            id = ApprovalRequestId.empty,
            capability = CapabilityName("shell.execute"),
            scopeJson = None,
            agentId = None,
            requestorUserId = UserId(1L),
            sessionId = Some(sessionId1),
            riskClass = RiskClass.ExternalEffect,
            status = ApprovalStatus.Pending,
            createdAt = T0,
            expiresAt = None,
          ),
          actorId = None,
        )
        decision = ApprovalDecision(
          id = ApprovalDecisionId.empty,
          approvalRequestId = req.id,
          decidedBy = UserId(99L),
          decision = ApprovalStatus.Approved,
          scopeOverride = None,
          decidedAt = T0,
        )
        _ <- permSvc.recordApprovalDecision(decision)
        // Second call: existing approval for this session, should return Allowed
        result2 <- svc.authorize(capReq("shell.execute", sessionId = Some(sessionId1)))
      } yield assertTrue(
        result1.isInstanceOf[AuthorizationResult.PendingApproval],
        result2 == AuthorizationResult.Allowed,
      )
    },
    test("Session grant with no sessionId in request returns PendingApproval (no DB round-trip)") {
      for {
        permSvc <- ZIO.service[PermissionService]
        svc     <- ZIO.service[ApprovalService]
        _       <- permSvc.upsertCapabilityGrant(sessionGrant)
        // Request with no sessionId: loadExistingApprovals falls through to the _ case
        result <- svc.authorize(capReq("shell.execute", sessionId = None))
      } yield assertTrue(result.isInstanceOf[AuthorizationResult.PendingApproval])
    },
  ).provide(freshLayers)

  private val companionAccessorSuite = suite("ApprovalService companion accessors")(
    test("companion accessors delegate to implementation") {
      for {
        permSvc <- ZIO.service[PermissionService]
        // Seed a persistent grant so authorize returns Allowed for the final assertion
        _   <- permSvc.upsertCapabilityGrant(persistentGrant)
        req <- permSvc.requestApproval(
          ApprovalRequest(
            id = ApprovalRequestId.empty,
            capability = CapabilityName("memory.read"),
            scopeJson = None,
            agentId = None,
            requestorUserId = UserId(1L),
            sessionId = None,
            riskClass = RiskClass.ReadOnly,
            status = ApprovalStatus.Pending,
            createdAt = T0,
            expiresAt = None,
          ),
          actorId = None,
        )
        decision = ApprovalDecision(
          id = ApprovalDecisionId.empty,
          approvalRequestId = req.id,
          decidedBy = UserId(99L),
          decision = ApprovalStatus.Approved,
          scopeOverride = None,
          decidedAt = T0,
        )
        saved  <- ApprovalService.recordDecision(decision)
        _      <- ApprovalService.expireStaleRequests()
        result <- ApprovalService.authorize(capReq("memory.read"))
      } yield assertTrue(
        saved.decision == ApprovalStatus.Approved,
        result == AuthorizationResult.Allowed,
      )
    },
  ).provide(freshLayers)

}
