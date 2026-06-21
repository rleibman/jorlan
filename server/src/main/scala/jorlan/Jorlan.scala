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
import jorlan.*
import jorlan.calculator.CalculatorSkill
import jorlan.db.FlywayMigration
import jorlan.db.repository.*
import jorlan.email.{ImapSmtpProvider, PgpService}
import jorlan.google.*
import jorlan.httpfetch.{HttpFetchConfig, HttpFetchSkill}
import jorlan.init.{InitServiceImpl, InitTokenStore, SetupModeApp, StatusRoutes}
import jorlan.lyrion.*
import jorlan.market.*
import jorlan.routes.*
import jorlan.search.{SearchConfig, SearchSkill}
import jorlan.service.*
import jorlan.service.mcp.McpManagerImpl
import jorlan.service.schedule.TriggerEngine
import jorlan.service.skills.*
import jorlan.time.TimeSkill
import jorlan.units.UnitConversionSkill
import jorlan.weather.*
import zio.*
import zio.http.*
import zio.logging.backend.SLF4J

import java.io.*
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Subset of [[JorlanEnvironment]] required by the GraphQL API layer. */
type JorlanApiEnv = ZIORepositories & CapabilityEvaluator & AgentSessionManager & AgentRunner & MemoryService &
  JobManager & ApprovalService & ModelGateway & SkillRegistry & NotificationRouter & ToolEventHub &
  ConfigurationService & jorlan.service.OAuthCredentialService & Client

/** ZIO environment type required by the main application. */
type JorlanEnvironment =
  JorlanApiEnv & AuthServer[User, UserId, ConnectionId] & AuthConfig & OAuthService & OAuthStateStore & SessionHub &
    TriggerEngine & ConnectorManager & Client & jorlan.service.OAuthCredentialService

/** Main entry point for the Jorlan server. */
object Jorlan extends ZIOApp {

  override type Environment = JorlanEnvironment
  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> EnvironmentBuilder.live

  private object AllTogether {

    def apply(startTime: Long): ZIO[
      ConfigurationService & Client & AuthConfig & OAuthCredentialService & AuthServer[User, UserId, ConnectionId],
      ConfigurationError,
      AppRoutes[JorlanEnvironment, JorlanSession, JorlanError],
    ] = {
      for {
        authServer   <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
        oauthCredSvc <- ZIO.service[jorlan.service.OAuthCredentialService]
        authConfig   <- ZIO.service[AuthConfig]
        httpClient   <- ZIO.service[Client]
        config       <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
        oauthRoutes  <- OAuthRoutes()
      } yield new AppRoutes[JorlanEnvironment, JorlanSession, JorlanError] {

        private val routes: Seq[AppRoutes[JorlanEnvironment, JorlanSession, JorlanError]] =
          Seq(
            AuthRoutes(authServer),
            JorlanRoutes,
            HealthRoutes,
            StaticRoutes,
            StatusRoutes(startTime),
            oauthRoutes,
          )

        override def api: ZIO[
          JorlanEnvironment,
          JorlanError,
          Routes[JorlanEnvironment & JorlanSession, JorlanError],
        ] = ZIO.foreach(routes)(_.api).map(_.reduce(_ ++ _) @@ Middleware.debug)

        override def unauth: ZIO[JorlanEnvironment, JorlanError, Routes[JorlanEnvironment, JorlanError]] =
          ZIO.foreach(routes)(_.unauth).map(_.reduce(_ ++ _) @@ Middleware.debug)

      }
    }

  }

  def mapError(original: Cause[Throwable]): UIO[Response] = {
    lazy val contentTypeJson: Headers = Headers(Header.ContentType(MediaType.application.json).untyped)

    val squashed = original.squash
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    squashed.printStackTrace(pw)

    val body = "Error in Jorlan"
    // We really don't want details

    val status = squashed match {
      case _: NotFoundError                    => Status.NotFound
      case e: RepositoryError if e.isTransient => Status.BadGateway
      case _: JorlanError                      => Status.InternalServerError
      case _ => Status.InternalServerError
    }
    ZIO
      .logErrorCause("Error in Jorlan", original).as(
        Response.apply(body = Body.fromString(body), status = status, headers = contentTypeJson),
      )
  }

  /** Build the combined application routes. Extracted so integration tests can wire up the app with a test environment
    * without starting a real HTTP server on a production port.
    */
  def zapp(startTime: Long = 0L): ZIO[JorlanEnvironment, JorlanError, Routes[JorlanEnvironment, Nothing]] =
    for {
      _           <- ZIO.log("Initializing Web Routes")
      config      <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      httpClient  <- ZIO.service[Client]
      repo        <- ZIO.service[ZIORepositories]
      authServer  <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
      allTogether <- AllTogether(startTime)
      unauth      <- allTogether.unauth
      api         <- allTogether.api
    } yield (
      (api @@ authServer.bearerSessionProvider) ++ unauth
    )
      .handleErrorCauseZIO(mapError)

  private def registerBuiltInSkills: ZIO[JorlanEnvironment, JorlanError, Unit] =
    (for {
      registry     <- ZIO.service[SkillRegistry]
      config       <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      memService   <- ZIO.service[MemoryService]
      jobManager   <- ZIO.service[JobManager]
      repos        <- ZIO.service[ZIORepositories]
      notifRouter  <- ZIO.service[NotificationRouter]
      httpClient   <- ZIO.service[Client]
      oauthCredSvc <- ZIO.service[OAuthCredentialService]
      workRoot     <- ZIO.attempt(Paths.get(config.jorlan.workspace.root).toAbsolutePath.normalize())
      pgpService = PgpService.noOp
      emailCfg = config.jorlan.email
      emailProvider <-
        if (emailCfg.defaultProvider.toLowerCase == "gmail") {
          GmailProvider(oauthCredSvc)
        } else {
          ZIO.succeed(
            ImapSmtpProvider(
              imapHost = emailCfg.imap.host,
              imapPort = emailCfg.imap.port,
              imapSsl = emailCfg.imap.ssl,
              smtpHost = emailCfg.smtp.host,
              smtpPort = emailCfg.smtp.port,
              smtpTls = emailCfg.smtp.startTls,
              username = "",
              password = "",
            ),
          )
        }
      calProvider     <- GoogleCalendarProvider(oauthCredSvc)
      driveProvider   <- GoogleDriveProvider(oauthCredSvc)
      contactProvider <- GoogleContactsProvider(oauthCredSvc)
      _               <- registry.register(CalculatorSkill())
//      _               <- registry.register(MemorySkill(memService))
//      _               <- registry.register(SchedulerSkill(jobManager))
      _ <- registry.register(ContactsSkill(repos))
//      _               <- registry.register(WorkspaceSkill(workRoot, config.jorlan.workspace))
//      _               <- registry.register(ShellSkill(config.jorlan.shell, repos))
//      _               <- registry.register(NotifySkill(notifRouter))
//      _               <- registry.register(EmailSkill(emailProvider))
//      _               <- registry.register(GoogleCalendarSkill(calProvider))
//      _               <- registry.register(GoogleContactsSkill(contactProvider))
//      _               <- registry.register(GoogleDriveSkill(driveProvider))
      _ <- registry.register(TimeSkill())
      _ <- registry.register(UnitConversionSkill())
//      _               <- registry.register(UserManagementSkill(repos))
      _ <- repos.setting
        .get("skill.market")
        .mapError(e => new Throwable(e.msg))
        .flatMap {
          case Some(json) =>
            json.as[AlphaVantageConfig] match {
              case Right(cfg) =>
                registry.register(MarketDataSkill(cfg.apiKey, httpClient, cfg.baseUrl))
              case Left(err) =>
                ZIO.logWarning(s"Skipping market skill: invalid config JSON: $err")
            }
          case None =>
            ZIO.logDebug("Market data skill not configured (set skill.market in server_settings to enable)")
        }
      _ <- repos.setting.get("skill.httpFetch").flatMap {
        case Some(json) =>
          json.as[HttpFetchConfig] match {
            case Right(cfg) =>
              registry.register(HttpFetchSkill(cfg, httpClient))
            case Left(err) =>
              ZIO.logWarning(s"Skipping lyrion skill: invalid config JSON: $err")
          }
        case None =>
          ZIO.logDebug("Lyrion skill not configured (set skill.lyrion in server_settings to enable)")
      }
      _ <- repos.setting.get("skill.lyrion").flatMap {
        case Some(json) =>
          json.as[LyrionConfig] match {
            case Right(cfg) =>
              registry.register(LyrionSkill(cfg, httpClient))
            case Left(err) =>
              ZIO.logWarning(s"Skipping lyrion skill: invalid config JSON: $err")
          }
        case None =>
          ZIO.logDebug("Lyrion skill not configured (set skill.lyrion in server_settings to enable)")
      }
      _ <- repos.setting.get("skill.search").flatMap {
        case Some(json) =>
          json.as[SearchConfig] match {
            case Right(cfg) =>
              registry.register(SearchSkill(cfg, httpClient))
            case Left(err) =>
              ZIO.logWarning(s"Skipping search skill: invalid config JSON: $err")
          }
        case None =>
          ZIO.logDebug("Search skill not configured (set skill.search in server_settings to enable)")
      }
      _ <- repos.setting.get("skill.weather").flatMap {
        case Some(json) =>
          json.as[WeatherConfig] match {
            case Right(cfg) =>
              registry.register(WeatherSkill(cfg, httpClient, cfg.baseUrl))
            case Left(err) =>
              ZIO.logWarning(s"Skipping weather skill: invalid config JSON: $err")
          }
        case None =>
          ZIO.logDebug("Weather skill not configured (set skill.weather in server_settings to enable)")
      }
      // Load MCP servers from server_settings
      _ <- ZIO
        .scoped {
          McpManagerImpl(registry, httpClient, repos.setting).loadAndRegister
        }.mapError(e => new Throwable(e.msg))
      _ <- repos.setting.get("skill.disabled").mapError(e => new Throwable(e.msg)).flatMap {
        case Some(zio.json.ast.Json.Arr(elems)) =>
          val names = elems.collect { case zio.json.ast.Json.Str(s) => s }
          ZIO.foreachDiscard(names)(name => registry.disableSkill(name))
        case _ => ZIO.unit
      }
      _ <- registry.allSkills
        .map(_.map(_.descriptor.name).mkString(",")).flatMap(s => ZIO.logInfo(s"All Registered skills: $s"))
      // TODO log disabled skills
    } yield ()).mapError(JorlanError.apply)

  private def startServices: ZIO[Scope & SkillRegistry & ConnectorManager & JorlanEnvironment, JorlanError, Unit] =
    for {
      _                <- ZIO.serviceWithZIO[TriggerEngine](_.start.forkDaemon)
      _                <- registerBuiltInSkills
      connectorManager <- ZIO.service[ConnectorManager]
      registry         <- ZIO.service[SkillRegistry]
      _                <- ZIO.foreachDiscard(connectorManager.connectors)(registry.register)
      _                <- ZIO.acquireRelease(connectorManager.startAll)(_ => connectorManager.stopAll)
    } yield ()

  // $COVERAGE-OFF$
  // Server bootstrap requires a running MariaDB, Qdrant, and HTTP server — tested via integration suite
  override def run: ZIO[Environment & ZIOAppArgs & Scope, JorlanError, Unit] =
    for {
      config      <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      _           <- FlywayMigration.migrate(config.jorlan.flyway, config.jorlan.db).mapError(JorlanError.apply)
      startTime   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      repo        <- ZIO.service[ZIORepositories]
      initialized <- repo.setting.get(ZIOServerSettingsRepository.InitializedKey).map {
        case Some(zio.json.ast.Json.Bool(v)) => v
        case _                               => false
      }
      tokenStore    <- InitTokenStore.make(initialized)
      skillRegistry <- ZIO.service[SkillRegistry]
      initService = InitServiceImpl(repo, tokenStore, skillRegistry)
      _ <- ZIO.logInfo(s"Jorlan starting on ${config.jorlan.http.host}:${config.jorlan.http.port}")
      serverConfig = ZLayer.succeed(
        Server.Config.default
          .binding(config.jorlan.http.host, config.jorlan.http.port)
          .copy(requestStreaming = Server.RequestStreaming.Enabled),
      )
      randomConnectionId <- ConnectionId.randomZIO
      _                  <- (for {
        _      <- initService.topUpAdminCapabilities
        _      <- startServices
        routes <- zapp(startTime)
        _      <- Server.serve(routes)
      } yield ())
        .provideSome[Environment & Scope](serverConfig, Server.live)
        .mapError(JorlanError.apply)
        .when(initialized)
      _ <- (for {
        initDone <- Promise.make[Nothing, Unit]
        setupApp <- SetupModeApp.make(
          startTime = startTime,
          initService = initService,
          tokenStore = tokenStore,
          initDone = Some(initDone),
        )
        serverFiber <- Server
          .serve(setupApp)
          .fork
        _      <- initDone.await
        _      <- serverFiber.interrupt
        _      <- ZIO.logInfo("Server initialized — switching to full application routes")
        _      <- initService.topUpAdminCapabilities
        _      <- startServices
        routes <- zapp(startTime)
        _      <- Server.serve(routes)
      } yield ())
        .provideSome[Environment & Scope](
          serverConfig,
          Server.live,
          ZLayer.succeed(JorlanSession.guestSession(randomConnectionId)),
        )
        .mapError(JorlanError.apply)
        .when(!initialized)
    } yield ()
  // $COVERAGE-ON$

}
