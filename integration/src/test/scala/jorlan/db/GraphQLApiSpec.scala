/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import caliban.*
import jorlan.*
import jorlan.domain.*
import jorlan.graphql.JorlanAPI
import jorlan.service.*
import zio.*
import zio.test.*

/** Integration tests for the Caliban GraphQL API.
  *
  * Tests execute queries and mutations directly via the Caliban interpreter (no HTTP), with a real MariaDB backed by
  * Testcontainers and a server-identity session. Caliban flattens input-type fields as individual arguments (no
  * `input:` wrapper), so queries use e.g. `createUser(displayName: "x")` — not `createUser(input: {displayName: "x"})`.
  */
object GraphQLApiSpec extends ZIOSpecDefault {

  private val stubCapabilityEvaluator: ULayer[CapabilityEvaluator] =
    ZLayer.succeed(new CapabilityEvaluator {
      override def evaluate(request: CapabilityRequest): IO[JorlanError, EvaluationResult] =
        ZIO.succeed(EvaluationResult.ResourcePermissionAllows)
    })

  private val stubApprovalService: ULayer[ApprovalService] = ZLayer.succeed(
    new ApprovalService {
      override def authorize(request: CapabilityRequest): IO[JorlanError, AuthorizationResult] =
        ZIO.succeed(AuthorizationResult.Allowed)
      override def recordDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision] =
        ZIO.succeed(decision)
      override def expireStaleRequests(): IO[JorlanError, Long] = ZIO.succeed(0L)
    }: ApprovalService,
  )

  private val appLayer = ZLayer.make[
    JorlanAPI.JorlanApiEnv & JorlanSession,
  ](
    JorlanContainer.repositoryLayer,
    stubCapabilityEvaluator,
    stubApprovalService,
    ZLayer.succeed(JorlanSession.serverSession),
    SessionHub.live,
    FakeModelGateway.layer(List("test")),
    AgentSessionManagerImpl.live,
    MemoryClassifierImpl.live,
    MemoryAccessPolicyImpl.live,
    CheckpointSummarizerImpl.live,
    ZLayer.succeed(CheckpointPolicy.onSessionEnd),
    MemoryServiceImpl.live,
    MemorySkill.live,
    AgentRunnerImpl.live,
    JobManagerImpl.live,
  )

  private val interpreterLayer: ZLayer[JorlanAPI.JorlanApiEnv, Nothing, GraphQLInterpreter[
    JorlanAPI.JorlanApiEnv & JorlanSession,
    Any,
  ]] =
    ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GraphQL API")(
      test("schema renders with expected types") {
        ZIO.succeed(JorlanAPI.api.render).map { schema =>
          assertTrue(
            schema.contains("type Query") || schema.contains("Queries"),
            schema.contains("type Mutation") || schema.contains("Mutations"),
            schema.contains("type Subscription") || schema.contains("Subscriptions"),
            schema.contains("users"),
            schema.contains("createUser"),
            schema.contains("createRole"),
          )
        }
      },
      test("users query includes the seeded server user") {
        for {
          interp <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          result <- interp.execute("""{ users { id displayName } }""")
        } yield assertTrue(
          result.errors.isEmpty,
          result.data.toString.contains("server"),
        )
      },
      test("createUser mutation creates a user") {
        for {
          interp <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          result <- interp.execute(
            """mutation { createUser(displayName: "GraphQLUser1", email: "gql1@test.com") { id displayName email } }""",
          )
        } yield assertTrue(
          result.errors.isEmpty,
          result.data.toString.contains("GraphQLUser1"),
          result.data.toString.contains("gql1@test.com"),
        )
      },
      test("user(id) query returns a specific user") {
        for {
          interp       <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          createResult <- interp.execute(
            """mutation { createUser(displayName: "GraphQLUser2", email: "gql2@test.com") { id displayName } }""",
          )
          id = extractLongField(createResult.data.toString, "id")
          queryResult <- interp.execute(s"""{ user(value: $id) { id displayName } }""")
        } yield assertTrue(
          createResult.errors.isEmpty,
          queryResult.errors.isEmpty,
          queryResult.data.toString.contains("GraphQLUser2"),
        )
      },
      test("user(id) returns null for non-existent id") {
        for {
          interp <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          result <- interp.execute("""{ user(value: 999999) { id displayName } }""")
        } yield assertTrue(
          result.errors.isEmpty,
          result.data.toString.contains("null"),
        )
      },
      test("updateUser mutation updates a user and preserves createdAt") {
        for {
          interp       <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          createResult <- interp.execute(
            """mutation { createUser(displayName: "UpdateMe", email: "update@test.com") { id displayName createdAt } }""",
          )
          id = extractLongField(createResult.data.toString, "id")
          updateResult <- interp.execute(
            s"""mutation { updateUser(id: $id, displayName: "Updated", email: "updated@test.com", active: true) { id displayName email } }""",
          )
          refetchResult <- interp.execute(s"""{ user(value: $id) { id displayName email } }""")
        } yield assertTrue(
          createResult.errors.isEmpty,
          updateResult.errors.isEmpty,
          refetchResult.errors.isEmpty,
          updateResult.data.toString.contains("Updated"),
          updateResult.data.toString.contains("updated@test.com"),
        )
      },
      test("createRole mutation creates a role") {
        for {
          interp <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          result <- interp.execute(
            """mutation { createRole(name: "gql-admin", description: "GQL test admin") { id name description } }""",
          )
        } yield assertTrue(
          result.errors.isEmpty,
          result.data.toString.contains("gql-admin"),
          result.data.toString.contains("GQL test admin"),
        )
      },
      test("role(id) returns null for non-existent id") {
        for {
          interp <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          result <- interp.execute("""{ role(value: 999999) { id name } }""")
        } yield assertTrue(
          result.errors.isEmpty,
          result.data.toString.contains("null"),
        )
      },
      test("assignRole and roles(userId) round-trip") {
        for {
          interp     <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          userResult <- interp.execute(
            """mutation { createUser(displayName: "GraphQLUser3", email: "gql3@test.com") { id } }""",
          )
          userId = extractLongField(userResult.data.toString, "id")
          roleResult <- interp.execute("""mutation { createRole(name: "gql-tester") { id } }""")
          roleId = extractLongField(roleResult.data.toString, "id")
          _           <- interp.execute(s"""mutation { assignRole(userId: $userId, roleId: $roleId) }""")
          rolesResult <- interp.execute(s"""{ roles(userId: $userId) { id name } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          roleResult.errors.isEmpty,
          rolesResult.errors.isEmpty,
          rolesResult.data.toString.contains("gql-tester"),
        )
      },
      test("revokeRole removes a role from a user") {
        for {
          interp     <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          userResult <- interp.execute(
            """mutation { createUser(displayName: "GraphQLUser6", email: "gql6@test.com") { id } }""",
          )
          userId = extractLongField(userResult.data.toString, "id")
          roleResult <- interp.execute("""mutation { createRole(name: "gql-revoke-test") { id } }""")
          roleId = extractLongField(roleResult.data.toString, "id")
          _                <- interp.execute(s"""mutation { assignRole(userId: $userId, roleId: $roleId) }""")
          rolesAfterAssign <- interp.execute(s"""{ roles(userId: $userId) { id name } }""")
          _                <- interp.execute(s"""mutation { revokeRole(userId: $userId, roleId: $roleId) }""")
          rolesAfterRevoke <- interp.execute(s"""{ roles(userId: $userId) { id name } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          roleResult.errors.isEmpty,
          rolesAfterAssign.errors.isEmpty,
          rolesAfterRevoke.errors.isEmpty,
          rolesAfterAssign.data.toString.contains("gql-revoke-test"),
          !rolesAfterRevoke.data.toString.contains("gql-revoke-test"),
        )
      },
      test("role(id) returns a specific role by id") {
        for {
          interp       <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          createResult <- interp.execute("""mutation { createRole(name: "gql-specific") { id name } }""")
          roleId = extractLongField(createResult.data.toString, "id")
          queryResult <- interp.execute(s"""{ role(value: $roleId) { id name } }""")
        } yield assertTrue(
          createResult.errors.isEmpty,
          queryResult.errors.isEmpty,
          queryResult.data.toString.contains("gql-specific"),
        )
      },
      test("grantPermission with userId and permissions(userId) round-trip") {
        for {
          interp     <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          userResult <- interp.execute(
            """mutation { createUser(displayName: "GraphQLUser4", email: "gql4@test.com") { id } }""",
          )
          userId = extractLongField(userResult.data.toString, "id")
          grantResult <- interp.execute(
            s"""mutation { grantPermission(resource: "shell", action: "execute", userId: $userId) { id resource action } }""",
          )
          permsResult <- interp.execute(s"""{ permissions(userId: $userId) { id resource action } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          grantResult.errors.isEmpty,
          permsResult.errors.isEmpty,
          permsResult.data.toString.contains("shell"),
          permsResult.data.toString.contains("execute"),
        )
      },
      test("grantPermission with roleId targets the role") {
        for {
          interp     <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          roleResult <- interp.execute("""mutation { createRole(name: "gql-perm-role") { id } }""")
          roleId = extractLongField(roleResult.data.toString, "id")
          grantResult <- interp.execute(
            s"""mutation { grantPermission(resource: "memory", action: "read", roleId: $roleId) { id resource action } }""",
          )
        } yield assertTrue(
          roleResult.errors.isEmpty,
          grantResult.errors.isEmpty,
          grantResult.data.toString.contains("memory"),
          grantResult.data.toString.contains("read"),
        )
      },
      test("revokePermission removes a permission and returns count") {
        for {
          interp     <- ZIO.service[GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]]
          userResult <- interp.execute(
            """mutation { createUser(displayName: "GraphQLUser5", email: "gql5@test.com") { id } }""",
          )
          userId = extractLongField(userResult.data.toString, "id")
          grantResult <- interp.execute(
            s"""mutation { grantPermission(resource: "memory", action: "read", userId: $userId) { id } }""",
          )
          permId = extractLongField(grantResult.data.toString, "id")
          revokeResult <- interp.execute(s"""mutation { revokePermission(value: $permId) }""")
          permsResult  <- interp.execute(s"""{ permissions(userId: $userId) { id resource action } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          grantResult.errors.isEmpty,
          revokeResult.errors.isEmpty,
          permsResult.errors.isEmpty,
          revokeResult.data.toString.contains("1"),
          !permsResult.data.toString.contains("memory"),
        )
      },
    ).provideLayerShared(
      ZLayer
        .make[JorlanAPI.JorlanApiEnv & JorlanSession & GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]](
          appLayer,
          interpreterLayer,
        ),
    ) @@ TestAspect.sequential

  private def extractLongField(
    data:      String,
    fieldName: String,
  ): Long = {
    import scala.language.unsafeNulls
    val pattern = s""""$fieldName":([0-9]+)""".r
    pattern
      .findFirstMatchIn(data)
      .map(_.group(1).toLong)
      .getOrElse(throw AssertionError(s"Field '$fieldName' not found in: $data"))
  }

}
