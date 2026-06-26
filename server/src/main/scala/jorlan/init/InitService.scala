/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.init

import jorlan.*
import jorlan.db.repository.*
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.json.ast.Json

import java.security.SecureRandom
import scala.language.unsafeNulls

/** One-time token generated at startup when the server is uninitialized.
  *
  * The token is printed to stdout and must be supplied with `POST /api/init`. It is discarded (set to `None`) after the
  * first successful initialization and cannot be reused.
  */
trait InitTokenStore {

  /** The current token, or `None` if the store has been invalidated. */
  def token: UIO[Option[String]]

  /** Discards the token permanently; subsequent `verify` calls will always return `false`. */
  def invalidate: UIO[Unit]

  /** `true` while a token is held (not yet invalidated). */
  def isValid: UIO[Boolean]

  /** Returns `true` if `provided` matches the stored token. */
  def verify(provided: String): UIO[Boolean]

}

object InitTokenStore {

  def token:                    URIO[InitTokenStore, Option[String]] = ZIO.serviceWithZIO[InitTokenStore](_.token)
  def invalidate:               URIO[InitTokenStore, Unit] = ZIO.serviceWithZIO[InitTokenStore](_.invalidate)
  def isValid:                  URIO[InitTokenStore, Boolean] = ZIO.serviceWithZIO[InitTokenStore](_.isValid)
  def verify(provided: String): URIO[InitTokenStore, Boolean] = ZIO.serviceWithZIO[InitTokenStore](_.verify(provided))

  private val rng: SecureRandom = SecureRandom()

  private def generateToken(): IO[JorlanError, String] =
    ZIO
      .attempt {
        val bytes = new Array[Byte](16)
        rng.nextBytes(bytes)
        bytes.map(b => f"${b & 0xff}%02x").mkString
      }.mapError(JorlanError.apply)

  /** Creates the token store. If `initialized` is false, a 32-hex token is generated and printed to stdout. If already
    * initialized, the store holds `None` and setup is permanently disabled.
    */
  def make(initialized: Boolean): IO[JorlanError, InitTokenStore] =
    if (initialized) {
      Ref.make(Option.empty[String]).map(InitTokenStoreImpl(_))
    } else {
      generateToken().flatMap { tok =>
        ZIO.succeed {
          val border = "═" * 58
          println(s"\n╔$border╗")
          println(s"║  JORLAN SETUP TOKEN  (valid for this process only)       ║")
          println(s"║  $tok  ║")
          println(s"╚$border╝\n")
        } *> Ref.make(Option(tok)).map(InitTokenStoreImpl(_))
      }
    }

}

private class InitTokenStoreImpl(tokenRef: Ref[Option[String]]) extends InitTokenStore {

  override def token:                    UIO[Option[String]] = tokenRef.get
  override def invalidate:               UIO[Unit] = tokenRef.set(None)
  override def isValid:                  UIO[Boolean] = tokenRef.get.map(_.isDefined)
  override def verify(provided: String): UIO[Boolean] = tokenRef.get.map(_.contains(provided))

}

/** Performs first-run server initialization. */
trait InitService {

  /** Returns `true` if the server has been initialized (i.e., the `initialized` flag is `true` in `server_settings`).
    */
  def isInitialized: IO[JorlanError, Boolean]

  /** Validates inputs, creates the admin user with the given password, persists server settings, and invalidates the
    * setup token. Fails with [[ValidationError]] (→ HTTP 400) for bad inputs, or [[JorlanError]] (→ HTTP 403) if the
    * token is invalid or the server is already initialized.
    *
    * @param token
    *   the one-time setup token printed to stdout at startup
    * @param serverName
    *   human-readable name for this server instance
    * @param adminEmail
    *   email address for the first admin user
    * @param adminName
    *   display name for the first admin user
    * @param adminPassword
    *   plain-text password for the first admin user (min 12 chars)
    */
  def complete(
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): IO[JorlanError, Unit]

  /** Idempotently grants all admin capabilities to every existing admin user.
    *
    * Runs on every startup so that capabilities added after the initial `complete` call are automatically applied to
    * pre-existing admin users. Uses upsert semantics — existing grants are untouched.
    */
  def topUpAdminCapabilities: IO[JorlanError, Unit]

}

object InitService {

  def isInitialized: ZIO[InitService, JorlanError, Boolean] =
    ZIO.serviceWithZIO[InitService](_.isInitialized)

  def complete(
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): ZIO[InitService, JorlanError, Unit] =
    ZIO.serviceWithZIO[InitService](_.complete(token, serverName, adminEmail, adminName, adminPassword))

  def topUpAdminCapabilities: ZIO[InitService, JorlanError, Unit] =
    ZIO.serviceWithZIO[InitService](_.topUpAdminCapabilities)

}

@scala.annotation.nowarn("msg=IsUnionOf")
class InitServiceImpl(
  repo:          ZIORepositories,
  tokenStore:    InitTokenStore,
  skillRegistry: SkillRegistry,
) extends InitService {

  override def isInitialized: IO[JorlanError, Boolean] =
    repo.setting.get(ZIOServerSettingsRepository.InitializedKey).map {
      case Some(Json.Bool(v)) => v
      case _                  => false
    }

  override def complete(
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): IO[JorlanError, Unit] =
    for {
      alreadyDone <- isInitialized
      _           <- ZIO.when(alreadyDone)(ZIO.fail(JorlanError("Server is already initialized")))
      tokenOk     <- tokenStore.verify(token)
      _           <- ZIO.unless(tokenOk)(ZIO.fail(JorlanError("Invalid setup token")))
      _           <- validateInputs(serverName, adminEmail, adminPassword)
      now         <- Clock.instant
      createdUser <- repo.user.upsert(User(UserId.empty, adminName, adminEmail, now, now))
      _           <- repo.user.changePassword(createdUser.id, adminPassword).mapError(JorlanError(_))
      adminRole   <- ensureAdminRole(now)
      _           <- repo.permission.assignRole(createdUser.id, adminRole.id).mapError(JorlanError(_))
      _           <- repo.setting.set(ZIOServerSettingsRepository.InitializedKey, Json.Bool(true))
      _           <- repo.setting.set(ZIOServerSettingsRepository.ServerNameKey, Json.Str(serverName))
      _           <- tokenStore.invalidate
      _           <- repo.eventLog.append(
        EventLog[Nothing](
          id = EventLogId.empty,
          eventType = EventType.ServerInitialized,
          actorId = Some(createdUser.id),
          agentId = None,
          sessionId = None,
          resource = None,
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield ()

  // Platform-level capabilities not tied to any particular skill.
  // Skill-specific capabilities (memory.*, notify.*, telegram.*, etc.) are derived
  // automatically from the SkillRegistry so new skills never require an update here.
  private val systemCapabilities: List[CapabilityName] = List(
    CapabilityName("agent.session.create"),
    CapabilityName("agent.session.list"),
    CapabilityName("agent.message"),
    CapabilityName("agent.session.terminate"),
    CapabilityName("admin.personality.read"),
    CapabilityName("admin.personality.update"),
    CapabilityName("admin.user.list"),
    CapabilityName("admin.user.manage"),
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
    // Shell
    CapabilityName("shell.read"),
    // Weather
    CapabilityName("weather.read"),
    // Discord
    CapabilityName("discord.send"),
    CapabilityName("discord.read"),
    // Declarative skill lifecycle
    CapabilityName("skill.create"),
    CapabilityName("skill.propose"),
    CapabilityName("admin.skills.approve"),
  )

  val AdminRoleName = "Admin"

  private def allAdminCaps: UIO[List[CapabilityName]] =
    skillRegistry.allAdminCapabilities.map(skillCaps => (systemCapabilities ++ skillCaps).distinct)

  /** Idempotently ensures the Admin role exists and has all admin capabilities granted to it as Persistent. */
  private def ensureAdminRole(now: java.time.Instant): IO[JorlanError, Role] =
    for {
      existing <- repo.permission.searchRoles(RoleSearch()).mapError(JorlanError(_))
      role     <- existing.find(_.name == AdminRoleName) match {
        case Some(r) => ZIO.succeed(r)
        case None    =>
          repo.permission
            .upsertRole(Role(RoleId.empty, AdminRoleName, Some("Full system administrator with all capabilities")))
            .mapError(JorlanError(_))
      }
      existingGrants <- repo.permission
        .searchGrants(GrantSearch(roleId = Some(role.id), pageSize = 1000))
        .mapError(JorlanError(_))
      caps <- allAdminCaps
      _    <- ZIO
        .foreachDiscard(caps) { cap =>
          ZIO.unless(existingGrants.exists(_.capability == cap)) {
            repo.permission
              .upsertCapabilityGrant(
                CapabilityGrant(
                  id = CapabilityGrantId.empty,
                  capability = cap,
                  scopeJson = None,
                  granteeId = role.id.value,
                  granteeType = GranteeType.Role,
                  grantorId = None,
                  approvalMode = ApprovalMode.Persistent,
                  expiresAt = None,
                  resourceConstraints = None,
                  createdAt = now,
                ),
              )
              .mapError(JorlanError(_))
          }
        }
    } yield role

  override def topUpAdminCapabilities: IO[JorlanError, Unit] =
    for {
      now            <- Clock.instant
      role           <- ensureAdminRole(now)
      existingGrants <- repo.permission
        .searchGrants(GrantSearch(roleId = Some(role.id), pageSize = 1000))
        .mapError(JorlanError(_))
      caps <- allAdminCaps
      _    <- ZIO
        .foreachDiscard(caps) { cap =>
          ZIO.unless(existingGrants.exists(_.capability == cap)) {
            repo.permission
              .upsertCapabilityGrant(
                CapabilityGrant(
                  id = CapabilityGrantId.empty,
                  capability = cap,
                  scopeJson = None,
                  granteeId = role.id.value,
                  granteeType = GranteeType.Role,
                  grantorId = None,
                  approvalMode = ApprovalMode.Persistent,
                  expiresAt = None,
                  resourceConstraints = None,
                  createdAt = now,
                ),
              )
              .mapError(JorlanError(_))
          }
        }
    } yield ()

  private def validateInputs(
    serverName:    String,
    adminEmail:    String,
    adminPassword: String,
  ): IO[JorlanError, Unit] = {
    val errors = List(
      Option.when(serverName.trim.isEmpty)("Server name must not be empty"),
      Option.when(!adminEmail.contains("@"))("Admin email is not valid"),
      Option.when(adminPassword.length < 12)("Password must be at least 12 characters"),
    ).flatten
    ZIO.when(errors.nonEmpty)(ZIO.fail(ValidationError(errors.mkString("; ")))).unit
  }

}

object InitServiceImpl {

  // NOTE: `InitServiceImpl` is deliberately constructed with `new` in `Jorlan.run` rather than through
  // this layer. `InitTokenStore` requires the `initialized` flag read from the DB after Flyway has run,
  // which is not available at ZLayer bootstrap time. This layer is available for test code that
  // provides a pre-built `InitTokenStore` via its own setup logic.
  val layer: URLayer[ZIORepositories & InitTokenStore & SkillRegistry, InitService] =
    ZLayer.fromFunction(InitServiceImpl(_, _, _))

}
