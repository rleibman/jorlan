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
import jorlan.service.{CorrelationId, EventLogFilter, EventLogOrder}
import zio.*
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object DomainSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Domain")(
      idsSuite,
      channelTypeSuite,
      sessionSuite,
      connectionIdSuite,
      enumCodecSuite,
      orchestratorCodecSuite,
      personalitySuite,
      eventLogFilterSuite,
      correlationIdSuite,
      schedulerJobSuite,
      channelIdentitySuite,
      connectorInstanceSuite,
    )

  private val idsSuite = suite("Opaque ID types")(
    test("UserId apply, value, empty, JSON roundtrip") {
      val id = UserId(42L)
      assertTrue(
        id.value == 42L,
        UserId.empty.value == 0L,
        id.toJson.fromJson[UserId].contains(id),
      )
    },
    test("AgentId roundtrip") {
      val id = AgentId(7L)
      assertTrue(id.value == 7L, id.toJson.fromJson[AgentId].contains(id))
    },
    test("AgentSessionId roundtrip") {
      val id = AgentSessionId(99L)
      assertTrue(id.value == 99L, id.toJson.fromJson[AgentSessionId].contains(id))
    },
    test("ConversationId roundtrip") {
      val id = ConversationId(3L)
      assertTrue(id.value == 3L, id.toJson.fromJson[ConversationId].contains(id))
    },
    test("MessageId roundtrip") {
      val id = MessageId(5L)
      assertTrue(id.value == 5L, id.toJson.fromJson[MessageId].contains(id))
    },
    test("SkillId roundtrip") {
      val id = SkillId(11L)
      assertTrue(id.value == 11L, id.toJson.fromJson[SkillId].contains(id))
    },
    test("SkillVersionId roundtrip") {
      val id = SkillVersionId(22L)
      assertTrue(id.value == 22L, id.toJson.fromJson[SkillVersionId].contains(id))
    },
    test("ConnectorInstanceId roundtrip") {
      val id = ConnectorInstanceId(33L)
      assertTrue(id.value == 33L, id.toJson.fromJson[ConnectorInstanceId].contains(id))
    },
    test("MemoryRecordId roundtrip") {
      val id = MemoryRecordId(44L)
      assertTrue(id.value == 44L, id.toJson.fromJson[MemoryRecordId].contains(id))
    },
    test("MemoryEmbeddingId roundtrip") {
      val id = MemoryEmbeddingId(55L)
      assertTrue(id.value == 55L, id.toJson.fromJson[MemoryEmbeddingId].contains(id))
    },
    test("SchedulerJobId roundtrip") {
      val id = SchedulerJobId(66L)
      assertTrue(id.value == 66L, id.toJson.fromJson[SchedulerJobId].contains(id))
    },
    test("SchedulerTriggerId roundtrip") {
      val id = SchedulerTriggerId(77L)
      assertTrue(id.value == 77L, id.toJson.fromJson[SchedulerTriggerId].contains(id))
    },
    test("EventLogId roundtrip") {
      val id = EventLogId(88L)
      assertTrue(id.value == 88L, id.toJson.fromJson[EventLogId].contains(id))
    },
    test("ArtifactId roundtrip") {
      val id = ArtifactId(10L)
      assertTrue(id.value == 10L, id.toJson.fromJson[ArtifactId].contains(id))
    },
    test("WorkspaceId roundtrip") {
      val id = WorkspaceId(20L)
      assertTrue(id.value == 20L, id.toJson.fromJson[WorkspaceId].contains(id))
    },
    test("OrchestratorId roundtrip") {
      val id = OrchestratorId(30L)
      assertTrue(id.value == 30L, id.toJson.fromJson[OrchestratorId].contains(id))
    },
    test("PermissionId roundtrip") {
      val id = PermissionId(40L)
      assertTrue(id.value == 40L, id.toJson.fromJson[PermissionId].contains(id))
    },
    test("ChannelIdentityId roundtrip") {
      val id = ChannelIdentityId(50L)
      assertTrue(id.value == 50L, id.toJson.fromJson[ChannelIdentityId].contains(id))
    },
    test("RoleId roundtrip") {
      val id = RoleId(60L)
      assertTrue(id.value == 60L, id.toJson.fromJson[RoleId].contains(id))
    },
    test("CapabilityGrantId roundtrip") {
      val id = CapabilityGrantId(70L)
      assertTrue(id.value == 70L, id.toJson.fromJson[CapabilityGrantId].contains(id))
    },
    test("ApprovalRequestId roundtrip") {
      val id = ApprovalRequestId(80L)
      assertTrue(id.value == 80L, id.toJson.fromJson[ApprovalRequestId].contains(id))
    },
    test("ApprovalDecisionId roundtrip") {
      val id = ApprovalDecisionId(90L)
      assertTrue(id.value == 90L, id.toJson.fromJson[ApprovalDecisionId].contains(id))
    },
    test("ModelId apply and value") {
      val id = ModelId("llama3")
      assertTrue(id.value == "llama3", id.toJson.fromJson[ModelId].contains(id))
    },
    test("EmbeddingModelId apply and value") {
      val id = EmbeddingModelId("nomic-embed-text")
      assertTrue(id.value == "nomic-embed-text", id.toJson.fromJson[EmbeddingModelId].contains(id))
    },
  )

  private val channelTypeSuite = suite("ChannelType.fromProvider")(
    test("google maps to Google") {
      assertTrue(ChannelType.fromProvider("google").contains(ChannelType.Google))
    },
    test("GitHub maps to GitHub (case-insensitive)") {
      assertTrue(ChannelType.fromProvider("github").contains(ChannelType.GitHub))
    },
    test("discord maps to Discord") {
      assertTrue(ChannelType.fromProvider("discord").contains(ChannelType.Discord))
    },
    test("unknown provider returns None") {
      assertTrue(ChannelType.fromProvider("unknown-provider").isEmpty)
    },
    test("empty string returns None") {
      assertTrue(ChannelType.fromProvider("").isEmpty)
    },
  )

  private val sessionSuite = suite("JorlanSession")(
    test("serverUser has id=1 and displayName=server") {
      val u = JorlanSession.serverUser
      assertTrue(u.id == UserId(1L), u.displayName == "server", u.email.contains("server@jorlan.internal"))
    },
    test("serverSession is AuthenticatedSession wrapping serverUser") {
      val session = JorlanSession.serverSession
      assertTrue(session.user.contains(JorlanSession.serverUser))
    },
  )

  private val connectionIdSuite = suite("ConnectionId")(
    test("apply and value roundtrip") {
      val id = ConnectionId(UUID.fromString("6a1fb8f4-a9ae-4bf2-9c79-5931488d6bf8"))
      assertTrue(id.value.toString == "6a1fb8f4-a9ae-4bf2-9c79-5931488d6bf8")
    },
    test("unsafeRandom generates a non-empty UUID string") {
      val id = ConnectionId.unsafeRandom
      assertTrue(id.value.toString.length == 36)
    },
    test("JSON roundtrip") {
      val id = ConnectionId.unsafeRandom
      assertTrue(id.toJson.fromJson[ConnectionId].contains(id))
    },
  )

  private def enumRoundtrip[A: {JsonEncoder, JsonDecoder}](values: Seq[A]): Boolean =
    values.forall(v => v.toJson.fromJson[A].contains(v))

  private val personalitySuite = suite("Personality")(
    test("Formality JSON decoder is case-insensitive") {
      val results = List("casual", "PROFESSIONAL", "academic", "Technical").map { s =>
        zio.json.ast.Json.Str(s).as[Formality]
      }
      assertTrue(results.forall(_.isRight))
    },
    test("Formality decoder rejects unknown value") {
      val result = zio.json.ast.Json.Str("hipster").as[Formality]
      assertTrue(result.isLeft)
    },
    test("Personality.default has name Jorlan and Professional formality") {
      val p = Personality.default
      assertTrue(p.name == "Jorlan", p.formality == Formality.Professional)
    },
    test("buildSystemPrompt Casual contains 'casual'") {
      val p = Personality.default.copy(formality = Formality.Casual)
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.toLowerCase.contains("casual"))
    },
    test("buildSystemPrompt Professional contains 'professional'") {
      val p = Personality.default.copy(formality = Formality.Professional)
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.toLowerCase.contains("professional"))
    },
    test("buildSystemPrompt Academic contains 'academic'") {
      val p = Personality.default.copy(formality = Formality.Academic)
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.toLowerCase.contains("academic"))
    },
    test("buildSystemPrompt Technical contains 'technical'") {
      val p = Personality.default.copy(formality = Formality.Technical)
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.toLowerCase.contains("technical"))
    },
    test("buildSystemPrompt with non-English language includes language instruction") {
      val p = Personality.default.copy(languages = List("en", "es"))
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.contains("es"))
    },
    test("buildSystemPrompt with English-only language omits language instruction") {
      val p = Personality.default.copy(languages = List("en"))
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(!prompt.contains("following languages"))
    },
    test("buildSystemPrompt with expertise includes expertise instruction") {
      val p = Personality.default.copy(expertise = List("Scala", "ZIO"))
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.contains("Scala") && prompt.contains("ZIO"))
    },
    test("buildSystemPrompt with empty expertise omits expertise instruction") {
      val p = Personality.default.copy(expertise = Nil)
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(!prompt.contains("deep expertise"))
    },
    test("buildSystemPrompt Quirky contains 'quirky'") {
      val p = Personality.default.copy(formality = Formality.Quirky)
      assertTrue(Personality.buildSystemPrompt(p).toLowerCase.contains("quirky"))
    },
    test("buildSystemPrompt Fresh contains 'upbeat'") {
      val p = Personality.default.copy(formality = Formality.Fresh)
      assertTrue(Personality.buildSystemPrompt(p).toLowerCase.contains("upbeat"))
    },
    test("buildSystemPrompt Rude contains 'blunt'") {
      val p = Personality.default.copy(formality = Formality.Rude)
      assertTrue(Personality.buildSystemPrompt(p).toLowerCase.contains("blunt"))
    },
    test("buildSystemPrompt Boomer contains '1960'") {
      val p = Personality.default.copy(formality = Formality.Boomer)
      assertTrue(Personality.buildSystemPrompt(p).contains("1960"))
    },
    test("buildSystemPrompt GenX contains 'sardonic'") {
      val p = Personality.default.copy(formality = Formality.GenX)
      assertTrue(Personality.buildSystemPrompt(p).toLowerCase.contains("sardonic"))
    },
    test("buildSystemPrompt Millennial contains 'collaborative'") {
      val p = Personality.default.copy(formality = Formality.Millennial)
      assertTrue(Personality.buildSystemPrompt(p).toLowerCase.contains("collaborative"))
    },
    test("buildSystemPrompt GenZ contains 'internet-native'") {
      val p = Personality.default.copy(formality = Formality.GenZ)
      assertTrue(Personality.buildSystemPrompt(p).toLowerCase.contains("internet-native"))
    },
    test("buildSystemPrompt GenAlpha contains 'hyper-digital'") {
      val p = Personality.default.copy(formality = Formality.GenAlpha)
      assertTrue(Personality.buildSystemPrompt(p).toLowerCase.contains("hyper-digital"))
    },
    test("buildSystemPrompt Custom returns empty formality instruction") {
      val p = Personality.default.copy(formality = Formality.Custom, prompt = "My custom prompt")
      val result = Personality.buildSystemPrompt(p)
      assertTrue(!result.toLowerCase.contains("you are a"), result.contains("My custom prompt"))
    },
    test("buildSystemPrompt with multiple languages includes all languages") {
      val p = Personality.default.copy(languages = List("en", "es", "fr"))
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.contains("es"), prompt.contains("fr"))
    },
    test("buildSystemPrompt with empty language list omits language instruction") {
      val p = Personality.default.copy(languages = Nil)
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(!prompt.contains("following languages"))
    },
    test("Personality JSON roundtrip") {
      val p = Personality.default
      assertTrue(p.toJson.fromJson[Personality].contains(p))
    },
  )

  private val eventLogFilterSuite = suite("EventLogFilter")(
    test("validatePageSize accepts valid sizes") {
      val f1 = EventLogFilter(pageSize = 1)
      val f2 = EventLogFilter(pageSize = 100)
      val f3 = EventLogFilter(pageSize = EventLogFilter.MaxLimit)
      assertTrue(
        EventLogFilter.validatePageSize(f1).isRight,
        EventLogFilter.validatePageSize(f2).isRight,
        EventLogFilter.validatePageSize(f3).isRight,
      )
    },
    test("validatePageSize rejects zero or negative pageSize") {
      val f0 = EventLogFilter(pageSize = 0)
      val fNeg = EventLogFilter(pageSize = -1)
      assertTrue(
        EventLogFilter.validatePageSize(f0).isLeft,
        EventLogFilter.validatePageSize(fNeg).isLeft,
      )
    },
    test("validatePageSize rejects pageSize exceeding MaxLimit") {
      val fOver = EventLogFilter(pageSize = EventLogFilter.MaxLimit + 1)
      assertTrue(EventLogFilter.validatePageSize(fOver).isLeft)
    },
    test("EventLogFilter default values are valid") {
      assertTrue(EventLogFilter.validatePageSize(EventLogFilter()).isRight)
    },
  )

  private val correlationIdSuite = suite("CorrelationId")(
    test("withNew annotates the ZIO log with a correlationId") {
      for {
        id <- CorrelationId.withNew(CorrelationId.get)
      } yield assertTrue(id.isDefined)
    },
    test("withId propagates the given id") {
      for {
        id <- CorrelationId.withId("req-123")(CorrelationId.get)
      } yield assertTrue(id.contains("req-123"))
    },
    test("get returns None when no correlationId is set") {
      for {
        id <- CorrelationId.get
      } yield assertTrue(id.isEmpty)
    },
    test("withNew ids are distinct across two calls") {
      for {
        id1 <- CorrelationId.withNew(CorrelationId.get)
        id2 <- CorrelationId.withNew(CorrelationId.get)
      } yield assertTrue(id1 != id2)
    },
  )

  private val orchestratorCodecSuite = suite("OrchestratorIdentity")(
    test("codec roundtrip with no public key") {
      val o = OrchestratorIdentity(
        id = OrchestratorId(1L),
        name = "test-orchestrator",
        description = Some("a test"),
        publicKeyPem = None,
        trustLevel = 2,
        createdAt = T0,
        updatedAt = T0,
      )
      assertTrue(o.toJson.fromJson[OrchestratorIdentity].contains(o))
    },
  )

  private val enumCodecSuite = suite("Enum codec roundtrips")(
    test("ApprovalMode") {
      assertTrue(enumRoundtrip(ApprovalMode.values.toSeq))
    },
    test("ApprovalStatus") {
      assertTrue(enumRoundtrip(ApprovalStatus.values.toSeq))
    },
    test("SessionStatus") {
      assertTrue(enumRoundtrip(SessionStatus.values.toSeq))
    },
    test("MessageRole") {
      assertTrue(enumRoundtrip(MessageRole.values.toSeq))
    },
    test("EventType") {
      assertTrue(enumRoundtrip(EventType.values.toSeq))
    },
    test("MemoryScope") {
      assertTrue(enumRoundtrip(MemoryScope.values.toSeq))
    },
    test("SkillTier") {
      assertTrue(enumRoundtrip(SkillTier.values.toSeq))
    },
    test("SkillStatus") {
      assertTrue(enumRoundtrip(SkillStatus.values.toSeq))
    },
    test("ConnectorType") {
      assertTrue(enumRoundtrip(ConnectorType.values.toSeq))
    },
    test("JobStatus") {
      assertTrue(enumRoundtrip(JobStatus.values.toSeq))
    },
    test("TriggerType") {
      assertTrue(enumRoundtrip(TriggerType.values.toSeq))
    },
  )

  private val channelIdentitySuite = suite("ChannelIdentity")(
    test("JSON codec roundtrip") {
      import zio.json.*
      val now = T0
      val ci = ChannelIdentity(
        id = ChannelIdentityId(1L),
        userId = UserId(2L),
        channelType = ChannelType.Telegram,
        channelUserId = "tg-12345",
        verified = true,
        providerData = None,
        createdAt = now,
      )
      assertTrue(ci.toJson.fromJson[ChannelIdentity].contains(ci))
    },
    test("ChannelIdentity defaults verified=false") {
      val ci = ChannelIdentity(
        id = ChannelIdentityId.empty,
        userId = UserId(1L),
        channelType = ChannelType.Slack,
        channelUserId = "U12345",
        createdAt = T0,
      )
      assertTrue(!ci.verified, ci.providerData.isEmpty)
    },
    test("ChannelIdentity with OAuth providerData roundtrip") {
      import zio.json.*
      import zio.json.ast.Json
      val ci = ChannelIdentity(
        id = ChannelIdentityId(3L),
        userId = UserId(1L),
        channelType = ChannelType.Google,
        channelUserId = "google-uid-abc",
        verified = true,
        providerData = Some(Json.Obj("sub" -> Json.Str("google-uid-abc"))),
        createdAt = T0,
      )
      assertTrue(ci.toJson.fromJson[ChannelIdentity].contains(ci))
    },
    test("ChannelType.fromProvider returns None for unknown provider") {
      assertTrue(ChannelType.fromProvider("unknown").isEmpty)
    },
  )

  private val connectorInstanceSuite = suite("ConnectorInstance")(
    test("JSON codec roundtrip") {
      import zio.json.*
      import zio.json.ast.Json
      val ci = ConnectorInstance(
        id = ConnectorInstanceId(1L),
        connectorType = ConnectorType.Telegram,
        name = "my-bot",
        configJson = Json.Obj("token" -> Json.Str("secret")),
        status = "connected",
        createdAt = T0,
      )
      assertTrue(ci.toJson.fromJson[ConnectorInstance].contains(ci))
    },
    test("toString redacts configJson") {
      import zio.json.ast.Json
      val ci = ConnectorInstance(
        id = ConnectorInstanceId(2L),
        connectorType = ConnectorType.Slack,
        name = "my-slack",
        configJson = Json.Obj("apiKey" -> Json.Str("super-secret")),
        status = "idle",
        createdAt = T0,
      )
      val str = ci.toString
      assertTrue(str.contains("ConnectorInstance"), str.contains("[redacted]"), !str.contains("super-secret"))
    },
    test("ConnectorType codec roundtrip") {
      import zio.json.*
      val values = ConnectorType.values.toSeq
      assertTrue(values.forall(v => v.toJson.fromJson[ConnectorType].contains(v)))
    },
  )

  private def baseJob: SchedulerJob =
    SchedulerJob(
      id = SchedulerJobId.empty,
      agentId = AgentId(1L),
      userId = UserId(1L),
      skillId = None,
      name = "test-job",
      inputJson = None,
      status = JobStatus.Pending,
      scheduledAt = T0,
      startedAt = None,
      finishedAt = None,
      resultJson = None,
      maxRetries = 0,
      retryCount = 0,
      backoffSeconds = 60,
      backoffPolicy = RetryBackoffPolicy.Fixed,
      missedRunPolicy = MissedRunPolicy.Skip,
      leasedAt = None,
      leasedBy = None,
      createdAt = T0,
    )

  private val schedulerJobSuite = suite("SchedulerJob")(
    test("validate passes for a well-formed job with no retries") {
      assertTrue(baseJob.validate.isRight)
    },
    test("validate passes for a job with retries and positive backoff") {
      assertTrue(baseJob.copy(maxRetries = 3, backoffSeconds = 30).validate.isRight)
    },
    test("validate fails when maxRetries is negative") {
      assertTrue(baseJob.copy(maxRetries = -1).validate.isLeft)
    },
    test("validate fails when maxRetries > 0 and backoffSeconds <= 0") {
      assertTrue(baseJob.copy(maxRetries = 2, backoffSeconds = 0).validate.isLeft)
    },
    test("validate passes when maxRetries is 0 and backoffSeconds is 0") {
      assertTrue(baseJob.copy(maxRetries = 0, backoffSeconds = 0).validate.isRight)
    },
    test("released clears leasedAt, leasedBy and sets new status and scheduledAt") {
      val leased = baseJob.copy(status = JobStatus.Running, leasedAt = Some(T0), leasedBy = Some("worker-1"))
      val released = leased.released(JobStatus.Pending, T0.plusSeconds(60))
      assertTrue(
        released.status == JobStatus.Pending,
        released.scheduledAt == T0.plusSeconds(60),
        released.leasedAt.isEmpty,
        released.leasedBy.isEmpty,
      )
    },
    test("MissedRunPolicy codec roundtrip") {
      import zio.json.*
      val values = MissedRunPolicy.values.toSeq
      assertTrue(values.forall(v => v.toJson.fromJson[MissedRunPolicy].contains(v)))
    },
    test("RetryBackoffPolicy codec roundtrip") {
      import zio.json.*
      val values = RetryBackoffPolicy.values.toSeq
      assertTrue(values.forall(v => v.toJson.fromJson[RetryBackoffPolicy].contains(v)))
    },
    test("SchedulerTrigger JSON roundtrip") {
      import zio.json.*
      val trigger = SchedulerTrigger(
        id = SchedulerTriggerId(1L),
        jobId = SchedulerJobId(2L),
        triggerType = TriggerType.Cron,
        expression = "0 0 * ? * 1",
        enabled = true,
        createdAt = T0,
      )
      val json = trigger.toJson
      val decoded = json.fromJson[SchedulerTrigger]
      assertTrue(decoded.contains(trigger))
    },
    test("SchedulerTrigger OneShot type encodes correctly") {
      import zio.json.*
      val trigger = SchedulerTrigger(
        id = SchedulerTriggerId.empty,
        jobId = SchedulerJobId.empty,
        triggerType = TriggerType.OneShot,
        expression = "2026-12-01T09:00:00Z",
        enabled = false,
        createdAt = T0,
      )
      assertTrue(trigger.toJson.contains("OneShot"), !trigger.enabled)
    },
    test("SchedulerTrigger Event type roundtrip") {
      import zio.json.*
      val trigger = SchedulerTrigger(
        id = SchedulerTriggerId.empty,
        jobId = SchedulerJobId.empty,
        triggerType = TriggerType.Event,
        expression = "agent.completed",
        enabled = true,
        createdAt = T0,
      )
      assertTrue(trigger.toJson.fromJson[SchedulerTrigger].contains(trigger))
    },
    test("SchedulerJob JSON roundtrip preserves all fields") {
      import zio.json.*
      val job = baseJob.copy(
        id = SchedulerJobId(5L),
        maxRetries = 3,
        backoffSeconds = 30,
        backoffPolicy = RetryBackoffPolicy.Exponential,
        missedRunPolicy = MissedRunPolicy.RunOnce,
        startedAt = Some(T0),
        resultJson = Some("""{"ok":true}"""),
      )
      assertTrue(job.toJson.fromJson[SchedulerJob].contains(job))
    },
  )

}
