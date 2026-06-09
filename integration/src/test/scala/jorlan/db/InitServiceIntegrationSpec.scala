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

import jorlan.db.repository.*
import jorlan.init.{InitService, InitServiceImpl, InitTokenStore}
import zio.*
import zio.json.ast.Json
import zio.test.*

import scala.language.unsafeNulls

/** P8.1-S9: Integration test for the first-run initialization flow against a real MariaDB (Testcontainers).
  *
  * Tests run sequentially against a shared container to simulate the lifecycle: fresh DB → init → verify.
  *
  * Verifies:
  *   1. Fresh DB (after Flyway V017) has `initialized = false`.
  *   2. `InitService.complete` with valid token and correct inputs succeeds.
  *   3. After completion, `initialized = true` and `serverName` is persisted.
  *   4. The user created during init is queryable via `UserZIORepository`.
  *   5. A second `complete` call fails with "already initialized".
  */
object InitServiceIntegrationSpec extends ZIOSpec[ZIORepositories & InitTokenStore & InitService] {

  override def bootstrap: ZLayer[Any, Any, ZIORepositories & InitTokenStore & InitService] =
    ZLayer.make[ZIORepositories & InitTokenStore & InitService](
      JorlanContainer.repositoryLayer,
      ZLayer.fromZIO(InitTokenStore.make(false)),
      InitServiceImpl.layer,
    )

  override def spec: Spec[ZIORepositories & InitTokenStore & InitService & TestEnvironment & Scope, Any] =
    suite("InitService integration (real DB)")(
      test("1. fresh DB has initialized = false (V017 seed row present)") {
        for {
          settings    <- ZIO.serviceWith[ZIORepositories](_.setting)
          initialized <- settings.get("initialized")
        } yield assertTrue(initialized.contains(Json.Bool(false)))
      },
      test("2. complete with valid token sets initialized = true and persists serverName") {
        for {
          tokenStore  <- ZIO.service[InitTokenStore]
          token       <- tokenStore.token.map(_.getOrElse(""))
          svc         <- ZIO.service[InitService]
          _           <- svc.complete(token, "TestServer", "admin@example.com", "Admin User", "adminPassword123!")
          settings    <- ZIO.serviceWith[ZIORepositories](_.setting)
          initialized <- settings.get("initialized")
          serverName  <- settings.get("serverName")
        } yield assertTrue(
          initialized.contains(Json.Bool(true)),
          serverName.contains(Json.Str("TestServer")),
        )
      },
      test("3. admin user created during init is queryable via UserZIORepository") {
        for {
          userRepo     <- ZIO.serviceWith[ZIORepositories](_.user)
          eventLogRepo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          users        <- userRepo.search(jorlan.UserSearch())
        } yield assertTrue(users.exists(_.email.contains("admin@example.com")))
      },
      test("4. second complete fails with already initialized") {
        for {
          svc    <- ZIO.service[InitService]
          result <- svc.complete("any-token", "OtherServer", "other@example.com", "Other", "password123!").either
        } yield assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.getMessage.contains("already initialized")),
        )
      },
      test("5. token is invalidated after successful init") {
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          tokenAfter <- tokenStore.token
        } yield assertTrue(tokenAfter.isEmpty)
      },
    ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

}
