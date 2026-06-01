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
import jorlan.db.repository.{EventLogZIORepository, PermissionZIORepository}
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

import java.time.Instant

object ApprovalServiceSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  // ─── Layer helpers ────────────────────────────────────────────────────────────

  /** Build a fresh set of layers for each test. All service layers share the same in-memory repositories so that
    * objects created via PermissionZIORepository are visible to CapabilityEvaluator and vice-versa.
    */
  private def freshLayers: ULayer[ApprovalService & PermissionZIORepository & EventLogZIORepository] = {
    val base = InMemoryRepositories.InMemoryPermissionRepo.layer ++ InMemoryRepositories.InMemoryEventLogRepo.layer
    base >+> CapabilityEvaluatorImpl.live >+> ApprovalServiceImpl.live
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
      serviceMethodsSuite,
    )

  private val recordDecisionSuite = suite("recordDecision")(
    test("recordDecision delegates to PermissionZIORepository and returns the saved decision") {
      for {
        permRepo <- ZIO.service[PermissionZIORepository]
        svc      <- ZIO.service[ApprovalService]
        // Create a pending approval request to decide on
        req <- permRepo.createApprovalRequest(
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
        permRepo <- ZIO.service[PermissionZIORepository]
        svc      <- ZIO.service[ApprovalService]
        req      <- permRepo.createApprovalRequest(
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
        permRepo <- ZIO.service[PermissionZIORepository]
        svc      <- ZIO.service[ApprovalService]
        // Add a Session-mode grant for the user
        _ <- permRepo.upsertCapabilityGrant(sessionGrant)
        // First call: no existing approval, should return PendingApproval
        result1 <- svc.authorize(capReq("shell.execute", sessionId = Some(sessionId1)))
        // Manually create an approved request for the same session
        req <- permRepo.createApprovalRequest(
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
        )
        decision = ApprovalDecision(
          id = ApprovalDecisionId.empty,
          approvalRequestId = req.id,
          decidedBy = UserId(99L),
          decision = ApprovalStatus.Approved,
          scopeOverride = None,
          decidedAt = T0,
        )
        _ <- permRepo.recordApprovalDecision(decision)
        // Second call: existing approval for this session, should return Allowed
        result2 <- svc.authorize(capReq("shell.execute", sessionId = Some(sessionId1)))
      } yield assertTrue(
        result1.isInstanceOf[AuthorizationResult.PendingApproval],
        result2 == AuthorizationResult.Allowed,
      )
    },
    test("Session grant with no sessionId in request returns PendingApproval (no DB round-trip)") {
      for {
        permRepo <- ZIO.service[PermissionZIORepository]
        svc      <- ZIO.service[ApprovalService]
        _        <- permRepo.upsertCapabilityGrant(sessionGrant)
        // Request with no sessionId: loadExistingApprovals falls through to the _ case
        result <- svc.authorize(capReq("shell.execute", sessionId = None))
      } yield assertTrue(result.isInstanceOf[AuthorizationResult.PendingApproval])
    },
  ).provide(freshLayers)

  private val serviceMethodsSuite = suite("ApprovalService methods")(
    test("service methods work end-to-end") {
      for {
        permRepo <- ZIO.service[PermissionZIORepository]
        svc      <- ZIO.service[ApprovalService]
        // Seed a persistent grant so authorize returns Allowed for the final assertion
        _   <- permRepo.upsertCapabilityGrant(persistentGrant)
        req <- permRepo.createApprovalRequest(
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
        )
        decision = ApprovalDecision(
          id = ApprovalDecisionId.empty,
          approvalRequestId = req.id,
          decidedBy = UserId(99L),
          decision = ApprovalStatus.Approved,
          scopeOverride = None,
          decidedAt = T0,
        )
        saved  <- svc.recordDecision(decision)
        _      <- svc.expireStaleRequests()
        result <- svc.authorize(capReq("memory.read"))
      } yield assertTrue(
        saved.decision == ApprovalStatus.Approved,
        result == AuthorizationResult.Allowed,
      )
    },
  ).provide(freshLayers)

}
