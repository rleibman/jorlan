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

import _root_.auth.oauth.{OAuthService, OAuthStateStore}
import _root_.auth.{AuthConfig, AuthServer, SecretKey}
import caliban.GraphQLInterpreter
import jorlan.db.JorlanContainer
import jorlan.db.repository.QuillRepositories
import jorlan.domain.{ConnectionId, User, UserId}
import jorlan.graphql.{JorlanAPI, JorlanRoutes}
import jorlan.domain.*
import jorlan.service.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

import scala.language.unsafeNulls

/** End-to-end integration tests using a real MariaDB (Testcontainers) and the full service stack via
  * [[Jorlan.buildRoutes]].
  *
  * Tests verify:
  *   - `Jorlan.buildRoutes` succeeds with a container-backed environment (exercises JorlanRoutes + auth setup)
  *   - GraphQL queries/mutations work end-to-end through the full service layer
  *   - The server-identity session (seeded user id=1) is present and queryable
  */
object JorlanEndToEndSpec extends ZIOSpecDefault {

  private val configLayer = JorlanContainer.configLayer

  private val authConfigLayer: ZLayer[ConfigurationService, Nothing, AuthConfig] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie.map { cfg =>
        val a = cfg.jorlan.auth
        AuthConfig(
          secretKey = SecretKey(a.secretKey),
          accessTTL = a.accessTtlMinutes.minutes,
          refreshTTL = a.refreshTtlDays.days,
        )
      },
    )

  private val oauthLayer: ZLayer[ConfigurationService, Nothing, OAuthService] =
    ZLayer
      .fromZIO(
        ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie.map { _ =>
          OAuthService.live(googleConfig = None, githubConfig = None, discordConfig = None)
        },
      ).flatten

  private val stubCapabilityEvaluator: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.ResourcePermissionAllows))

  private val envLayer: TaskLayer[JorlanEnvironment] =
    ZLayer.make[JorlanEnvironment](
      configLayer,
      jorlan.db.FlywayMigration.live,
      QuillRepositories.live,
      EventLogServiceImpl.live,
      UserServiceImpl.live,
      PermissionServiceImpl.live,
      RiskClassifierImpl.live,
      stubCapabilityEvaluator, // real CapabilityEvaluator tested separately in CapabilityEvaluatorSpec
      ApprovalPolicyEngineImpl.live,
      ApprovalServiceImpl.live,
      jorlan.auth.JorlanAuthServer.live,
      authConfigLayer,
      oauthLayer,
      OAuthStateStore.live(),
    )

  private val interpLayer: ZLayer[
    JorlanAPI.JorlanApiEnv & JorlanSession,
    Nothing,
    GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any],
  ] =
    ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie)

  private val fullLayer: TaskLayer[
    JorlanEnvironment & JorlanSession & GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any],
  ] =
    envLayer >+> ZLayer.succeed(JorlanSession.serverSession) >+> interpLayer

  private type Interp = GraphQLInterpreter[JorlanAPI.JorlanApiEnv & JorlanSession, Any]

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Jorlan end-to-end (real DB)")(
      test("Jorlan.buildRoutes succeeds with full environment") {
        Jorlan.buildRoutes.map(_ => assertTrue(true))
      },
      test("seeded server user is returned by users query") {
        for {
          interp <- ZIO.service[Interp]
          result <- interp.execute("""{ users { id displayName } }""")
        } yield assertTrue(
          result.errors.isEmpty,
          result.data.toString.contains("server"),
        )
      },
      test("createUser and user(id) round-trip with real DB") {
        for {
          interp       <- ZIO.service[Interp]
          createResult <- interp.execute(
            """mutation { createUser(displayName: "E2EUser", email: "e2e@test.com") { id displayName email } }""",
          )
          id = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(createResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          queryResult <- interp.execute(s"""{ user(id: $id) { id displayName email } }""")
        } yield assertTrue(
          createResult.errors.isEmpty,
          queryResult.errors.isEmpty,
          queryResult.data.toString.contains("E2EUser"),
          queryResult.data.toString.contains("e2e@test.com"),
        )
      },
      test("createRole then assignRole to a user persisted to DB") {
        for {
          interp     <- ZIO.service[Interp]
          userResult <- interp.execute("""mutation { createUser(displayName: "E2ERoleUser") { id } }""")
          userId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(userResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          roleResult <- interp.execute("""mutation { createRole(name: "e2e-role", description: "E2E") { id } }""")
          roleId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(roleResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          _           <- interp.execute(s"""mutation { assignRole(userId: $userId, roleId: $roleId) }""")
          rolesResult <- interp.execute(s"""{ roles(userId: $userId) { id name } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          roleResult.errors.isEmpty,
          rolesResult.errors.isEmpty,
          rolesResult.data.toString.contains("e2e-role"),
        )
      },
      test("grantPermission and permissions(userId) with real DB") {
        for {
          interp     <- ZIO.service[Interp]
          userResult <- interp.execute("""mutation { createUser(displayName: "E2EPermUser") { id } }""")
          userId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(userResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          grantResult <- interp.execute(
            s"""mutation { grantPermission(resource: "shell", action: "exec", userId: $userId) { id resource } }""",
          )
          permsResult <- interp.execute(s"""{ permissions(userId: $userId) { resource action } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          grantResult.errors.isEmpty,
          permsResult.errors.isEmpty,
          permsResult.data.toString.contains("shell"),
          permsResult.data.toString.contains("exec"),
        )
      },
    ).provideLayerShared(fullLayer) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

}
