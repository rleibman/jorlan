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
import jorlan.*
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClientDecoders.given
import jorlan.shell.*
import jorlan.shell.client.*
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import org.slf4j.{Logger as Slf4jLogger, LoggerFactory}
import zio.*

import scala.language.unsafeNulls

/** Dispatches a parsed [[ShellCommand]] and writes output to [[JorlanScreen]]. */
object CommandHandler {

  private type Env = JorlanScreen & AuthClient & GraphQLClient & ShellConfig & ShellState & SubscriptionClient &
    InitClient

  def handle(
    cmd:  ShellCommand,
    exit: Promise[Nothing, Unit],
  ): ZIO[Env, Nothing, Unit] = {
    cmd match {
      case ShellCommand.Message(text)                    => handleMessage(text)
      case ShellCommand.Help                             => showCommands
      case ShellCommand.Commands                         => showCommands
      case ShellCommand.Status                           => showStatus
      case ShellCommand.About                            => showAbout
      case ShellCommand.WhoAmI                           => showWhoAmI
      case ShellCommand.Quit                             => exit.succeed(()).unit
      case ShellCommand.NewSession(m)                    => handleNewSession(m)
      case ShellCommand.ModelInfo                        => showModelInfo
      case ShellCommand.ListModels                       => listModels
      case ShellCommand.Trace(level)                     => setTrace(level)
      case ShellCommand.Personality                      => showPersonality
      case ShellCommand.PersonalitySet(field, v)         => setPersonalityField(field, v)
      case ShellCommand.MemoryList(scope)                => listMemory(scope)
      case ShellCommand.MemorySearch(text)               => searchMemory(text)
      case ShellCommand.MemoryForget(id)                 => forgetMemory(id)
      case ShellCommand.MemoryShare(id)                  => shareMemory(id)
      case ShellCommand.MemoryPrivatize(id)              => privatizeMemory(id)
      case ShellCommand.MemoryRemember(key, text, scope) =>
        rememberMemory(
          key,
          text,
          scope.flatMap(s => MemoryScope.values.find(_.toString.equalsIgnoreCase(s))).getOrElse(MemoryScope.User),
        )
      case ShellCommand.Skills                 => showSkills
      case ShellCommand.ContactsFind(name)     => findContacts(name)
      case ShellCommand.Capabilities           => showCapabilities
      case ShellCommand.AgentsList             => listAgents
      case ShellCommand.AgentsStop(id)         => stopAgent(id)
      case ShellCommand.ApprovalsList          => listApprovals
      case ShellCommand.ApprovalsApprove(id)   => decideApproval(id, approved = true)
      case ShellCommand.ApprovalsDeny(id)      => decideApproval(id, approved = false)
      case ShellCommand.OAuthStatus(provider)  => showOAuthStatus(provider)
      case ShellCommand.OAuthConnect(provider) => connectOAuth(provider)
      case ShellCommand.OAuthRevoke(provider)  => revokeOAuth(provider)
      case ShellCommand.OAuthList              => listOAuthProviders
      case ShellCommand.EmailList(maxResults)  => emailList(maxResults)
      case ShellCommand.EmailRead(messageId)   => emailRead(messageId)
      case ShellCommand.EmailSearch(query)     => emailSearch(query)
      case ShellCommand.CalendarToday          => calendarToday
      case ShellCommand.CalendarList(date)     => calendarList(date)
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
            MessageKind.Error,
            "No active session — use /new to start one.",
          ),
        )
      case Some(liveSession) =>
        for {
          // Interrupt any in-progress drain from a previous message so stale tokens are discarded.
          _ <- ZIO.serviceWithZIO[ShellState](_.interruptDrain)
          // Clear any remaining tokens buffered since the interrupt.
          _      <- liveSession.tokenQueue.takeAll.unit
          result <- gql(_.run(JorlanClient.Mutations.submitMessage(liveSession.sessionId, text))).either
          _      <- result match {
            case Left(err) => screen(_.addMessage(MessageKind.Error, s"Submit failed: $err"))
            case Right(_)  =>
              // Fork the drain so the processLoop can accept new input (commands or messages)
              // while the LLM response is still streaming. The drain fiber is tracked in ShellState
              // so it can be interrupted by a subsequent message, /new, or /quit.
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
                .ensuring(ZIO.serviceWithZIO[ShellState](_.setDrainingFiber(None)))
                .fork
                .flatMap(f => ZIO.serviceWithZIO[ShellState](_.setDrainingFiber(Some(f))))
          }
        } yield ()
    }

  private val showCommands: ZIO[Env, Nothing, Unit] = {
    val lines = List(
      "Jorlan Shell — type a message to talk to the agent, or use a /command.",
      "Key bindings: Enter submit · Backspace delete · ↑↓/PgUp/PgDn scroll · Ctrl-C quit",
      "",
      "/help /commands     Show this command list",
      "/status             Server connectivity and versions",
      "/about              About Jorlan Shell",
      "/whoami             Show current authenticated user",
      "/new [model]        Start a new agent session",
      "/model              Show the active session model",
      "/models             List available models",
      "/personality                         Show server personality",
      "/personality set <field> <value>     Update a personality field (admin only)",
      "  fields: name | formality | prompt | languages | expertise",
      "  formality values: Casual | Professional | Academic | Technical |",
      "                    Quirky | Fresh | Rude | Boomer | GenX | Millennial | GenZ | GenAlpha",
      "/memory list [scope]                 List memory records (scope: User|Shared|Workspace|Private)",
      "/memory search <text>                Search memory records by text",
      "/memory forget <id>                  Delete a memory record by id",
      "/memory remember <key> <text>        Store a new memory record",
      "/capabilities                        List your current capability grants",
      "/agents list                         List active agent sessions",
      "/agents stop <id>                    Terminate an agent session",
      "/approvals list                      List pending approval requests",
      "/approvals approve <id>              Approve a pending request",
      "/approvals deny <id>                 Deny a pending request",
      "/oauth status <provider>             Show OAuth connection status for a provider",
      "/oauth connect <provider>            Start OAuth authorization flow for a provider",
      "/oauth revoke <provider>             Revoke stored OAuth credentials for a provider",
      "/oauth list                          List all connected OAuth providers",
      "/email list [maxResults]             List recent email messages",
      "/email read <messageId>              Read a specific email message",
      "/email search <query>                Search email messages",
      "/calendar today                      List today's calendar events",
      "/calendar list [YYYY-MM-DD]          List calendar events for a date",
      "/trace [level]      Set log level: none | error | warning | info | debug",
      "/quit /exit         Exit the shell",
    )
    screen(_.addMessage(MessageKind.System, lines.mkString("\n")))
  }

  private val showStatus: ZIO[Env, Nothing, Unit] =
    for {
      serverUrl <- cfg(_.serverUrl)
      typedUrl  <- cfg(_.typedServerUrl)
      clientVersion = jorlan.BuildInfo.version
      serverStatus <- ZIO.serviceWithZIO[InitClient](_.checkStatus(typedUrl)).either
      gqlResult    <- gql(_.execute("{ __typename }"))
        .fold(
          err => s"✗ GraphQL unreachable: $err",
          _ => "✔ GraphQL API reachable",
        )
      serverVersionLine = serverStatus match {
        case Right(s) => s"Server version: ${s.version}  uptime: ${s.uptimeMs / 1000}s  name: ${s.serverName}"
        case Left(e)  => s"Server version: (unavailable — $e)"
      }
      _ <- screen(
        _.addMessage(
          MessageKind.System,
          s"Server:  $serverUrl\nClient version: $clientVersion\n$serverVersionLine\n$gqlResult",
        ),
      )
    } yield ()

  private val showAbout: ZIO[Env, Nothing, Unit] =
    screen(
      _.addMessage(
        MessageKind.System,
        s"Jorlan Shell — Secure Agent Runtime & Orchestration Platform\n" +
          s"Version: ${jorlan.BuildInfo.version}\n" +
          "Copyright © 2026 Roberto Leibman — All Rights Reserved\n" +
          "This software is proprietary and confidential. Unauthorised use, reproduction,\n" +
          "or distribution is strictly prohibited.",
      ),
    )

  private val showWhoAmI: ZIO[Env, Nothing, Unit] =
    for {
      result <- authCli(_.whoAmI).foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not fetch identity: $err")),
        body =>
          zio.json.JsonDecoder[User].decodeJson(body) match {
            case Left(_) =>
              screen(_.addMessage(MessageKind.System, body))
            case Right(u) =>
              screen(
                _.addMessage(
                  MessageKind.System,
                  s"Display name: ${u.displayName}\nEmail:        ${u.email}\nUser ID:      ${u.id.value}\nActive:       ${u.active}",
                ),
              )
          },
      )
    } yield result

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
            _        <- ZIO.foreachDiscard(existing) { ls =>
              ls.subscriptionFiber.interrupt *> ls.toolEventFiber.interrupt
            }
            screenSvc <- ZIO.service[JorlanScreen]
            _         <- LiveSession.start(
              session.id,
              onToolEvent = ev =>
                ev.eventType match {
                  case "SkillInvoked" =>
                    val argsSummary = ev.payload.take(120).replaceAll("\\s+", " ")
                    screenSvc.addMessage(MessageKind.System, s"⟳ calling ${ev.toolName}  args: $argsSummary")
                  case "SkillSucceeded" =>
                    val resultSummary = ev.payload.take(120).replaceAll("\\s+", " ")
                    screenSvc.addMessage(MessageKind.System, s"✓ ${ev.toolName}  → $resultSummary")
                  case "SkillFailed" =>
                    screenSvc.addMessage(MessageKind.Error, s"✗ ${ev.toolName}  → ${ev.payload.take(120)}")
                  case _ => ZIO.unit
                },
            )
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
      case None      => screen(_.addMessage(MessageKind.System, "No active session. Use /new to start one."))
      case Some(sid) =>
        for {
          sessionsResult <- gql(_.run(JorlanClient.Queries.listSessions()(JorlanClient.AgentSession.view))).either
          modelsResult   <- gql(_.run(JorlanClient.Queries.availableModels(JorlanClient.ModelInfo.view))).either
          _              <- (sessionsResult, modelsResult) match {
            case (Left(err), _) => screen(_.addMessage(MessageKind.Error, s"Failed to fetch session info: $err"))
            case (Right(None | Some(Nil)), _) =>
              screen(_.addMessage(MessageKind.System, "No sessions found on server."))
            case (Right(Some(sessions)), modelsEither) =>
              sessions.find(_.id == sid) match {
                case None => screen(_.addMessage(MessageKind.System, s"Session ${sid.value} not found on server."))
                case Some(session) =>
                  val defaultModel =
                    modelsEither.toOption.flatten.flatMap(_.headOption).map(_.id.value).getOrElse("unknown")
                  val model = session.modelId.map(_.value).getOrElse(defaultModel)
                  screen(
                    _.addMessage(MessageKind.System, s"Session: ${sid.value}  Model: $model  Status: ${session.status}"),
                  )
              }
          }
        } yield ()
    }

  private val listModels: ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.availableModels(JorlanClient.ModelInfo.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list models: $err")),
      {
        case None | Some(Nil) => screen(_.addMessage(MessageKind.System, "No models available."))
        case Some(models)     =>
          val lines = models
            .map { m =>
              val stream = if (m.supportsStreaming) "streaming" else "no-streaming"
              val ctx = if (m.contextWindow > 0) s", ctx=${m.contextWindow}" else ""
              s"  ${m.id.value}  (${m.provider}$ctx, $stream)"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Available models:\n$lines"))
      },
    )

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
            val f = field.toLowerCase
            val splitList = value.split(",").map(_.trim).filter(_.nonEmpty).toList
            val name = if (f == "name") value else p.name
            val formality =
              if (f == "formality")
                Formality.values.find(_.toString.equalsIgnoreCase(value)).getOrElse(Formality.Custom)
              else p.formality
            val prompt = if (f == "prompt") value else p.prompt
            val languages = if (f == "languages") splitList else p.languages
            val expertise = if (f == "expertise") splitList else p.expertise

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

  private def listMemory(scope: Option[MemoryScope]): ZIO[Env, Nothing, Unit] = {
    scope.fold(ZIO.unit) { s =>
      gql(_.run(JorlanClient.Queries.listMemory(s, None)(JorlanClient.MemoryRecord.view))).foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not list memory: $err")),
        {
          case None | Some(Nil) => screen(_.addMessage(MessageKind.System, "No memory records found."))
          case Some(records)    =>
            val lines = records.map(r => s"[${r.id.value}] (${r.scope}) ${r.recordKey}: ${r.value}").mkString("\n")
            screen(_.addMessage(MessageKind.System, s"Memory records:\n$lines"))
        },
      )
    }
  }

  private def searchMemory(text: String): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.listMemory(MemoryScope.User, Some(text))(JorlanClient.MemoryRecord.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Memory search failed: $err")),
      {
        case None | Some(Nil) => screen(_.addMessage(MessageKind.System, s"No memory records matching '$text'."))
        case Some(records)    =>
          val lines = records.map(r => s"[${r.id.value}] (${r.scope}) ${r.recordKey}: ${r.value}").mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Matching records:\n$lines"))
      },
    )

  private def forgetMemory(id: MemoryRecordId): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Mutations.forgetMemory(id))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Forget failed: $err")),
      _ => screen(_.addMessage(MessageKind.System, s"Memory record ${id.value} deleted.")),
    )

  private def rememberMemory(
    key:   String,
    text:  String,
    scope: MemoryScope = MemoryScope.User,
  ): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Mutations.storeMemory(key, text, scope)(JorlanClient.MemoryRecord.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Remember failed: $err")),
      {
        case None    => screen(_.addMessage(MessageKind.System, "Memory stored."))
        case Some(r) => screen(_.addMessage(MessageKind.System, s"Stored [${r.id.value}] ${r.recordKey}: ${r.value}"))
      },
    )

  private def shareMemory(id: MemoryRecordId): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Mutations.markMemoryShared(id)(JorlanClient.MemoryRecord.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Share failed: $err")),
      {
        case None    => screen(_.addMessage(MessageKind.Error, s"Memory record ${id.value} not found."))
        case Some(r) => screen(_.addMessage(MessageKind.System, s"Memory record ${r.id.value} is now Shared."))
      },
    )

  private def privatizeMemory(id: MemoryRecordId): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Mutations.markMemoryPrivate(id)(JorlanClient.MemoryRecord.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Privatize failed: $err")),
      {
        case None    => screen(_.addMessage(MessageKind.Error, s"Memory record ${id.value} not found."))
        case Some(r) => screen(_.addMessage(MessageKind.System, s"Memory record ${r.id.value} is now Private."))
      },
    )

  private val showCapabilities: ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.listCapabilities(JorlanClient.CapabilityGrant.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not load capabilities: $err")),
      {
        case None | Some(Nil) => screen(_.addMessage(MessageKind.System, "No capability grants found."))
        case Some(grants)     =>
          val lines = grants
            .map { g =>
              val expiry = g.expiresAt.map(e => s" (expires: $e)").getOrElse("")
              s"  ${g.capability.value}  [${g.approvalMode}]$expiry"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Your capability grants:\n$lines"))
      },
    )

  private val listAgents: ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.listSessions()(JorlanClient.AgentSession.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list sessions: $err")),
      {
        case None | Some(Nil) => screen(_.addMessage(MessageKind.System, "No active sessions."))
        case Some(sessions)   =>
          val lines = sessions
            .map { s =>
              s"  [${s.id.value}] status=${s.status}  model=${s.modelId.map(_.value).getOrElse("default")}"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Agent sessions:\n$lines"))
      },
    )

  private def stopAgent(id: AgentSessionId): ZIO[Env, Nothing, Unit] =
    // Use raw execute so GraphQL errors (e.g. permission denied) are surfaced even when
    // Caliban would silently decode a null result as None.
    gql(_.execute(s"mutation { terminateSession(value: ${id.value}) }")).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Stop failed: $err")),
      _ =>
        for {
          existing <- ZIO.serviceWithZIO[ShellState](_.getLiveSession)
          _        <- ZIO.foreachDiscard(existing.filter(_.sessionId == id)) { ls =>
            ls.subscriptionFiber.interrupt *>
              ls.toolEventFiber.interrupt *>
              ZIO.serviceWithZIO[ShellState](_.clearLiveSession) *>
              ZIO.serviceWithZIO[JorlanScreen](_.setModeStatus(" [no session]  [disconnected]"))
          }
          _ <- screen(_.addMessage(MessageKind.System, s"Session ${id.value} terminated."))
        } yield (),
    )

  private val listApprovals: ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.listApprovals(JorlanClient.ApprovalRequest.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list approvals: $err")),
      {
        case None | Some(Nil) => screen(_.addMessage(MessageKind.System, "No pending approval requests."))
        case Some(requests)   =>
          val lines = requests
            .map { r =>
              s"  [${r.id.value}] ${r.capability.value}  risk=${r.riskClass}  status=${r.status}"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Pending approvals:\n$lines"))
      },
    )

  private def decideApproval(
    id:       ApprovalRequestId,
    approved: Boolean,
  ): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Mutations.decideApproval(id, approved))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Decision failed: $err")),
      _ =>
        screen(
          _.addMessage(MessageKind.System, s"Approval request ${id.value} ${if (approved) "approved" else "denied"}."),
        ),
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

  private val showSkills: ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.skills(JorlanClient.SkillInfo.view(JorlanClient.SkillToolInfo.view)))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list skills: $err")),
      {
        case None | Some(Nil) => screen(_.addMessage(MessageKind.System, "No skills registered."))
        case Some(skills)     =>
          val lines = skills
            .map { s =>
              val toolLines = s.tools
                .map(t => s"    • ${t.name}  [${t.requiredCapabilities.mkString(", ")}]")
                .mkString("\n")
              s"  ${s.name}  (${s.tier})\n$toolLines"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Registered skills:\n$lines"))
      },
    )

  private def findContacts(name: String): ZIO[Env, Nothing, Unit] = {
    val query =
      s"""query { contacts(name: ${'"'}$name${'"'}) { userId displayName identities { channelType channelUserId } } }"""

    def strField(
      fields: Seq[(String, zio.json.ast.Json)],
      key:    String,
    ): String =
      fields.collectFirst { case (`key`, zio.json.ast.Json.Str(v)) => v }.getOrElse("?")

    def renderContact(fields: Seq[(String, zio.json.ast.Json)]): String = {
      val displayName = strField(fields, "displayName")
      val userId = fields
        .collectFirst { case ("userId", zio.json.ast.Json.Num(n)) => n.longValue.toString }
        .getOrElse("?")
      val ids = fields
        .collectFirst { case ("identities", zio.json.ast.Json.Arr(is)) =>
          is.collect { case zio.json.ast.Json.Obj(i) =>
            s"${strField(i.toSeq, "channelType")}:${strField(i.toSeq, "channelUserId")}"
          }.mkString(", ")
        }.getOrElse("")
      s"  [$userId] $displayName  $ids"
    }

    gql(_.execute(query)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"contacts.find failed: $err")),
      json => {
        val users: Seq[zio.json.ast.Json] = json match {
          case zio.json.ast.Json.Obj(root) =>
            root
              .collectFirst { case ("data", zio.json.ast.Json.Obj(data)) =>
                data.collectFirst { case ("contacts", zio.json.ast.Json.Arr(arr)) => arr.toSeq }.getOrElse(Seq.empty)
              }.getOrElse(Seq.empty)
          case _ => Seq.empty
        }
        if (users.isEmpty) {
          screen(_.addMessage(MessageKind.System, s"No contacts found matching '$name'."))
        } else {
          val lines = users
            .collect { case zio.json.ast.Json.Obj(fields) => renderContact(fields.toSeq) }
            .mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Contacts matching '$name':\n$lines"))
        }
      },
    )
  }

  private def showOAuthStatus(provider: String): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.oauthStatus(provider)(JorlanClient.OAuthStatus.view))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not get OAuth status: $err")),
      {
        case None    => screen(_.addMessage(MessageKind.System, s"OAuth status for '$provider': not found"))
        case Some(s) =>
          val expiry = s.expiresAt.map(t => s"  expires: $t").getOrElse("")
          screen(_.addMessage(MessageKind.System, s"OAuth '$provider': connected=${s.connected}$expiry"))
      },
    )

  private val listOAuthProviders: ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Queries.listOAuthProviders)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list OAuth providers: $err")),
      {
        case None | Some(Nil) => screen(_.addMessage(MessageKind.System, "No OAuth providers connected."))
        case Some(providers)  =>
          screen(_.addMessage(MessageKind.System, s"Connected OAuth providers: ${providers.mkString(", ")}"))
      },
    )

  private def connectOAuth(provider: String): ZIO[Env, Nothing, Unit] =
    for {
      serverUrl <- cfg(_.serverUrl)
      _ <- gql(_.run(JorlanClient.Mutations.startOAuth(provider)(JorlanClient.OAuthStartResult.authUrl))).foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not start OAuth: $err")),
        {
          case None =>
            screen(_.addMessage(MessageKind.Error, s"No auth URL returned for provider '$provider'"))
          case Some(authPath) =>
            val fullUrl =
              if (authPath.startsWith("http")) authPath
              else s"$serverUrl$authPath"
            screen(
              _.addMessage(MessageKind.System, s"Open this URL in your browser to connect $provider:\n  $fullUrl"),
            )
        },
      )
    } yield ()

  private def revokeOAuth(provider: String): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Mutations.revokeOAuth(provider))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not revoke OAuth: $err")),
      _ => screen(_.addMessage(MessageKind.System, s"OAuth credentials for '$provider' revoked.")),
    )

  private def emailList(maxResults: Int): ZIO[Env, Nothing, Unit] =
    gql(_.run(JorlanClient.Mutations.invokeTool("email.list", s"""{"maxResults":$maxResults}"""))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"email.list failed: $err")),
      {
        case None         => screen(_.addMessage(MessageKind.System, "No response from email.list"))
        case Some(result) => screen(_.addMessage(MessageKind.System, result))
      },
    )

  private def emailRead(messageId: String): ZIO[Env, Nothing, Unit] = {
    import zio.json.*
    import zio.json.ast.Json

    val argsJson = Json.Obj("messageId" -> Json.Str(messageId)).toJson
    gql(
      _.run(JorlanClient.Mutations.invokeTool("email.read", argsJson)),
    ).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"email.read failed: $err")),
      {
        case None         => screen(_.addMessage(MessageKind.System, "No response from email.read"))
        case Some(result) => screen(_.addMessage(MessageKind.System, result))
      },
    )
  }

  private def emailSearch(query: String): ZIO[Env, Nothing, Unit] = {
    import zio.json.*
    import zio.json.ast.Json

    val argsJson = Json.Obj("query" -> Json.Str(query)).toJson
    gql(
      _.run(JorlanClient.Mutations.invokeTool("email.search", argsJson)),
    ).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"email.search failed: $err")),
      {
        case None         => screen(_.addMessage(MessageKind.System, "No response from email.search"))
        case Some(result) => screen(_.addMessage(MessageKind.System, result))
      },
    )
  }

  private val calendarToday: ZIO[Env, Nothing, Unit] = {
    import java.time.{LocalDate, ZoneOffset}
    val todayMin = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant
    val todayMax = todayMin.plusSeconds(86400)
    gql(
      _.run(
        JorlanClient.Mutations.invokeTool(
          "calendar.listEvents",
          s"""{"timeMin":"$todayMin","timeMax":"$todayMax","maxResults":50}""",
        ),
      ),
    ).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"calendar.listEvents failed: $err")),
      {
        case None         => screen(_.addMessage(MessageKind.System, "No events today."))
        case Some(result) => screen(_.addMessage(MessageKind.System, result))
      },
    )
  }

  private def calendarList(date: Option[String]): ZIO[Env, Nothing, Unit] = {
    import java.time.{LocalDate, ZoneOffset}
    date.flatMap(d => scala.util.Try(LocalDate.parse(d)).failed.toOption.map(_ => d)) match {
      case Some(raw) =>
        screen(_.addMessage(MessageKind.Error, s"Invalid date format '$raw'. Use YYYY-MM-DD."))
      case None =>
        val day = date.flatMap(d => scala.util.Try(LocalDate.parse(d)).toOption).getOrElse(LocalDate.now())
        val dayMin = day.atStartOfDay(ZoneOffset.UTC).toInstant
        val dayMax = dayMin.plusSeconds(86400)
        gql(
          _.run(
            JorlanClient.Mutations.invokeTool(
              "calendar.listEvents",
              s"""{"timeMin":"$dayMin","timeMax":"$dayMax","maxResults":50}""",
            ),
          ),
        ).foldZIO(
          err => screen(_.addMessage(MessageKind.Error, s"calendar.listEvents failed: $err")),
          {
            case None         => screen(_.addMessage(MessageKind.System, s"No events for $day."))
            case Some(result) => screen(_.addMessage(MessageKind.System, result))
          },
        )
    }
  }

}
