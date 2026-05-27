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

import jorlan.domain.*
import jorlan.service.*
import zio.*
import zio.json.*
import zio.test.*

import java.time.Instant

object CapabilityKernelSpec extends ZIOSpecDefault {

  // ─── Shared fixtures ─────────────────────────────────────────────────────────

  private val userId = UserId(1L)
  private val agentId = AgentId(10L)
  private val sessionId = AgentSessionId(100L)
  private val now = Instant.parse("2026-06-01T12:00:00Z")
  private val future = now.plusSeconds(3600)
  private val past = now.minusSeconds(3600)

  private def req(cap: String): CapabilityRequest =
    CapabilityRequest(
      capability = CapabilityName(cap),
      requestorId = userId,
      agentId = Some(agentId),
      sessionId = Some(sessionId),
      resourceConstraints = None,
    )

  private def grant(
    mode:      ApprovalMode,
    expiresAt: Option[Instant] = None,
  ): CapabilityGrant =
    CapabilityGrant(
      id = CapabilityGrantId(1L),
      capability = CapabilityName("shell.execute"),
      scopeJson = None,
      granteeId = userId,
      grantorId = None,
      approvalMode = mode,
      expiresAt = expiresAt,
      resourceConstraints = None,
      createdAt = now,
    )

  private def approvalReq(sessionId: Option[AgentSessionId] = None): ApprovalRequest =
    ApprovalRequest(
      id = ApprovalRequestId(42L),
      capability = CapabilityName("shell.execute"),
      scopeJson = None,
      agentId = Some(agentId),
      requestorUserId = userId,
      sessionId = sessionId,
      riskClass = RiskClass.ExternalEffect,
      status = ApprovalStatus.Approved,
      createdAt = now,
      expiresAt = None,
    )

  // ─── RiskClassifier ───────────────────────────────────────────────────────────
  private val rc = new RiskClassifierImpl

  private val riskClassifierSuite = suite("RiskClassifier")(
    test("shell.sudo.execute → SecuritySensitive") {
      assertTrue(rc.classify(CapabilityName("shell.sudo.execute")) == RiskClass.SecuritySensitive)
    },
    test("shell.interactive.start → Privileged") {
      assertTrue(rc.classify(CapabilityName("shell.interactive.start")) == RiskClass.Privileged)
    },
    test("shell.script.run → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("shell.script.run")) == RiskClass.ExternalEffect)
    },
    test("shell.binary.execute → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("shell.binary.execute")) == RiskClass.ExternalEffect)
    },
    test("shell.anything → ExternalEffect (fallback shell prefix)") {
      assertTrue(rc.classify(CapabilityName("shell.anything")) == RiskClass.ExternalEffect)
    },
    test("filesystem.delete.file → Destructive") {
      assertTrue(rc.classify(CapabilityName("filesystem.delete.file")) == RiskClass.Destructive)
    },
    test("filesystem.write → WorkspaceWrite") {
      assertTrue(rc.classify(CapabilityName("filesystem.write")) == RiskClass.WorkspaceWrite)
    },
    test("filesystem.read → ReadOnly") {
      assertTrue(rc.classify(CapabilityName("filesystem.read")) == RiskClass.ReadOnly)
    },
    test("memory.forget → Destructive") {
      assertTrue(rc.classify(CapabilityName("memory.forget")) == RiskClass.Destructive)
    },
    test("memory.search → ReadOnly") {
      assertTrue(rc.classify(CapabilityName("memory.search")) == RiskClass.ReadOnly)
    },
    test("memory.write → WorkspaceWrite") {
      assertTrue(rc.classify(CapabilityName("memory.write")) == RiskClass.WorkspaceWrite)
    },
    test("network.post → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("network.post")) == RiskClass.ExternalEffect)
    },
    test("network.read → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("network.read")) == RiskClass.ExternalEffect)
    },
    test("role.assign → Privileged") {
      assertTrue(rc.classify(CapabilityName("role.assign")) == RiskClass.Privileged)
    },
    test("permission.grant exact override → Privileged") {
      assertTrue(rc.classify(CapabilityName("permission.grant")) == RiskClass.Privileged)
    },
    test("capability.grant → SecuritySensitive") {

      assertTrue(rc.classify(CapabilityName("capability.grant")) == RiskClass.SecuritySensitive)
    },
    test("unknown capability → SecuritySensitive (deny-by-default)") {

      assertTrue(rc.classify(CapabilityName("some.unknown.capability")) == RiskClass.SecuritySensitive)
    },
    test("no-dot capability → SecuritySensitive (deny-by-default)") {
      assertTrue(rc.classify(CapabilityName("ping")) == RiskClass.SecuritySensitive)
    },
  )

  // ─── ApprovalPolicyEngine ─────────────────────────────────────────────────────

  private val engine = new ApprovalPolicyEngineImpl
  private val riskLow = RiskClass.ReadOnly
  private val riskHigh = RiskClass.ExternalEffect

  private val policyEngineSuite = suite("ApprovalPolicyEngine")(
    // Step 1: explicit deny
    test("ExplicitDeny → Denied regardless of risk") {
      val result = engine.decide(req("shell.execute"), EvaluationResult.ExplicitDeny, riskHigh, Nil, now)
      assertTrue(result.isInstanceOf[AuthorizationResult.Denied])
    },
    // Step 2: resource permission
    test("ResourcePermissionAllows → Allowed") {
      val result = engine.decide(req("shell.execute"), EvaluationResult.ResourcePermissionAllows, riskHigh, Nil, now)
      assertTrue(result == AuthorizationResult.Allowed)
    },
    // Step 3: role permission
    test("RolePermissionAllows → Allowed") {
      val result = engine.decide(req("shell.execute"), EvaluationResult.RolePermissionAllows, riskHigh, Nil, now)
      assertTrue(result == AuthorizationResult.Allowed)
    },
    // Step 4: capability grant — various modes
    test("CapabilityGrantAllows with Denied mode → Denied") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Denied)),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result.isInstanceOf[AuthorizationResult.Denied])
    },
    test("CapabilityGrantAllows with Persistent → Allowed") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Persistent)),
        riskLow,
        Nil,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrantAllows with PerInvocation → PendingApproval(PerInvocation)") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.PerInvocation)),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.PerInvocation) => true
        case _                                                                  => false
      })
    },
    test("CapabilityGrantAllows with Timed and future expiry → Allowed") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Timed, Some(future))),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrantAllows with Timed and past expiry → PendingApproval(Timed)") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Timed, Some(past))),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Timed) => true
        case _                                                          => false
      })
    },
    test("CapabilityGrantAllows with Timed and no expiry → PendingApproval(Timed)") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Timed, None)),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Timed) => true
        case _                                                          => false
      })
    },
    test("CapabilityGrantAllows with Once and existing approval → Allowed") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Once)),
        riskHigh,
        List(approvalReq()),
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrantAllows with Once and no existing approval → PendingApproval(Once)") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Once)),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Once) => true
        case _                                                         => false
      })
    },
    test("CapabilityGrantAllows with Session and matching session approval → Allowed") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Session)),
        riskHigh,
        List(approvalReq(Some(sessionId))),
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrantAllows with Session and different session → PendingApproval(Session)") {
      val otherSession = Some(AgentSessionId(999L))
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Session)),
        riskHigh,
        List(approvalReq(otherSession)),
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Session) => true
        case _                                                            => false
      })
    },
    test("CapabilityGrantAllows with Session and no approvals → PendingApproval(Session)") {
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Session)),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Session) => true
        case _                                                            => false
      })
    },
    // Step 5 & 6: connector / skill policy stubs
    test("ConnectorPolicyAllows → Allowed") {
      val result = engine.decide(req("shell.execute"), EvaluationResult.ConnectorPolicyAllows, riskHigh, Nil, now)
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("SkillPolicyAllows → Allowed") {
      val result = engine.decide(req("shell.execute"), EvaluationResult.SkillPolicyAllows, riskHigh, Nil, now)
      assertTrue(result == AuthorizationResult.Allowed)
    },
    // Step 7: default deny
    test("DefaultDeny → Denied") {
      val result = engine.decide(req("shell.execute"), EvaluationResult.DefaultDeny, riskHigh, Nil, now)
      assertTrue(result.isInstanceOf[AuthorizationResult.Denied])
    },
    // PendingApproval carries the unsaved ApprovalRequest template
    test("PendingApproval carries ApprovalRequest with correct fields") {
      val result = engine.decide(
        req("memory.write"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.PerInvocation)),
        riskLow,
        Nil,
        now,
      )
      result match {
        case AuthorizationResult.PendingApproval(template, _) =>
          assertTrue(
            template.id == ApprovalRequestId.empty,
            template.requestorUserId == userId,
            template.agentId.contains(agentId),
            template.sessionId.contains(sessionId),
            template.status == ApprovalStatus.Pending,
            template.createdAt == now,
            template.riskClass == riskLow,
          )
        case _ => assertTrue(false)
      }
    },
  )

  // ─── RiskClass enum ──────────────────────────────────────────────────────────

  private val riskClassSuite = suite("RiskClass")(
    test("levels are ordered 0–5") {
      assertTrue(
        RiskClass.ReadOnly.level == 0,
        RiskClass.WorkspaceWrite.level == 1,
        RiskClass.Destructive.level == 2,
        RiskClass.ExternalEffect.level == 3,
        RiskClass.Privileged.level == 4,
        RiskClass.SecuritySensitive.level == 5,
      )
    },
  )

  // ─── Additional prefix rules (P5-020) ───────────────────────────────────────

  private val additionalPrefixSuite = suite("RiskClassifier additional prefix rules")(
    test("filesystem.remove → Destructive") {
      assertTrue(rc.classify(CapabilityName("filesystem.remove")) == RiskClass.Destructive)
    },
    test("filesystem.list → ReadOnly") {
      assertTrue(rc.classify(CapabilityName("filesystem.list")) == RiskClass.ReadOnly)
    },
    test("filesystem.anything → WorkspaceWrite (fallback)") {
      assertTrue(rc.classify(CapabilityName("filesystem.anything")) == RiskClass.WorkspaceWrite)
    },
    test("memory.delete → Destructive") {
      assertTrue(rc.classify(CapabilityName("memory.delete")) == RiskClass.Destructive)
    },
    test("memory.read → ReadOnly") {
      assertTrue(rc.classify(CapabilityName("memory.read")) == RiskClass.ReadOnly)
    },
    test("memory.anything → WorkspaceWrite (fallback)") {
      assertTrue(rc.classify(CapabilityName("memory.anything")) == RiskClass.WorkspaceWrite)
    },
    test("network.send → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("network.send")) == RiskClass.ExternalEffect)
    },
    test("network.external → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("network.external")) == RiskClass.ExternalEffect)
    },
    test("network.anything → WorkspaceWrite (fallback)") {
      assertTrue(rc.classify(CapabilityName("network.anything")) == RiskClass.WorkspaceWrite)
    },
    test("role.remove → Privileged") {
      assertTrue(rc.classify(CapabilityName("role.remove")) == RiskClass.Privileged)
    },
    test("role.anything → Privileged (fallback)") {
      assertTrue(rc.classify(CapabilityName("role.anything")) == RiskClass.Privileged)
    },
    test("permission.revoke exact override → Privileged") {
      assertTrue(rc.classify(CapabilityName("permission.revoke")) == RiskClass.Privileged)
    },
    test("permission.anything → Privileged (prefix fallback)") {
      assertTrue(rc.classify(CapabilityName("permission.anything")) == RiskClass.Privileged)
    },
    test("capability.view → SecuritySensitive (prefix fallback)") {
      assertTrue(rc.classify(CapabilityName("capability.view")) == RiskClass.SecuritySensitive)
    },
    test("skill.install → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("skill.install")) == RiskClass.ExternalEffect)
    },
    test("skill.approve → ExternalEffect") {
      assertTrue(rc.classify(CapabilityName("skill.approve")) == RiskClass.ExternalEffect)
    },
    test("skill.anything → WorkspaceWrite (fallback)") {
      assertTrue(rc.classify(CapabilityName("skill.anything")) == RiskClass.WorkspaceWrite)
    },
    test("scheduler.anything → WorkspaceWrite") {
      assertTrue(rc.classify(CapabilityName("scheduler.run")) == RiskClass.WorkspaceWrite)
    },
    test("agent.anything → WorkspaceWrite") {
      assertTrue(rc.classify(CapabilityName("agent.start")) == RiskClass.WorkspaceWrite)
    },
    test("shell.sudo.anything → SecuritySensitive (hits shell.sudo prefix)") {
      assertTrue(rc.classify(CapabilityName("shell.sudo.anything")) == RiskClass.SecuritySensitive)
    },
    test("shell.interactive.anything → Privileged (hits shell.interactive prefix)") {
      assertTrue(rc.classify(CapabilityName("shell.interactive.anything")) == RiskClass.Privileged)
    },
  )

  // ─── Boundary conditions (P5-021) ────────────────────────────────────────────

  private val boundarySuite = suite("ApprovalPolicyEngine boundary conditions")(
    test("Once mode with Rejected approval → PendingApproval (not Allowed)") {
      val rejectedApproval = approvalReq().copy(status = ApprovalStatus.Rejected)
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Once)),
        riskHigh,
        List(rejectedApproval),
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Once) => true
        case _                                                         => false
      })
    },
    test("Timed grant with expiresAt == now → PendingApproval (strict isAfter)") {
      // isAfter is strict: expiresAt == now means expired
      val result = engine.decide(
        req("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Timed, Some(now))),
        riskHigh,
        Nil,
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Timed) => true
        case _                                                          => false
      })
    },
    test("Session mode with sessionless request (None) → PendingApproval even with sessionless existing approval") {
      val sessionlessRequest = req("shell.execute").copy(sessionId = None)
      val sessionlessApproval = approvalReq(None)
      val result = engine.decide(
        sessionlessRequest,
        EvaluationResult.CapabilityGrantAllows(grant(ApprovalMode.Session)),
        riskHigh,
        List(sessionlessApproval),
        now,
      )
      assertTrue(result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Session) => true
        case _                                                            => false
      })
    },
  )

  // ─── JSON codec roundtrips (P5-022) ─────────────────────────────────────────

  private val codecSuite = suite("JSON codec roundtrips")(
    test("CapabilityName encodes and decodes") {
      val name = CapabilityName("shell.execute")
      val json = name.toJson
      assertTrue(json.fromJson[CapabilityName] == Right(name))
    },
    test("RiskClass encodes and decodes") {
      val allClasses = List(
        RiskClass.ReadOnly,
        RiskClass.WorkspaceWrite,
        RiskClass.Destructive,
        RiskClass.ExternalEffect,
        RiskClass.Privileged,
        RiskClass.SecuritySensitive,
      )
      assertTrue(allClasses.forall(rc => rc.toJson.fromJson[RiskClass] == Right(rc)))
    },
  )

  override def spec: Spec[TestEnvironment, Any] =
    suite("Capability Kernel")(
      riskClassSuite,
      riskClassifierSuite,
      additionalPrefixSuite,
      policyEngineSuite,
      boundarySuite,
      codecSuite,
    )

}
