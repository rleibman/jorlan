/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.init

import jorlan.*
import jorlan.db.repository.ServerSettingsRepository
import jorlan.service.{EventLogServiceImpl, UserService, UserServiceImpl}
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*

/** P8.1-S8: Unit tests for [[InitService]] using in-memory repositories and a controlled token store.
  *
  * Tests: (a) invalid token → `JorlanError` (b) duplicate init → `JorlanError` (c) successful init flips DB flag and
  * invalidates token
  */
object InitServiceSpec extends ZIOSpecDefault {

  // ─── In-memory ServerSettingsRepository ────────────────────────────────────

  private class InMemorySettingsRepo(store: Ref[Map[String, Json]]) extends ServerSettingsRepository {

    override def get(key: String): UIO[Option[Json]] = store.get.map(_.get(key))
    override def set(
      key:   String,
      value: Json,
    ): UIO[Unit] = store.update(_.updated(key, value))

  }

  private def settingsLayer(initial: Map[String, Json]): ULayer[ServerSettingsRepository] =
    ZLayer.fromZIO(
      Ref
        .make(initial)
        .map(r => new InMemorySettingsRepo(r): ServerSettingsRepository),
    )

  private val uninitializedSettings: ULayer[ServerSettingsRepository] =
    settingsLayer(Map("initialized" -> Json.Bool(false)))

  private val alreadyInitializedSettings: ULayer[ServerSettingsRepository] =
    settingsLayer(Map("initialized" -> Json.Bool(true)))

  // ─── Helper: build an InitService with a given token and settings ───────────

  private type TestEnv = ServerSettingsRepository & UserService & InitTokenStore

  private def makeTokenStore(initialized: Boolean): ZLayer[Any, Nothing, InitTokenStore] =
    ZLayer.fromZIO(InitTokenStore.make(initialized))

  private val userServiceLayer: ULayer[UserService] =
    InMemoryRepositories.InMemoryUserRepo.layer ++
      (InMemoryRepositories.InMemoryEventLogRepo.layer >>> EventLogServiceImpl.live) >>>
      UserServiceImpl.live

  private def testLayer(
    settingsLayer: ULayer[ServerSettingsRepository],
    initialized:   Boolean,
  ): ULayer[TestEnv] =
    settingsLayer ++ userServiceLayer ++ makeTokenStore(initialized)

  private val initServiceLayer: URLayer[TestEnv, InitService] =
    ZLayer.fromFunction(new InitServiceImpl(_, _, _))

  // ─── Tests ─────────────────────────────────────────────────────────────────

  override def spec: Spec[Any, Any] =
    suite("InitService")(
      test("invalid token returns JorlanError") {
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          _          <- tokenStore.token // consume to ensure it's been generated
          svc        <- ZIO.service[InitService]
          result     <- svc.complete("wrong-token", "MyServer", "admin@example.com", "Admin", "password123!").either
        } yield assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.getMessage.contains("Invalid setup token")),
        )
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
      test("duplicate init returns JorlanError") {
        for {
          svc    <- ZIO.service[InitService]
          result <- svc.complete("any-token", "MyServer", "admin@example.com", "Admin", "password123!").either
        } yield assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.getMessage.contains("already initialized")),
        )
      }.provide(
        testLayer(alreadyInitializedSettings, initialized = true),
        initServiceLayer,
      ),
      test("successful init flips initialized flag and invalidates token") {
        for {
          tokenStore  <- ZIO.service[InitTokenStore]
          validToken  <- tokenStore.token.map(_.getOrElse(""))
          svc         <- ZIO.service[InitService]
          _           <- svc.complete(validToken, "MyServer", "admin@example.com", "Admin", "password123!")
          settings    <- ZIO.service[ServerSettingsRepository]
          initialized <- settings.get("initialized")
          serverName  <- settings.get("serverName")
          tokenAfter  <- tokenStore.token
        } yield assertTrue(
          initialized.contains(Json.Bool(true)),
          serverName.contains(Json.Str("MyServer")),
          tokenAfter.isEmpty,
        )
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
      test("validation rejects password shorter than 12 characters") {
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          validToken <- tokenStore.token.map(_.getOrElse(""))
          svc        <- ZIO.service[InitService]
          result     <- svc.complete(validToken, "MyServer", "admin@example.com", "Admin", "short").either
        } yield assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.getMessage.contains("12 characters")),
        )
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
      test("validation rejects malformed email") {
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          validToken <- tokenStore.token.map(_.getOrElse(""))
          svc        <- ZIO.service[InitService]
          result     <- svc.complete(validToken, "MyServer", "notanemail", "Admin", "password123!").either
        } yield assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.getMessage.contains("email")),
        )
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
    )

}
