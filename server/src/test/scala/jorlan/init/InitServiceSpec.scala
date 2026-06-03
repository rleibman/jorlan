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
import jorlan.db.repository.{
  EventLogZIORepository,
  PermissionZIORepository,
  RepositoryError,
  RepositoryTask,
  ServerSettingsRepository,
  UserZIORepository,
}
import jorlan.domain.{ChannelIdentity, ChannelIdentityId, ChannelType, User, UserId}
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

  private val failingUserRepo: ULayer[UserZIORepository] = ZLayer.succeed(new UserZIORepository {
    override def getById(id: UserId):            RepositoryTask[Option[User]] = ZIO.die(new RuntimeException("stub"))
    override def search(s:   jorlan.UserSearch): RepositoryTask[List[User]] = ZIO.die(new RuntimeException("stub"))
    override def upsert(user:   User):   RepositoryTask[User] = ZIO.fail(RepositoryError("simulated DB failure"))
    override def deactivate(id: UserId): RepositoryTask[Long] = ZIO.die(new RuntimeException("stub"))
    override def getChannelIdentities(userId: UserId): RepositoryTask[List[ChannelIdentity]] =
      ZIO.die(new RuntimeException("stub"))
    override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
      ZIO.die(new RuntimeException("stub"))
    override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
      ZIO.die(new RuntimeException("stub"))
    override def login(
      email:    String,
      password: String,
    ):                                       RepositoryTask[Option[User]] = ZIO.die(new RuntimeException("stub"))
    override def userByEmail(email: String): RepositoryTask[Option[User]] = ZIO.die(new RuntimeException("stub"))
    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): RepositoryTask[Unit] = ZIO.die(new RuntimeException("stub"))
    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ): RepositoryTask[Option[User]] = ZIO.die(new RuntimeException("stub"))
  })

  private type TestEnv =
    ServerSettingsRepository & UserZIORepository & InitTokenStore & EventLogZIORepository & PermissionZIORepository

  private def makeTokenStore(initialized: Boolean): ZLayer[Any, Nothing, InitTokenStore] =
    ZLayer.fromZIO(InitTokenStore.make(initialized))

  private val eventLogLayer: ULayer[EventLogZIORepository] =
    InMemoryRepositories.InMemoryEventLogRepo.layer

  private val userRepoLayer: ULayer[UserZIORepository] =
    InMemoryRepositories.InMemoryUserRepo.layer

  private val permRepoLayer: ULayer[PermissionZIORepository] =
    InMemoryRepositories.InMemoryPermissionRepo.layer

  private def testLayer(
    settingsLayer: ULayer[ServerSettingsRepository],
    initialized:   Boolean,
  ): ULayer[TestEnv] =
    settingsLayer ++ userRepoLayer ++ makeTokenStore(initialized) ++ eventLogLayer ++ permRepoLayer

  private val initServiceLayer: URLayer[TestEnv, InitService] =
    ZLayer.fromFunction(new InitServiceImpl(_, _, _, _, _))

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
      // P8.1-021: createUser failure leaves initialized = false
      test("createUser failure leaves initialized flag unchanged") {
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          validToken <- tokenStore.token.map(_.getOrElse(""))
          svc        <- ZIO.service[InitService]
          result     <- svc.complete(validToken, "MyServer", "admin@example.com", "Admin", "password123!").either
          settings   <- ZIO.service[ServerSettingsRepository]
          flagAfter  <- settings.get(ServerSettingsRepository.InitializedKey)
        } yield assertTrue(
          result.isLeft,
          flagAfter.contains(Json.Bool(false)),
        )
      }.provide(
        settingsLayer(Map(ServerSettingsRepository.InitializedKey -> Json.Bool(false))) ++
          failingUserRepo ++
          makeTokenStore(false) ++
          eventLogLayer ++
          permRepoLayer,
        initServiceLayer,
      ),
      // P8.1-020: blank/whitespace serverName
      test("validation rejects blank server name") {
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          validToken <- tokenStore.token.map(_.getOrElse(""))
          svc        <- ZIO.service[InitService]
          result     <- svc.complete(validToken, "   ", "admin@example.com", "Admin", "password123!").either
        } yield assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.getMessage.contains("Server name")),
        )
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
      // P85-033 / Phase 9: all 14 admin capability grants are seeded after successful init
      test("successful init seeds all 14 admin capability grants") {
        val expectedCapabilities = Set(
          "agent.session.create",
          "agent.session.list",
          "agent.message",
          "admin.personality.read",
          "admin.personality.update",
          "user.create",
          "user.update",
          "role.create",
          "role.assign",
          "role.revoke",
          "permission.grant",
          "permission.revoke",
          "memory.read",
          "memory.write",
        )
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          validToken <- tokenStore.token.map(_.getOrElse(""))
          svc        <- ZIO.service[InitService]
          _          <- svc.complete(validToken, "MyServer", "admin@example.com", "Admin", "password123!")
          permRepo   <- ZIO.service[PermissionZIORepository]
          // Find the user that was created (id=1 from InMemoryUserRepo)
          grants <- permRepo.searchGrants(jorlan.GrantSearch(userId = jorlan.domain.UserId(1L)))
          grantedCaps = grants.map(_.capability.value).toSet
        } yield assertTrue(
          grantedCaps == expectedCapabilities,
          grants.size == expectedCapabilities.size,
        )
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
      // ─── InitTokenStore companion accessors ──────────────────────────────────
      test("InitTokenStore companion: token, isValid, verify, invalidate") {
        for {
          tok      <- InitTokenStore.token
          valid0   <- InitTokenStore.isValid
          verified <- InitTokenStore.verify(tok.getOrElse(""))
          _        <- InitTokenStore.invalidate
          valid1   <- InitTokenStore.isValid
          tokAfter <- InitTokenStore.token
        } yield assertTrue(
          tok.isDefined,
          valid0,
          verified,
          !valid1,
          tokAfter.isEmpty,
        )
      }.provide(makeTokenStore(false)),
      // ─── InitTokenStoreImpl.isValid when no token (initialized = true) ───────
      test("InitTokenStore.isValid returns false when initialized=true") {
        for {
          valid <- InitTokenStore.isValid
        } yield assertTrue(!valid)
      }.provide(makeTokenStore(true)),
      // ─── InitService companion accessors ─────────────────────────────────────
      test("InitService companion: isInitialized") {
        for {
          result <- InitService.isInitialized
        } yield assertTrue(!result)
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
      test("InitService companion: complete delegates to implementation") {
        for {
          tok    <- InitTokenStore.token
          result <- InitService.complete(tok.getOrElse(""), "MyServer", "admin@example.com", "Admin", "password123!")
        } yield assertTrue(result == ())
      }.provide(
        testLayer(uninitializedSettings, initialized = false),
        initServiceLayer,
      ),
      // ─── ServerSettingsRepository companion accessors ─────────────────────────
      test("ServerSettingsRepository companion: get and set") {
        for {
          _      <- ZIO.serviceWithZIO[ServerSettingsRepository](_.set("testKey", Json.Str("hello")))
          gotten <- ZIO.serviceWithZIO[ServerSettingsRepository](_.get("testKey"))
        } yield assertTrue(gotten.contains(Json.Str("hello")))
      }.provide(uninitializedSettings),
      test("ServerSettingsRepository companion: isServerInitialized returns true when flag is true") {
        for {
          result <- ServerSettingsRepository.isServerInitialized
        } yield assertTrue(result)
      }.provide(alreadyInitializedSettings),
      test("ServerSettingsRepository companion: isServerInitialized returns false when key absent") {
        for {
          result <- ServerSettingsRepository.isServerInitialized
        } yield assertTrue(!result)
      }.provide(settingsLayer(Map.empty)),
      test("ServerSettingsRepository companion: isServerInitialized returns false for non-Bool value") {
        for {
          result <- ServerSettingsRepository.isServerInitialized
        } yield assertTrue(!result)
      }.provide(settingsLayer(Map(ServerSettingsRepository.InitializedKey -> Json.Str("yes")))),
    )

}
