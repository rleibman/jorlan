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
import jorlan.db.repository.*
import jorlan.domain.{ConnectionId, User, UserId}
import jorlan.graphql.JorlanRoutes
import jorlan.init.{InitServiceImpl, InitTokenStore, SetupModeApp, StatusRoutes}
import jorlan.web.StaticFileRoutes
import jorlan.service.*
import zio.*
import zio.http.*
import zio.logging.backend.SLF4J

import java.io.*
import java.util.concurrent.TimeUnit

/** ZIO environment type required by the main application. */
type JorlanEnvironment = ConfigurationService & FlywayMigration & AuthServer[User, UserId, ConnectionId] & AuthConfig &
  OAuthService & OAuthStateStore & ApprovalService & CapabilityEvaluator & AgentSessionManager & AgentRunner &
  SessionHub & ModelGateway & ZIORepositories & MemoryService & MemorySkill & JobManager & TriggerEngine &
  ConnectorManager & Client

/** Main entry point for the Jorlan server. */
object Jorlan extends ZIOApp {

  override type Environment = JorlanEnvironment

  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> EnvironmentBuilder.live

  private val healthRoutes: Routes[Any, Nothing] = Routes(
    Method.GET / "health" -> Handler.ok,
  )

  def mapError(original: Cause[Throwable]): UIO[Response] = {
    lazy val contentTypeJson: Headers = Headers(Header.ContentType(MediaType.application.json).untyped)

    val squashed = original.squash
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    squashed.printStackTrace(pw)

    val body = "Error in DMScreen"
    // We really don't want details

    val status = squashed match {
      case _: NotFoundError                    => Status.NotFound
      case e: RepositoryError if e.isTransient => Status.BadGateway
      case _: JorlanError                      => Status.InternalServerError
      case _ => Status.InternalServerError
    }
    ZIO
      .logErrorCause("Error in DMScreen", original).as(
        Response.apply(body = Body.fromString(body), status = status, headers = contentTypeJson),
      )
  }

  /** Build the combined application routes. Extracted so integration tests can wire up the app with a test environment
    * without starting a real HTTP server on a production port.
    */
  def zapp(startTime: Long = 0L): ZIO[JorlanEnvironment, Throwable, Routes[JorlanEnvironment, Nothing]] =
    for {
      config     <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
      repo       <- ZIO.service[ZIORepositories]
      authR      <- authServer.authRoutes
      unauthR    <- authServer.unauthRoutes
      graphqlR   <- JorlanRoutes.routes
      statusR = StatusRoutes.routes(startTime, repo)
      staticR = StaticFileRoutes.routes(config.jorlan.web.root)
    } yield {
      ((healthRoutes ++ statusR ++ authR ++ graphqlR).handleErrorCauseZIO(
        mapError,
      ) @@ authServer.bearerSessionProvider ++ unauthR ++ staticR)
        .handleErrorCause { cause =>
          cause.squash match {
            case ExpiredToken(msg, _) => Response.unauthorized(msg)
            case InvalidToken(msg, _) => Response.unauthorized(msg)
            case e: AuthError => Response.internalServerError(Option(e.getMessage).getOrElse("Authentication error"))
            case e => Response.internalServerError(Option(e.getMessage).getOrElse("Internal server error"))
          }
        }
    }

  private def startServices: ZIO[TriggerEngine & ConnectorManager & Scope, JorlanError, Unit] =
    for {
      _                <- ZIO.serviceWithZIO[TriggerEngine](_.start.forkDaemon)
      connectorManager <- ZIO.service[ConnectorManager]
      _                <- ZIO.acquireRelease(connectorManager.startAll)(_ => connectorManager.stopAll)
    } yield ()

  // $COVERAGE-OFF$ Server bootstrap requires a running MariaDB, Qdrant, and HTTP server — tested via integration suite
  override def run: ZIO[JorlanEnvironment & ZIOAppArgs & Scope, Any, Any] =
    for {
      config      <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      _           <- FlywayMigration.runMigrations
      startTime   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      repo        <- ZIO.service[ZIORepositories]
      initialized <- repo.setting.get(ZIOServerSettingsRepository.InitializedKey).map {
        case Some(zio.json.ast.Json.Bool(v)) => v
        case _                               => false
      }
      tokenStore <- InitTokenStore.make(initialized)
      initService = InitServiceImpl(repo, tokenStore)
      _ <- ZIO.logInfo(s"Jorlan starting on ${config.jorlan.http.host}:${config.jorlan.http.port}")
      _ <-
        if (initialized) {
          for {
            _      <- startServices
            routes <- zapp(startTime)
            _      <- Server.serve(routes).provideSomeLayer(Server.defaultWithPort(config.jorlan.http.port))
          } yield ()
        } else {
          for {
            initDone <- Promise.make[Nothing, Unit]
            setupApp = SetupModeApp.make(startTime, repo, initService, tokenStore, Some(initDone))
            serverFiber <- Server
              .serve(setupApp)
              .provideSomeLayer(Server.defaultWithPort(config.jorlan.http.port))
              .fork
            _      <- initDone.await
            _      <- serverFiber.interrupt
            _      <- ZIO.logInfo("Server initialized — switching to full application routes")
            _      <- startServices
            routes <- zapp(startTime)
            _      <- Server.serve(routes).provideSomeLayer(Server.defaultWithPort(config.jorlan.http.port))
          } yield ()
        }
    } yield ()
  // $COVERAGE-ON$

}
