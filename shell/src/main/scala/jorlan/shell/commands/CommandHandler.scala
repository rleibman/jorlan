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
import jorlan.shell.ShellConfig
import jorlan.shell.ShellState
import jorlan.shell.client.{AuthClient, GraphQLClient, SubscriptionClient}
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import org.slf4j.{Logger as Slf4jLogger, LoggerFactory}
import zio.*
import zio.json.EncoderOps
import zio.json.ast.Json

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

  private val personalityQuery = """{ serverPersonality { name formality languages expertise prompt } }"""

  private def fetchCurrentPersonality: ZIO[GraphQLClient, String, Json] =
    gql(_.execute(personalityQuery))

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
      fetchCurrentPersonality.foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not fetch current personality: $err")),
        currentJson => {
          val pObjOpt = for {
            obj  <- currentJson.asObject
            p    <- obj.get("serverPersonality")
            pObj <- p.asObject
          } yield pObj

          pObjOpt match {
            case None =>
              screen(_.addMessage(MessageKind.Error, "Could not parse current personality response"))
            case Some(pObj) =>
              val str = (k: String) => pObj.get(k).flatMap(_.asString).getOrElse("")
              val arr = (k: String) => pObj.get(k).flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
              val name = if (field.toLowerCase == "name") value else str("name")
              val formality = if (field.toLowerCase == "formality") value else str("formality")
              val prompt = if (field.toLowerCase == "prompt") value else str("prompt")
              val languages =
                if (field.toLowerCase == "languages") value.split(",").map(_.trim).filter(_.nonEmpty).toList
                else arr("languages")
              val expertise =
                if (field.toLowerCase == "expertise") value.split(",").map(_.trim).filter(_.nonEmpty).toList
                else arr("expertise")

              val langArg = Json.Arr(Chunk.fromIterable(languages.map(Json.Str(_)))).toJson
              val expertArg = Json.Arr(Chunk.fromIterable(expertise.map(Json.Str(_)))).toJson
              val mutation =
                s"""mutation { updatePersonality(name: ${Json.Str(name).toJson}, formality: ${Json
                    .Str(formality).toJson}, languages: $langArg, expertise: $expertArg, prompt: ${Json
                    .Str(prompt).toJson}) { name formality prompt } }"""

              gql(_.execute(mutation)).foldZIO(
                err => screen(_.addMessage(MessageKind.Error, s"Update failed: $err")),
                json =>
                  screen(
                    _.addMessage(
                      MessageKind.System,
                      parsePersonalityText(
                        json.asObject
                          .flatMap(_.get("updatePersonality")).map(p => Json.Obj("serverPersonality" -> p)).getOrElse(
                            json,
                          ),
                      ).getOrElse(s"Personality $field updated to: $value"),
                    ),
                  ),
              )
          }
        },
      )
    }
  }

  private def parsePersonalityText(json: Json): Option[String] =
    for {
      obj  <- json.asObject
      p    <- obj.get("serverPersonality")
      pObj <- p.asObject
      name = pObj.get("name").flatMap(_.asString).getOrElse("?")
      formality = pObj.get("formality").flatMap(_.asString).getOrElse("?")
      languages = pObj
        .get("languages").flatMap(_.asArray).map(_.flatMap(_.asString).mkString(", ")).getOrElse("?")
      expertise = pObj
        .get("expertise").flatMap(_.asArray).map(a =>
          if (a.isEmpty) "(none)" else a.flatMap(_.asString).mkString(", "),
        ).getOrElse("?")
      prompt = pObj.get("prompt").flatMap(_.asString).getOrElse("?")
    } yield s"Server personality:\n  name:       $name\n  formality:  $formality\n  languages:  $languages\n  expertise:  $expertise\n  prompt:     $prompt"

  private val showPersonality: ZIO[Env, Nothing, Unit] = {
    val query = """{ serverPersonality { name formality languages expertise prompt } }"""
    gql(_.execute(query)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not fetch personality: $err")),
      json =>
        screen(
          _.addMessage(MessageKind.System, parsePersonalityText(json).getOrElse("Could not parse personality response")),
        ),
    )
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
