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
import jorlan.db.repository.*
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
    val repoLayer = InMemoryRepositories.live()
    val policy = ZLayer.succeed(MemoryAccessPolicyImpl(): MemoryAccessPolicy)
    val summarizer = ZLayer.succeed(
      new CheckpointSummarizer {
        override def summarize(
          messages: List[Message],
          userId:   UserId,
          agentId:  AgentId,
        ): IO[JorlanError, List[MemoryRecord]] = ZIO.succeed(Nil)
      },
    )
    val classifier = ZLayer.succeed(MemoryClassifierImpl(): MemoryClassifier)
    val cpPolicy = ZLayer.succeed(CheckpointPolicy.onSessionEnd)

    ZLayer.make[CheckpointSummarizer & MemoryClassifier & CheckpointPolicy & MemoryAccessPolicy & ZIORepositories](
      policy,
      summarizer,
      classifier,
      cpPolicy,
      repoLayer,
    ) >>> MemoryServiceImpl.live
  }

  private def makeAppLayer(
    capEval:     ULayer[CapabilityEvaluator] = allowAll,
    session:     ULayer[JorlanSession] = serverSessionLayer,
    memSvcLayer: ULayer[MemoryService] = NoOpMemoryService.layer,
    repoLayer:   ULayer[ZIORepositories] = InMemoryRepositories.live(),
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
    ZLayer.make[FullEnv](
      agentRepoLayer,
      hubLayer,
      capEval,
      session,
      FakeModelGateway.layer(List("ok")),
      AgentSessionManagerImpl.live,
      memSvcLayer, {
        ZLayer.fromZIO {
          for {
            memorySkill <- ZIO.service[MemorySkill]
          } yield SkillRegistry.liveWith(memorySkill)
        }.flatten
      },
      ZLayer.succeed(AgentSettings()),
      MemorySkill.live,
      AgentRunnerImpl.live,
      JobManagerImpl.live,
      approvalSvcLayer,
      ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie),
    )
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
      schedulerSuite,
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

  // ─── Scheduler tests ─────────────────────────────────────────────────────────

  // Repo pre-seeded with a job owned by UserId(2) — distinct from serverSession's UserId(1).
  private val foreignJobRepoLayer: ULayer[ZIOSchedulerRepository] = ZLayer.fromZIO {
    import InMemoryRepositories.InMemorySchedulerRepo
    import jorlan.domain.*

    import java.time.Instant
    InMemorySchedulerRepo.make.flatMap { repo =>
      val foreignJob = SchedulerJob(
        id = SchedulerJobId.empty,
        agentId = AgentId(1L),
        userId = UserId(2L),
        skillId = None,
        name = "foreign-job",
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
        result <- interp.execute("""{ jobs(value: null) { id name status } }""")
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
          """mutation { createJob(name: "x", maxRetries: 0, backoffSeconds: 60, backoffPolicy: "Fixed", missedRunPolicy: "Skip") { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer()),
    test("createJob fails when scheduler.manage capability is denied") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute(
          """mutation { createJob(name: "test", maxRetries: 0, backoffSeconds: 60, backoffPolicy: "Fixed", missedRunPolicy: "Skip") { id } }""",
        )
      } yield assertTrue(result.errors.nonEmpty)
    }.provideLayer(makeAppLayer(capEval = denyAll)),
    test("createJob with real scheduler repo creates job in Pending state") {
      for {
        interp <- ZIO.service[Interp]
        // Create a session so resolveAgentIdStrict finds one
        sessionResult <- interp.execute("""mutation { createSession { id } }""")
        result        <- interp.execute(
          """mutation { createJob(name: "my-job", maxRetries: 0, backoffSeconds: 60, backoffPolicy: "Fixed", missedRunPolicy: "Skip") { id name status } }""",
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

}
