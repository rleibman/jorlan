/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.commands

import jorlan.shell.ShellConfig
import jorlan.shell.client.{AuthClient, GraphQLClient}
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import zio.*

/** Dispatches a parsed [[ShellCommand]] and writes output to [[JorlanScreen]]. */
object CommandHandler {

  type Env = JorlanScreen & AuthClient & GraphQLClient & ShellConfig

  def handle(
    cmd:  ShellCommand,
    exit: Promise[Nothing, Unit],
  ): ZIO[Env, Nothing, Unit] = {
    cmd match {
      case ShellCommand.Message(text) => handleMessage(text)
      case ShellCommand.Help          => showHelp
      case ShellCommand.Commands      => showCommands
      case ShellCommand.Status        => showStatus
      case ShellCommand.About         => showAbout
      case ShellCommand.WhoAmI        => showWhoAmI
      case ShellCommand.Quit          => exit.succeed(()).unit
      case ShellCommand.NewSession    => stubPhase8("/new")
      case ShellCommand.ModelInfo     => stubPhase8("/model")
      case ShellCommand.ListModels    => stubPhase8("/models")
      case ShellCommand.Trace(level)  => setTrace(level)
      case ShellCommand.Unknown(raw)  => screen(_.addMessage(MessageKind.Error, s"Unknown command: $raw  — try /help"))
    }
  }

  private def screen[A](f:  JorlanScreen => UIO[A]):      URIO[JorlanScreen, A] = ZIO.serviceWithZIO[JorlanScreen](f)
  private def authCli[A](f: AuthClient => IO[String, A]): ZIO[AuthClient, String, A] = ZIO.serviceWithZIO[AuthClient](f)
  private def gql[A](f: GraphQLClient => IO[String, A]):  ZIO[GraphQLClient, String, A] =
    ZIO.serviceWithZIO[GraphQLClient](f)
  private def cfg[A](f: ShellConfig => A): URIO[ShellConfig, A] = ZIO.serviceWith[ShellConfig](f)

  // ─── Built-in command implementations ───────────────────────────────────────

  private def handleMessage(@annotation.unused text: String): ZIO[Env, Nothing, Unit] =
    screen(
      _.addMessage(
        MessageKind.System,
        "Message submission requires Phase 8 (Agent Session Runtime). " +
          "Try /status to verify connectivity or /help for available commands.",
      ),
    )

  private val showHelp: ZIO[Env, Nothing, Unit] =
    screen(
      _.addMessage(
        MessageKind.System,
        "Jorlan Shell — type a message to talk to the agent (Phase 8+), or use a /command.\n" +
          "Key bindings: Enter submit · Backspace delete · PgUp/PgDn scroll · Ctrl-C quit",
      ),
    )

  private val showCommands: ZIO[Env, Nothing, Unit] = {
    val lines = List(
      "/help          Show help summary",
      "/commands      List all commands",
      "/status        Server connectivity and version",
      "/about         About Jorlan Shell",
      "/whoami        Show current authenticated user",
      "/new           Start a new session (Phase 8)",
      "/model         Show / configure the active model (Phase 8)",
      "/models        List available models (Phase 8)",
      "/trace [level] Set log level: none | error | warning | info | debug",
      "/quit /exit    Exit the shell",
    )
    screen(_.addMessage(MessageKind.System, lines.mkString("\n")))
  }

  private val showStatus: ZIO[Env, Nothing, Unit] =
    for {
      serverUrl <- cfg(_.serverUrl)
      // P7-017: Use __typename introspection query — zero-cost, no auth required,
      // cannot produce false positives from empty tables or permission errors.
      gqlResult <- ZIO
        .serviceWithZIO[GraphQLClient](
          _.execute("""{ __typename }"""),
        )
        .fold(
          err => s"✗ GraphQL unreachable: $err",
          _ => "✔ GraphQL API reachable",
        )
      _ <- screen(_.addMessage(MessageKind.System, s"Server:  $serverUrl\n$gqlResult"))
    } yield ()

  private val showAbout: ZIO[Env, Nothing, Unit] =
    screen(
      _.addMessage(
        MessageKind.System,
        "Jorlan Shell — Secure Agent Runtime & Orchestration Platform\n" +
          "Phase 7: Shell Interface  |  github.com/rleibman/jorlan",
      ),
    )

  private val showWhoAmI: ZIO[Env, Nothing, Unit] =
    for {
      result <- authCli(_.whoAmI).fold(
        err => s"✗ Could not fetch identity: $err",
        body => body,
      )
      _ <- screen(_.addMessage(MessageKind.System, result))
    } yield ()

  private def stubPhase8(cmd: String): ZIO[Env, Nothing, Unit] =
    screen(_.addMessage(MessageKind.System, s"$cmd is available from Phase 8 (Agent Session Runtime)."))

  private def setTrace(level: String): ZIO[Env, Nothing, Unit] = {
    // P7-016: Actually change the Logback root logger level at runtime.
    // P7-025: Level names are case-insensitive — normalised to lowercase before the Set lookup
    // so /trace DEBUG and /trace debug are both valid.
    val valid = Set("none", "error", "warning", "info", "debug")
    val normalized = level.toLowerCase
    if (valid.contains(normalized)) {
      import ch.qos.logback.classic.{Level, LoggerContext}
      import org.slf4j.{Logger as Slf4jLogger, LoggerFactory}

      val logbackLevel = normalized match {
        case "none"    => Level.OFF
        case "error"   => Level.ERROR
        case "warning" => Level.WARN
        case "info"    => Level.INFO
        case "debug"   => Level.DEBUG
        case _         => Level.INFO
      }

      ZIO
        .attempt {
          import scala.language.unsafeNulls
          LoggerFactory.getILoggerFactory match {
            case ctx: LoggerContext =>
              // Slf4jLogger.ROOT_LOGGER_NAME = "ROOT" — the root logger name constant.
              ctx.getLogger(Slf4jLogger.ROOT_LOGGER_NAME).setLevel(logbackLevel)
            case _ => () // Not logback — skip silently
          }
        }.fold(
          err => screen(_.addMessage(MessageKind.Error, s"Failed to set log level: ${err.getClass.getName}")),
          _ => screen(_.addMessage(MessageKind.System, s"Log level set to: $level")),
        ).flatten
    } else {
      screen(_.addMessage(MessageKind.Error, s"Unknown trace level '$level'. Valid: ${valid.mkString(", ")}"))
    }
  }

}
