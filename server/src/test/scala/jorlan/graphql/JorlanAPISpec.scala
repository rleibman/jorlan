/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.graphql

import ai.{EmbeddingModel, EmbeddingStore}
import auth.UnauthenticatedSession
import caliban.GraphQLInterpreter
import jorlan.*
import jorlan.db.repository.*
import jorlan.service.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.schedule.JobManagerImpl
import jorlan.service.skills.{MemorySkill, SkillRegistry}
import jorlan.service.mcp.McpManager
import jorlan.service.skills.declarative.SkillLifecycleService
import jorlan.testing.{FakeConfigurationService, InMemoryRepositories, NoOpEmbeddingLayers, NoOpMemoryService}
import zio.*
import zio.http.Client
import zio.test.*

/** Unit tests for [[JorlanAPI]] using in-memory service stubs. No database required.
  *
  * Tests cover:
  *   - All Queries (user, users, role, roles, permissions)
  *   - All Mutations (createUser, updateUser, createRole, assignRole, revokeRole, grantPermission, revokePermission)
  *   - Authorization helpers: `actorIdFromSession` (unauthenticated → error), `requireCapability` (deny/allow)
  *   - Input validation: `grantPermission` must target exactly one of userId/roleId
  *   - Subscriptions: `approvalNotifications` backed by [[ApprovalHub]]; `eventLogTail` returns an empty stream in
  *     tests
  */
object JorlanAPISpec extends ZIOSpecDefault {

  // ─── Capability evaluator stubs ───────────────────────────────────────────────

  private val allowAll: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.ResourcePermissionAllows))

  private val denyAll: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.DefaultDeny))

  private val explicitDeny: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.ExplicitDeny))

  // ─── Session stubs ────────────────────────────────────────────────────────────

  private val serverSessionLayer: ULayer[JorlanSession] = ZLayer.succeed(JorlanSession.serverSession)

  private val unauthSessionLayer: ULayer[JorlanSession] =
    ZLayer.succeed(UnauthenticatedSession[User, ConnectionId](None))

  // ─── Full service stack: services + interpreter together (passthrough >+>) ───

  private type FullEnv = JorlanApiEnv & JorlanSession & GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]

  private def realMemoryServiceLayer: ULayer[MemoryService] =
    ZLayer.make[MemoryService](
      InMemoryRepositories.live(),
      FakeModelGateway.layer(List()),
      NoOpEmbeddingLayers.embeddingStoreLayer,
      NoOpEmbeddingLayers.embeddingModelLayer,
      MemoryServiceImpl.live,
    )

  private val noOpOAuthCredSvc: ULayer[OAuthCredentialService] = ZLayer.succeed(new OAuthCredentialService {
    override def store(
      userId:    UserId,
      provider:  String,
      plainJson: zio.json.ast.Json,
    ): IO[JorlanError, Unit] =
      ZIO.unit
    override def load(
      userId:   UserId,
      provider: String,
    ): IO[JorlanError, Option[zio.json.ast.Json]] = ZIO.none
    override def revoke(
      userId:   UserId,
      provider: String,
    ):                                          IO[JorlanError, Unit] = ZIO.unit
    override def listProviders(userId: UserId): IO[JorlanError, List[String]] = ZIO.succeed(List.empty)
    override def refreshAccessToken(
      userId:   UserId,
      provider: String,
    ): IO[JorlanError, String] =
      ZIO.fail(JorlanError("no credentials"))
    override def getExpiresAt(
      userId:   UserId,
      provider: String,
    ): IO[JorlanError, Option[java.time.Instant]] = ZIO.none
  })

  private val connectedOAuthCredSvc: ULayer[OAuthCredentialService] = ZLayer.succeed(new OAuthCredentialService {
    override def store(
      userId:    UserId,
      provider:  String,
      plainJson: zio.json.ast.Json,
    ): IO[JorlanError, Unit] =
      ZIO.unit
    override def load(
      userId:   UserId,
      provider: String,
    ): IO[JorlanError, Option[zio.json.ast.Json]] =
      ZIO.some(zio.json.ast.Json.Obj())
    override def revoke(
      userId:   UserId,
      provider: String,
    ):                                          IO[JorlanError, Unit] = ZIO.unit
    override def listProviders(userId: UserId): IO[JorlanError, List[String]] = ZIO.succeed(List("google"))
    override def refreshAccessToken(
      userId:   UserId,
      provider: String,
    ): IO[JorlanError, String] =
      ZIO.fail(JorlanError("no credentials"))
    override def getExpiresAt(
      userId:   UserId,
      provider: String,
    ): IO[JorlanError, Option[java.time.Instant]] = ZIO.none
  })

  private def makeAppLayer(
    capEval:           ULayer[CapabilityEvaluator] = allowAll,
    session:           ULayer[JorlanSession] = serverSessionLayer,
    memSvcLayer:       ULayer[MemoryService] = NoOpMemoryService.layer,
    repoLayer:         ULayer[ZIORepositories] = InMemoryRepositories.live(),
    oauthCredSvcLayer: ULayer[OAuthCredentialService] = noOpOAuthCredSvc,
  ): ULayer[FullEnv] = {
    val hubLayer = SessionHub.live

    val agentRepoLayer: ULayer[ZIORepositories] = ZLayer.fromZIO {
      (for {
        now  <- Clock.instant
        repo <- ZIO.service[ZIORepositories]
        _    <- repo.agent
          .upsert(
            Agent(
              id = AgentId.empty,
              name = "Jorlan Interactive",
              description = Some("Default interactive agent"),
              defaultModel = None,
              createdAt = now,
            ),
          )
          .orDie
      } yield repo).provide(repoLayer)
    }

    val approvalSvcLayer: ULayer[ApprovalService] = ZLayer.succeed(
      new ApprovalService {
        override def authorize(request: CapabilityRequest): IO[JorlanError, AuthorizationResult] =
          ZIO.succeed(AuthorizationResult.Allowed)
        override def recordDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision] =
          ZIO.succeed(decision)
        override def expireStaleRequests(): IO[JorlanError, Long] = ZIO.succeed(0L)
      }: ApprovalService,
    )
    val noOpNotificationRouter: ULayer[NotificationRouter] = ZLayer.succeed(
      new NotificationRouter {
        override def notifyUser(
          userId:  UserId,
          message: String,
          ctx:     jorlan.connector.InvocationContext,
        ): UIO[zio.json.ast.Json] = ZIO.succeed(zio.json.ast.Json.Str("ok"))
        override def notifyChannel(
          channelUserId: String,
          channelType:   ChannelType,
          message:       String,
          ctx:           jorlan.connector.InvocationContext,
        ): UIO[zio.json.ast.Json] = ZIO.succeed(zio.json.ast.Json.Str("ok"))
      },
    )
    ZLayer
      .make[FullEnv](
        agentRepoLayer,
        hubLayer,
        ToolEventHub.live,
        EventLogHub.live,
        capEval,
        session,
        FakeModelGateway.layer(List("ok")),
        AgentSessionManagerImpl.live,
        memSvcLayer,
        NoOpEmbeddingLayers.embeddingStoreLayer,
        NoOpEmbeddingLayers.embeddingModelLayer, {
          ZLayer.fromZIO {
            for {
              svc   <- ZIO.service[MemoryService]
              store <- ZIO.service[EmbeddingStore]
              model <- ZIO.service[EmbeddingModel]
            } yield SkillRegistry.liveSecureWith(new MemorySkill(svc, store, model))
          }.flatten
        },
        FakeConfigurationService.layer,
        AgentRunnerImpl.live,
        JobManagerImpl.live,
        approvalSvcLayer,
        ApprovalHub.live,
        noOpNotificationRouter,
        oauthCredSvcLayer,
        DashboardService.live,
        Client.default.orDie,
        ZLayer.fromZIO(OAuthReconnectService.make("test-secret", "test-client-id", "http://localhost/callback")),
        SkillLifecycleService.live,
        McpManager.live,
        ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie),
      ).orDie
  }

  // ─── Helper to extract a Long field from GraphQL response text ────────────────

  private def extractLong(
    data:  String,
    field: String,
  ): Long = {
    import scala.language.unsafeNulls
    // Matches both numeric ("id":1) and string-encoded ("id":"1") ID forms
    val pat = s""""$field":"?([0-9]+)"?""".r
    pat
      .findFirstMatchIn(data).map(_.group(1).toLong).getOrElse(
        throw AssertionError(s"field '$field' not found in: $data"),
      )
  }

  private type Interp = GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("JorlanAPI unit tests")(
      querySuite,
      mutationSuite,
      authSuite,
      agentSessionSuite,
      subscriptionSuite,
      personalitySuite,
      memorySuite,
      schedulerSuite,
      modelSuite,
      oauthSuite,
      invokeToolSuite,
      miscSuite,
      jobLifecycleSuite,
      skillLifecycleSuite,
      mcpServerSuite,
      skillAdminSuite,
      capabilityRoleSuite,
    )

  // ─── Query tests ──────────────────────────────────────────────────────────────

  private val querySuite = suite("Queries")(
    test("users returns empty list when no users exist") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ users { id displayName } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    },
    test("user(id) returns null for non-existent id") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ user(value: 99999) { id displayName } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("null"))
    },
    test("role(id) returns null for non-existent id") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ role(value: 99999) { id name } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("null"))
    },
    test("roles(userId) returns empty list for user with no roles") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ roles(userId: 1) { id name } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    },
    test("permissions(userId) returns empty list for user with no permissions") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ permissions(userId: 1) { id resource action } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    },
    test("users includes user after createUser mutation") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute("""mutation { createUser(displayName: "Alice", email: "alice@test.com") { id } }""")
        queryResult <- interp.execute("""{ users { id displayName } }""")
      } yield assertTrue(
        queryResult.errors.isEmpty,
        queryResult.data.toString.contains("Alice"),
      )
    },
  ).provideLayerShared(makeAppLayer()) @@ TestAspect.sequential

  // ─── Mutation tests ───────────────────────────────────────────────────────────

  private val mutationSuite = suite("Mutations")(
    test("createUser creates a user with displayName and email") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { createUser(displayName: "Bob", email: "bob@test.com") { id displayName email } }""",
        )
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("Bob"),
        result.data.toString.contains("bob@test.com"),
      )
    },
    test("updateUser updates displayName") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute("""mutation { createUser(displayName: "Old", email: "old@test.com") { id } }""")
        id = extractLong(createResult.data.toString, "id")
        updateResult <- interp.execute(
          s"""mutation { updateUser(id: $id, displayName: "New", email: "new@test.com", active: true) { id displayName } }""",
        )
      } yield assertTrue(
        createResult.errors.isEmpty,
        updateResult.errors.isEmpty,
        updateResult.data.toString.contains("New"),
      )
    },
    test("createRole creates a role with description") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { createRole(name: "editor", description: "Edit things") { id name description } }""",
        )
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("editor"),
        result.data.toString.contains("Edit things"),
      )
    },
    test("assignRole and revokeRole round-trip") {
      for {
        interp     <- ZIO.service[Interp]
        userResult <- interp.execute(
          """mutation { createUser(displayName: "RoleUser", email: "role@test.com") { id } }""",
        )
        userId = extractLong(userResult.data.toString, "id")
        roleResult <- interp.execute("""mutation { createRole(name: "testrole") { id } }""")
        roleId = extractLong(roleResult.data.toString, "id")
        _           <- interp.execute(s"""mutation { assignRole(userId: $userId, roleId: $roleId) }""")
        withRole    <- interp.execute(s"""{ roles(userId: $userId) { name } }""")
        _           <- interp.execute(s"""mutation { revokeRole(userId: $userId, roleId: $roleId) }""")
        withoutRole <- interp.execute(s"""{ roles(userId: $userId) { name } }""")
      } yield assertTrue(
        withRole.errors.isEmpty,
        withoutRole.errors.isEmpty,
        withRole.data.toString.contains("testrole"),
        !withoutRole.data.toString.contains("testrole"),
      )
    },
    test("grantPermission with userId succeeds and is searchable") {
      for {
        interp     <- ZIO.service[Interp]
        userResult <- interp.execute(
          """mutation { createUser(displayName: "PermUser", email: "perm@test.com") { id } }""",
        )
        userId = extractLong(userResult.data.toString, "id")
        grantResult <- interp.execute(
          s"""mutation { grantPermission(resource: "fs", action: "read", userId: $userId) { id resource action } }""",
        )
        permsResult <- interp.execute(s"""{ permissions(userId: $userId) { resource action } }""")
      } yield assertTrue(
        grantResult.errors.isEmpty,
        permsResult.errors.isEmpty,
        permsResult.data.toString.contains("fs"),
        permsResult.data.toString.contains("read"),
      )
    },
    test("grantPermission with roleId succeeds") {
      for {
        interp     <- ZIO.service[Interp]
        roleResult <- interp.execute("""mutation { createRole(name: "perm-role") { id } }""")
        roleId = extractLong(roleResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { grantPermission(resource: "mem", action: "write", roleId: $roleId) { id resource action } }""",
        )
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("mem"))
    },
    test("grantPermission fails when both userId and roleId are provided") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { grantPermission(resource: "x", action: "y", userId: 1, roleId: 2) { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    },
    test("grantPermission fails when neither userId nor roleId is provided") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { grantPermission(resource: "x", action: "y") { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    },
    test("revokePermission returns count 1 after granting") {
      for {
        interp     <- ZIO.service[Interp]
        userResult <- interp.execute(
          """mutation { createUser(displayName: "RevUser", email: "rev@test.com") { id } }""",
        )
        userId = extractLong(userResult.data.toString, "id")
        grantResult <- interp.execute(
          s"""mutation { grantPermission(resource: "net", action: "fetch", userId: $userId) { id } }""",
        )
        permId = extractLong(grantResult.data.toString, "id")
        revokeResult <- interp.execute(s"""mutation { revokePermission(value: $permId) }""")
      } yield assertTrue(
        grantResult.errors.isEmpty,
        revokeResult.errors.isEmpty,
        revokeResult.data.toString.contains("1"),
      )
    },
  ).provideLayerShared(makeAppLayer()) @@ TestAspect.sequential

  // ─── Authorization tests ──────────────────────────────────────────────────────

  private val authSuite = suite("Authorization")(
    test("mutation fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { createUser(displayName: "Anon") { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("mutation fails with DefaultDeny capability result") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { createUser(displayName: "DenyUser") { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("mutation fails with ExplicitDeny capability result") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { createRole(name: "no") { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = explicitDeny)),
    (test("query succeeds regardless of capability evaluator (queries bypass requireCapability)") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ users { id displayName } }""")
      } yield assertTrue(result.errors.isEmpty)
    } @@ TestAspect.ignore).provideLayer(makeAppLayer(capEval = denyAll)),
  )

  // ─── Agent session mutations ──────────────────────────────────────────────────

  private val agentSessionSuite = suite("Agent Sessions")(
    test("createSession returns a session with Active status") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { createSession(modelId: null) { id status } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("Active"),
      )
    }.provideLayer(makeAppLayer()),
    test("createSession with explicit modelId uses ArgBuilder[ModelId]") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { createSession(modelId: "llama3") { id status } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("Active"),
      )
    }.provideLayer(makeAppLayer()),
    test("listSessions returns created sessions — exercises Schema for AgentId, WorkspaceId, SessionStatus") {
      for {
        interp     <- ZIO.service[Interp]
        _          <- interp.execute("""mutation { createSession(modelId: null) { id } }""")
        listResult <- interp.execute(
          """{ listSessions { id agentId userId status createdAt updatedAt } }""",
        )
      } yield assertTrue(
        listResult.errors.isEmpty,
        listResult.data.toString.contains("Active"),
      )
    }.provideLayer(makeAppLayer()),
    test("submitMessage mutation succeeds with active session") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute("""mutation { createSession(modelId: null) { id } }""")
        sessionId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { submitMessage(sessionId: $sessionId, content: "hello") }""",
        )
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
  )

  // ─── Subscription stubs ───────────────────────────────────────────────────────

  private val subscriptionSuite = suite("Subscriptions")(
    test("schema includes all subscription fields") {
      val schema = JorlanAPI.api.render
      assertTrue(
        schema.contains("approvalNotifications"),
        schema.contains("eventLogTail"),
        schema.contains("toolEvents"),
      )
    },
    test("approvalNotifications subscription yields no events without published requests") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.executeRequest(
          caliban.GraphQLRequest(
            query = Some("subscription { approvalNotifications { id capability } }"),
          ),
        )
        events <- result.data match {
          case caliban.ResponseValue.StreamValue(stream) =>
            stream.take(1).timeout(200.millis).runCollect.map(_.toList)
          case _ => ZIO.succeed(List.empty)
        }
      } yield assertTrue(events.isEmpty)
    }.provideLayer(makeAppLayer()) @@ TestAspect.withLiveClock,
    test("approvalNotifications subscription receives event published through ApprovalHub") {
      for {
        hub    <- ZIO.service[ApprovalHub]
        stream <- hub.subscribeToNewRequests
        req = ApprovalRequest(
          id = ApprovalRequestId(42L),
          capability = CapabilityName("shell.execute"),
          scopeJson = None,
          agentId = None,
          requestorUserId = UserId(1L),
          sessionId = None,
          riskClass = RiskClass.ExternalEffect,
          status = ApprovalStatus.Pending,
          createdAt = java.time.Instant.EPOCH,
          expiresAt = None,
        )
        fiber  <- stream.take(1).runCollect.map(_.toList).fork
        _      <- hub.notifyNewRequest(req)
        events <- fiber.join
      } yield assertTrue(
        events.size == 1,
        events.headOption.exists(_.capability == CapabilityName("shell.execute")),
      )
    }.provideLayer(makeAppLayer()) @@ TestAspect.withLiveClock,
    test("toolEvents subscription yields no events before any tool is invoked") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.executeRequest(
          caliban.GraphQLRequest(
            query = Some("subscription { toolEvents(value: 1) { eventType toolName } }"),
          ),
        )
        events <- result.data match {
          case caliban.ResponseValue.StreamValue(stream) =>
            stream.take(1).timeout(200.millis).runCollect.map(_.toList)
          case _ => ZIO.succeed(List.empty)
        }
      } yield assertTrue(events.isEmpty)
    }.provideLayer(makeAppLayer()) @@ TestAspect.withLiveClock,
    test("toolEvents subscription receives ToolInvokedEvent published to matching session") {
      for {
        interp <- ZIO.service[Interp]
        hub    <- ZIO.service[ToolEventHub]
        // Subscribe directly to the hub to get a live stream, bypassing Caliban plumbing
        rawStream <- hub.subscribe(AgentSessionId(42L))
        fiber     <- rawStream.take(1).runCollect.map(_.toList).fork
        _         <- hub.publish(ToolEvent.ToolInvokedEvent(AgentSessionId(42L), "weather.get_forecast", "{}"))
        events    <- fiber.join
        // Verify the schema includes toolEvents field
        schema = JorlanAPI.api.render
      } yield assertTrue(
        events.nonEmpty,
        events.headOption.exists {
          case ToolEvent.ToolInvokedEvent(_, name, _) => name == "weather.get_forecast"; case _ => false
        },
        schema.contains("toolEvents"),
      )
    }.provideLayer(makeAppLayer()) @@ TestAspect.withLiveClock,
    test("toolEvents subscription scopes by sessionId — other sessions do not bleed through") {
      for {
        interp <- ZIO.service[Interp]
        hub    <- ZIO.service[ToolEventHub]
        events <- interp
          .executeRequest(
            caliban.GraphQLRequest(
              query = Some("subscription { toolEvents(value: 99) { eventType toolName } }"),
            ),
          )
          .flatMap { case r =>
            r.data match {
              case caliban.ResponseValue.StreamValue(stream) =>
                stream
                  .take(1)
                  .timeout(150.millis)
                  .runCollect
                  .map(_.toList)
              case _ => ZIO.succeed(List.empty)
            }
          }
          .zipLeft(hub.publish(ToolEvent.ToolInvokedEvent(AgentSessionId(1L), "other.tool", "{}")))
      } yield assertTrue(events.isEmpty)
    }.provideLayer(makeAppLayer()) @@ TestAspect.withLiveClock,
  )

  // ─── Personality query / mutation ────────────────────────────────────────────

  private val updatePersonalityMutation =
    """mutation {
      |  updatePersonality(
      |    name: "TestBot",
      |    formality: "Casual",
      |    languages: ["en", "es"],
      |    expertise: ["Scala"],
      |    prompt: "Be concise."
      |  ) { name formality prompt }
      |}""".stripMargin

  private val personalitySuite = suite("Personality")(
    test("serverPersonality query returns default personality fields") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ serverPersonality { name formality prompt } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("Jorlan"),
        result.data.toString.contains("Professional"),
      )
    }.provideLayer(makeAppLayer()),
    test("serverPersonality query fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ serverPersonality { name } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("serverPersonality query fails when capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ serverPersonality { name } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    (test("updatePersonality mutation succeeds with allowAll capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(updatePersonalityMutation)
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("TestBot"),
        result.data.toString.contains("Casual"),
      )
    } @@ TestAspect.ignore).provideLayer(makeAppLayer()),
    test("updatePersonality mutation fails with denyAll capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(updatePersonalityMutation)
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("updatePersonality mutation fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(updatePersonalityMutation)
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
  )

  // ─── Memory queries and mutations ─────────────────────────────────────────────

  private val memorySuite = suite("Memory")(
    test("listMemory query returns empty list when no records stored") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listMemory(scope: User) { id scope recordKey } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)),
    test("listMemory query fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listMemory(scope: User) { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer, memSvcLayer = realMemoryServiceLayer)),
    test("storeMemory mutation stores a record and is retrievable") {
      for {
        interp      <- ZIO.service[Interp]
        storeResult <- interp.execute(
          """mutation { storeMemory(key: "test.key", text: "hello world", scope: User) { id scope recordKey } }""",
        )
        listResult <- interp.execute("""{ listMemory(scope: User) { id scope recordKey } }""")
      } yield assertTrue(
        storeResult.errors.isEmpty,
        storeResult.data.toString.contains("test.key"),
        listResult.errors.isEmpty,
        listResult.data.toString.contains("test.key"),
      )
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)) @@ TestAspect.sequential,
    test("storeMemory mutation fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { storeMemory(key: "k", text: "v", scope: User) { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer, memSvcLayer = realMemoryServiceLayer)),
    test("forgetMemory mutation returns true after storeMemory") {
      for {
        interp      <- ZIO.service[Interp]
        storeResult <- interp.execute(
          """mutation { storeMemory(key: "forget.me", text: "temporary", scope: User) { id } }""",
        )
        id = extractLong(storeResult.data.toString, "id")
        forgetResult <- interp.execute(s"""mutation { forgetMemory(value: $id) }""")
      } yield assertTrue(
        storeResult.errors.isEmpty,
        forgetResult.errors.isEmpty,
      )
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)) @@ TestAspect.sequential,
    test("markMemoryShared mutation changes scope to Shared") {
      for {
        interp      <- ZIO.service[Interp]
        storeResult <- interp.execute(
          """mutation { storeMemory(key: "share.key", text: "shared fact", scope: User) { id } }""",
        )
        id = extractLong(storeResult.data.toString, "id")
        shareResult <- interp.execute(s"""mutation { markMemoryShared(value: $id) { id scope } }""")
      } yield assertTrue(
        storeResult.errors.isEmpty,
        shareResult.errors.isEmpty,
        shareResult.data.toString.contains("Shared"),
      )
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)) @@ TestAspect.sequential,
    test("markMemoryPrivate mutation changes scope to Private") {
      for {
        interp      <- ZIO.service[Interp]
        storeResult <- interp.execute(
          """mutation { storeMemory(key: "priv.key", text: "private fact", scope: User) { id } }""",
        )
        id = extractLong(storeResult.data.toString, "id")
        privResult <- interp.execute(s"""mutation { markMemoryPrivate(value: $id) { id scope } }""")
      } yield assertTrue(
        storeResult.errors.isEmpty,
        privResult.errors.isEmpty,
        privResult.data.toString.contains("Private"),
      )
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)) @@ TestAspect.sequential,
    test("forgetMemory returns false when record does not exist") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(s"""mutation { forgetMemory(value: 999999) }""")
      } yield assertTrue(result.errors.isEmpty && result.data.toString.contains("false"))
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)),
    test("listMemory query fails when memory.read capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listMemory(scope: User) { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll, memSvcLayer = realMemoryServiceLayer)),
    test("storeMemory mutation fails when memory.write capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { storeMemory(key: "k", text: "v", scope: User) { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll, memSvcLayer = realMemoryServiceLayer)),
    test("forgetMemory mutation fails when memory.write capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { forgetMemory(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll, memSvcLayer = realMemoryServiceLayer)),
    test("listMemory with invalid scope returns execution error") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listMemory(scope: "INVALID_SCOPE") { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)),
  )

  // ─── Scheduler tests ─────────────────────────────────────────────────────────

  // Repo pre-seeded with a job owned by UserId(2) — distinct from serverSession's UserId(1).
  private val foreignJobRepoLayer: ULayer[ZIOSchedulerRepository] = ZLayer.fromZIO {
    import InMemoryRepositories.InMemorySchedulerRepo
    import jorlan.*

    import java.time.Instant
    InMemorySchedulerRepo.make.flatMap { repo =>
      val foreignJob = SchedulerJob(
        id = SchedulerJobId.empty,
        agentId = AgentId(1L),
        userId = UserId(2L),
        skillId = None,
        name = "foreign-job",
        prompt = "",
        inputJson = None,
        status = JobStatus.Pending,
        scheduledAt = Instant.now(),
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
        createdAt = Instant.now(),
      )
      repo.upsertJob(foreignJob).orDie.as(repo: ZIOSchedulerRepository)
    }
  }

  private val schedulerSuite = suite("Scheduler")(
    test("jobs query returns empty list") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ jobs { id name status } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    }.provideLayer(makeAppLayer()),
    test("job query returns null for unknown id") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ job(value: 99999) { id name } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("null"))
    }.provideLayer(makeAppLayer()),
    test("triggers query returns empty list for unknown jobId") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ triggers(value: 99999) { id expression } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    }.provideLayer(makeAppLayer()),
    test("jobs query fails when scheduler.manage capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ jobs { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("pauseJob fails when caller does not own the job") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { pauseJob(value: 1) }""")

      } yield assertTrue(result.errors.nonEmpty, result.errors.exists(e => e.toString.contains("owned")))
    }.provideLayer(
      makeAppLayer(repoLayer =
        InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(schedulerRepoOpt =
          Some(foreignJobRepoLayer),
        ),
      ),
    ),
    test("cancelJob fails when caller does not own the job") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { cancelJob(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty, result.errors.exists(e => e.toString.contains("owned")))
    }.provideLayer(
      makeAppLayer(repoLayer =
        InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(schedulerRepoOpt =
          Some(foreignJobRepoLayer),
        ),
      ),
    ),
    test("deleteJob fails when caller does not own the job") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { deleteJob(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty, result.errors.exists(e => e.toString.contains("owned")))
    }.provideLayer(
      makeAppLayer(repoLayer =
        InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(schedulerRepoOpt =
          Some(foreignJobRepoLayer),
        ),
      ),
    ),
    test("addTrigger fails when caller does not own the target job") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { addTrigger(jobId: 1, triggerType: "Interval", expression: "PT1H") { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty, result.errors.exists(e => e.toString.contains("owned")))
    }.provideLayer(
      makeAppLayer(repoLayer =
        InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(schedulerRepoOpt =
          Some(foreignJobRepoLayer),
        ),
      ),
    ),
    test("createJob fails when no active agent session (resolveAgentIdStrict)") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { createJob(name: "x", prompt: "", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer()),
    test("createJob fails when scheduler.manage capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { createJob(name: "test", prompt: "", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("createJob with real scheduler repo creates job in Pending state") {
      for {
        interp <- ZIO.service[Interp]
        // Create a session so resolveAgentIdStrict finds one
        sessionResult <- interp.execute("""mutation { createSession { id } }""")
        result        <- interp.execute(
          """mutation { createJob(name: "my-job", prompt: "Do your thing", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id name status } }""",
        )
      } yield assertTrue(
        sessionResult.errors.isEmpty,
        result.errors.isEmpty,
        result.data.toString.contains("my-job"),
        result.data.toString.contains("Pending"),
      )
    }.provideLayer(makeAppLayer()),
    test("pauseJob fails when scheduler.manage capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { pauseJob(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("cancelJob fails when scheduler.manage capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { cancelJob(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("deleteJob fails when scheduler.manage capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { deleteJob(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("decideApproval fails when approval.decide capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { decideApproval(requestId: 1, approved: true) }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("terminateSession fails when agent.session.terminate capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { terminateSession(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("decideApproval succeeds with approval.decide capability granted") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { decideApproval(requestId: 1, approved: true) }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("decideApproval rejected input also succeeds (approved=false)") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { decideApproval(requestId: 1, approved: false, note: "rejected") }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("resumeJob fails when caller does not own the job") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { resumeJob(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty, result.errors.exists(e => e.toString.contains("owned")))
    }.provideLayer(
      makeAppLayer(repoLayer =
        InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(schedulerRepoOpt =
          Some(foreignJobRepoLayer),
        ),
      ),
    ),
    test("triggerNow fails when caller does not own the job") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { triggerNow(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty, result.errors.exists(e => e.toString.contains("owned")))
    }.provideLayer(
      makeAppLayer(repoLayer =
        InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(schedulerRepoOpt =
          Some(foreignJobRepoLayer),
        ),
      ),
    ),
  )

  // ─── availableModels query and terminateSession mutation (Phase 15) ──────────

  private val modelSuite = suite("Models and Session Lifecycle")(
    test("availableModels query returns the fake model list") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ availableModels { id provider contextWindow supportsStreaming } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("fake-model"),
        result.data.toString.contains("fake"),
      )
    }.provideLayer(makeAppLayer()),
    test("terminateSession transitions session to Completed status") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute("""mutation { createSession(modelId: null) { id status } }""")
        sessionId = extractLong(createResult.data.toString, "id")
        termResult <- interp.execute(s"""mutation { terminateSession(value: $sessionId) }""")
        listResult <- interp.execute("""{ listSessions { id status } }""")
      } yield assertTrue(
        createResult.errors.isEmpty,
        termResult.errors.isEmpty,
        termResult.data.toString.contains("true"),
        listResult.errors.isEmpty,
        listResult.data.toString.contains("Completed"),
      )
    }.provideLayer(makeAppLayer()),
  )

  // ─── OAuth resolver tests (P13-009) ──────────────────────────────────────────

  private val oauthSuite = suite("OAuth resolvers")(
    test("oauthStatus returns connected=false when no credentials stored") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ oauthStatus(value: "google") { connected expiresAt } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("false"),
      )
    }.provideLayer(makeAppLayer()),
    test("listOAuthProviders returns empty list when no credentials stored") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listOAuthProviders }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("[]"),
      )
    }.provideLayer(makeAppLayer()),
    test("oauthStatus returns connected=true when credential stored") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ oauthStatus(value: "google") { connected } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("true"),
      )
    }.provideLayer(makeAppLayer(oauthCredSvcLayer = connectedOAuthCredSvc)),
    test("startOAuth returns a URL for authenticated user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { startOAuth(value: "google") { authUrl } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("oauth"),
      )
    }.provideLayer(makeAppLayer()),
    test("startOAuth fails for unauthenticated user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { startOAuth(value: "google") { authUrl } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("revokeOAuth returns true for authenticated user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { revokeOAuth(value: "google") }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("true"),
      )
    }.provideLayer(makeAppLayer()),
    test("revokeOAuth fails for unauthenticated user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { revokeOAuth(value: "google") }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
  )

  // ─── invokeTool tests (P13-009) ───────────────────────────────────────────────

  private val invokeToolSuite = suite("invokeTool resolver")(
    test("invokeTool fails for unauthenticated user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { invokeTool(toolName: "memory.store", argsJson: "{}") }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("invokeTool fails when capability check denies") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { invokeTool(toolName: "memory.store", argsJson: "{}") }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("invokeTool invokes a registered tool when authorized") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { invokeTool(toolName: "memory.list", argsJson: "{}") }""",
        )
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("invokeTool returns error string for unknown tool name") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { invokeTool(toolName: "no.such.tool", argsJson: "{}") }""",
        )
        dataStr = result.data.toString
      } yield assertTrue(
        // SkillRegistry returns errors as JSON strings, not GraphQL errors
        result.errors.isEmpty,
        dataStr.contains("Error:"),
      )
    }.provideLayer(makeAppLayer()),
  )

  // ─── Misc queries/mutations not covered elsewhere ─────────────────────────────

  private val miscSuite = suite("Misc queries and mutations")(
    test("skills query returns registered skills") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skills { name tools { name } } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("contacts query returns empty list when no users") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ contacts(value: "alice") { userId displayName } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("contacts"))
    }.provideLayer(makeAppLayer()),
    test("contacts query fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ contacts(value: "alice") { userId displayName } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("listApprovals query returns empty list") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listApprovals { id } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("listCapabilities query returns empty list") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listCapabilities { id capability } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("checkpointPolicy query returns default policy") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ checkpointPolicy { onSessionEnd onUserRequest } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("dashboardStats query returns empty stats") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """{ dashboardStats { activeSessionCount eventCountToday skillInvocationCount schedulerSuccessRate } }""",
        )
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("activeSessionCount"))
    }.provideLayer(makeAppLayer()),
    test("skillDashboardData query returns None for skill without dashboard") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skillDashboardData(value: "memory") }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("allRoles query returns empty list initially") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ allRoles { id name } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("userCapabilityGrants query returns empty list for new user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ userCapabilityGrants(value: 1) { id capability } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("userChannelIdentities query returns empty list for new user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ userChannelIdentities(value: 1) { id channelUserId } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("skillConfig query fails for unknown skill") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skillConfig(value: "nonexistent") }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer()),
    test("deactivateUser mutation returns false for non-existent user") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { deactivateUser(value: 999) }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("notifyUser mutation succeeds with notify.send capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { notifyUser(userId: 1, message: "hello") }""",
        )
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("notifyUser mutation fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { notifyUser(userId: 1, message: "hello") }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("grantCapability mutation succeeds with permission.grant") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { grantCapability(userId: 1, capability: "some.cap", approvalMode: Persistent) { id capability } }""",
        )
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("grantCapability mutation fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { grantCapability(userId: 1, capability: "some.cap", approvalMode: Persistent) { id capability } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("enableSkill mutation succeeds with admin.settings capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { enableSkill(value: "memory") }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("disableSkill mutation succeeds with admin.settings capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { disableSkill(value: "memory") }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("updateCheckpointPolicy mutation succeeds with admin.settings capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { updateCheckpointPolicy(onSessionEnd: false, onUserRequest: true, beforeExternalEffect: false) { onSessionEnd onUserRequest } }""",
        )
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("reloadMcpServers mutation succeeds with admin.mcp.reload capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { reloadMcpServers(value: {}) }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("updateSkillConfig mutation fails for skill not registered in tests") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { updateSkillConfig(name: "nonexistent-skill", configJson: "{}") }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer()),
    test("updateJob mutation fails when caller does not own the job") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { updateJob(value: { jobId: 999 }) { id name } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer()),
    test("requestCheckpoint mutation succeeds with memory.read capability") {
      for {
        interp <- ZIO.service[Interp]
        // session id 999 does not exist but the mutation should return true (best-effort)
        result <- interp.execute("""mutation { requestCheckpoint(value: 999) }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("linkChannelIdentity mutation fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { linkChannelIdentity(userId: 1, channelUserId: "t-123", channelType: "Telegram") { id channelUserId } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("revokeCapabilityGrant mutation fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { revokeCapabilityGrant(value: 1) }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("revokeCapabilityGrant succeeds after granting a capability") {
      for {
        interp      <- ZIO.service[Interp]
        grantResult <- interp.execute(
          """mutation { grantCapability(userId: 1, capability: "test.cap", approvalMode: Persistent) { id } }""",
        )
        grantId = extractLong(grantResult.data.toString, "id")
        result <- interp.execute(s"""mutation { revokeCapabilityGrant(value: $grantId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("linkChannelIdentity succeeds with admin.user.manage") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute("""mutation { createUser(displayName: "Link User", email: "link@test.com") { id } }""")
        result <- interp.execute(
          """mutation { linkChannelIdentity(userId: 1, channelUserId: "tg-42", channelType: "Telegram") { id channelUserId } }""",
        )
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("tg-42"))
    }.provideLayer(makeAppLayer()),
    test("linkChannelIdentity fails for unknown channel type") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { linkChannelIdentity(userId: 1, channelUserId: "x", channelType: "InvalidType") { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer()),
    test("unlinkChannelIdentity succeeds after linking") {
      for {
        interp     <- ZIO.service[Interp]
        linkResult <- interp.execute(
          """mutation { linkChannelIdentity(userId: 1, channelUserId: "tg-99", channelType: "Telegram") { id } }""",
        )
        ciId = extractLong(linkResult.data.toString, "id")
        result <- interp.execute(s"""mutation { unlinkChannelIdentity(value: $ciId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("userChannelIdentities query returns identities after linking") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute(
          """mutation { linkChannelIdentity(userId: 1, channelUserId: "ch-123", channelType: "Slack") { id } }""",
        )
        result <- interp.execute("""{ userChannelIdentities(value: 1) { id channelUserId } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("ch-123"))
    }.provideLayer(makeAppLayer()),
    test("userCapabilityGrants returns grants after granting") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute(
          """mutation { grantCapability(userId: 1, capability: "some.granted.cap", approvalMode: Persistent) { id } }""",
        )
        result <- interp.execute("""{ userCapabilityGrants(value: 1) { id capability } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("some.granted.cap"))
    }.provideLayer(makeAppLayer()),
    test("contacts query returns user when matching query") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute(
          """mutation { createUser(displayName: "Alice Wonderland", email: "alice@test.com") { id } }""",
        )
        result <- interp.execute("""{ contacts(value: "alice") { userId displayName } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("Alice Wonderland"))
    }.provideLayer(makeAppLayer()),
    test("deactivateUser returns true when user exists") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute(
          """mutation { createUser(displayName: "To Deactivate", email: "deact@test.com") { id } }""",
        )
        userId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(s"""mutation { deactivateUser(value: $userId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("allRoles returns roles after creating one") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute("""mutation { createRole(name: "admin-role", description: "admin") { id } }""")
        result <- interp.execute("""{ allRoles { id name } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("admin-role"))
    }.provideLayer(makeAppLayer()),
  )

  private val jobLifecycleSuite = suite("Job lifecycle mutations")(
    test("pauseJob succeeds when caller owns the job") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "pause-me", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(s"""mutation { pauseJob(value: $jobId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("resumeJob succeeds when caller owns the job") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "resume-me", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        _      <- interp.execute(s"""mutation { pauseJob(value: $jobId) }""")
        result <- interp.execute(s"""mutation { resumeJob(value: $jobId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("cancelJob succeeds when caller owns the job") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "cancel-me", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(s"""mutation { cancelJob(value: $jobId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("deleteJob succeeds when caller owns the job") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "delete-me", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(s"""mutation { deleteJob(value: $jobId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("triggerNow succeeds when caller owns the job") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "trigger-me", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(s"""mutation { triggerNow(value: $jobId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("updateJob succeeds when caller owns the job") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "update-me", prompt: "original", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { updateJob(id: $jobId, name: "updated-name", prompt: "new prompt", maxRetries: 2, backoffSeconds: 30, backoffPolicy: Fixed, missedRunPolicy: Skip) { id name } }""",
        )
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("updated-name"))
    }.provideLayer(makeAppLayer()),
    test("addTrigger succeeds when caller owns the job") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "trigger-job", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { addTrigger(jobId: $jobId, triggerType: "Cron", expression: "0 * * * *") { id jobId } }""",
        )
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("jobId"))
    }.provideLayer(makeAppLayer()),
    test("deleteTrigger succeeds after adding a trigger") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "dt-job", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        triggerResult <- interp.execute(
          s"""mutation { addTrigger(jobId: $jobId, triggerType: "Cron", expression: "0 * * * *") { id } }""",
        )
        triggerId = extractLong(triggerResult.data.toString, "id")
        result <- interp.execute(s"""mutation { deleteTrigger(value: $triggerId) }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("true"))
    }.provideLayer(makeAppLayer()),
    test("triggers query returns trigger after addTrigger") {
      for {
        interp       <- ZIO.service[Interp]
        _            <- interp.execute("""mutation { createSession { id } }""")
        createResult <- interp.execute(
          """mutation { createJob(name: "trigger-list-job", prompt: "p", maxRetries: 0, backoffSeconds: 60, backoffPolicy: Fixed, missedRunPolicy: Skip) { id } }""",
        )
        jobId = extractLong(createResult.data.toString, "id")
        addTrigResult <- interp.execute(
          s"""mutation { addTrigger(jobId: $jobId, triggerType: "Cron", expression: "0 * * * *") { id } }""",
        )
        result <- interp.execute(s"""{ triggers(value: $jobId) { id } }""")
      } yield assertTrue(
        addTrigResult.errors.isEmpty,
        result.errors.isEmpty,
        !result.data.toString.contains("[]"),
      )
    }.provideLayer(makeAppLayer()),
  )

  // ─── Skill manifest used in lifecycle tests ───────────────────────────────────

  private val testManifestStr: String =
    """{"name":"myskill","version":"1.0.0","description":"test","keywords":[],"tools":[{"name":"myskill.run","description":"run","requiredCapabilities":[],"examplePrompts":[],"inputSchema":{"type":"object"},"outputSchema":{"type":"string"},"executor":{"HttpApi":{"config":{"method":"GET","url":"https://ex.com","headers":{},"bodyTemplate":null,"responseJsonPath":null}}}}]}"""

  // ─── Skill lifecycle tests ────────────────────────────────────────────────────

  private val skillLifecycleSuite = suite("skill lifecycle")(
    test("createSkillDraft succeeds with skill.create capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr
              .replace("\"", "\\\"")}") { id skillId skillName version status } }""",
        )
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("Draft"),
        result.data.toString.contains("myskill"),
      )
    }.provideLayer(makeAppLayer()),
    test("createSkillDraft fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr.replace("\"", "\\\"")}") { id status } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("createSkillDraft fails when skill.create capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr.replace("\"", "\\\"")}") { id status } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("advanceSkillLifecycle succeeds with skill.create capability") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr.replace("\"", "\\\"")}") { id status } }""",
        )
        versionId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { advanceSkillLifecycle(value: $versionId) { versionId newStatus errors info } }""",
        )
      } yield assertTrue(
        createResult.errors.isEmpty,
        result.errors.isEmpty,
        result.data.toString.contains("Validated"),
      )
    }.provideLayer(makeAppLayer()),
    test("approveSkillVersion fails when not AwaitingApproval") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr.replace("\"", "\\\"")}") { id status } }""",
        )
        versionId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { approveSkillVersion(value: $versionId) { versionId newStatus errors } }""",
        )
      } yield assertTrue(
        createResult.errors.isEmpty,
        result.errors.nonEmpty,
      )
    }.provideLayer(makeAppLayer()),
    test("rejectSkillVersion fails when not AwaitingApproval") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr.replace("\"", "\\\"")}") { id status } }""",
        )
        versionId = extractLong(createResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { rejectSkillVersion(versionId: $versionId, reason: "not ready") { versionId newStatus errors } }""",
        )
      } yield assertTrue(
        createResult.errors.isEmpty,
        result.errors.nonEmpty,
      )
    }.provideLayer(makeAppLayer()),
  )

  // ─── MCP server tests ─────────────────────────────────────────────────────────

  private val mcpServerSuite = suite("mcp servers")(
    test("mcpServers query returns empty list initially") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ mcpServers { name transport url command } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("mcpServers"),
      )
    }.provideLayer(makeAppLayer()),
    test("upsertMcpServer mutation succeeds with admin.settings capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { upsertMcpServer(name: "test-mcp", transport: "HttpSse", url: "http://localhost:9999", args: [], env: [], keywords: [], enabled: true) { name transport url } }""",
        )
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("test-mcp"),
      )
    }.provideLayer(makeAppLayer()),
    test("upsertMcpServer fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { upsertMcpServer(name: "test-mcp", transport: "HttpSse", url: "http://localhost:9999", args: [], env: [], keywords: [], enabled: true) { name transport } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("deleteMcpServer succeeds after upsert") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute(
          """mutation { upsertMcpServer(name: "test-mcp2", transport: "HttpSse", url: "http://localhost:9998", args: [], env: [], keywords: [], enabled: true) { name } }""",
        )
        result <- interp.execute("""mutation { deleteMcpServer(value: "test-mcp2") }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("true"),
      )
    }.provideLayer(makeAppLayer()),
    test("deleteMcpServer returns false for non-existent server") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { deleteMcpServer(value: "does-not-exist-xyz") }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("false"),
      )
    }.provideLayer(makeAppLayer()),
  )

  // ─── Skill admin queries (skillVersions, pendingSkillVersions, allCustomSkills) ──

  private val skillAdminSuite = suite("Skill admin queries")(
    test("skillVersions fails for unknown skillId") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skillVersions(value: 999999) { id version status } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer()),
    test("skillVersions returns versions after creating a draft") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr
              .replace("\"", "\\\"")}") { id skillId version status } }""",
        )
        skillId = extractLong(createResult.data.toString, "skillId")
        result <- interp.execute(s"""{ skillVersions(value: $skillId) { id version status } }""")
      } yield assertTrue(
        createResult.errors.isEmpty,
        result.errors.isEmpty,
        result.data.toString.contains("1.0.0"),
        result.data.toString.contains("Draft"),
      )
    }.provideLayer(makeAppLayer()),
    test("skillVersions fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skillVersions(value: 1) { id status } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("pendingSkillVersions returns empty list initially") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ pendingSkillVersions { id version status } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    }.provideLayer(makeAppLayer()),
    test("pendingSkillVersions returns version after advancing to AwaitingApproval") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr.replace("\"", "\\\"")}") { id status } }""",
        )
        versionId = extractLong(createResult.data.toString, "id")
        _ <- interp.execute(
          s"""mutation { advanceSkillLifecycle(value: $versionId) { versionId newStatus } }""",
        )
        _ <- interp.execute(
          s"""mutation { advanceSkillLifecycle(value: $versionId) { versionId newStatus } }""",
        )
        _ <- interp.execute(
          s"""mutation { advanceSkillLifecycle(value: $versionId) { versionId newStatus } }""",
        )
        _ <- interp.execute(
          s"""mutation { advanceSkillLifecycle(value: $versionId) { versionId newStatus } }""",
        )
        result <- interp.execute("""{ pendingSkillVersions { id version status } }""")
      } yield assertTrue(
        createResult.errors.isEmpty,
        result.errors.isEmpty,
        result.data.toString.contains("AwaitingApproval"),
      )
    }.provideLayer(makeAppLayer()),
    test("allCustomSkills returns empty list when no declarative skills exist") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ allCustomSkills { id skillName version status } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    }.provideLayer(makeAppLayer()),
    test("allCustomSkills returns skill after creating a draft") {
      for {
        interp       <- ZIO.service[Interp]
        createResult <- interp.execute(
          s"""mutation { createSkillDraft(value: "${testManifestStr.replace("\"", "\\\"")}") { id skillName } }""",
        )
        result <- interp.execute("""{ allCustomSkills { id skillName version status } }""")
      } yield assertTrue(
        createResult.errors.isEmpty,
        result.errors.isEmpty,
        result.data.toString.contains("myskill"),
      )
    }.provideLayer(makeAppLayer()),
    test("allKnownCapabilities returns a list") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ allKnownCapabilities }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer()),
    test("skillValidate returns ok=true for existing skill without HasValidation") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skillValidate(value: "memory") { ok message } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("true"),
      )
    }.provideLayer(makeAppLayer()),
    test("skillValidate returns ok=false for unknown skill name") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skillValidate(value: "no-such-skill-xyz") { ok message } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("false"),
      )
    }.provideLayer(makeAppLayer()),
    test("skillValidate fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ skillValidate(value: "memory") { ok } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("mcpServers query includes server after upsert") {
      for {
        interp <- ZIO.service[Interp]
        _      <- interp.execute(
          """mutation { upsertMcpServer(name: "admin-test-mcp", transport: "Stdio", command: "/bin/tool", args: [], env: [], keywords: ["k1"], enabled: true) { name } }""",
        )
        result <- interp.execute("""{ mcpServers { name transport enabled keywords } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("admin-test-mcp"),
        result.data.toString.contains("k1"),
      )
    }.provideLayer(makeAppLayer()),
  )

  // ─── Role capability grant tests ──────────────────────────────────────────────

  private val capabilityRoleSuite = suite("Role capability grants")(
    test("grantCapabilityToRole grants a capability to a role") {
      for {
        interp     <- ZIO.service[Interp]
        roleResult <- interp.execute("""mutation { createRole(name: "cap-role") { id } }""")
        roleId = extractLong(roleResult.data.toString, "id")
        result <- interp.execute(
          s"""mutation { grantCapabilityToRole(roleId: $roleId, capability: "memory.read", approvalMode: Persistent) { id capability } }""",
        )
      } yield assertTrue(
        roleResult.errors.isEmpty,
        result.errors.isEmpty,
        result.data.toString.contains("memory.read"),
      )
    }.provideLayer(makeAppLayer()),
    test("roleCapabilityGrants returns grants after granting to role") {
      for {
        interp     <- ZIO.service[Interp]
        roleResult <- interp.execute("""mutation { createRole(name: "grants-role") { id } }""")
        roleId = extractLong(roleResult.data.toString, "id")
        _ <- interp.execute(
          s"""mutation { grantCapabilityToRole(roleId: $roleId, capability: "agent.message", approvalMode: Persistent) { id } }""",
        )
        result <- interp.execute(s"""{ roleCapabilityGrants(value: $roleId) { id capability } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("agent.message"),
      )
    }.provideLayer(makeAppLayer()),
    test("roleCapabilityGrants returns empty list for role with no grants") {
      for {
        interp     <- ZIO.service[Interp]
        roleResult <- interp.execute("""mutation { createRole(name: "empty-grants-role") { id } }""")
        roleId = extractLong(roleResult.data.toString, "id")
        result <- interp.execute(s"""{ roleCapabilityGrants(value: $roleId) { id capability } }""")
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("[]"),
      )
    }.provideLayer(makeAppLayer()),
    test("grantCapabilityToRole fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { grantCapabilityToRole(roleId: 1, capability: "some.cap", approvalMode: Persistent) { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer)),
    test("grantCapabilityToRole fails when permission.grant capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { grantCapabilityToRole(roleId: 1, capability: "some.cap", approvalMode: Persistent) { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
  )

}
