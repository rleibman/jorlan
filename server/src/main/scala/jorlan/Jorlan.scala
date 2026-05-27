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

import _root_.auth.{AuthConfig, AuthError, AuthServer, ExpiredToken, InvalidToken, Session}
import _root_.auth.oauth.{OAuthService, OAuthStateStore}
import jorlan.db.FlywayMigration
import jorlan.domain.{ConnectionId, User, UserId}
import jorlan.service.EventLogService
import zio.*
import zio.http.*
import zio.logging.backend.SLF4J

/** ZIO environment type required by the main application. */
type JorlanEnvironment = ConfigurationService & FlywayMigration & EventLogService &
  AuthServer[User, UserId, ConnectionId] & AuthConfig & OAuthService & OAuthStateStore

/** Main entry point for the Jorlan server. */
object Jorlan extends ZIOApp {

  override type Environment = JorlanEnvironment

  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> EnvironmentBuilder.live

  private val healthRoutes: Routes[Any, Nothing] = Routes(
    Method.GET / "health" -> Handler.ok,
  )

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Throwable, Unit] = {
    val zapp = for {
      authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
      authR      <- authServer.authRoutes
      unauthR    <- authServer.unauthRoutes
    } yield {
      ((healthRoutes ++ authR) @@ authServer.bearerSessionProvider ++ unauthR)
        .handleErrorCause { cause =>
          cause.squash match {
            case ExpiredToken(msg, _) => Response.unauthorized(msg)
            case InvalidToken(msg, _) => Response.unauthorized(msg)
            case e: AuthError => Response.internalServerError(e.getMessage)
            case e => Response.internalServerError(e.getMessage)
          }
        }
    }

    for {
      config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie
      _      <- FlywayMigration.runMigrations
      _      <- ZIO.logInfo(s"Jorlan starting on ${config.jorlan.http.host}:${config.jorlan.http.port}")
      app    <- zapp
      _ <- Server
        .serve(app)
        .provideSomeLayer(Server.defaultWithPort(config.jorlan.http.port))
    } yield ()
  }

}
