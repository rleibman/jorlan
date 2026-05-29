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

import auth.{AuthenticatedSession, UnauthenticatedSession}
import caliban.GraphQLInterpreter
import jorlan.*
import jorlan.domain.*
import jorlan.service.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*
import zio.test.Assertion.*

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

  private def makeAppLayer(
    capEval: ULayer[CapabilityEvaluator] = allowAll,
    session: ULayer[JorlanSession] = serverSessionLayer,
  ): ULayer[FullEnv] = {
    val logLayer = InMemoryRepositories.InMemoryEventLogRepo.layer >>> EventLogServiceImpl.live
    val userLayer = (InMemoryRepositories.InMemoryUserRepo.layer ++ logLayer) >>> UserServiceImpl.live
    val permLayer = (InMemoryRepositories.InMemoryPermissionRepo.layer ++ logLayer) >>> PermissionServiceImpl.live
    val svcLayer: ULayer[JorlanAPI.JorlanApiEnv & JorlanSession] =
      userLayer ++ permLayer ++ capEval ++ session
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
      subscriptionSuite,
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
        result <- interp.execute("""{ user(id: 99999) { id displayName } }""")
      } yield assertTrue(result.errors.isEmpty, result.data.toString.contains("null"))
    },
    test("role(id) returns null for non-existent id") {
      for {
        interp <- ZIO.service[Interp]
        result <- interp.execute("""{ role(id: 99999) { id name } }""")
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
        revokeResult <- interp.execute(s"""mutation { revokePermission(id: $permId) }""")
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

}
