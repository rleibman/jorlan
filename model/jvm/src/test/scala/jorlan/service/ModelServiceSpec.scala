/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object ModelServiceSpec extends ZIOSpecDefault {

  private val now: Instant = Instant.parse("2026-01-15T10:00:00Z")

  // ─── RiskClassifier ─────────────────────────────────────────────────────────

  private val riskClassifierSuite = suite("RiskClassifier")(
    test("exact override: shell.sudo.execute is SecuritySensitive") {
      assertTrue(RiskClassifier.classify(CapabilityName("shell.sudo.execute")) == RiskClass.SecuritySensitive)
    },
    test("exact override: shell.interactive.start is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("shell.interactive.start")) == RiskClass.Privileged)
    },
    test("exact override: capability.grant is SecuritySensitive") {
      assertTrue(RiskClassifier.classify(CapabilityName("capability.grant")) == RiskClass.SecuritySensitive)
    },
    test("exact override: permission.grant is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("permission.grant")) == RiskClass.Privileged)
    },
    test("exact override: permission.revoke is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("permission.revoke")) == RiskClass.Privileged)
    },
    test("prefix: shell.sudo.* is SecuritySensitive") {
      assertTrue(RiskClassifier.classify(CapabilityName("shell.sudo.run")) == RiskClass.SecuritySensitive)
    },
    test("prefix: shell.script is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("shell.script.run")) == RiskClass.ExternalEffect)
    },
    test("prefix: shell.binary is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("shell.binary.execute")) == RiskClass.ExternalEffect)
    },
    test("prefix: shell.interactive is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("shell.interactive.stop")) == RiskClass.Privileged)
    },
    test("prefix: shell is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("shell.execute")) == RiskClass.ExternalEffect)
    },
    test("prefix: filesystem.delete is Destructive") {
      assertTrue(RiskClassifier.classify(CapabilityName("filesystem.delete")) == RiskClass.Destructive)
    },
    test("prefix: filesystem.remove is Destructive") {
      assertTrue(RiskClassifier.classify(CapabilityName("filesystem.remove")) == RiskClass.Destructive)
    },
    test("prefix: filesystem.write is WorkspaceWrite") {
      assertTrue(RiskClassifier.classify(CapabilityName("filesystem.write")) == RiskClass.WorkspaceWrite)
    },
    test("prefix: filesystem.read is ReadOnly") {
      assertTrue(RiskClassifier.classify(CapabilityName("filesystem.read")) == RiskClass.ReadOnly)
    },
    test("prefix: filesystem.list is ReadOnly") {
      assertTrue(RiskClassifier.classify(CapabilityName("filesystem.list")) == RiskClass.ReadOnly)
    },
    test("prefix: filesystem is WorkspaceWrite") {
      assertTrue(RiskClassifier.classify(CapabilityName("filesystem.other")) == RiskClass.WorkspaceWrite)
    },
    test("prefix: memory.forget is Destructive") {
      assertTrue(RiskClassifier.classify(CapabilityName("memory.forget")) == RiskClass.Destructive)
    },
    test("prefix: memory.delete is Destructive") {
      assertTrue(RiskClassifier.classify(CapabilityName("memory.delete")) == RiskClass.Destructive)
    },
    test("prefix: memory.search is ReadOnly") {
      assertTrue(RiskClassifier.classify(CapabilityName("memory.search")) == RiskClass.ReadOnly)
    },
    test("prefix: memory.read is ReadOnly") {
      assertTrue(RiskClassifier.classify(CapabilityName("memory.read")) == RiskClass.ReadOnly)
    },
    test("prefix: memory is WorkspaceWrite") {
      assertTrue(RiskClassifier.classify(CapabilityName("memory.write")) == RiskClass.WorkspaceWrite)
    },
    test("prefix: network.post is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("network.post")) == RiskClass.ExternalEffect)
    },
    test("prefix: network.send is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("network.send")) == RiskClass.ExternalEffect)
    },
    test("prefix: network.external is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("network.external")) == RiskClass.ExternalEffect)
    },
    test("prefix: network.read is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("network.read")) == RiskClass.ExternalEffect)
    },
    test("prefix: network is WorkspaceWrite") {
      assertTrue(RiskClassifier.classify(CapabilityName("network.other")) == RiskClass.WorkspaceWrite)
    },
    test("prefix: role.assign is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("role.assign")) == RiskClass.Privileged)
    },
    test("prefix: role.remove is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("role.remove")) == RiskClass.Privileged)
    },
    test("prefix: role is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("role.other")) == RiskClass.Privileged)
    },
    test("prefix: permission is Privileged") {
      assertTrue(RiskClassifier.classify(CapabilityName("permission.list")) == RiskClass.Privileged)
    },
    test("prefix: capability is SecuritySensitive") {
      assertTrue(RiskClassifier.classify(CapabilityName("capability.revoke")) == RiskClass.SecuritySensitive)
    },
    test("prefix: skill.install is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("skill.install")) == RiskClass.ExternalEffect)
    },
    test("prefix: skill.approve is ExternalEffect") {
      assertTrue(RiskClassifier.classify(CapabilityName("skill.approve")) == RiskClass.ExternalEffect)
    },
    test("prefix: skill is WorkspaceWrite") {
      assertTrue(RiskClassifier.classify(CapabilityName("skill.other")) == RiskClass.WorkspaceWrite)
    },
    test("prefix: scheduler is WorkspaceWrite") {
      assertTrue(RiskClassifier.classify(CapabilityName("scheduler.job.create")) == RiskClass.WorkspaceWrite)
    },
    test("prefix: agent is WorkspaceWrite") {
      assertTrue(RiskClassifier.classify(CapabilityName("agent.session.list")) == RiskClass.WorkspaceWrite)
    },
    test("unknown capability defaults to SecuritySensitive") {
      assertTrue(
        RiskClassifier.classify(CapabilityName("completely.unknown.capability")) == RiskClass.SecuritySensitive,
      )
    },
    test("single segment unknown defaults to SecuritySensitive") {
      assertTrue(RiskClassifier.classify(CapabilityName("unknown")) == RiskClass.SecuritySensitive)
    },
  )

  // ─── ApprovalPolicyEngine ─────────────────────────────────────────────────

  private def makeRequest(
    capability: String,
    sessionId:  Option[AgentSessionId] = None,
  ): CapabilityRequest =
    CapabilityRequest(
      capability = CapabilityName(capability),
      requestorId = UserId(1L),
      agentId = Some(AgentId(1L)),
      sessionId = sessionId,
      resourceConstraints = None,
    )

  private def makeGrant(
    approvalMode: ApprovalMode,
    expiresAt:    Option[Instant] = None,
  ): CapabilityGrant =
    CapabilityGrant(
      id = CapabilityGrantId(1L),
      capability = CapabilityName("shell.execute"),
      scopeJson = None,
      granteeId = 1L,
      granteeType = GranteeType.User,
      grantorId = None,
      approvalMode = approvalMode,
      expiresAt = expiresAt,
      resourceConstraints = None,
      createdAt = now,
    )

  private def makeApproval(
    status:    ApprovalStatus,
    sessionId: Option[AgentSessionId] = None,
  ): ApprovalRequest =
    ApprovalRequest(
      id = ApprovalRequestId(1L),
      capability = CapabilityName("shell.execute"),
      scopeJson = None,
      agentId = Some(AgentId(1L)),
      requestorUserId = UserId(1L),
      sessionId = sessionId,
      riskClass = RiskClass.ExternalEffect,
      status = status,
      createdAt = now,
      expiresAt = None,
    )

  private val approvalPolicyEngineSuite = suite("ApprovalPolicyEngine")(
    test("ExplicitDeny always returns Denied") {
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.ExplicitDeny,
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Denied("explicitly denied by capability grant"))
    },
    test("DefaultDeny returns Denied") {
      val result = ApprovalPolicyEngine.decide(
        makeRequest("unknown"),
        EvaluationResult.DefaultDeny,
        RiskClass.SecuritySensitive,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Denied("no matching policy — default deny"))
    },
    test("ResourcePermissionAllows returns Allowed") {
      val result = ApprovalPolicyEngine.decide(
        makeRequest("filesystem.read"),
        EvaluationResult.ResourcePermissionAllows,
        RiskClass.ReadOnly,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("RolePermissionAllows returns Allowed") {
      val result = ApprovalPolicyEngine.decide(
        makeRequest("memory.read"),
        EvaluationResult.RolePermissionAllows,
        RiskClass.ReadOnly,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("ConnectorPolicyAllows returns Allowed") {
      val result = ApprovalPolicyEngine.decide(
        makeRequest("network.read"),
        EvaluationResult.ConnectorPolicyAllows,
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("SkillPolicyAllows returns Allowed") {
      val result = ApprovalPolicyEngine.decide(
        makeRequest("skill.list"),
        EvaluationResult.SkillPolicyAllows,
        RiskClass.WorkspaceWrite,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrant with Denied returns Denied") {
      val grant = makeGrant(ApprovalMode.Denied)
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Denied("capability grant has ApprovalMode.Denied"))
    },
    test("CapabilityGrant with Persistent returns Allowed") {
      val grant = makeGrant(ApprovalMode.Persistent)
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrant with PerInvocation returns PendingApproval") {
      val grant = makeGrant(ApprovalMode.PerInvocation)
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.PerInvocation) => assertCompletes
        case _                                                                  => assertTrue(false)
      }
    },
    test("CapabilityGrant with Timed and future expiry returns Allowed") {
      val grant = makeGrant(ApprovalMode.Timed, expiresAt = Some(now.plusSeconds(3600)))
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrant with Timed and expired returns PendingApproval") {
      val grant = makeGrant(ApprovalMode.Timed, expiresAt = Some(now.minusSeconds(1)))
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Timed) => assertCompletes
        case _                                                          => assertTrue(false)
      }
    },
    test("CapabilityGrant with Timed and no expiry returns PendingApproval") {
      val grant = makeGrant(ApprovalMode.Timed, expiresAt = None)
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Timed) => assertCompletes
        case _                                                          => assertTrue(false)
      }
    },
    test("CapabilityGrant with Once and prior approval returns Allowed") {
      val grant = makeGrant(ApprovalMode.Once)
      val existingApproval = makeApproval(ApprovalStatus.Approved)
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List(existingApproval),
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrant with Once and no prior approval returns PendingApproval") {
      val grant = makeGrant(ApprovalMode.Once)
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute"),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Once) => assertCompletes
        case _                                                         => assertTrue(false)
      }
    },
    test("CapabilityGrant with Session and matching session approval returns Allowed") {
      val sessionId = AgentSessionId(42L)
      val grant = makeGrant(ApprovalMode.Session)
      val existingApproval = makeApproval(ApprovalStatus.Approved, sessionId = Some(sessionId))
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute", sessionId = Some(sessionId)),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List(existingApproval),
        now,
      )
      assertTrue(result == AuthorizationResult.Allowed)
    },
    test("CapabilityGrant with Session and no sessionId returns PendingApproval") {
      val grant = makeGrant(ApprovalMode.Session)
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute", sessionId = None),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List.empty,
        now,
      )
      result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Session) => assertCompletes
        case _                                                            => assertTrue(false)
      }
    },
    test("CapabilityGrant with Session and different session approval returns PendingApproval") {
      val sessionId = AgentSessionId(42L)
      val otherSessionId = AgentSessionId(99L)
      val grant = makeGrant(ApprovalMode.Session)
      val existingApproval = makeApproval(ApprovalStatus.Approved, sessionId = Some(otherSessionId))
      val result = ApprovalPolicyEngine.decide(
        makeRequest("shell.execute", sessionId = Some(sessionId)),
        EvaluationResult.CapabilityGrantAllows(grant),
        RiskClass.ExternalEffect,
        List(existingApproval),
        now,
      )
      result match {
        case AuthorizationResult.PendingApproval(_, ApprovalMode.Session) => assertCompletes
        case _                                                            => assertTrue(false)
      }
    },
  )

  // ─── EventLogFilter ───────────────────────────────────────────────────────

  private val eventLogFilterSuite = suite("EventLogFilter")(
    test("default construction") {
      val f = EventLogFilter()
      assertTrue(f.page == 0 && f.pageSize == 100 && f.sorts.isEmpty)
    },
    test("validatePageSize accepts valid pageSize") {
      val f = EventLogFilter(pageSize = 50)
      assertTrue(EventLogFilter.validatePageSize(f) == Right(f))
    },
    test("validatePageSize rejects pageSize = 0") {
      val f = EventLogFilter(pageSize = 0)
      assertTrue(EventLogFilter.validatePageSize(f).isLeft)
    },
    test("validatePageSize rejects pageSize > MaxLimit") {
      val f = EventLogFilter(pageSize = EventLogFilter.MaxLimit + 1)
      assertTrue(EventLogFilter.validatePageSize(f).isLeft)
    },
    test("validatePageSize accepts MaxLimit exactly") {
      val f = EventLogFilter(pageSize = EventLogFilter.MaxLimit)
      assertTrue(EventLogFilter.validatePageSize(f) == Right(f))
    },
    test("MaxLimit is 10000") {
      assertTrue(EventLogFilter.MaxLimit == 10_000)
    },
    test("filter with event type") {
      val f = EventLogFilter(eventType = Some(EventType.AgentStarted), agentId = Some(AgentId(1L)))
      assertTrue(f.eventType.contains(EventType.AgentStarted))
    },
  )

  // ─── CheckpointPolicy ────────────────────────────────────────────────────

  private val checkpointPolicySuite = suite("CheckpointPolicy")(
    test("onSessionEnd fires on SessionEnd trigger") {
      CheckpointPolicy.onSessionEnd.shouldCheckpoint(CheckpointTrigger.SessionEnd).map(result => assertTrue(result))
    },
    test("onSessionEnd does not fire on other triggers") {
      CheckpointPolicy.onSessionEnd.shouldCheckpoint(CheckpointTrigger.UserRequest).map(result => assertTrue(!result))
    },
    test("fromConfig with onSessionEnd=true fires on SessionEnd") {
      val config = CheckpointPolicyConfig(onSessionEnd = true)
      CheckpointPolicy
        .fromConfig(config).shouldCheckpoint(CheckpointTrigger.SessionEnd).map(result => assertTrue(result))
    },
    test("fromConfig with onSessionEnd=false does not fire on SessionEnd") {
      val config = CheckpointPolicyConfig(onSessionEnd = false)
      CheckpointPolicy
        .fromConfig(config).shouldCheckpoint(CheckpointTrigger.SessionEnd).map(result => assertTrue(!result))
    },
    test("fromConfig with onUserRequest=true fires on UserRequest") {
      val config = CheckpointPolicyConfig(onUserRequest = true)
      CheckpointPolicy
        .fromConfig(config).shouldCheckpoint(CheckpointTrigger.UserRequest).map(result => assertTrue(result))
    },
    test("fromConfig with timedIntervalTurns set fires on TimedInterval") {
      val config = CheckpointPolicyConfig(timedIntervalTurns = Some(5))
      CheckpointPolicy
        .fromConfig(config).shouldCheckpoint(CheckpointTrigger.TimedInterval).map(result => assertTrue(result))
    },
    test("fromConfig with timedIntervalTurns=None does not fire on TimedInterval") {
      val config = CheckpointPolicyConfig(timedIntervalTurns = None)
      CheckpointPolicy
        .fromConfig(config).shouldCheckpoint(CheckpointTrigger.TimedInterval).map(result => assertTrue(!result))
    },
    test("fromConfig with beforeExternalEffect=true fires on BeforeExternalEffect") {
      val config = CheckpointPolicyConfig(beforeExternalEffect = true)
      CheckpointPolicy
        .fromConfig(config).shouldCheckpoint(CheckpointTrigger.BeforeExternalEffect).map(result => assertTrue(result))
    },
    test("CheckpointPolicyConfig default has onUserRequest=true") {
      assertTrue(CheckpointPolicyConfig.default.onUserRequest)
    },
    test("CheckpointPolicyConfig JSON round-trip") {
      val config = CheckpointPolicyConfig(
        onSessionEnd = true,
        onUserRequest = false,
        timedIntervalTurns = Some(20),
        beforeExternalEffect = true,
      )
      assertTrue(config.toJson.fromJson[CheckpointPolicyConfig] == Right(config))
    },
    test("CheckpointPolicyConfig serverSettingsKey is correct") {
      assertTrue(CheckpointPolicyConfig.serverSettingsKey == "checkpoint.policy")
    },
  )

  // ─── MemoryService companion accessors ───────────────────────────────────

  private class StubMemoryService extends MemoryService {
    private val nowInst = java.time.Instant.now()
    private val stubRecord = MemoryRecord(
      id = MemoryRecordId(1L),
      scope = MemoryScope.User,
      userId = Some(UserId(1L)),
      workspaceId = None,
      agentId = None,
      recordKey = "test.key",
      value = zio.json.ast.Json.Str("value"),
      ttl = None,
      createdAt = nowInst,
      updatedAt = nowInst,
    )
    override def store(record: MemoryRecord): IO[JorlanError, MemoryRecord] = ZIO.succeed(stubRecord)
    override def query(scope: MemoryScope, userId: UserId, agentId: AgentId, text: Option[String]): IO[JorlanError, List[MemoryRecord]] = ZIO.succeed(List(stubRecord))
    override def forget(id: MemoryRecordId, requestingUserId: UserId): IO[JorlanError, Boolean] = ZIO.succeed(true)
    override def markShared(id: MemoryRecordId, requestingUserId: UserId): IO[JorlanError, MemoryRecord] = ZIO.succeed(stubRecord)
    override def markPrivate(id: MemoryRecordId, requestingUserId: UserId): IO[JorlanError, MemoryRecord] = ZIO.succeed(stubRecord)
    override def checkpoint(sessionId: AgentSessionId, messages: List[Message], userId: UserId, agentId: AgentId, trigger: CheckpointTrigger): IO[JorlanError, Unit] = ZIO.unit
    override def requestCheckpoint(sessionId: AgentSessionId, userId: UserId, agentId: AgentId): IO[JorlanError, Unit] = ZIO.unit
    override def getCheckpointPolicy: UIO[CheckpointPolicyConfig] = ZIO.succeed(CheckpointPolicyConfig.default)
    override def updateCheckpointPolicy(config: CheckpointPolicyConfig): IO[JorlanError, Unit] = ZIO.unit
    override def semanticQuery(scope: MemoryScope, userId: UserId, agentId: AgentId, queryText: String, limit: Int): IO[JorlanError, List[MemoryRecord]] = ZIO.succeed(List.empty)
  }

  private val stubMemoryLayer: ULayer[MemoryService] = ZLayer.succeed(new StubMemoryService())

  private def nowInst = java.time.Instant.now()
  private def stubRecord = MemoryRecord(
    id = MemoryRecordId(1L),
    scope = MemoryScope.User,
    userId = Some(UserId(1L)),
    workspaceId = None,
    agentId = None,
    recordKey = "test.key",
    value = zio.json.ast.Json.Str("value"),
    ttl = None,
    createdAt = nowInst,
    updatedAt = nowInst,
  )

  private val memoryServiceCompanionSuite = suite("MemoryService companion accessors")(
    test("store accessor delegates to service") {
      MemoryService.store(stubRecord).map(r => assertTrue(r.recordKey == "test.key"))
        .provide(stubMemoryLayer)
    },
    test("query accessor delegates to service") {
      MemoryService.query(MemoryScope.User, UserId(1L), AgentId(1L)).map(list =>
        assertTrue(list.nonEmpty)
      ).provide(stubMemoryLayer)
    },
    test("forget accessor delegates to service") {
      MemoryService.forget(MemoryRecordId(1L), UserId(1L)).map(result =>
        assertTrue(result)
      ).provide(stubMemoryLayer)
    },
    test("markShared accessor delegates to service") {
      MemoryService.markShared(MemoryRecordId(1L), UserId(1L)).map(r =>
        assertTrue(r.recordKey == "test.key")
      ).provide(stubMemoryLayer)
    },
    test("markPrivate accessor delegates to service") {
      MemoryService.markPrivate(MemoryRecordId(1L), UserId(1L)).map(r =>
        assertTrue(r.recordKey == "test.key")
      ).provide(stubMemoryLayer)
    },
    test("checkpoint accessor delegates to service") {
      MemoryService.checkpoint(AgentSessionId(1L), List.empty, UserId(1L), AgentId(1L), CheckpointTrigger.SessionEnd)
        .as(assertCompletes)
        .provide(stubMemoryLayer)
    },
    test("requestCheckpoint accessor delegates to service") {
      MemoryService.requestCheckpoint(AgentSessionId(1L), UserId(1L), AgentId(1L))
        .as(assertCompletes)
        .provide(stubMemoryLayer)
    },
    test("getCheckpointPolicy accessor delegates to service") {
      MemoryService.getCheckpointPolicy.map(cfg =>
        assertTrue(cfg == CheckpointPolicyConfig.default)
      ).provide(stubMemoryLayer)
    },
    test("updateCheckpointPolicy accessor delegates to service") {
      MemoryService.updateCheckpointPolicy(CheckpointPolicyConfig.default)
        .as(assertCompletes)
        .provide(stubMemoryLayer)
    },
  )

  // ─── CorrelationId ────────────────────────────────────────────────────────

  private val correlationIdSuite = suite("CorrelationId")(
    test("key is 'correlationId'") {
      assertTrue(CorrelationId.key == "correlationId")
    },
    test("withNew annotates with a correlation ID") {
      for {
        idOpt <- CorrelationId.withNew(CorrelationId.get)
      } yield assertTrue(idOpt.isDefined)
    },
    test("withId annotates with the given ID") {
      val testId = "test-corr-123"
      for {
        idOpt <- CorrelationId.withId(testId)(CorrelationId.get)
      } yield assertTrue(idOpt.contains(testId))
    },
    test("get returns None outside a withNew/withId block") {
      for {
        idOpt <- CorrelationId.get
      } yield assertTrue(idOpt.isEmpty)
    },
    test("withNew produces different IDs on each call") {
      for {
        id1 <- CorrelationId.withNew(CorrelationId.get)
        id2 <- CorrelationId.withNew(CorrelationId.get)
      } yield assertTrue(id1 != id2)
    },
  )

  // ─── ModelError types ─────────────────────────────────────────────────────

  private val modelErrorSuite = suite("ModelError types")(
    test("ModelUnavailable has correct message") {
      val e: ModelError = ModelUnavailable("LLM provider unreachable")
      assertTrue(e.msg == "LLM provider unreachable") && assertTrue(!e.isTransient)
    },
    test("ModelTimeout is transient") {
      val e: ModelError = ModelTimeout("timed out after 30s")
      assertTrue(e.isTransient)
    },
    test("ModelResponseMalformed has correct message") {
      val e: ModelError = ModelResponseMalformed("unexpected JSON format")
      assertTrue(e.msg == "unexpected JSON format")
    },
  )

  // ─── AgentMessage types ───────────────────────────────────────────────────

  private val agentMessageSuite = suite("AgentMessage types")(
    test("SystemMsg holds content") {
      val msg = SystemMsg("You are a helpful assistant.")
      assertTrue(msg.content == "You are a helpful assistant.")
    },
    test("UserMsg holds content") {
      val msg = UserMsg("What is the weather?")
      assertTrue(msg.content == "What is the weather?")
    },
    test("AssistantMsg holds content") {
      val msg = AssistantMsg("The weather is sunny.")
      assertTrue(msg.content == "The weather is sunny.")
    },
    test("ToolCallMsg holds id, name, and argsJson") {
      val msg = ToolCallMsg(id = "call-1", name = "units.convert", argsJson = """{"value":1}""")
      assertTrue(msg.id == "call-1") && assertTrue(msg.name == "units.convert")
    },
    test("ToolResultMsg holds id, name, and resultJson") {
      val msg = ToolResultMsg(id = "call-1", name = "units.convert", resultJson = """{"result":1000}""")
      assertTrue(msg.resultJson == """{"result":1000}""")
    },
    test("ToolSpec holds name, description, inputSchemaJson") {
      val spec = ToolSpec(
        name = "units.convert",
        description = "Convert units",
        inputSchemaJson = """{"type":"object"}""",
      )
      assertTrue(spec.name == "units.convert") && assertTrue(spec.description == "Convert units")
    },
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ModelServiceSpec")(
      riskClassifierSuite,
      approvalPolicyEngineSuite,
      eventLogFilterSuite,
      checkpointPolicySuite,
      memoryServiceCompanionSuite,
      correlationIdSuite,
      modelErrorSuite,
      agentMessageSuite,
    )

}
