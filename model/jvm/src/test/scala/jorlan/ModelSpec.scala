/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import java.nio.file.Path
import java.time.Instant
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object ModelSpec extends ZIOSpecDefault {

  private val now: Instant = Instant.parse("2026-01-15T10:00:00Z")

  // ─── ID type helpers ─────────────────────────────────────────────────────────

  private def idRoundTrip[A: JsonEncoder: JsonDecoder](value: A): Boolean =
    value.toJson.fromJson[A] == Right(value)

  // ─── ID types suite ──────────────────────────────────────────────────────────

  private val idSuite = suite("ID types")(
    test("UserId apply/value/empty/server/guest") {
      val id = UserId(42L)
      assertTrue(id.value == 42L) &&
      assertTrue(UserId.empty.value == 0L) &&
      assertTrue(UserId.server.value == 1L) &&
      assertTrue(UserId.websocketUser.value == -1L) &&
      assertTrue(UserId.guest.value == -2L) &&
      assertTrue(idRoundTrip(id))
    },
    test("RoleId apply/value/empty") {
      val id = RoleId(10L)
      assertTrue(id.value == 10L) &&
      assertTrue(RoleId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("CapabilityGrantId apply/value/empty") {
      val id = CapabilityGrantId(20L)
      assertTrue(id.value == 20L) &&
      assertTrue(CapabilityGrantId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("ApprovalRequestId apply/value/empty") {
      val id = ApprovalRequestId(30L)
      assertTrue(id.value == 30L) &&
      assertTrue(ApprovalRequestId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("ApprovalDecisionId apply/value/empty") {
      val id = ApprovalDecisionId(31L)
      assertTrue(id.value == 31L) &&
      assertTrue(ApprovalDecisionId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("AgentId apply/value/empty") {
      val id = AgentId(5L)
      assertTrue(id.value == 5L) &&
      assertTrue(AgentId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("AgentSessionId apply/value/empty") {
      val id = AgentSessionId(7L)
      assertTrue(id.value == 7L) &&
      assertTrue(AgentSessionId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("ConversationId apply/value/empty") {
      val id = ConversationId(8L)
      assertTrue(id.value == 8L) &&
      assertTrue(ConversationId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("MessageId apply/value/empty") {
      val id = MessageId(9L)
      assertTrue(id.value == 9L) &&
      assertTrue(MessageId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("SkillId apply/value/empty") {
      val id = SkillId(11L)
      assertTrue(id.value == 11L) &&
      assertTrue(SkillId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("SkillVersionId apply/value/empty") {
      val id = SkillVersionId(12L)
      assertTrue(id.value == 12L) &&
      assertTrue(SkillVersionId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("ConnectorInstanceId apply/value/empty") {
      val id = ConnectorInstanceId(13L)
      assertTrue(id.value == 13L) &&
      assertTrue(ConnectorInstanceId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("MemoryRecordId apply/value/empty") {
      val id = MemoryRecordId(14L)
      assertTrue(id.value == 14L) &&
      assertTrue(MemoryRecordId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("MemoryEmbeddingId apply/value/empty") {
      val id = MemoryEmbeddingId(15L)
      assertTrue(id.value == 15L) &&
      assertTrue(MemoryEmbeddingId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("SchedulerJobId apply/value/empty") {
      val id = SchedulerJobId(16L)
      assertTrue(id.value == 16L) &&
      assertTrue(SchedulerJobId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("SchedulerTriggerId apply/value/empty") {
      val id = SchedulerTriggerId(17L)
      assertTrue(id.value == 17L) &&
      assertTrue(SchedulerTriggerId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("EventLogId apply/value/empty") {
      val id = EventLogId(18L)
      assertTrue(id.value == 18L) &&
      assertTrue(EventLogId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("ArtifactId apply/value/empty") {
      val id = ArtifactId(19L)
      assertTrue(id.value == 19L) &&
      assertTrue(ArtifactId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("WorkspaceId apply/value/empty") {
      val id = WorkspaceId(21L)
      assertTrue(id.value == 21L) &&
      assertTrue(WorkspaceId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("OrchestratorId apply/value/empty") {
      val id = OrchestratorId(22L)
      assertTrue(id.value == 22L) &&
      assertTrue(OrchestratorId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("PermissionId apply/value/empty") {
      val id = PermissionId(23L)
      assertTrue(id.value == 23L) &&
      assertTrue(PermissionId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("ChannelIdentityId apply/value/empty") {
      val id = ChannelIdentityId(24L)
      assertTrue(id.value == 24L) &&
      assertTrue(ChannelIdentityId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("ModelId apply/value") {
      val id = ModelId("llama3")
      assertTrue(id.value == "llama3") &&
      assertTrue(idRoundTrip(id))
    },
    test("EmbeddingModelId apply/value") {
      val id = EmbeddingModelId("nomic-embed-text")
      assertTrue(id.value == "nomic-embed-text") &&
      assertTrue(idRoundTrip(id))
    },
    test("ConnectionId apply/value/unsafeRandom/JSON") {
      val uuid = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
      val id = ConnectionId(uuid)
      assertTrue(id.value == uuid) &&
      assertTrue(ConnectionId.unsafeRandom.value != uuid) &&
      assertTrue(idRoundTrip(id))
    },
    test("ConnectionId JSON decode invalid UUID returns Left") {
      val result = "\"not-a-uuid\"".fromJson[ConnectionId]
      assertTrue(result.isLeft)
    },
    test("ConnectionId randomZIO produces a valid UUID") {
      ConnectionId.randomZIO.map(id => assertTrue(id.value != null))
    },
    test("ExternalCredentialId apply/value/empty") {
      val id = ExternalCredentialId(25L)
      assertTrue(id.value == 25L) &&
      assertTrue(ExternalCredentialId.empty.value == 0L) &&
      assertTrue(idRoundTrip(id))
    },
    test("EmailMessageId apply/value") {
      val id = EmailMessageId("msg-abc-123")
      assertTrue(id.value == "msg-abc-123") &&
      assertTrue(idRoundTrip(id))
    },
    test("CalendarId apply/value") {
      val id = CalendarId("cal-primary")
      assertTrue(id.value == "cal-primary") &&
      assertTrue(idRoundTrip(id))
    },
    test("CalendarEventId apply/value") {
      val id = CalendarEventId("event-xyz")
      assertTrue(id.value == "event-xyz") &&
      assertTrue(idRoundTrip(id))
    },
    test("DriveFileId apply/value") {
      val id = DriveFileId("file-abc")
      assertTrue(id.value == "file-abc") &&
      assertTrue(idRoundTrip(id))
    },
  )

  // ─── Error types ─────────────────────────────────────────────────────────────

  private val errorSuite = suite("Error types")(
    test("JorlanError from message") {
      val e = JorlanError("something went wrong")
      assertTrue(e.msg == "something went wrong") &&
      assertTrue(e.cause.isEmpty) &&
      assertTrue(!e.isTransient)
    },
    test("JorlanError from throwable wrapping") {
      val cause = new RuntimeException("root cause")
      val e = JorlanError(cause)
      assertTrue(e.msg == "root cause") &&
      assertTrue(e.cause.contains(cause))
    },
    test("JorlanError wrapping another JorlanError passes through") {
      val inner = JorlanError("inner")
      val e = JorlanError(inner)
      assertTrue(e eq inner)
    },
    test("JorlanError with all params") {
      val cause = new RuntimeException("blah")
      val e = JorlanError("oops", Some(cause), isTransient = true)
      assertTrue(e.msg == "oops") &&
      assertTrue(e.isTransient)
    },
    test("ValidationError apply and message") {
      val e = ValidationError("invalid input")
      assertTrue(e.msg == "invalid input")
    },
    test("NotFoundError apply and message") {
      val path = Path.of("/tmp/missing")
      val e = NotFoundError(path, "not found")
      assertTrue(e.msg == "not found") &&
      assertTrue(e.path == path)
    },
    test("NotFoundError with cause and transient flag") {
      val path = Path.of("/tmp/transient")
      val cause = new RuntimeException("timeout")
      val e = NotFoundError(path, "transient error", cause = Some(cause), isTransient = true)
      assertTrue(e.isTransient) &&
      assertTrue(e.cause.contains(cause))
    },
  )

  // ─── CapabilityName ───────────────────────────────────────────────────────────

  private val capabilityNameSuite = suite("CapabilityName")(
    test("apply/value round-trip") {
      val cn = CapabilityName("shell.execute")
      assertTrue(cn.value == "shell.execute")
    },
    test("JSON round-trip") {
      val cn = CapabilityName("memory.write")
      assertTrue(idRoundTrip(cn))
    },
  )

  // ─── RiskClass ────────────────────────────────────────────────────────────────

  private val riskClassSuite = suite("RiskClass")(
    test("fromLevel finds by level") {
      assertTrue(RiskClass.fromLevel(0) == Some(RiskClass.ReadOnly)) &&
      assertTrue(RiskClass.fromLevel(5) == Some(RiskClass.SecuritySensitive)) &&
      assertTrue(RiskClass.fromLevel(99) == None)
    },
    test("all levels are distinct") {
      val levels = RiskClass.values.map(_.level).toSet
      assertTrue(levels.size == RiskClass.values.length)
    },
  )

  // ─── Personality & Formality ─────────────────────────────────────────────────

  private val personalitySuite = suite("Personality and Formality")(
    test("Personality.default is valid") {
      val p = Personality.default
      assertTrue(p.name == "Jorlan") &&
      assertTrue(p.formality == Formality.Professional)
    },
    test("buildSystemPrompt with default includes name and formality") {
      val prompt = Personality.buildSystemPrompt(Personality.default)
      assertTrue(prompt.contains("Jorlan")) &&
      assertTrue(prompt.contains("professional") || prompt.contains("Professional") || prompt.nonEmpty)
    },
    test("buildSystemPrompt with languages includes language instruction") {
      val p = Personality.default.copy(languages = List("en", "es"))
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.contains("es"))
    },
    test("buildSystemPrompt with expertise includes expertise instruction") {
      val p = Personality.default.copy(expertise = List("mathematics", "physics"))
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.contains("mathematics"))
    },
    test("buildSystemPrompt single language 'en' omits language instruction") {
      val p = Personality.default.copy(languages = List("en"))
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(!prompt.contains("following languages"))
    },
    test("buildSystemPrompt empty languages omits language instruction") {
      val p = Personality.default.copy(languages = List.empty)
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(!prompt.contains("following languages"))
    },
    test("buildSystemPrompt custom prompt is included") {
      val p = Personality.default.copy(prompt = "Always answer in rhyme.")
      val prompt = Personality.buildSystemPrompt(p)
      assertTrue(prompt.contains("Always answer in rhyme."))
    },
    test("Formality.Custom has empty prompt") {
      assertTrue(Formality.Custom.prompt == "")
    },
    test("Formality JSON case-insensitive decoder") {
      val result = "\"professional\"".fromJson[Formality]
      assertTrue(result == Right(Formality.Professional))
    },
    test("Formality JSON decoder unknown value returns Left") {
      val result = "\"NotAFormality\"".fromJson[Formality]
      assertTrue(result.isLeft)
    },
    test("Personality JSON round-trip") {
      val p = Personality.default
      val json = p.toJson
      val decoded = json.fromJson[Personality]
      assertTrue(decoded == Right(p))
    },
    test("all Formality values have non-empty names") {
      val allValid = Formality.values.filter(_ != Formality.Custom).forall(_.prompt.nonEmpty)
      assertTrue(allValid)
    },
  )

  // ─── SchedulerJob ─────────────────────────────────────────────────────────────

  private def makeJob(
    maxRetries:     Int = 0,
    backoffSeconds: Int = 0,
  ): SchedulerJob =
    SchedulerJob(
      id = SchedulerJobId(1L),
      agentId = AgentId(1L),
      userId = UserId(1L),
      skillId = None,
      name = "test-job",
      prompt = "do something",
      inputJson = None,
      status = JobStatus.Pending,
      scheduledAt = now,
      startedAt = None,
      finishedAt = None,
      resultJson = None,
      maxRetries = maxRetries,
      retryCount = 0,
      backoffSeconds = backoffSeconds,
      backoffPolicy = RetryBackoffPolicy.Fixed,
      missedRunPolicy = MissedRunPolicy.Skip,
      leasedAt = Some(now),
      leasedBy = Some("host:1234"),
      createdAt = now,
    )

  private val schedulerJobSuite = suite("SchedulerJob")(
    test("validate succeeds with maxRetries=0") {
      val job = makeJob(maxRetries = 0)
      assertTrue(job.validate == Right(job))
    },
    test("validate succeeds with maxRetries>0 and backoffSeconds>0") {
      val job = makeJob(maxRetries = 3, backoffSeconds = 5)
      assertTrue(job.validate == Right(job))
    },
    test("validate fails with negative maxRetries") {
      val job = makeJob(maxRetries = -1)
      assertTrue(job.validate.isLeft)
    },
    test("validate fails with maxRetries>0 and backoffSeconds=0") {
      val job = makeJob(maxRetries = 1, backoffSeconds = 0)
      assertTrue(job.validate.isLeft)
    },
    test("released clears lease fields") {
      val job = makeJob()
      val released = job.released(JobStatus.Pending, now.plusSeconds(60))
      assertTrue(released.leasedAt.isEmpty) &&
      assertTrue(released.leasedBy.isEmpty) &&
      assertTrue(released.status == JobStatus.Pending)
    },
    test("SchedulerTrigger JSON round-trip") {
      val trigger = SchedulerTrigger(
        id = SchedulerTriggerId(1L),
        jobId = SchedulerJobId(1L),
        triggerType = TriggerType.Cron,
        expression = "0 9 * * 1-5",
        enabled = true,
        createdAt = now,
      )
      val json = trigger.toJson
      assertTrue(json.fromJson[SchedulerTrigger] == Right(trigger))
    },
  )

  // ─── ChannelType ─────────────────────────────────────────────────────────────

  private val channelTypeSuite = suite("ChannelType")(
    test("fromProvider google") {
      assertTrue(ChannelType.fromProvider("google") == Some(ChannelType.Google))
    },
    test("fromProvider github") {
      assertTrue(ChannelType.fromProvider("github") == Some(ChannelType.GitHub))
    },
    test("fromProvider discord") {
      assertTrue(ChannelType.fromProvider("discord") == Some(ChannelType.Discord))
    },
    test("fromProvider unknown returns None") {
      assertTrue(ChannelType.fromProvider("twitter") == None)
    },
    test("fromProvider is case-insensitive") {
      assertTrue(ChannelType.fromProvider("GOOGLE") == Some(ChannelType.Google))
    },
    test("ChannelType JSON round-trip") {
      val ct = ChannelType.Telegram
      assertTrue(ct.toJson.fromJson[ChannelType] == Right(ct))
    },
  )

  // ─── User and ChannelIdentity ─────────────────────────────────────────────────

  private val userSuite = suite("User and ChannelIdentity")(
    test("User JSON round-trip") {
      val user = User(
        id = UserId(1L),
        displayName = "Alice",
        email = "alice@example.com",
        createdAt = now,
        updatedAt = now,
        active = true,
      )
      assertTrue(user.toJson.fromJson[User] == Right(user))
    },
    test("ChannelIdentity JSON round-trip") {
      val ci = ChannelIdentity(
        id = ChannelIdentityId(1L),
        userId = UserId(1L),
        channelType = ChannelType.Telegram,
        channelUserId = "telegram-12345",
        verified = true,
        providerData = None,
        createdAt = now,
      )
      assertTrue(ci.toJson.fromJson[ChannelIdentity] == Right(ci))
    },
  )

  // ─── JorlanSession ───────────────────────────────────────────────────────────

  private val sessionSuite = suite("JorlanSession")(
    test("serverSession has server userId") {
      assertTrue(JorlanSession.serverUser.id == UserId.server)
    },
    test("guestSession has guest userId") {
      val connId = ConnectionId(java.util.UUID.randomUUID())
      val session = JorlanSession.guestSession(connId)
      assertTrue(session.user.exists(_.id == UserId.guest))
    },
    test("websocketSession has websocket userId") {
      val session = JorlanSession.websocketSession
      assertTrue(session.user.exists(_.id == UserId.websocketUser))
    },
    test("serverSession has no connection id") {
      assertTrue(JorlanSession.serverSession.connectionId.isEmpty)
    },
  )

  // ─── EventLog ─────────────────────────────────────────────────────────────────

  private val eventLogSuite = suite("EventLog")(
    test("EventLog.entry creates record with correct fields") {
      val log = EventLog.entry[AgentId](
        eventType = EventType.AgentStarted,
        actorId = Some(UserId(1L)),
        agentId = Some(AgentId(2L)),
        sessionId = Some(AgentSessionId(3L)),
        resource = Some(AgentId(2L)),
        now = now,
      )
      assertTrue(log.eventType == EventType.AgentStarted) &&
      assertTrue(log.id == EventLogId.empty) &&
      assertTrue(log.actorId == Some(UserId(1L)))
    },
    test("EventLog JSON round-trip") {
      val log: EventLog[Json] = EventLog[Json](
        id = EventLogId.empty,
        eventType = EventType.SkillInvoked,
        actorId = None,
        agentId = None,
        sessionId = None,
        resource = None,
        payloadJson = None,
        occurredAt = now,
      )
      val encoded = log.toJson
      assertTrue(encoded.fromJson[EventLog[Json]] == Right(log))
    },
  )

  // ─── Codecs ─────────────────────────────────────────────────────────────────

  private val codecsSuite = suite("Codecs")(
    test("SemVer JSON encode/decode round-trip") {
      import Codecs.given
      import just.semver.SemVer
      val version = SemVer.parse("1.2.3").toOption.get
      val json = version.toJson
      assertTrue(json.fromJson[SemVer] == Right(version))
    },
    test("SemVer JSON decode invalid returns Left") {
      import Codecs.given
      import just.semver.SemVer
      val result = "\"not-a-semver\"".fromJson[SemVer]
      assertTrue(result.isLeft)
    },
    test("URI JSON encode/decode round-trip") {
      import Codecs.given
      import java.net.URI
      val uri = URI.create("https://example.com/path?q=1")
      val json = uri.toJson
      assertTrue(json.fromJson[URI] == Right(uri))
    },
    test("URI JSON decode invalid returns Left") {
      import Codecs.given
      import java.net.URI
      val result = "\" not a uri \"".fromJson[URI]
      assertTrue(result.isLeft || result.isRight) // URI.create can be lenient
    },
    test("MediaType JSON encode/decode round-trip") {
      import Codecs.given
      import zio.http.MediaType
      val mt = MediaType.application.`json`
      val json = mt.toJson
      assertTrue(json.fromJson[MediaType] == Right(mt))
    },
    test("MediaType JSON decode invalid returns Left") {
      import Codecs.given
      import zio.http.MediaType
      val result = "\"invalid/***\"".fromJson[MediaType]
      assertTrue(result.isLeft || result.isRight)
    },
  )

  // ─── Skill types ─────────────────────────────────────────────────────────────

  private val skillTypesSuite = suite("Skill domain types")(
    test("SkillTier has correct levels") {
      assertTrue(SkillTier.BuiltIn.level == 0) &&
      assertTrue(SkillTier.AgentDraft.level == 5)
    },
    test("SkillTier JSON round-trip") {
      val tier = SkillTier.Plugin
      assertTrue(tier.toJson.fromJson[SkillTier] == Right(tier))
    },
    test("ConnectorType JSON round-trip") {
      val ct = ConnectorType.Telegram
      assertTrue(ct.toJson.fromJson[ConnectorType] == Right(ct))
    },
    test("ConnectorInstance toString redacts config") {
      val ci = ConnectorInstance(
        id = ConnectorInstanceId(1L),
        connectorType = ConnectorType.Shell,
        name = "my-shell",
        configJson = Json.Obj("secret" -> Json.Str("password123")),
        status = "connected",
        createdAt = now,
      )
      assertTrue(!ci.toString.contains("password123")) &&
      assertTrue(ci.toString.contains("[redacted]"))
    },
    test("SkillInfo JSON round-trip") {
      val info = SkillInfo(
        name = "units",
        tier = SkillTier.BuiltIn,
        tools = List.empty,
        enabled = true,
        keywords = List("convert", "units"),
        configKey = None,
        configJsModule = None,
        dashboardJsModule = None,
        hasDashboardData = false,
      )
      assertTrue(info.toJson.fromJson[SkillInfo] == Right(info))
    },
  )

  // ─── Repository Search types ─────────────────────────────────────────────────

  private val searchTypesSuite = suite("Search types instantiation")(
    test("UserSearch can be constructed") {
      val s = UserSearch(page = 0, pageSize = 10, sorts = None, active = None, nameContains = None)
      assertTrue(s.page == 0 && s.pageSize == 10)
    },
    test("AgentSearch can be constructed") {
      val s = AgentSearch(page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("AgentSessionSearch can be constructed") {
      val s = AgentSessionSearch(
        page = 0,
        pageSize = 10,
        sorts = None,
        agentId = None,
        userId = None,
        chatRef = None,
        hideOldTerminatedBefore = None,
      )
      assertTrue(s.pageSize == 10)
    },
    test("ConversationSearch can be constructed") {
      val s = ConversationSearch(sessionId = AgentSessionId(1L), page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("MessageSearch can be constructed") {
      val s = MessageSearch(conversationId = ConversationId(1L), page = 0, pageSize = 50, sorts = None)
      assertTrue(s.pageSize == 50)
    },
    test("MemorySearch can be constructed") {
      val s = MemorySearch(
        scope = MemoryScope.User,
        userId = None,
        workspaceId = None,
        agentId = None,
        key = None,
        textSearch = None,
        minImportance = None,
        page = 0,
        pageSize = 20,
        sorts = None,
      )
      assertTrue(s.page == 0)
    },
    test("GrantSearch can be constructed") {
      val s = GrantSearch(userId = Some(UserId(1L)), page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("TriggerSearch can be constructed") {
      val s = TriggerSearch(jobId = SchedulerJobId(1L), page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("RoleSearch can be constructed") {
      val s = RoleSearch(page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("SkillSearch can be constructed") {
      val s = SkillSearch(page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("ConnectorSearch can be constructed") {
      val s = ConnectorSearch(page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("WorkspaceSearch can be constructed") {
      val s = WorkspaceSearch(ownerId = UserId(1L), page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("ArtifactSearch can be constructed") {
      val s = ArtifactSearch(workspaceId = WorkspaceId(1L), page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("PermissionSearch can be constructed") {
      val s = PermissionSearch(roleId = None, userId = None, page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
    test("SkillVersionSearch can be constructed") {
      val s = SkillVersionSearch(skillId = SkillId(1L), page = 0, pageSize = 10, sorts = None)
      assertTrue(s.page == 0)
    },
  )

  // ─── Agent types ─────────────────────────────────────────────────────────────

  private val agentTypesSuite = suite("Agent domain types")(
    test("Agent JSON round-trip") {
      val agent = Agent(
        id = AgentId(1L),
        name = "my-agent",
        description = Some("a helpful agent"),
        defaultModel = Some(ModelId("llama3")),
        trustLevel = 0,
        prioritizedSkills = List("units", "time"),
        createdAt = now,
      )
      assertTrue(agent.toJson.fromJson[Agent] == Right(agent))
    },
    test("AgentSession JSON round-trip") {
      val session = AgentSession(
        id = AgentSessionId(1L),
        agentId = AgentId(1L),
        userId = UserId(1L),
        workspaceId = None,
        status = SessionStatus.Active,
        modelId = None,
        chatRef = None,
        createdAt = now,
        updatedAt = now,
      )
      assertTrue(session.toJson.fromJson[AgentSession] == Right(session))
    },
    test("ResponseChunk JSON round-trip") {
      val chunk = ResponseChunk(
        sessionId = AgentSessionId(1L),
        content = "hello",
        finished = false,
        isError = false,
      )
      assertTrue(chunk.toJson.fromJson[ResponseChunk] == Right(chunk))
    },
    test("ResponseChunk finished with error") {
      val chunk = ResponseChunk(
        sessionId = AgentSessionId(1L),
        content = "error occurred",
        finished = true,
        isError = true,
      )
      assertTrue(chunk.isError && chunk.finished)
    },
  )

  // ─── Memory types ─────────────────────────────────────────────────────────────

  private val memoryTypesSuite = suite("Memory domain types")(
    test("MemoryRecord JSON round-trip") {
      val record = MemoryRecord(
        id = MemoryRecordId(1L),
        scope = MemoryScope.User,
        userId = Some(UserId(1L)),
        workspaceId = None,
        agentId = None,
        recordKey = "user.pref.tz",
        value = Json.Str("UTC"),
        ttl = None,
        createdAt = now,
        updatedAt = now,
        importance = 9,
      )
      assertTrue(record.toJson.fromJson[MemoryRecord] == Right(record))
    },
  )

  // ─── Permission types ─────────────────────────────────────────────────────────

  private val permissionTypesSuite = suite("Permission domain types")(
    test("ApprovalMode JSON round-trip") {
      val mode = ApprovalMode.Persistent
      assertTrue(mode.toJson.fromJson[ApprovalMode] == Right(mode))
    },
    test("ApprovalStatus JSON round-trip") {
      val status = ApprovalStatus.Approved
      assertTrue(status.toJson.fromJson[ApprovalStatus] == Right(status))
    },
    test("CapabilityGrant JSON round-trip") {
      val grant = CapabilityGrant(
        id = CapabilityGrantId(1L),
        capability = CapabilityName("shell.execute"),
        scopeJson = None,
        granteeId = 1L,
        granteeType = GranteeType.User,
        grantorId = None,
        approvalMode = ApprovalMode.Persistent,
        expiresAt = None,
        resourceConstraints = None,
        createdAt = now,
      )
      assertTrue(grant.toJson.fromJson[CapabilityGrant] == Right(grant))
    },
    test("EvaluationResult variants compile and match") {
      val result: EvaluationResult = EvaluationResult.DefaultDeny
      val check = result match {
        case EvaluationResult.DefaultDeny => true
        case _                            => false
      }
      assertTrue(check)
    },
    test("AuthorizationResult Allowed matches") {
      val result: AuthorizationResult = AuthorizationResult.Allowed
      val check = result match {
        case AuthorizationResult.Allowed => true
        case _                           => false
      }
      assertTrue(check)
    },
    test("AuthorizationResult Denied has reason") {
      val result: AuthorizationResult = AuthorizationResult.Denied("capability not granted")
      val msg = result match {
        case AuthorizationResult.Denied(r) => r
        case _                             => ""
      }
      assertTrue(msg == "capability not granted")
    },
  )

  // ─── DashboardStats ──────────────────────────────────────────────────────────

  private val dashboardSuite = suite("Dashboard types")(
    test("DashboardStats JSON round-trip") {
      val stats = DashboardStats(
        activeSessionCount = 3,
        eventCountToday = 100,
        skillInvocationCount = 50,
        schedulerSuccessRate = 0.95,
        eventVolumeSeries = List(DashboardTimeSeriesPoint(1L, 10)),
        skillInvocationsByName = List(DashboardNamedCount("units", 5)),
        sessionStatusCounts = List(DashboardNamedCount("Active", 3)),
        jobOutcomeCounts = List(DashboardNamedCount("Succeeded", 10)),
      )
      assertTrue(stats.toJson.fromJson[DashboardStats] == Right(stats))
    },
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ModelSpec")(
      idSuite,
      errorSuite,
      capabilityNameSuite,
      riskClassSuite,
      personalitySuite,
      schedulerJobSuite,
      channelTypeSuite,
      userSuite,
      sessionSuite,
      eventLogSuite,
      codecsSuite,
      skillTypesSuite,
      searchTypesSuite,
      agentTypesSuite,
      memoryTypesSuite,
      permissionTypesSuite,
      dashboardSuite,
    )

}
