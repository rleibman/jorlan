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

import ch.qos.logback.classic.{Level, LoggerContext}
import jorlan.domain.{ModelId, ResponseChunk}
import jorlan.graphql.client.JorlanClient
import jorlan.shell.{LiveSession, ShellConfig, ShellState}
import jorlan.shell.client.{AuthClient, GraphQLClient, JorlanClientDecoders, SubscriptionClient}
import jorlan.shell.client.JorlanClientDecoders.*
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import org.slf4j.{Logger as Slf4jLogger, LoggerFactory}
import zio.*

import scala.language.unsafeNulls

/** Dispatches a parsed [[ShellCommand]] and writes output to [[JorlanScreen]]. */
object CommandHandler {

  private type Env = JorlanScreen & AuthClient & GraphQLClient & ShellConfig & ShellState & SubscriptionClient

  def handle(
    cmd:  ShellCommand,
    exit: Promise[Nothing, Unit],
  ): ZIO[Env, Nothing, Unit] = {
    cmd match {
      case ShellCommand.Message(text)            => handleMessage(text)
      case ShellCommand.Help                     => showHelp
      case ShellCommand.Commands                 => showCommands
      case ShellCommand.Status                   => showStatus
      case ShellCommand.About                    => showAbout
      case ShellCommand.WhoAmI                   => showWhoAmI
      case ShellCommand.Quit                     => exit.succeed(()).unit
      case ShellCommand.NewSession(m)            => handleNewSession(m)
      case ShellCommand.ModelInfo                => showModelInfo
      case ShellCommand.ListModels               => listModels
      case ShellCommand.Trace(level)             => setTrace(level)
      case ShellCommand.Personality              => showPersonality
      case ShellCommand.PersonalitySet(field, v) => setPersonalityField(field, v)
      case ShellCommand.Unknown(raw) => screen(_.addMessage(MessageKind.Error, s"Unknown command: $raw  — try /help"))
    }
  }

  private def screen[A](f:  JorlanScreen => UIO[A]):      URIO[JorlanScreen, A] = ZIO.serviceWithZIO[JorlanScreen](f)
  private def authCli[A](f: AuthClient => IO[String, A]): ZIO[AuthClient, String, A] = ZIO.serviceWithZIO[AuthClient](f)
  private def gql[A](f: GraphQLClient => IO[String, A]):  ZIO[GraphQLClient, String, A] =
    ZIO.serviceWithZIO[GraphQLClient](f)
  private def cfg[A](f: ShellConfig => A): URIO[ShellConfig, A] = ZIO.serviceWith[ShellConfig](f)

  // ─── Built-in command implementations ───────────────────────────────────────

  private def handleMessage(text: String): ZIO[Env, Nothing, Unit] =
    ZIO.serviceWithZIO[ShellState](_.getLiveSession).flatMap {
      case None =>
        screen(
          _.addMessage(
            MessageKind.System,
            "No active session — type /new to start one.",
          ),
        )
      case Some(liveSession) =>
        // Submit the message; tokens are already streaming via the session's long-lived WS subscription.
        gql(_.run(JorlanClient.Mutations.submitMessage(liveSession.sessionId, text))).either.flatMap {
          case Left(err) => screen(_.addMessage(MessageKind.Error, s"Submit failed: $err"))
          case Right(_)  =>
            def drain: ZIO[JorlanScreen, Nothing, Unit] =
              liveSession.tokenQueue.take.flatMap {
                case Left(err)          => screen(_.addMessage(MessageKind.Error, s"Streaming error: $err"))
                case Right(None)        => ZIO.unit
                case Right(Some(chunk)) =>
                  val display =
                    if (chunk.isError) screen(_.addMessage(MessageKind.Error, s"Agent error: ${chunk.content}"))
                    else screen(_.appendToLastMessage(MessageKind.Server, chunk.content))
                  display *> drain
              }
            drain
        }
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
      "/help               Show help summary",
      "/commands           List all commands",
      "/status             Server connectivity and version",
      "/about              About Jorlan Shell",
      "/whoami             Show current authenticated user",
      "/new [model]        Start a new agent session",
      "/model              Show the active model",
      "/models             List available models",
      "/personality                         Show server personality",
      "/personality set <field> <value>     Update a personality field (admin only)",
      "  fields: name | formality | prompt | languages | expertise",
      "  formality values: Casual | Professional | Academic | Technical",
      "/trace [level]      Set log level: none | error | warning | info | debug",
      "/quit /exit         Exit the shell",
    )
    screen(_.addMessage(MessageKind.System, lines.mkString("\n")))
  }

  private val showStatus: ZIO[Env, Nothing, Unit] =
    for {
      serverUrl <- cfg(_.serverUrl)
      // Use a raw introspection query for the connectivity check — no domain SelectionBuilder exists for __typename.
      gqlResult <- gql(_.execute("{ __typename }"))
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
    for {
      result <- gql(
        _.run(JorlanClient.Mutations.createSession(modelId.map(ModelId(_)))(JorlanClient.AgentSession.view)),
      ).either
      _ <- result match {
        case Left(err)            => screen(_.addMessage(MessageKind.Error, s"Failed to create session: $err"))
        case Right(None)          => screen(_.addMessage(MessageKind.Error, "Server returned no session"))
        case Right(Some(session)) =>
          val id = session.id.value
          for {
            // Tear down any existing session's subscription fiber before replacing it.
            existing <- ZIO.serviceWithZIO[ShellState](_.getLiveSession)
            _        <- ZIO.foreachDiscard(existing)(ls => ls.subscriptionFiber.interrupt)
            // Create the queue that handleMessage will drain per-message.
            tokenQueue <- Queue.bounded[Either[String, Option[ResponseChunk]]](1024)
            // Fork one long-lived WS subscription for the whole session.
            // ZIO.scoped gives the fiber its own Scope; interrupting the fiber closes the WS.
            fiber <- ZIO
              .scoped(
                SubscriptionClient
                  .agentResponseStream(session.id)
                  .ensuring(ZIO.logInfo(s"[Shell] subscription stream ended for session=${session.id.value}"))
                  .foreach { chunk =>
                    if (chunk.finished) tokenQueue.offer(Right(None)).unit
                    else tokenQueue.offer(Right(Some(chunk))).unit
                  }
                  .foldZIO(
                    err =>
                      ZIO.logError(s"[Shell] subscription error for session=${session.id.value}: $err") *>
                        tokenQueue.offer(Left(s"$err — type /new to reconnect")).unit,
                    _ =>
                      ZIO.logInfo(s"[Shell] subscription completed normally for session=${session.id.value}") *>
                        tokenQueue.offer(Left("Connection to server was lost — type /new to reconnect")).unit,
                  ),
              )
              .ensuring(ZIO.serviceWithZIO[ShellState](_.clearLiveSession))
              .fork
            _ <- ZIO.serviceWithZIO[ShellState](_.setLiveSession(LiveSession(session.id, tokenQueue, fiber)))
            _ <- ZIO.serviceWithZIO[JorlanScreen](
              _.setModeStatus(s" [session: $id]  [model: ${modelId.getOrElse("default")}]"),
            )
            _ <- screen(_.addMessage(MessageKind.System, s"Session $id started."))
          } yield ()
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

  private val showPersonality: ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.serverPersonality(JorlanClient.Personality.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not fetch personality: $err")),
      {
        case None    => screen(_.addMessage(MessageKind.Error, "Server returned no personality"))
        case Some(p) => screen(_.addMessage(MessageKind.System, formatPersonality(p)))
      },
    )

  private def setPersonalityField(
    field: String,
    value: String,
  ): ZIO[Env, Nothing, Unit] = {
    val validFields = Set("name", "formality", "prompt", "languages", "expertise")
    if (!validFields.contains(field.toLowerCase)) {
      screen(
        _.addMessage(
          MessageKind.Error,
          s"Unknown personality field '$field'. Valid fields: ${validFields.mkString(", ")}",
        ),
      )
    } else {
      gql(_.run(JorlanClient.Queries.serverPersonality(JorlanClient.Personality.view))).foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not fetch current personality: $err")),
        {
          case None    => screen(_.addMessage(MessageKind.Error, "Server returned no personality"))
          case Some(p) =>
            val name = if (field.toLowerCase == "name") value else p.name
            val formality = if (field.toLowerCase == "formality") value else p.formality
            val prompt = if (field.toLowerCase == "prompt") value else p.prompt
            val languages =
              if (field.toLowerCase == "languages") value.split(",").map(_.trim).filter(_.nonEmpty).toList
              else p.languages
            val expertise =
              if (field.toLowerCase == "expertise") value.split(",").map(_.trim).filter(_.nonEmpty).toList
              else p.expertise

            gql(
              _.run(
                JorlanClient.Mutations.updatePersonality(name, formality, languages, expertise, prompt)(
                  JorlanClient.Personality.view,
                ),
              ),
            ).foldZIO(
              err => screen(_.addMessage(MessageKind.Error, s"Update failed: $err")),
              {
                case None          => screen(_.addMessage(MessageKind.System, s"Personality $field updated to: $value"))
                case Some(updated) => screen(_.addMessage(MessageKind.System, formatPersonality(updated)))
              },
            )
        },
      )
    }
  }

  private def formatPersonality(p: JorlanClient.Personality.PersonalityView): String = {
    val expertiseStr = if (p.expertise.isEmpty) "(none)" else p.expertise.mkString(", ")
    s"Server personality:\n  name:       ${p.name}\n  formality:  ${p.formality}\n  languages:  ${p.languages.mkString(", ")}\n  expertise:  $expertiseStr\n  prompt:     ${p.prompt}"
  }

  private val traceLevels: Map[String, Level] = Map(
    "none"    -> Level.OFF,
    "error"   -> Level.ERROR,
    "warning" -> Level.WARN,
    "info"    -> Level.INFO,
    "debug"   -> Level.DEBUG,
  )

  private def setTrace(level: String): ZIO[Env, Nothing, Unit] =
    traceLevels.get(level.toLowerCase) match {
      case None =>
        screen(
          _.addMessage(
            MessageKind.Error,
            s"Unknown trace level '$level'. Valid: ${traceLevels.keys.mkString(", ")}",
          ),
        )
      case Some(logbackLevel) =>
        ZIO
          .attempt {
            LoggerFactory.getILoggerFactory match {
              case ctx: LoggerContext =>
                ctx.getLogger(Slf4jLogger.ROOT_LOGGER_NAME).setLevel(logbackLevel)
              case _ => ()
            }
          }.fold(
            err => screen(_.addMessage(MessageKind.Error, s"Failed to set log level: ${err.getClass.getName}")),
            _ => screen(_.addMessage(MessageKind.System, s"Log level set to: $level")),
          ).flatten
    }

}
