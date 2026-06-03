/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.graphql

import auth.UnauthenticatedSession
import caliban.GraphQLInterpreter
import jorlan.*
import jorlan.db.repository.{
  AgentZIORepository,
  EventLogZIORepository,
  PermissionZIORepository,
  ServerSettingsRepository,
  UserZIORepository,
}
import jorlan.domain.*
import jorlan.service.*
import jorlan.testing.{InMemoryRepositories, NoOpMemoryService}
import zio.*
import zio.test.*

/** Unit tests for [[JorlanAPI]] using in-memory service stubs. No database required.
  *
  * Tests cover:
  *   - All Queries (user, users, role, roles, permissions)
  *   - All Mutations (createUser, updateUser, createRole, assignRole, revokeRole, grantPermission, revokePermission)
  *   - Authorization helpers: `actorIdFromSession` (unauthenticated → error), `requireCapability` (deny/allow)
  *   - Input validation: `grantPermission` must target exactly one of userId/roleId
  *   - Subscription stubs: `approvalNotifications` and `eventLogTail` return empty streams
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

  private type FullEnv = JorlanAPI.JorlanApiEnv & JorlanSession &
    GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]

  private def realMemoryServiceLayer: ULayer[MemoryService] = {
    val memRepo = InMemoryRepositories.InMemoryMemoryRepo.layer
    val policy = ZLayer.succeed(new MemoryAccessPolicyImpl(): MemoryAccessPolicy)
    val summarizer = ZLayer.succeed(
      new CheckpointSummarizer {
        override def summarize(
          messages: List[Message],
          userId:   UserId,
          agentId:  AgentId,
        ): IO[JorlanError, List[MemoryRecord]] = ZIO.succeed(Nil)
      }: CheckpointSummarizer,
    )
    val classifier = ZLayer.succeed(new MemoryClassifierImpl(): MemoryClassifier)
    val cpPolicy = ZLayer.succeed(CheckpointPolicy.onSessionEnd)
    (memRepo ++ policy ++ summarizer ++ classifier ++ cpPolicy) >>> MemoryServiceImpl.live
  }

  private def makeAppLayer(
    capEval:     ULayer[CapabilityEvaluator] = allowAll,
    session:     ULayer[JorlanSession] = serverSessionLayer,
    memSvcLayer: ULayer[MemoryService] = NoOpMemoryService.layer,
  ): ULayer[FullEnv] = {
    val userRepoLayer: ULayer[UserZIORepository] = InMemoryRepositories.InMemoryUserRepo.layer
    val permRepoLayer: ULayer[PermissionZIORepository] = InMemoryRepositories.InMemoryPermissionRepo.layer
    val eventLogRepo:  ULayer[EventLogZIORepository] = InMemoryRepositories.InMemoryEventLogRepo.layer
    val settingsRepo:  ULayer[ServerSettingsRepository] = InMemoryRepositories.InMemoryServerSettingsRepo.layer
    val hubLayer = SessionHub.live
    val agentRepoLayer: ULayer[AgentZIORepository] = ZLayer.fromZIO {
      for {
        now  <- Clock.instant
        repo <- InMemoryRepositories.InMemoryAgentRepo.make
        _    <- repo
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
      } yield repo: AgentZIORepository
    }
    val fakeGateway = FakeModelGateway.layer(List("ok"))
    val sessionMgrLayer: ULayer[AgentSessionManager] =
      (agentRepoLayer ++ hubLayer ++ fakeGateway ++ eventLogRepo) >>> AgentSessionManagerImpl.live
    val convRepoLayer = InMemoryRepositories.InMemoryConversationRepo.layer
    val runnerLayer: ULayer[AgentRunner] =
      (fakeGateway ++ hubLayer ++ eventLogRepo ++ settingsRepo ++ convRepoLayer ++ agentRepoLayer ++ memSvcLayer) >>>
        AgentRunnerImpl.live
    val memSkillLayer: ULayer[MemorySkill] = memSvcLayer >>> MemorySkill.live
    val svcLayer:      ULayer[JorlanAPI.JorlanApiEnv & JorlanSession] =
      userRepoLayer ++ permRepoLayer ++ eventLogRepo ++ settingsRepo ++ capEval ++ session ++ sessionMgrLayer ++
        runnerLayer ++ memSvcLayer ++ memSkillLayer
    val interpLayer
      : ZLayer[JorlanAPI.JorlanApiEnv, Nothing, GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]] =
      ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie)
    svcLayer >+> interpLayer
  }

  // ─── Helper to extract a Long field from GraphQL response text ────────────────

  private def extractLong(
    data:  String,
    field: String,
  ): Long = {
    import scala.language.unsafeNulls
    val pat = s""""$field":([0-9]+)""".r
    pat
      .findFirstMatchIn(data).map(_.group(1).toLong).getOrElse(
        throw AssertionError(s"field '$field' not found in: $data"),
      )
  }

  private type Interp = GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("JorlanAPI unit tests")(
      querySuite,
      mutationSuite,
      authSuite,
      agentSessionSuite,
      subscriptionSuite,
      personalitySuite,
      memorySuite,
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
        interp      <- ZIO.service[Interp]
        _           <- interp.execute("""mutation { createUser(displayName: "Alice") { id } }""")
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
        createResult <- interp.execute("""mutation { createUser(displayName: "Old") { id } }""")
        id = extractLong(createResult.data.toString, "id")
        updateResult <- interp.execute(
          s"""mutation { updateUser(id: $id, displayName: "New", active: true) { id displayName } }""",
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
        userResult <- interp.execute("""mutation { createUser(displayName: "RoleUser") { id } }""")
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
        userResult <- interp.execute("""mutation { createUser(displayName: "PermUser") { id } }""")
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
        userResult <- interp.execute("""mutation { createUser(displayName: "RevUser") { id } }""")
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
    test("query succeeds regardless of capability evaluator (queries bypass requireCapability)") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ users { id displayName } }""")
      } yield assertTrue(result.errors.isEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
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
    test("schema includes both subscription fields") {
      val schema = JorlanAPI.api.render
      assertTrue(
        schema.contains("approvalNotifications"),
        schema.contains("eventLogTail"),
      )
    },
    test("approvalNotifications stub returns an empty stream") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.executeRequest(
          caliban.GraphQLRequest(
            query = Some("subscription { approvalNotifications { id capability } }"),
          ),
        )
        events <- result.data match {
          case caliban.ResponseValue.StreamValue(stream) => stream.take(10).runCollect.map(_.toList)
          case _                                         => ZIO.succeed(List.empty)
        }
      } yield assertTrue(events.isEmpty)
    }.provideLayer(makeAppLayer()),
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
    test("updatePersonality mutation succeeds with allowAll capability") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(updatePersonalityMutation)
      } yield assertTrue(
        result.errors.isEmpty,
        result.data.toString.contains("TestBot"),
        result.data.toString.contains("Casual"),
      )
    }.provideLayer(makeAppLayer()),
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
        result <- interp.execute("""{ listMemory(scope: "User") { id scope recordKey } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("[]"))
    }.provideLayer(makeAppLayer(memSvcLayer = realMemoryServiceLayer)),
    test("listMemory query fails when unauthenticated") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ listMemory(scope: "User") { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer, memSvcLayer = realMemoryServiceLayer)),
    test("storeMemory mutation stores a record and is retrievable") {
      for {
        interp      <- ZIO.service[Interp]
        storeResult <- interp.execute(
          """mutation { storeMemory(key: "test.key", text: "hello world", scope: "User") { id scope recordKey } }""",
        )
        listResult <- interp.execute("""{ listMemory(scope: "User") { id scope recordKey } }""")
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
          """mutation { storeMemory(key: "k", text: "v", scope: "User") { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(session = unauthSessionLayer, memSvcLayer = realMemoryServiceLayer)),
    test("forgetMemory mutation returns true after storeMemory") {
      for {
        interp      <- ZIO.service[Interp]
        storeResult <- interp.execute(
          """mutation { storeMemory(key: "forget.me", text: "temporary", scope: "User") { id } }""",
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
          """mutation { storeMemory(key: "share.key", text: "shared fact", scope: "User") { id } }""",
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
          """mutation { storeMemory(key: "priv.key", text: "private fact", scope: "User") { id } }""",
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
        result <- interp.execute("""{ listMemory(scope: "User") { id } }""")
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll, memSvcLayer = realMemoryServiceLayer)),
    test("storeMemory mutation fails when memory.write capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""mutation { storeMemory(key: "k", text: "v", scope: "User") { id } }""")
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

}
