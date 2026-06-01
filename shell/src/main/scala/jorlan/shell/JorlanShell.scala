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

import jorlan.shell.client.{AuthClient, GraphQLClient, InitClient, LoginResult, SubscriptionClient}
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
          FirstRunWizard.run(effectiveCfg.serverUrl, writePath)
        } else {
          ZIO.succeed(effectiveCfg)
        }

      _ <- screen.addMessage(MessageKind.System, s"Connecting to ${activeCfg.serverUrl} …")

      (email, password) <- resolveCredentials(activeCfg, screen)

      loginResult <- connectWithRetry(email, password, activeCfg.serverUrl)

      _ <- initialisePostLogin(loginResult, activeCfg.serverUrl)

      exitPromise    <- Promise.make[Nothing, Unit]
      loopFiber      <- processLoop(screen, exitPromise).fork
      heartbeatFiber <- connectionHeartbeat(email, password, activeCfg.serverUrl).fork

      _ <- exitPromise.await

      _ <- shutdownCleanly(loopFiber, heartbeatFiber, screen)
      _ <- renderFiber.interrupt

      // ZIO.succeed rather than ZIO.attempt: sys.exit does not fail in practice.
      // P7-020: After P7-001 the HTTP backends are closed by ZLayer.scoped before this
      // point, so this only needs to kill ZIO's blocking thread pool.
      _ <- ZIO.succeed(sys.exit(0))
    } yield ()

    // On any fatal error: display it for 3s then close cleanly.
    mainFlow.catchAll { err =>
      ZIO.serviceWithZIO[JorlanScreen](screen =>
        screen.addMessage(
          MessageKind.Error,
          s"Fatal: ${Option(err.getMessage).getOrElse(err.getClass.getName)}",
        ) *>
          ZIO.sleep(3.seconds) *>
          screen.shutdown,
      )
    }
  }

  // ─── Phase helpers ────────────────────────────────────────────────────────────

  /** Set status and mode bars to the post-login connected state. Fetches the server name from `/api/status` to display
    * in the title bar; falls back to the server URL on failure.
    */
  private def initialisePostLogin(
    loginResult: LoginResult,
    serverUrl:   String,
  ): ZIO[JorlanScreen & InitClient, Nothing, Unit] = {
    for {
      screen     <- ZIO.service[JorlanScreen]
      serverName <- InitClient
        .checkStatus(serverUrl)
        .map(_.serverName)
        .orElse(ZIO.succeed(serverUrl))
      _ <- screen.setStatus(s" ● $serverName  [${loginResult.displayName}]  [$serverUrl]")
      _ <- screen.setModeStatus(
        s" [connected: $serverName]  [user: ${loginResult.displayName}]  [no session]",
      )
      _ <- screen.addMessage(
        MessageKind.System,
        s"Connected to $serverName as ${loginResult.displayName}. Type /help for commands.",
      )
    } yield ()
  }

  /** Show goodbye message, interrupt background fibers, and shut the screen down. */
  private def shutdownCleanly(
    loopFiber:      Fiber.Runtime[?, ?],
    heartbeatFiber: Fiber.Runtime[?, ?],
    screen:         JorlanScreen,
  ): ZIO[Any, Nothing, Unit] = {
    // Fire-and-forget interrupts so we never block on a fiber inside a slow TCP call.
    loopFiber.interrupt.fork *>
      heartbeatFiber.interrupt.fork *>
      screen.addMessage(MessageKind.Raw, "") *>
      screen.addMessage(MessageKind.Raw, "  Goodbye!  Thanks for using Jorlan Shell.") *>
      screen.addMessage(MessageKind.Raw, "") *>
      ZIO.sleep(1500.millis) *>
      screen.shutdown
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
    "       ░███  ███░░███░░███ ░███  ░░░░░███ ░░███░░███",
    "       ░███ ░███ ░███ ░░░  ░███   ███████  ░███ ░░░",
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
    serverUrl: String,
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

    ZIO.serviceWithZIO[JorlanScreen](_.setModeStatus(s" [connecting to $serverUrl…]  [no session]")) *>
      AuthClient
        .login(email, password)
        .tapError(err => ZIO.serviceWithZIO[JorlanScreen](_.addMessage(MessageKind.Error, s"Connection failed: $err")))
        // Stop retrying on 4xx — wrong credentials won't succeed on the next attempt.
        .retry(backoff.whileInput(!isClientError(_)))
        .mapError(new RuntimeException(_))
  }

  private def connectionHeartbeat(
    email:     String,
    password:  String,
    serverUrl: String,
  ): ZIO[AuthClient & JorlanScreen, Nothing, Unit] = {
    val check = AuthClient.whoAmI
      .foldZIO(
        _ =>
          ZIO.serviceWithZIO[JorlanScreen](_.addMessage(MessageKind.Error, "Lost connection to server.")) *>
            ZIO.serviceWithZIO[JorlanScreen](_.setModeStatus(s" [reconnecting…]  [no session]")) *>
            // If reconnect fails with a 4xx, orDie surfaces as a defect that kills only this
            // heartbeat fiber; the main shell process continues until the user quits.
            connectWithRetry(email, password, serverUrl).orDie.flatMap { r =>
              ZIO.serviceWithZIO[JorlanScreen](_.setStatus(s" ● Jorlan Shell  [${r.displayName}]  [$serverUrl]")) *>
                ZIO.serviceWithZIO[JorlanScreen](
                  _.setModeStatus(s" [connected: $serverUrl]  [user: ${r.displayName}]  [no session]"),
                ) *>
                ZIO.serviceWithZIO[JorlanScreen](_.addMessage(MessageKind.System, s"Reconnected as ${r.displayName}."))
            },
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
              case ShellCommand.Quit => ZIO.fail(new RuntimeException("Cancelled"))
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

}
