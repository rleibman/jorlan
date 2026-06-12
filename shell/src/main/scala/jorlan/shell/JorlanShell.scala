/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import jorlan.{AgentSessionId, SessionStatus}
import jorlan.graphql.client.JorlanClient
import jorlan.shell.client.*
import jorlan.graphql.client.JorlanClientDecoders.given
import jorlan.shell.commands.{CommandHandler, ShellCommand}
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import zio.*
import zio.logging.backend.SLF4J

/** Main entry point for the Jorlan interactive shell.
  *
  * Startup sequence:
  *   1. Load [[ShellConfig]] from `~/.jorlan/jorlan.json` (merged with `application.conf` defaults and CLI args).
  *   2. Prompt for credentials if not present in config.
  *   3. Authenticate against the server; display connected status.
  *   4. Fork the Lanterna rendering loop.
  *   5. Process input in the main fiber: dispatch slash-commands or stub message responses.
  *   6. Shut down cleanly on `/quit`, `/exit`, or Ctrl-C.
  */
object JorlanShell extends ZIOApp {

  override type Environment =
    ShellConfig & AuthClient & GraphQLClient & JorlanScreen & ShellState & SubscriptionClient & InitClient

  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    (Runtime.removeDefaultLoggers >>> SLF4J.slf4j) ++
      ZLayer.makeSome[ZIOAppArgs, Environment](
        ShellConfig.layer,
        AuthClient.live,
        GraphQLClient.live,
        JorlanScreen.live,
        ShellState.live,
        SubscriptionClient.live,
        InitClient.live,
      )

  // $COVERAGE-OFF$ TUI entry point requires a live terminal (Lanterna) and a running server
  override def run: ZIO[Environment & ZIOAppArgs & Scope, Throwable, Unit] = {
    // P7-018: `run` is a named phase sequence; each phase is delegated to a helper.
    val mainFlow = for {
      args   <- ZIOAppArgs.getArgs
      cfg    <- ZIO.service[ShellConfig]
      screen <- ZIO.service[JorlanScreen]

      effectiveCfg = ShellConfig.applyArgs(cfg, args.toList)

      renderFiber <- screen.startRendering.fork

      _ <- displayWelcome(screen)

      // Detect first-run and invoke the setup wizard before attempting login.
      firstRun  <- ShellConfig.isFirstRun(effectiveCfg, args.toList)
      writePath <- ShellConfig.resolveWritePath(args.toList)
      activeCfg <-
        if (firstRun) {
          FirstRunWizard.run(effectiveCfg.typedServerUrl, writePath)
        } else {
          ZIO.succeed(effectiveCfg)
        }
      _ <- screen.setInputPrompt("❯ ")

      _ <- screen.addMessage(MessageKind.System, s"Connecting to ${activeCfg.serverUrl} …")

      (email, password) <- resolveCredentials(activeCfg, screen)

      loginResult <- connectWithRetry(email, password, activeCfg.typedServerUrl)

      _ <- initialisePostLogin(loginResult, activeCfg.typedServerUrl)
      _ <- loadOrCreateSession(screen)

      exitPromise    <- Promise.make[Nothing, Unit]
      loopFiber      <- processLoop(screen, exitPromise).fork
      heartbeatFiber <- connectionHeartbeat(email, password, activeCfg.typedServerUrl).fork

      _ <- exitPromise.await

      _ <- shutdownCleanly(loopFiber, heartbeatFiber, screen)
      _ <- renderFiber.interrupt
      // Return normally so ZIO closes the bootstrap scope (Lanterna, HTTP backends) before
      // calling System.exit.  Calling sys.exit(0) here would bypass scope finalization and
      // trigger ZIO's own shutdown hook while the main fiber is still running, creating a
      // deadlock: System.exit waits for ZIO's hook, ZIO's hook waits for the main fiber.
    } yield ()

    // On any fatal error: display it for 3s then close cleanly.
    mainFlow
      .catchAll { err =>
        ZIO.serviceWithZIO[JorlanScreen](screen =>
          screen.addMessage(
            MessageKind.Error,
            s"Fatal: ${Option(err.getMessage).getOrElse(err.getClass.getName)}",
          ) *>
            ZIO.sleep(3.seconds) *>
            screen.shutdown,
        )
      }
      // On interruption (e.g. SIGINT / Ctrl-C) just shut down the screen; ZIO's own
      // Platform.exit will call System.exit after the scope finalizers have run.
      .ensuring(ZIO.serviceWithZIO[JorlanScreen](_.shutdown))
  }

  // ─── Phase helpers ────────────────────────────────────────────────────────────

  /** Set status and mode bars to the post-login connected state.
    *
    * Fetches `GET /api/status` to obtain the server name, version, and build time. Fails with a descriptive
    * [[RuntimeException]] if the server version is incompatible with this client — the failure propagates to
    * `mainFlow.catchAll` which displays the message and exits.
    *
    * If `/api/status` is unreachable the version check is skipped and the server URL is used as the display name.
    */
  private def initialisePostLogin(
    loginResult: LoginResult,
    serverUrl:   ServerUrl,
  ): ZIO[JorlanScreen & InitClient, Throwable, Unit] = {
    for {
      screen    <- ZIO.service[JorlanScreen]
      statusOpt <- InitClient.checkStatus(serverUrl).option
      _         <- ZIO.foreach(statusOpt) { status =>
        ZIO
          .fromEither(
            VersionCheck.check(
              jorlan.BuildInfo.version,
              jorlan.BuildInfo.buildTime,
              status.version,
              status.buildTime,
            ),
          )
          .mapError(msg =>
            VersionIncompatibleError(s"Version incompatibility with server at ${serverUrl.value} — $msg"),
          )
      }
      serverName = statusOpt.map(_.serverName).getOrElse(serverUrl.value)
      _ <- screen.setStatus(s" ● $serverName  [${loginResult.displayName}]  [${serverUrl.value}]")
      _ <- screen.setModeStatus(
        s" [connected: $serverName]  [user: ${loginResult.displayName}]  [no session]",
      )
      _ <- screen.addMessage(
        MessageKind.System,
        s"Connected to $serverName as ${loginResult.displayName}. Type /help for commands.",
      )
    } yield ()
  }

  /** After login, resume the most recent active session or create a new one. */
  private def loadOrCreateSession(screen: JorlanScreen): ZIO[Environment, Nothing, Unit] = {

    def applySession(
      sessionId: AgentSessionId,
      msg:       String,
    ): ZIO[ShellState & JorlanScreen & SubscriptionClient, Nothing, Unit] =
      for {
        ls <- LiveSession.start(
          sessionId,
          onToolEvent = ev =>
            if (ev.eventType == "SkillInvoked")
              screen.addMessage(MessageKind.System, s"⟳ calling ${ev.toolName}…")
            else if (ev.eventType == "SkillSucceeded")
              screen.addMessage(MessageKind.System, s"✓ ${ev.toolName} done")
            else ZIO.unit,
        )
        _ <- screen.setModeStatus(s" [session: ${ls.sessionId.value}]  [model: default]")
        _ <- screen.addMessage(MessageKind.System, msg)
      } yield ()

    def createNew: ZIO[GraphQLClient & ShellState & JorlanScreen & SubscriptionClient, Nothing, Unit] =
      ZIO
        .serviceWithZIO[GraphQLClient](
          _.run(JorlanClient.Mutations.createSession(None)(JorlanClient.AgentSession.view)),
        ).either.flatMap {
          case Right(Some(session)) => applySession(session.id, s"Session ${session.id.value} started.")
          case Right(None)          => screen.addMessage(MessageKind.Error, "Server returned no session.")
          case Left(err)            => screen.addMessage(MessageKind.Error, s"Failed to create session: $err")
        }

    ZIO
      .serviceWithZIO[GraphQLClient](
        _.run(JorlanClient.Queries.listSessions()(JorlanClient.AgentSession.view)),
      ).either.flatMap {
        case Right(Some(sessions)) =>
          sessions.find(_.status == SessionStatus.Active) match {
            case Some(s) => applySession(s.id, s"Resumed session ${s.id.value}.")
            case None    => createNew
          }
        case _ => createNew
      }
  }

  /** Show goodbye message, interrupt background fibers, and shut the screen down.
    *
    * The subscription fiber is interrupted and awaited first so that the long-lived WebSocket connection is closed
    * before `sys.exit` is called. sttp's HTTP client registers a JVM shutdown hook that waits for open connections; if
    * the WS is still open when the hook fires, the process hangs indefinitely.
    */
  private def shutdownCleanly(
    loopFiber:      Fiber.Runtime[?, ?],
    heartbeatFiber: Fiber.Runtime[?, ?],
    screen:         JorlanScreen,
  ): ZIO[ShellState, Nothing, Unit] = {
    for {
      _           <- ZIO.serviceWithZIO[ShellState](_.interruptDrain)
      liveSession <- ZIO.serviceWithZIO[ShellState](_.getLiveSession)
      // Use interruptFork (fire-and-forget) — the subscription fiber may be blocked on
      // ws.receive() which doesn't respond to ZIO interruption synchronously.  The WS
      // handler's .ensuring(ws.close()) will fire asynchronously; the SubscriptionClient
      // backend scope finalizer closes any remaining connections when the app scope closes.
      _ <- ZIO.foreachDiscard(liveSession) { ls =>
        ls.subscriptionFiber.interruptFork *> ls.toolEventFiber.interruptFork
      }
      _ <- loopFiber.interrupt.fork
      _ <- heartbeatFiber.interrupt.fork
      _ <- screen.addMessage(MessageKind.Raw, "")
      _ <- screen.addMessage(MessageKind.Raw, "  Goodbye!  Thanks for using Jorlan Shell.")
      _ <- screen.addMessage(MessageKind.Raw, "")
      _ <- ZIO.sleep(1500.millis)
      _ <- screen.shutdown
    } yield ()
  }

  // ─── Pure ASCII welcome art (0x20–0x7E only) ─────────────────────────────────

  // ─── Utilities ────────────────────────────────────────────────────────────────

  /** P7-037: Extracted to companion so it can be unit-tested independently. */
  private[shell] def fmtDelay(d: Duration): String =
    if (d.toSeconds >= 1) s"${d.toSeconds}s" else s"${d.toMillis}ms"

  // ─── Welcome art ─────────────────────────────────────────────────────────────

  private val welcomeArt: List[String] = List(
    "",
    "       █████                    ████",
    "      ░░███                    ░░███",
    "       ░███   ██████  ████████  ░███   ██████   ████████",
    "       ░███  ███░░███░░███░░███ ░███  ░░░░░███ ░░███░░███",
    "       ░███ ░███ ░███ ░███ ░░░  ░███   ███████  ░███ ░███",
    " ███   ░███ ░███ ░███ ░███      ░███  ███░░███  ░███ ░███",
    "░░████████  ░░██████  █████     █████░░████████ ████ █████",
    " ░░░░░░░░    ░░░░░░  ░░░░░     ░░░░░  ░░░░░░░░ ░░░░ ░░░░░",
    "",
    "  Secure Agent Runtime & Orchestration Platform",
    "  -----------------------------------------------",
  )

  private def displayWelcome(screen: JorlanScreen): UIO[Unit] =
    ZIO.foreachDiscard(welcomeArt)(line => screen.addMessage(MessageKind.Raw, line))

  // ─── Process loop ─────────────────────────────────────────────────────────────

  private def processLoop(
    screen:      JorlanScreen,
    exitPromise: Promise[Nothing, Unit],
  ): ZIO[Environment, Nothing, Unit] = {
    // P7-009: .catchAllDefect narrowed to CommandHandler.handle only.
    // Defects in readLine or ShellCommand.parse should propagate (they are bugs).
    ZIO
      .serviceWithZIO[JorlanScreen](_.readLine)
      .flatMap { raw =>
        if (raw.isEmpty) ZIO.unit
        else {
          val cmd = ShellCommand.parse(raw)
          screen
            .addMessage(MessageKind.User, raw)
            .zipRight(
              CommandHandler
                .handle(cmd, exitPromise)
                .catchAllDefect(t =>
                  screen.addMessage(
                    MessageKind.Error,
                    s"Command error: ${Option(t.getMessage).getOrElse(t.getClass.getName)}",
                  ),
                ),
            )
        }
      }
      .forever
      .unit
  }

  // ─── Connection management ────────────────────────────────────────────────────

  // 4xx responses indicate a credential or authorization problem that won't be solved by retrying.
  private def isClientError(err: String): Boolean = {
    val clientCodes = Seq("(400)", "(401)", "(403)", "(404)", "(422)", "(429)")
    clientCodes.exists(err.contains)
  }

  private def connectWithRetry(
    email:     String,
    password:  String,
    serverUrl: ServerUrl,
  ): ZIO[AuthClient & JorlanScreen, Throwable, LoginResult] = {

    // Exponential backoff starting at 0.5s, doubling each attempt, capped at 60s.
    val backoff = Schedule
      .exponential(500.millis, 2.0)
      .map(d => if (d.compareTo(60.seconds) > 0) 60.seconds else d)
      .tapOutput { delay =>
        ZIO.serviceWithZIO[JorlanScreen](
          _.setModeStatus(s" [retrying in ${fmtDelay(delay)}…]  [no session]"),
        ) *>
          ZIO.serviceWithZIO[JorlanScreen](
            _.addMessage(MessageKind.System, s"Cannot reach server — retrying in ${fmtDelay(delay)}…"),
          )
      }

    ZIO.serviceWithZIO[JorlanScreen](_.setModeStatus(s" [connecting to ${serverUrl.value}…]  [no session]")) *>
      AuthClient
        .login(email, password)
        .tapError(err => ZIO.serviceWithZIO[JorlanScreen](_.addMessage(MessageKind.Error, s"Connection failed: $err")))
        // Stop retrying on 4xx — wrong credentials won't succeed on the next attempt.
        .retry(backoff.whileInput(!isClientError(_)))
        .mapError(RuntimeException(_))
  }

  private def connectionHeartbeat(
    email:     String,
    password:  String,
    serverUrl: ServerUrl,
  ): ZIO[AuthClient & JorlanScreen, Nothing, Unit] = {
    val check = AuthClient.whoAmI
      .foldZIO(
        _ =>
          // If reconnect fails with a 4xx, orDie surfaces as a defect that kills only this
          // heartbeat fiber; the main shell process continues until the user quits.
          for {
            screen <- ZIO.service[JorlanScreen]
            _      <- screen.addMessage(MessageKind.Error, "Lost connection to server.")
            _      <- screen.setModeStatus(s" [reconnecting…]  [no session]")
            r      <- connectWithRetry(email, password, serverUrl).orDie
            _      <- screen.setStatus(s" ● Jorlan Shell  [${r.displayName}]  [${serverUrl.value}]")
            _      <- screen.setModeStatus(s" [connected: ${serverUrl.value}]  [user: ${r.displayName}]  [no session]")
            _      <- screen.addMessage(MessageKind.System, s"Reconnected as ${r.displayName}.")
          } yield (),
        _ => ZIO.unit,
      )

    // First check after 30s; then every 30s thereafter.
    check.repeat(Schedule.spaced(30.seconds)).unit.delay(30.seconds)
  }

  // ─── Credential resolution ────────────────────────────────────────────────────

  /** Prompt for a single field; return the config value if already set, otherwise read from the TUI input. P7-013:
    * Extracted from `resolveCredentials` to remove duplication. P7-019: Uses `ShellCommand.parse` so all quit aliases
    * (/quit, /exit) are honoured.
    */
  private def promptField(
    screen:   JorlanScreen,
    label:    String,
    existing: Option[String],
  ): ZIO[Any, Throwable, String] = {
    existing match {
      case Some(v) => ZIO.succeed(v)
      case None    =>
        screen.setInputPrompt(label) *>
          screen.readLine.flatMap { line =>
            ShellCommand.parse(line) match {
              case ShellCommand.Quit => ZIO.fail(RuntimeException("Cancelled"))
              case _                 => ZIO.succeed(line)
            }
          }
    }
  }

  // P7-034: The resolved password is held in memory as a plaintext String for the full process
  // lifetime so connectionHeartbeat can silently re-authenticate after a disconnect. This is an
  // accepted Phase 7 limitation. Phase 8: implement JWT refresh tokens to eliminate persistent
  // plaintext storage.
  private def resolveCredentials(
    cfg:    ShellConfig,
    screen: JorlanScreen,
  ): ZIO[Environment, Throwable, (String, String)] = {
    val emailOpt = cfg.email.filter(_.nonEmpty)
    val passwordOpt = cfg.password.filter(_.nonEmpty)

    (emailOpt, passwordOpt) match {
      case (Some(e), Some(p)) => ZIO.succeed((e, p))
      case _                  =>
        for {
          _ <- screen.addMessage(
            MessageKind.System,
            "No credentials in config. Enter email then password below " +
              "(password is visible — no masking in Phase 7).",
          )
          email    <- promptField(screen, "Email: ", emailOpt)
          password <- promptField(screen, "Password: ", passwordOpt)
          _        <- screen.setInputPrompt("❯ ")
        } yield (email, password)
    }
  }
  // $COVERAGE-ON$

}
