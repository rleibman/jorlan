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

import _root_.auth.*
import _root_.auth.oauth.{OAuthService, OAuthStateStore}
import jorlan.db.FlywayMigration
import jorlan.db.repository.{
  EventLogZIORepository,
  PermissionZIORepository,
  ServerSettingsRepository,
  UserZIORepository,
}
import jorlan.domain.{ConnectionId, User, UserId}
import jorlan.graphql.JorlanRoutes
import jorlan.init.{InitServiceImpl, InitTokenStore, SetupModeApp, StatusRoutes}
import jorlan.service.*
import zio.*
import zio.http.*
import zio.logging.backend.SLF4J

import java.util.concurrent.TimeUnit

/** ZIO environment type required by the main application. */
type JorlanEnvironment = ConfigurationService & FlywayMigration & AuthServer[User, UserId, ConnectionId] & AuthConfig &
  OAuthService & OAuthStateStore & ApprovalService & CapabilityEvaluator & AgentSessionManager & AgentRunner &
  SessionHub & ModelGateway & ServerSettingsRepository & UserZIORepository & PermissionZIORepository &
  EventLogZIORepository

/** Main entry point for the Jorlan server. */
object Jorlan extends ZIOApp {

  override type Environment = JorlanEnvironment

  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> EnvironmentBuilder.live

  private val healthRoutes: Routes[Any, Nothing] = Routes(
    Method.GET / "health" -> Handler.ok,
  )

  /** Build the combined application routes. Extracted so integration tests can wire up the app with a test environment
    * without starting a real HTTP server on a production port.
    */
  def buildRoutes(startTime: Long = 0L): ZIO[JorlanEnvironment, Throwable, Routes[JorlanEnvironment, Nothing]] =
    for {
      authServer   <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
      settingsRepo <- ZIO.service[ServerSettingsRepository]
      authR        <- authServer.authRoutes
      unauthR      <- authServer.unauthRoutes
      graphqlR     <- JorlanRoutes.routes.orDie
      statusR = StatusRoutes.routes(startTime, settingsRepo)
    } yield {
      ((healthRoutes ++ statusR ++ authR ++ graphqlR) @@ authServer.bearerSessionProvider ++ unauthR)
        .handleErrorCause { cause =>
          cause.squash match {
            case ExpiredToken(msg, _) => Response.unauthorized(msg)
            case InvalidToken(msg, _) => Response.unauthorized(msg)
            case e: AuthError => Response.internalServerError(Option(e.getMessage).getOrElse("Authentication error"))
            case e => Response.internalServerError(Option(e.getMessage).getOrElse("Internal server error"))
          }
        }
    }

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Throwable, Unit] =
    for {
      config       <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie
      _            <- FlywayMigration.runMigrations
      startTime    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      settingsRepo <- ZIO.service[ServerSettingsRepository]
      userRepo     <- ZIO.service[UserZIORepository]
      eventLogRepo <- ZIO.service[EventLogZIORepository]
      initialized  <- settingsRepo.get(ServerSettingsRepository.InitializedKey).map {
        case Some(zio.json.ast.Json.Bool(v)) => v
        case _                               => false
      }
      tokenStore <- InitTokenStore.make(initialized)
      initService = new InitServiceImpl(settingsRepo, userRepo, tokenStore, eventLogRepo)
      _   <- ZIO.logInfo(s"Jorlan starting on ${config.jorlan.http.host}:${config.jorlan.http.port}")
      app <-
        if (initialized) {
          buildRoutes(startTime)
        } else {
          ZIO.succeed(SetupModeApp.make(startTime, settingsRepo, initService))
        }
      _ <- Server
        .serve(app)
        .provideSomeLayer(Server.defaultWithPort(config.jorlan.http.port))
    } yield ()

}
