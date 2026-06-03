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

}
