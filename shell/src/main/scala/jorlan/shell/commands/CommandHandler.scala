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
import jorlan.shell.ShellState
import zio.json.EncoderOps
import jorlan.shell.client.{AuthClient, GraphQLClient, SubscriptionClient}
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import zio.*
import zio.json.ast.Json

/** Dispatches a parsed [[ShellCommand]] and writes output to [[JorlanScreen]]. */
object CommandHandler {

  private type Env = JorlanScreen & AuthClient & GraphQLClient & ShellConfig & ShellState & SubscriptionClient

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
      case ShellCommand.NewSession(m) => handleNewSession(m)
      case ShellCommand.ModelInfo     => showModelInfo
      case ShellCommand.ListModels    => listModels
      case ShellCommand.Trace(level)  => setTrace(level)
      case ShellCommand.Unknown(raw)  => screen(_.addMessage(MessageKind.Error, s"Unknown command: $raw  — try /help"))
    }
  }

  private def screen[A](f:  JorlanScreen => UIO[A]):      URIO[JorlanScreen, A] = ZIO.serviceWithZIO[JorlanScreen](f)
  private def authCli[A](f: AuthClient => IO[String, A]): ZIO[AuthClient, String, A] = ZIO.serviceWithZIO[AuthClient](f)
  private def gql[A](f: GraphQLClient => IO[String, A]):  ZIO[GraphQLClient, String, A] =
    ZIO.serviceWithZIO[GraphQLClient](f)
  private def cfg[A](f:   ShellConfig => A):     URIO[ShellConfig, A] = ZIO.serviceWith[ShellConfig](f)
  private def state[A](f: ShellState => UIO[A]): URIO[ShellState, A] = ZIO.serviceWithZIO[ShellState](f)

  // ─── Built-in command implementations ───────────────────────────────────────

  private def handleMessage(text: String): ZIO[Env, Nothing, Unit] =
    ZIO.serviceWithZIO[ShellState](_.getSessionId).flatMap {
      case None =>
        screen(
          _.addMessage(
            MessageKind.System,
            "No active session — type /new to start one.",
          ),
        )
      case Some(sessionId) =>
        val mutation =
          s"""mutation { submitMessage(sessionId: ${sessionId.value}, content: ${Json.Str(text).toJson}) }"""
        gql(_.execute(mutation))
          .foldZIO(
            err => screen(_.addMessage(MessageKind.Error, s"Submit failed: $err")),
            _ =>
              ZIO.scoped {
                SubscriptionClient
                  .agentResponseStream(sessionId)
                  .foreach { chunk =>
                    if (chunk.finished && !chunk.isError) ZIO.unit
                    else if (chunk.isError)
                      screen(_.addMessage(MessageKind.Error, s"Agent error: ${chunk.content}"))
                    else screen(_.addMessage(MessageKind.Server, chunk.content))
                  }
                  .foldZIO(
                    err => screen(_.addMessage(MessageKind.Error, s"Streaming error: $err")),
                    _ => ZIO.unit,
                  )
              },
          )
    }

  private val showHelp: ZIO[Env, Nothing, Unit] =
    screen(
      _.addMessage(
        MessageKind.System,
        "Jorlan Shell — type a message to talk to the agent, or use a /command.\n" +
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
      "/new [model]   Start a new agent session",
      "/model         Show the active model",
      "/models        List available models",
      "/trace [level] Set log level: none | error | warning | info | debug",
      "/quit /exit    Exit the shell",
    )
    screen(_.addMessage(MessageKind.System, lines.mkString("\n")))
  }

  private val showStatus: ZIO[Env, Nothing, Unit] =
    for {
      serverUrl <- cfg(_.serverUrl)
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
          "Phase 8: Agent Session Runtime  |  github.com/rleibman/jorlan",
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

  private def handleNewSession(modelId: Option[String]): ZIO[Env, Nothing, Unit] = {
    val modelArg = modelId.map(m => s"""modelId: "$m"""").getOrElse("modelId: null")
    val mutation = s"""mutation { createSession($modelArg) { id modelId status } }"""

    def parseSessionId(json: zio.json.ast.Json): Option[Long] =
      for {
        obj        <- json.asObject
        data       <- obj.get("createSession")
        sessionObj <- data.asObject
        idField    <- sessionObj.get("id")
        n          <- idField.asNumber
      } yield n.value.longValue

    for {
      result <- gql(_.execute(mutation)).either
      _      <- result match {
        case Left(err)   => screen(_.addMessage(MessageKind.Error, s"Failed to create session: $err"))
        case Right(json) =>
          parseSessionId(json) match {
            case None     => screen(_.addMessage(MessageKind.Error, "Could not parse session ID from response"))
            case Some(id) =>
              import jorlan.domain.AgentSessionId
              ZIO.serviceWithZIO[ShellState](_.setSessionId(AgentSessionId(id))) *>
                ZIO.serviceWithZIO[JorlanScreen](
                  _.setModeStatus(s" [session: $id]  [model: ${modelId.getOrElse("default")}]"),
                ) *>
                screen(_.addMessage(MessageKind.System, s"Session $id started."))
          }
      }
    } yield ()
  }

  private val showModelInfo: ZIO[Env, Nothing, Unit] =
    ZIO.serviceWithZIO[ShellState](_.getSessionId).flatMap {
      case None    => screen(_.addMessage(MessageKind.System, "No active session. Use /new to start one."))
      case Some(s) => screen(_.addMessage(MessageKind.System, s"Active session: ${s.value}"))
    }

  private val listModels: ZIO[Env, Nothing, Unit] =
    screen(_.addMessage(MessageKind.System, "Model listing requires a running Phase 8 server."))

  private def setTrace(level: String): ZIO[Env, Nothing, Unit] = {
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
        case _         => Level.INFO // unreachable: guarded by valid.contains above
      }

      ZIO
        .attempt {
          import scala.language.unsafeNulls
          LoggerFactory.getILoggerFactory match {
            case ctx: LoggerContext =>
              ctx.getLogger(Slf4jLogger.ROOT_LOGGER_NAME).setLevel(logbackLevel)
            case _ => ()
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
