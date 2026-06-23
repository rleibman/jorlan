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
import jorlan.time.{TimeConfig, TimeSkill}
import jorlan.units.UnitConversionSkill
import jorlan.weather.*
import zio.*
import zio.http.*
import zio.json.DecoderOps
import zio.logging.backend.SLF4J

import java.io.*
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Subset of [[JorlanEnvironment]] required by the GraphQL API layer. */
type JorlanApiEnv = ZIORepositories & CapabilityEvaluator & AgentSessionManager & AgentRunner & MemoryService &
  JobManager & ApprovalService & ModelGateway & SkillRegistry & NotificationRouter & ToolEventHub & EventLogHub &
  ConfigurationService & jorlan.service.OAuthCredentialService & Client & DashboardService

/** ZIO environment type required by the main application. */
type JorlanEnvironment =
  JorlanApiEnv & AuthServer[User, UserId, ConnectionId] & AuthConfig & OAuthService & OAuthStateStore & SessionHub &
    TriggerEngine & ConnectorManager & Client & jorlan.service.OAuthCredentialService & EventLogHub

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
      // ── Always-registered skills (no external API key required) ──────────────
      _ <- registry.register(CalculatorSkill())
      _ <- registry.register(MemorySkill(memService))
      _ <- registry.register(SchedulerSkill(jobManager))
      _ <- registry.register(ContactsSkill(repos))
      _ <- registry.register(WorkspaceSkill(workRoot, config.jorlan.workspace))
      _ <- registry.register(ShellSkill(config.jorlan.shell, repos))
      _ <- registry.register(NotifySkill(notifRouter))
      _ <- ZIO
        .attempt(java.time.ZoneId.systemDefault().getId)
        .orElseSucceed("UTC")
        .flatMap { sysTz =>
          repos.setting
            .get("skill.time")
            .mapError(e => new Throwable(e.msg))
            .flatMap {
              case Some(json) =>
                json.as[TimeConfig] match {
                  case Right(cfg) => registry.register(TimeSkill(cfg))
                  case Left(err)  =>
                    registry.register(TimeSkill(TimeConfig(sysTz))) *>
                      ZIO.logWarning(s"time skill: invalid config JSON '$err', using system timezone $sysTz")
                }
              case None =>
                repos.setting
                  .set(
                    "skill.time",
                    zio.json.ast.Json.Obj("defaultTimezone" -> zio.json.ast.Json.Str(sysTz)),
                  )
                  .mapError(e => new Throwable(e.msg)) *>
                  registry.register(TimeSkill(TimeConfig(sysTz))) *>
                  ZIO.logInfo(s"time skill: initialized with system timezone $sysTz")
            }
        }
      _ <- registry.register(UnitConversionSkill())
      _ <- registry.register(UserManagementSkill(repos))
      _ <- registry.register(EmailSkill(emailProvider))
      _ <- registry.register(GoogleCalendarSkill(calProvider))
      _ <- registry.register(GoogleContactsSkill(contactProvider))
      _ <- registry.register(GoogleDriveSkill(driveProvider))
      // ── Config-dependent skills: always register, disable when config is missing ─
      _ <- repos.setting
        .get("skill.market")
        .mapError(e => new Throwable(e.msg))
        .flatMap {
          case Some(json) =>
            json.as[AlphaVantageConfig] match {
              case Right(cfg) => registry.register(MarketDataSkill(cfg.apiKey, httpClient, cfg.baseUrl))
              case Left(err)  =>
                registry.register(MarketDataSkill("", httpClient)) *>
                  registry.disableSkill("market") *>
                  ZIO.logWarning(s"market skill registered but disabled: invalid config JSON: $err")
            }
          case None =>
            registry.register(MarketDataSkill("", httpClient)) *>
              registry.disableSkill("market") *>
              ZIO.logInfo("market skill registered but disabled (set skill.market in server_settings to enable)")
        }
      _ <- repos.setting
        .get("skill.httpFetch")
        .mapError(e => new Throwable(e.msg))
        .flatMap {
          case Some(json) =>
            json.as[HttpFetchConfig] match {
              case Right(cfg) => registry.register(HttpFetchSkill(cfg, httpClient))
              case Left(err)  =>
                registry.register(HttpFetchSkill(HttpFetchConfig(), httpClient)) *>
                  registry.disableSkill("http_fetch") *>
                  ZIO.logWarning(s"http_fetch skill registered but disabled: invalid config JSON: $err")
            }
          case None =>
            registry.register(HttpFetchSkill(HttpFetchConfig(), httpClient)) *>
              registry.disableSkill("http_fetch") *>
              ZIO.logInfo("http_fetch skill registered but disabled (set skill.httpFetch in server_settings to enable)")
        }
      _ <- repos.setting
        .get("skill.lyrion")
        .mapError(e => new Throwable(e.msg))
        .flatMap {
          case Some(json) =>
            json.as[LyrionConfig] match {
              case Right(cfg) => registry.register(LyrionSkill(cfg, httpClient))
              case Left(err)  =>
                registry.register(LyrionSkill(LyrionConfig(), httpClient)) *>
                  registry.disableSkill("lyrion") *>
                  ZIO.logWarning(s"lyrion skill registered but disabled: invalid config JSON: $err")
            }
          case None =>
            registry.register(LyrionSkill(LyrionConfig(), httpClient)) *>
              registry.disableSkill("lyrion") *>
              ZIO.logInfo("lyrion skill registered but disabled (set skill.lyrion in server_settings to enable)")
        }
      _ <- repos.setting
        .get("skill.search")
        .mapError(e => new Throwable(e.msg))
        .flatMap {
          case Some(json) =>
            json.as[SearchConfig] match {
              case Right(cfg) => registry.register(SearchSkill(cfg, httpClient))
              case Left(err)  =>
                registry.register(SearchSkill(SearchConfig(apiKey = ""), httpClient)) *>
                  registry.disableSkill("search") *>
                  ZIO.logWarning(s"search skill registered but disabled: invalid config JSON: $err")
            }
          case None =>
            registry.register(SearchSkill(SearchConfig(apiKey = ""), httpClient)) *>
              registry.disableSkill("search") *>
              ZIO.logInfo("search skill registered but disabled (set skill.search in server_settings to enable)")
        }
      _ <- repos.setting
        .get("skill.weather")
        .mapError(e => new Throwable(e.msg))
        .flatMap {
          case Some(json) =>
            json.as[WeatherConfig] match {
              case Right(cfg) => registry.register(WeatherSkill(cfg, httpClient, cfg.baseUrl))
              case Left(err)  =>
                registry.register(WeatherSkill(WeatherConfig(), httpClient)) *>
                  registry.disableSkill("weather") *>
                  ZIO.logWarning(s"weather skill registered but disabled: invalid config JSON: $err")
            }
          case None =>
            registry.register(WeatherSkill(WeatherConfig(), httpClient)) *>
              registry.disableSkill("weather") *>
              ZIO.logInfo("weather skill registered but disabled (set skill.weather in server_settings to enable)")
        }
      // ── Register reload factories for config-dependent skills ─────────────────
      _ <- registry.registerSkillFactory(
        "skill.time",
        json =>
          ZIO
            .fromEither(json.fromJson[TimeConfig])
            .mapError(e => JorlanError(s"Invalid time config: $e"))
            .map(cfg => TimeSkill(cfg)),
      )
      _ <- registry.registerSkillFactory(
        "skill.market",
        json =>
          ZIO
            .fromEither(json.fromJson[AlphaVantageConfig])
            .mapError(e => JorlanError(s"Invalid market config: $e"))
            .map(cfg => MarketDataSkill(cfg.apiKey, httpClient, cfg.baseUrl)),
      )
      _ <- registry.registerSkillFactory(
        "skill.httpFetch",
        json =>
          ZIO
            .fromEither(json.fromJson[HttpFetchConfig])
            .mapError(e => JorlanError(s"Invalid httpFetch config: $e"))
            .map(cfg => HttpFetchSkill(cfg, httpClient)),
      )
      _ <- registry.registerSkillFactory(
        "skill.lyrion",
        json =>
          ZIO
            .fromEither(json.fromJson[LyrionConfig])
            .mapError(e => JorlanError(s"Invalid lyrion config: $e"))
            .map(cfg => LyrionSkill(cfg, httpClient)),
      )
      _ <- registry.registerSkillFactory(
        "skill.search",
        json =>
          ZIO
            .fromEither(json.fromJson[SearchConfig])
            .mapError(e => JorlanError(s"Invalid search config: $e"))
            .map(cfg => SearchSkill(cfg, httpClient)),
      )
      _ <- registry.registerSkillFactory(
        "skill.weather",
        json =>
          ZIO
            .fromEither(json.fromJson[WeatherConfig])
            .mapError(e => JorlanError(s"Invalid weather config: $e"))
            .map(cfg => WeatherSkill(cfg, httpClient, cfg.baseUrl)),
      )
      // ── MCP servers ───────────────────────────────────────────────────────────
      _ <- ZIO
        .scoped {
          McpManagerImpl(registry, httpClient, repos.setting).loadAndRegister
        }.mapError(e => new Throwable(e.msg))
      // ── Apply explicit skill.disabled list from server_settings ───────────────
      _ <- repos.setting.get("skill.disabled").mapError(e => new Throwable(e.msg)).flatMap {
        case Some(zio.json.ast.Json.Arr(elems)) =>
          val names = elems.collect { case zio.json.ast.Json.Str(s) => s }
          ZIO.foreachDiscard(names)(name => registry.disableSkill(name))
        case _ => ZIO.unit
      }
      // ── Purge stale skillIndex rows from previous runs ────────────────────────
      _ <- registry.purgeStaleIndex()
      _ <- registry.allSkills
        .map(_.map(_.descriptor.name).mkString(", ")).flatMap(s => ZIO.logInfo(s"Registered skills: $s"))
    } yield ()).mapError(JorlanError.apply)

  private def startServices: ZIO[Scope & SkillRegistry & ConnectorManager & JorlanEnvironment, JorlanError, Unit] =
    for {
      _ <- ZIO.serviceWithZIO[TriggerEngine](_.start.forkDaemon)
      _ <- ZIO
        .serviceWithZIO[ZIORepositories] { repos =>
          repos.memory.purgeExpired
            .tap(n => ZIO.logDebug(s"Memory purge: removed $n expired record(s)"))
            .mapError(JorlanError(_))
            .repeat(Schedule.spaced(1.hour))
            .forkDaemon
        }.unit
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
        _      <- startServices
        _      <- initService.topUpAdminCapabilities
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
        _      <- startServices
        _      <- initService.topUpAdminCapabilities
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
