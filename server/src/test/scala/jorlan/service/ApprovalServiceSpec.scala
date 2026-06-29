/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.{ZIOEventLogRepository, ZIOPermissionRepository, ZIORepositories}
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

import java.time.Instant

object ApprovalServiceSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  private val sessionId1: AgentSessionId = AgentSessionId(42L)

  private val sessionGrant: CapabilityGrant = CapabilityGrant(
    id = CapabilityGrantId.empty,
    capability = CapabilityName("shell.execute"),
    scopeJson = None,
    granteeId = 1L,
    granteeType = GranteeType.User,
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
    granteeId = 1L,
    granteeType = GranteeType.User,
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

  private val testLayers: ULayer[ZIORepositories & CapabilityEvaluator & ApprovalService] =
    ZLayer.make[ZIORepositories & CapabilityEvaluator & ApprovalService](
      InMemoryRepositories.live(),
      CapabilityEvaluatorImpl.live,
      EventLogHub.live,
      ApprovalServiceImpl.live,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ApprovalService")(
      recordDecisionSuite,
      sessionBranchSuite,
      serviceMethodsSuite,
      pendingApprovalSuite,
    )

  private val recordDecisionSuite = suite("recordDecision")(
    test("recordDecision delegates to PermissionZIORepository and returns the saved decision") {
      for {
        permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc      <- ZIO.service[ApprovalService]
        req      <- permRepo.createApprovalRequest(
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
    }.provide(testLayers),
    test("recordDecision with Rejected status is delegated correctly") {
      for {
        permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
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
    }.provide(testLayers),
  )

  private val sessionBranchSuite = suite("authorize with sessionId branch")(
    test("Session grant with sessionId in request triggers findApprovedRequest with sessionId") {
      for {
        permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc      <- ZIO.service[ApprovalService]
        _        <- permRepo.upsertCapabilityGrant(sessionGrant)
        result1  <- svc.authorize(capReq("shell.execute", sessionId = Some(sessionId1)))
        req      <- permRepo.createApprovalRequest(
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
        _       <- permRepo.recordApprovalDecision(decision)
        result2 <- svc.authorize(capReq("shell.execute", sessionId = Some(sessionId1)))
      } yield assertTrue(
        result1.isInstanceOf[AuthorizationResult.PendingApproval],
        result2 == AuthorizationResult.Allowed,
      )
    }.provide(testLayers),
    test("Session grant with no sessionId in request returns PendingApproval (no DB round-trip)") {
      for {
        permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc      <- ZIO.service[ApprovalService]
        _        <- permRepo.upsertCapabilityGrant(sessionGrant)
        result   <- svc.authorize(capReq("shell.execute", sessionId = None))
      } yield assertTrue(result.isInstanceOf[AuthorizationResult.PendingApproval])
    }.provide(testLayers),
  )

  private val pendingApprovalSuite = suite("authorize PendingApproval paths")(
    test("PerInvocation grant triggers requestApproval and returns PendingApproval") {
      for {
        perm <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc  <- ZIO.service[ApprovalService]
        _    <- perm.upsertCapabilityGrant(
          CapabilityGrant(
            id = CapabilityGrantId.empty,
            capability = CapabilityName("invocation.op"),
            scopeJson = None,
            granteeId = 1L,
            granteeType = GranteeType.User,
            grantorId = None,
            approvalMode = ApprovalMode.PerInvocation,
            expiresAt = None,
            resourceConstraints = None,
            createdAt = T0,
          ),
        )
        result <- svc.authorize(capReq("invocation.op"))
      } yield assertTrue(result.isInstanceOf[AuthorizationResult.PendingApproval])
    }.provide(testLayers),
    test("Once grant with no existing approval returns PendingApproval (covers loadExistingApprovals Once path)") {
      for {
        perm <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc  <- ZIO.service[ApprovalService]
        _    <- perm.upsertCapabilityGrant(
          CapabilityGrant(
            id = CapabilityGrantId.empty,
            capability = CapabilityName("once.op"),
            scopeJson = None,
            granteeId = 1L,
            granteeType = GranteeType.User,
            grantorId = None,
            approvalMode = ApprovalMode.Once,
            expiresAt = None,
            resourceConstraints = None,
            createdAt = T0,
          ),
        )
        result <- svc.authorize(capReq("once.op"))
      } yield assertTrue(result.isInstanceOf[AuthorizationResult.PendingApproval])
    }.provide(testLayers),
    test("Session grant with sessionId and no prior approval returns PendingApproval (covers loadExistingApprovals Session path)") {
      for {
        perm <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc  <- ZIO.service[ApprovalService]
        _    <- perm.upsertCapabilityGrant(
          CapabilityGrant(
            id = CapabilityGrantId.empty,
            capability = CapabilityName("session.op"),
            scopeJson = None,
            granteeId = 1L,
            granteeType = GranteeType.User,
            grantorId = None,
            approvalMode = ApprovalMode.Session,
            expiresAt = None,
            resourceConstraints = None,
            createdAt = T0,
          ),
        )
        result <- svc.authorize(capReq("session.op", sessionId = Some(AgentSessionId(99L))))
      } yield assertTrue(result.isInstanceOf[AuthorizationResult.PendingApproval])
    }.provide(testLayers),
    test("recordDecision with Pending status fails with invariant error") {
      for {
        perm <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc  <- ZIO.service[ApprovalService]
        req  <- perm.createApprovalRequest(
          ApprovalRequest(
            id = ApprovalRequestId.empty,
            capability = CapabilityName("inv.op"),
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
        result <- svc
          .recordDecision(
            ApprovalDecision(
              id = ApprovalDecisionId.empty,
              approvalRequestId = req.id,
              decidedBy = UserId(1L),
              decision = ApprovalStatus.Pending,
              scopeOverride = None,
              decidedAt = T0,
            ),
          )
          .either
      } yield assertTrue(result.isLeft)
    }.provide(testLayers),
    test("authorize returns Allowed for Persistent grant (covers Allowed branch in authorize)") {
      for {
        perm   <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc    <- ZIO.service[ApprovalService]
        _      <- perm.upsertCapabilityGrant(persistentGrant)
        result <- svc.authorize(capReq("memory.read"))
      } yield assertTrue(result == AuthorizationResult.Allowed)
    }.provide(testLayers),
    test("authorize returns Denied for ApprovalMode.Denied grant (covers Denied branch in authorize)") {
      for {
        perm <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc  <- ZIO.service[ApprovalService]
        _    <- perm.upsertCapabilityGrant(
          CapabilityGrant(
            id = CapabilityGrantId.empty,
            capability = CapabilityName("denied.op"),
            scopeJson = None,
            granteeId = 1L,
            granteeType = GranteeType.User,
            grantorId = None,
            approvalMode = ApprovalMode.Denied,
            expiresAt = None,
            resourceConstraints = None,
            createdAt = T0,
          ),
        )
        result <- svc.authorize(capReq("denied.op"))
      } yield assertTrue(result.isInstanceOf[AuthorizationResult.Denied])
    }.provide(testLayers),
  )

  private val serviceMethodsSuite = suite("ApprovalService methods")(
    test("service methods work end-to-end") {
      for {
        permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
        svc      <- ZIO.service[ApprovalService]
        _        <- permRepo.upsertCapabilityGrant(persistentGrant)
        req      <- permRepo.createApprovalRequest(
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
    }.provide(testLayers),
  )

}
