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
import jorlan.domain.UserId
import jorlan.service.UserService
import zio.*
import zio.json.ast.Json

import java.security.SecureRandom
import scala.language.unsafeNulls

/** One-time token generated at startup when the server is uninitialized.
  *
  * The token is printed to stdout and must be supplied with `POST /api/init`. It is discarded (set to `None`) after the
  * first successful initialization and cannot be reused.
  */
class InitTokenStore private (tokenRef: Ref[Option[String]]) {

  val token: UIO[Option[String]] = tokenRef.get

  val invalidate: UIO[Unit] = tokenRef.set(None)

  val isValid: UIO[Boolean] = tokenRef.get.map(_.isDefined)

  def verify(provided: String): UIO[Boolean] =
    tokenRef.get.map(_.contains(provided))

}

object InitTokenStore {

  private def generateToken(): String = {
    val rng = new SecureRandom()
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    bytes.map(b => f"${b & 0xff}%02x").mkString
  }

  /** Creates the token store. If `initialized` is false, a 32-hex token is generated and printed to stdout. If already
    * initialized, the store holds `None` and setup is permanently disabled.
    */
  def make(initialized: Boolean): UIO[InitTokenStore] =
    if (initialized) {
      Ref.make(Option.empty[String]).map(new InitTokenStore(_))
    } else {
      val tok = generateToken()
      ZIO.succeed {
        val border = "═" * 58
        println(s"\n╔$border╗")
        println(s"║  JORLAN SETUP TOKEN  (valid for this process only)       ║")
        println(s"║  $tok  ║")
        println(s"╚$border╝\n")
      } *> Ref.make(Option(tok)).map(new InitTokenStore(_))
    }

}

/** Performs first-run server initialization. */
trait InitService {

  def isInitialized: UIO[Boolean]

  def complete(
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): IO[JorlanError, Unit]

}

object InitService {

  def isInitialized: URIO[InitService, Boolean] =
    ZIO.serviceWithZIO[InitService](_.isInitialized)

  def complete(
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): ZIO[InitService, JorlanError, Unit] =
    ZIO.serviceWithZIO[InitService](_.complete(token, serverName, adminEmail, adminName, adminPassword))

}

class InitServiceImpl(
  settings:   ServerSettingsRepository,
  users:      UserService,
  tokenStore: InitTokenStore,
) extends InitService {

  override val isInitialized: UIO[Boolean] =
    settings.get("initialized").map {
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
      _           <- ZIO.when(alreadyDone)(ZIO.fail(JorlanError("Server is already initialized"))).unit
      tokenOk     <- tokenStore.verify(token)
      _           <- ZIO.unless(tokenOk)(ZIO.fail(JorlanError("Invalid setup token"))).unit
      _           <- validateInputs(serverName, adminEmail, adminPassword)
      createdUser <- users.createUser(adminName, Some(adminEmail), None)
      _           <- users.setPassword(createdUser.id, adminPassword)
      _           <- settings.set("initialized", Json.Bool(true))
      _           <- settings.set("serverName", Json.Str(serverName))
      _           <- tokenStore.invalidate
    } yield ()

  private def validateInputs(
    serverName:    String,
    adminEmail:    String,
    adminPassword: String,
  ): IO[JorlanError, Unit] =
    (
      ZIO.when(serverName.trim.isEmpty)(ZIO.fail(ValidationError("Server name must not be empty"))) *>
        ZIO.when(!adminEmail.contains("@"))(ZIO.fail(ValidationError("Admin email is not valid"))) *>
        ZIO.when(adminPassword.length < 12)(ZIO.fail(ValidationError("Password must be at least 12 characters")))
    ).unit

}

object InitServiceImpl {

  val live: URLayer[ServerSettingsRepository & UserService & InitTokenStore, InitService] =
    ZLayer.fromFunction(new InitServiceImpl(_, _, _))

}
