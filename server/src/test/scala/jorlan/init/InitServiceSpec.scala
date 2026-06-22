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
import jorlan.db.repository.*
import jorlan.service.skills.SkillRegistry
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

  import TestUtil.*

  private val uninitializedSettings: URLayer[ZIORepositories, ZIORepositories] =
    ZLayer.fromZIO(withSettings(Map("initialized" -> Json.Bool(false))))
  private val alreadyInitializedSettings: URLayer[ZIORepositories, ZIORepositories] =
    ZLayer.fromZIO(withSettings(Map("initialized" -> Json.Bool(true))))

  // ─── Helper: build an InitService with a given token and settings ───────────

  private val failingUserRepo: ULayer[ZIOUserRepository] = ZLayer.succeed(new ZIOUserRepository {
    override def getById(id: UserId):            RepositoryTask[Option[User]] = ZIO.die(RuntimeException("stub"))
    override def search(s:   jorlan.UserSearch): RepositoryTask[List[User]] = ZIO.die(RuntimeException("stub"))
    override def upsert(user:   User):   RepositoryTask[User] = ZIO.fail(RepositoryError("simulated DB failure"))
    override def deactivate(id: UserId): RepositoryTask[Long] = ZIO.die(RuntimeException("stub"))
    override def getChannelIdentities(userId: UserId): RepositoryTask[List[ChannelIdentity]] =
      ZIO.die(RuntimeException("stub"))
    override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
      ZIO.die(RuntimeException("stub"))
    override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
      ZIO.die(RuntimeException("stub"))
    override def login(
      email:    String,
      password: String,
    ):                                       RepositoryTask[Option[User]] = ZIO.die(RuntimeException("stub"))
    override def userByEmail(email: String): RepositoryTask[Option[User]] = ZIO.die(RuntimeException("stub"))
    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): RepositoryTask[Unit] = ZIO.die(RuntimeException("stub"))
    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ):                                                  RepositoryTask[Option[User]] = ZIO.die(RuntimeException("stub"))
    override def findContacts(nameOpt: Option[String]): RepositoryTask[zio.json.ast.Json] =
      ZIO.die(RuntimeException("stub"))
  })

  private val initServiceLayer: URLayer[ZIORepositories & InitTokenStore & SkillRegistry, InitServiceImpl] =
    ZLayer.fromFunction(InitServiceImpl(_, _, _))

  // ─── Tests ─────────────────────────────────────────────────────────────────

  override def spec =
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
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
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
        InMemoryRepositories.live() >>> alreadyInitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(true)),
        SkillRegistry.live,
        initServiceLayer,
      ),
      test("successful init flips initialized flag and invalidates token") {
        for {
          tokenStore  <- ZIO.service[InitTokenStore]
          validToken  <- tokenStore.token.map(_.getOrElse(""))
          svc         <- ZIO.service[InitService]
          _           <- svc.complete(validToken, "MyServer", "admin@example.com", "Admin", "password123!")
          settings    <- ZIO.serviceWith[ZIORepositories](_.setting)
          initialized <- settings.get("initialized")
          serverName  <- settings.get("serverName")
          tokenAfter  <- tokenStore.token
        } yield assertTrue(
          initialized.contains(Json.Bool(true)),
          serverName.contains(Json.Str("MyServer")),
          tokenAfter.isEmpty,
        )
      }.provide(
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
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
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
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
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
        initServiceLayer,
      ),
      // P8.1-021: createUser failure leaves initialized = false
      test("createUser failure leaves initialized flag unchanged") {
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          validToken <- tokenStore.token.map(_.getOrElse(""))
          svc        <- ZIO.service[InitService]
          result     <- svc.complete(validToken, "MyServer", "admin@example.com", "Admin", "password123!").either
          settings   <- ZIO.serviceWith[ZIORepositories](_.setting)
          flagAfter  <- settings.get(ZIOServerSettingsRepository.InitializedKey)
        } yield assertTrue(
          result.isLeft,
          flagAfter.contains(Json.Bool(false)),
        )
      }.provide(
        InMemoryRepositories.live() >>> InMemoryRepositories.withOverridenLayers(userRepoOpt =
          Some(failingUserRepo),
        ) >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
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
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
        initServiceLayer,
      ),
      // P85-033 / Phase 9 + Phase 12: all admin capability grants are seeded after successful init.
      // Skill-specific caps (memory.*, notify.*, etc.) are sourced from the SkillRegistry at runtime;
      // this test uses an empty registry so only the platform system caps are expected.
      test("successful init seeds all admin capability grants") {
        val expectedCapabilities = Set(
          CapabilityName("agent.session.create"),
          CapabilityName("agent.session.list"),
          CapabilityName("agent.message"),
          CapabilityName("agent.session.terminate"),
          CapabilityName("admin.personality.read"),
          CapabilityName("admin.personality.update"),
          CapabilityName("admin.user.list"),
          CapabilityName("admin.settings"),
          CapabilityName("user.create"),
          CapabilityName("user.update"),
          CapabilityName("role.create"),
          CapabilityName("role.assign"),
          CapabilityName("role.revoke"),
          CapabilityName("permission.grant"),
          CapabilityName("permission.revoke"),
          CapabilityName("approval.decide"),
          CapabilityName("agent.skill.invoke"),
          // Email
          CapabilityName("email.read"),
          CapabilityName("email.write"),
          CapabilityName("email.send"),
          // Calendar
          CapabilityName("calendar.read"),
          CapabilityName("calendar.write"),
          // Drive
          CapabilityName("drive.read"),
          // MCP
          CapabilityName("mcp.call"),
          CapabilityName("admin.mcp.reload"),
          // Search
          CapabilityName("search.read"),
          // Shell read-only sandbox tools
          CapabilityName("shell.read"),
          // Weather
          CapabilityName("weather.read"),
          // Shell read-only sandbox tools
          CapabilityName("shell.read"),
        ).map(_.value)
        for {
          tokenStore <- ZIO.service[InitTokenStore]
          validToken <- tokenStore.token.map(_.getOrElse(""))
          svc        <- ZIO.service[InitService]
          _          <- svc.complete(validToken, "MyServer", "admin@example.com", "Admin", "password123!")
          permRepo   <- ZIO.serviceWith[ZIORepositories](_.permission)
          // Find the user that was created (id=1 from InMemoryUserRepo)
          grants <- permRepo.searchGrants(jorlan.GrantSearch(userId = jorlan.UserId(1L)))
          grantedCaps = grants.map(_.capability.value).toSet
        } yield assertTrue(
          grantedCaps == expectedCapabilities,
          grants.size == expectedCapabilities.size,
        )
      }.provide(
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
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
      }.provide(ZLayer.fromZIO(InitTokenStore.make(false))),
      // ─── InitTokenStoreImpl.isValid when no token (initialized = true) ───────
      test("InitTokenStore.make(false) generates a 32-char hex token") {
        for {
          store <- InitTokenStore.make(false)
          tok   <- store.token
        } yield assertTrue(
          tok.isDefined,
          tok.exists(_.length == 32),
          tok.exists(_.forall(c => "0123456789abcdef".contains(c))),
        )
      },
      test("InitTokenStore.isValid returns false when initialized=true") {
        for {
          valid <- InitTokenStore.isValid
        } yield assertTrue(!valid)
      }.provide(ZLayer.fromZIO(InitTokenStore.make(true))),
      // ─── InitService companion accessors ─────────────────────────────────────
      test("InitService companion: isInitialized") {
        for {
          result <- InitService.isInitialized
        } yield assertTrue(!result)
      }.provide(
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
        initServiceLayer,
      ),
      test("InitService companion: complete delegates to implementation") {
        for {
          tok    <- InitTokenStore.token
          result <- InitService.complete(tok.getOrElse(""), "MyServer", "admin@example.com", "Admin", "password123!")
        } yield assertTrue(result == ())
      }.provide(
        InMemoryRepositories.live() >>> uninitializedSettings,
        ZLayer.fromZIO(InitTokenStore.make(false)),
        SkillRegistry.live,
        initServiceLayer,
      ),
      // ─── ZIOServerSettingsRepository companion accessors ─────────────────────────
      test("ZIOServerSettingsRepository companion: get and set") {
        for {
          _      <- ZIO.serviceWithZIO[ZIORepositories](_.setting.set("testKey", Json.Str("hello")))
          gotten <- ZIO.serviceWithZIO[ZIORepositories](_.setting.get("testKey"))
        } yield assertTrue(gotten.contains(Json.Str("hello")))
      }.provide(InMemoryRepositories.live() >>> uninitializedSettings),
      test("isServerInitialized returns true when flag is true") {
        for {
          result <- ZIO.serviceWithZIO[ZIORepositories](_.setting.isServerInitialized)
        } yield assertTrue(result)
      }.provide(
        InMemoryRepositories.live() >>> alreadyInitializedSettings,
      ),
      test("isServerInitialized returns false when key absent") {
        for {
          result <- ZIO.serviceWithZIO[ZIORepositories](_.setting.isServerInitialized)
        } yield assertTrue(!result)
      }.provide(InMemoryRepositories.live() >>> ZLayer.fromZIO(withSettings(Map.empty))),
      test("isServerInitialized returns false for non-Bool value") {
        for {
          result <- ZIO.serviceWithZIO[ZIORepositories](_.setting.isServerInitialized)
        } yield assertTrue(!result)
      }.provide(
        InMemoryRepositories.live() >>> ZLayer.fromZIO(
          withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Str("yes"))),
        ),
      ),
    )

}
