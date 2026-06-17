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
import jorlan.google.{GmailProvider, GoogleCalendarProvider, GoogleDriveProvider}
import jorlan.init.{InitServiceImpl, InitTokenStore, SetupModeApp, StatusRoutes}
import jorlan.lyrion.{LyrionSettings, LyrionSkill}
import jorlan.market.MarketDataSkill
import jorlan.market.MarketDataSkill.AlphaVantageConfig
import jorlan.routes.*
import jorlan.service.*
import jorlan.service.schedule.TriggerEngine
import jorlan.service.skills.*
import jorlan.time.TimeSkill
import jorlan.units.UnitConversionSkill
import zio.http.*
import zio.logging.backend.SLF4J
import zio.{config, *}

import java.io.*
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** ZIO environment type required by the main application. */
type JorlanEnvironment = ConfigurationService & AuthServer[User, UserId, ConnectionId] & AuthConfig & OAuthService &
  OAuthStateStore & ApprovalService & CapabilityEvaluator & AgentSessionManager & AgentRunner & SessionHub &
  ToolEventHub & ModelGateway & ZIORepositories & MemoryService & SkillRegistry & JobManager & TriggerEngine &
  ConnectorManager & NotificationRouter & Client & jorlan.service.OAuthCredentialService

/** Subset of [[JorlanEnvironment]] required by the GraphQL API layer. */
type JorlanApiEnv = ZIORepositories & CapabilityEvaluator & AgentSessionManager & AgentRunner & MemoryService &
  JobManager & ApprovalService & ModelGateway & SkillRegistry & NotificationRouter & ToolEventHub &
  ConfigurationService & jorlan.service.OAuthCredentialService

/** Main entry point for the Jorlan server. */
object Jorlan extends ZIOApp {

  override type Environment = JorlanEnvironment
  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> EnvironmentBuilder.live

  private case class AllTogether(startTime: Long) extends AppRoutes[JorlanEnvironment, JorlanSession, JorlanError] {

    private val routes: Seq[AppRoutes[JorlanEnvironment, JorlanSession, JorlanError]] =
      Seq(
        JorlanRoutes,
        HealthRoutes,
        StaticRoutes,
        StatusRoutes(startTime),
      )

    override def api: ZIO[
      JorlanEnvironment,
      JorlanError,
      Routes[JorlanEnvironment & JorlanSession, JorlanError],
    ] = ZIO.foreach(routes)(_.api).map(_.reduce(_ ++ _) @@ Middleware.debug)

    override def unauth: ZIO[JorlanEnvironment, JorlanError, Routes[JorlanEnvironment, JorlanError]] =
      ZIO.foreach(routes)(_.unauth).map(_.reduce(_ ++ _) @@ Middleware.debug)

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
      _                <- ZIO.log("Initializing Web Routes")
      config           <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
      authServer       <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
      authConfig       <- ZIO.service[AuthConfig]
      oauthCredSvc     <- ZIO.service[jorlan.service.OAuthCredentialService]
      httpClient       <- ZIO.service[Client]
      repo             <- ZIO.service[ZIORepositories]
      authR            <- authServer.authRoutes // TODO move into allTogether
      unauthR          <- authServer.unauthRoutes // TODO move into allTogether
      authServerApi    <- authServer.authRoutes // TODO move into allTogether
      authServerUnauth <- authServer.unauthRoutes // TODO move into allTogether
      allTogether = AllTogether(startTime)
      unauth     <- allTogether.unauth
      api        <- allTogether.api
      nonceStore <- Ref.make(Map.empty[String, Long])
      oauthAuthR = OAuthRoutes.authenticatedRoutes(authConfig, config.jorlan.google, nonceStore)
      oauthUnauthR = OAuthRoutes.unauthenticatedRoutes(
        authConfig,
        config.jorlan.google,
        oauthCredSvc,
        httpClient,
        nonceStore,
      )
    } yield (
      ((api ++ authR ++ authServerApi ++ oauthAuthR) @@ authServer.bearerSessionProvider) ++
        authServerUnauth ++ unauthR ++ unauth ++ oauthUnauthR
    )
      .handleErrorCauseZIO(mapError)

  private def registerBuiltInSkills: ZIO[JorlanEnvironment, Throwable, Unit] =
    for {
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
          GmailProvider(oauthCredSvc).orDie
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
      calProvider   <- GoogleCalendarProvider(oauthCredSvc).orDie
      driveProvider <- GoogleDriveProvider(oauthCredSvc).orDie
      _             <- registry.register(new CalculatorSkill())
      _             <- registry.register(new MemorySkill(memService))
      _             <- registry.register(new SchedulerSkill(jobManager))
      _             <- registry.register(new ContactsSkill(repos))
      _             <- registry.register(new WorkspaceSkill(workRoot, config.jorlan.workspace))
      _             <- registry.register(new ShellSkill(config.jorlan.shell, repos))
      _             <- registry.register(new NotifySkill(notifRouter))
      _             <- registry.register(new EmailSkill(emailProvider, repos))
      _             <- registry.register(new GoogleCalendarSkill(calProvider, repos))
      _             <- registry.register(new GoogleDriveSkill(driveProvider, repos))
      _             <- repos.setting
        .get("skill.market")
        .mapError(e => new Throwable(e.msg))
        .flatMap {
          case Some(json) =>
            json.as[AlphaVantageConfig] match {
              case Right(cfg) =>
                registry.register(new MarketDataSkill(cfg.apiKey, httpClient, cfg.baseUrl))
              case Left(err) =>
                ZIO.logWarning(s"Skipping market skill: invalid config JSON: $err")
            }
          case None =>
            ZIO.logDebug("Market data skill not configured (set skill.market in server_settings to enable)")
        }
      _ <- repos.setting.get("skill.lyrion").flatMap {
        case Some(json) =>
          json.as[LyrionSettings] match {
            case Right(cfg) =>
              registry.register(new LyrionSkill(cfg, httpClient))
            case Left(err) =>
              ZIO.logWarning(s"Skipping lyrion skill: invalid config JSON: $err")
          }
        case None =>
          ZIO.logDebug("Lyrion skill not configured (set skill.lyrion in server_settings to enable)")
      }
      _ <- registry.register(new UnitConversionSkill())
      _ <- registry.register(new TimeSkill())
    } yield ()

  private def startServices: URIO[Scope & JorlanEnvironment, Unit] =
    for {
      _                <- ZIO.serviceWithZIO[TriggerEngine](_.start.forkDaemon)
      _                <- registerBuiltInSkills.orDie
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
