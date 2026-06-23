/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.commands

import ch.qos.logback.classic.{Level, LoggerContext}
import jorlan.*
import jorlan.shell.*
import jorlan.shell.client.*
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import org.slf4j.{Logger as Slf4jLogger, LoggerFactory}
import zio.*
import zio.json.ast.Json

import scala.language.unsafeNulls

/** Dispatches a parsed [[ShellCommand]] and writes output to [[JorlanScreen]]. */
object CommandHandler {

  private type Env = JorlanScreen & AuthClient & ZIOClientRepositories & ShellConfig & ShellState & SubscriptionClient &
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
      case ShellCommand.MemoryCheckpoint                         => requestCheckpoint
      case ShellCommand.MemoryPolicyShow                         => showCheckpointPolicy
      case ShellCommand.MemoryPolicySetInterval(n)               => setCheckpointInterval(n)
      case ShellCommand.MemoryPolicyToggle(t, e)                 => toggleCheckpointTrigger(t, e)
      case ShellCommand.Skills                                   => showSkills
      case ShellCommand.SkillsEnable(name)                       => enableSkill(name)
      case ShellCommand.SkillsDisable(name)                      => disableSkill(name)
      case ShellCommand.SkillsGetConfig(name)                    => getSkillConfig(name)
      case ShellCommand.SkillsSetConfig(name, json)              => setSkillConfig(name, json)
      case ShellCommand.ContactsFind(name)                       => findContacts(name)
      case ShellCommand.Capabilities                             => showCapabilities
      case ShellCommand.AgentsList                               => listAgents
      case ShellCommand.AgentsStop(id)                           => stopAgent(id)
      case ShellCommand.ApprovalsList                            => listApprovals
      case ShellCommand.ApprovalsApprove(id)                     => decideApproval(id, approved = true)
      case ShellCommand.ApprovalsDeny(id)                        => decideApproval(id, approved = false)
      case ShellCommand.UsersList(active)                        => listUsers(active)
      case ShellCommand.UsersCreate(name, email)                 => createUser(name, email)
      case ShellCommand.UsersDeactivate(id)                      => deactivateUser(id)
      case ShellCommand.UsersUpdate(id, field, v)                => updateUser(id, field, v)
      case ShellCommand.UsersCapabilities(uid)                   => listUserCapabilities(uid)
      case ShellCommand.UsersGrantCapability(uid, cap, mode)     => grantUserCapability(uid, cap, mode)
      case ShellCommand.UsersRevokeGrant(gid)                    => revokeCapabilityGrant(gid)
      case ShellCommand.UsersRoles(uid)                          => listUserRoles(uid)
      case ShellCommand.UsersAssignRole(uid, rid)                => assignUserRole(uid, rid)
      case ShellCommand.UsersRevokeRole(uid, rid)                => revokeUserRole(uid, rid)
      case ShellCommand.UsersIdentities(uid)                     => listUserIdentities(uid)
      case ShellCommand.UsersLinkIdentity(uid, chType, chUserId) => linkUserIdentity(uid, chType, chUserId)
      case ShellCommand.UsersUnlinkIdentity(iid)                 => unlinkUserIdentity(iid)
      case ShellCommand.RolesList                                => listRoles
      case ShellCommand.RolesCreate(name, desc)                  => createRole(name, desc)
      case ShellCommand.SchedulerList                            => listSchedulerJobs
      case ShellCommand.SchedulerResult(id)                      => showSchedulerResult(id)
      case ShellCommand.OAuthStatus(provider)                    => showOAuthStatus(provider)
      case ShellCommand.OAuthConnect(provider)                   => connectOAuth(provider)
      case ShellCommand.OAuthRevoke(provider)                    => revokeOAuth(provider)
      case ShellCommand.OAuthList                                => listOAuthProviders
      case ShellCommand.EmailList(maxResults)                    => emailList(maxResults)
      case ShellCommand.EmailRead(messageId)                     => emailRead(messageId)
      case ShellCommand.EmailSearch(query)                       => emailSearch(query)
      case ShellCommand.CalendarToday                            => calendarToday
      case ShellCommand.CalendarList(date)                       => calendarList(date)
      case ShellCommand.Unknown(raw) => screen(_.addMessage(MessageKind.Error, s"Unknown command: $raw  — try /help"))
    }
  }

  private def screen[A](f:  JorlanScreen => UIO[A]):      URIO[JorlanScreen, A] = ZIO.serviceWithZIO[JorlanScreen](f)
  private def authCli[A](f: AuthClient => IO[String, A]): ZIO[AuthClient, String, A] = ZIO.serviceWithZIO[AuthClient](f)
  private def cfg[A](f:     ShellConfig => A):            URIO[ShellConfig, A] = ZIO.serviceWith[ShellConfig](f)
  private def repo[A](f: ZIOClientRepositories => IO[String, A]): ZIO[ZIOClientRepositories, String, A] =
    ZIO.serviceWithZIO[ZIOClientRepositories](f)

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
          result <- repo(_.agent.submitMessage(liveSession.sessionId, text)).either
          _      <- result match {
            case Left(err) => screen(_.addMessage(MessageKind.Error, s"Submit failed: $err"))
            case Right(_)  =>
              // Fork the drain so the processLoop can accept new input (commands or messages)
              // while the LLM response is still streaming. The drain fiber is tracked in ShellState
              // so it can be interrupted by a subsequent message, /new, or /quit.
              //
              // commitInProgress flushes any inProgressMessage left by the previous response into
              // the message list, so the new response starts as a fresh Server message block.
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
              screen(_.commitInProgress()) *> drain
                .ensuring(ZIO.serviceWithZIO[ShellState](_.setDrainingFiber(None)))
                .fork
                .flatMap(f => ZIO.serviceWithZIO[ShellState](_.setDrainingFiber(Some(f))))
          }
        } yield ()
    }

  private val showCommands: ZIO[Env, Nothing, Unit] = {
    val lines = List(
      "Jorlan Shell — type a message to talk to the agent, or use a /command.",
      "Key bindings: Enter submit · Backspace delete · ↑↓ history · PgUp/PgDn scroll · Tab autocomplete · Ctrl-C quit",
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
      "/users list [all|inactive]           List users (default: active only)",
      "/users create <displayName> <email>  Create a new user",
      "/users deactivate <id>               Deactivate a user account",
      "/users update <id> name <value>      Update a user's display name",
      "/users update <id> email <value>     Update a user's email address",
      "/users capabilities <id>             List capability grants for a user",
      "/users grant <id> <cap> <mode>       Grant a capability to a user (mode: Manual|AutoApprove|Deny)",
      "/users revoke-grant <grantId>        Revoke a capability grant by id",
      "/users roles <id>                    List roles assigned to a user",
      "/users assign-role <userId> <roleId> Assign a role to a user",
      "/users revoke-role <userId> <roleId> Revoke a role from a user",
      "/users identities <id>               List channel identities for a user",
      "/users link-identity <id> <type> <channelUserId>  Link a channel identity to a user",
      "/users unlink-identity <identityId>  Remove a channel identity",
      "/roles list                          List all roles",
      "/roles create <name> [description]   Create a new role",
      "/scheduler list                      List all scheduler jobs with status and results",
      "/scheduler result <id>               Show full result/error for a scheduler job",
      "/agents list                         List active agent sessions",
      "/agents stop <id>                    Terminate an agent session",
      "/approvals list                      List pending approval requests",
      "/approvals approve <id>              Approve a pending request",
      "/approvals deny <id>                 Deny a pending request",
      "/skills                              List registered skills with enabled/disabled status",
      "/skills enable <name>               Enable a skill by name",
      "/skills disable <name>              Disable a skill by name (including built-in skills)",
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
      gqlResult    <- repo(_.serverInfo.statusCheck())
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
      result <- repo(_.agent.createSession(modelId.map(ModelId(_)))).either
      _      <- result match {
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
          sessionsResult <- repo(_.agent.searchSessions(AgentSessionSearch())).either
          modelsResult   <- repo(_.agent.availableModels()).either
          _              <- (sessionsResult, modelsResult) match {
            case (Left(err), _)  => screen(_.addMessage(MessageKind.Error, s"Failed to fetch session info: $err"))
            case (Right(Nil), _) =>
              screen(_.addMessage(MessageKind.System, "No sessions found on server."))
            case (Right(sessions), modelsEither) =>
              sessions.find(_.id == sid) match {
                case None => screen(_.addMessage(MessageKind.System, s"Session ${sid.value} not found on server."))
                case Some(session) =>
                  val defaultModel =
                    modelsEither.toOption.flatMap(_.headOption).map(_.id.value).getOrElse("unknown")
                  val model = session.modelId.map(_.value).getOrElse(defaultModel)
                  screen(
                    _.addMessage(MessageKind.System, s"Session: ${sid.value}  Model: $model  Status: ${session.status}"),
                  )
              }
          }
        } yield ()
    }

  private val listModels: ZIO[Env, Nothing, Unit] =
    repo(_.agent.availableModels()).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list models: $err")),
      {
        case Nil    => screen(_.addMessage(MessageKind.System, "No models available."))
        case models =>
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
    repo(_.setting.serverPersonality()).foldZIO(
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
      repo(_.setting.serverPersonality()).foldZIO(
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

            repo(_.setting.updatePersonality(name, formality, languages, expertise, prompt)).foldZIO(
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

  private def formatPersonality(p: Personality): String = {
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

  private def memoryValueStr(v: Json): String =
    v match {
      case Json.Str(s) => s
      case other       => other.toString
    }

  private def listMemory(scope: Option[MemoryScope]): ZIO[Env, Nothing, Unit] = {
    val s = scope.getOrElse(MemoryScope.User)
    repo(_.memory.search(MemorySearch(s))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list memory: $err")),
      {
        case Nil     => screen(_.addMessage(MessageKind.System, "No memory records found."))
        case records =>
          val lines =
            records
              .map(r => s"[${r.id.value}] (${r.scope} p${r.importance}) ${r.recordKey}: ${memoryValueStr(r.value)}")
              .mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Memory records:\n$lines"))
      },
    )
  }

  private def searchMemory(text: String): ZIO[Env, Nothing, Unit] =
    repo(_.memory.search(MemorySearch(MemoryScope.User, textSearch = Some(text)))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Memory search failed: $err")),
      {
        case Nil     => screen(_.addMessage(MessageKind.System, s"No memory records matching '$text'."))
        case records =>
          val lines =
            records.map(r => s"[${r.id.value}] (${r.scope}) ${r.recordKey}: ${memoryValueStr(r.value)}").mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Matching records:\n$lines"))
      },
    )

  private def forgetMemory(id: MemoryRecordId): ZIO[Env, Nothing, Unit] =
    repo(_.memory.delete(id)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Forget failed: $err")),
      _ => screen(_.addMessage(MessageKind.System, s"Memory record ${id.value} deleted.")),
    )

  private def rememberMemory(
    key:   String,
    text:  String,
    scope: MemoryScope = MemoryScope.User,
  ): ZIO[Env, Nothing, Unit] =
    repo(
      _.memory.upsert(
        MemoryRecord(
          id = MemoryRecordId.empty,
          scope = scope,
          userId = None,
          workspaceId = None,
          agentId = None,
          recordKey = key,
          value = Json.Str(text),
          ttl = None,
          createdAt = java.time.Instant.EPOCH,
          updatedAt = java.time.Instant.EPOCH,
          importance = 8,
        ),
      ),
    ).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Remember failed: $err")),
      r =>
        screen(_.addMessage(MessageKind.System, s"Stored [${r.id.value}] ${r.recordKey}: ${memoryValueStr(r.value)}")),
    )

  private def shareMemory(id: MemoryRecordId): ZIO[Env, Nothing, Unit] =
    repo(_.memory.updateScope(id, MemoryScope.Shared)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Share failed: $err")),
      count =>
        if (count > 0L) screen(_.addMessage(MessageKind.System, s"Memory record ${id.value} is now Shared."))
        else screen(_.addMessage(MessageKind.Error, s"Memory record ${id.value} not found.")),
    )

  private def privatizeMemory(id: MemoryRecordId): ZIO[Env, Nothing, Unit] =
    repo(_.memory.updateScope(id, MemoryScope.Private)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Privatize failed: $err")),
      count =>
        if (count > 0L) screen(_.addMessage(MessageKind.System, s"Memory record ${id.value} is now Private."))
        else screen(_.addMessage(MessageKind.Error, s"Memory record ${id.value} not found.")),
    )

  private val requestCheckpoint: ZIO[Env, Nothing, Unit] =
    ZIO.serviceWithZIO[ShellState](_.getLiveSession).flatMap {
      case None =>
        screen(_.addMessage(MessageKind.Error, "No active session — start a session first with /new"))
      case Some(session) =>
        ZIO
          .serviceWithZIO[ZIOClientRepositories](_.requestCheckpoint(session.sessionId))
          .foldZIO(
            err => screen(_.addMessage(MessageKind.Error, s"Checkpoint failed: $err")),
            _ => screen(_.addMessage(MessageKind.System, "Checkpoint requested — memories will be saved shortly.")),
          )
    }

  private val showCheckpointPolicy: ZIO[Env, Nothing, Unit] =
    ZIO
      .serviceWithZIO[ZIOClientRepositories](_.getCheckpointPolicy)
      .foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not load checkpoint policy: $err")),
        cfg => {
          val interval = cfg.timedIntervalTurns.fold("disabled")(n => s"every $n turns")
          val lines = List(
            s"  on-session-end:    ${if (cfg.onSessionEnd) "on" else "off"}",
            s"  on-user-request:   ${if (cfg.onUserRequest) "on" else "off"}",
            s"  timed-interval:    $interval",
            s"  before-effect:     ${if (cfg.beforeExternalEffect) "on" else "off"}",
          ).mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Checkpoint policy:\n$lines"))
        },
      )

  private def setCheckpointInterval(turns: Int): ZIO[Env, Nothing, Unit] = {
    val newInterval = if (turns <= 0) None else Some(turns)
    ZIO
      .serviceWithZIO[ZIOClientRepositories](_.updateCheckpointPolicy(timedIntervalTurns = Some(newInterval)))
      .foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not update policy: $err")),
        cfg => {
          val msg = cfg.timedIntervalTurns.fold("Timed interval disabled.")(n => s"Timed interval set to $n turns.")
          screen(_.addMessage(MessageKind.System, msg))
        },
      )
  }

  private def toggleCheckpointTrigger(
    trigger: String,
    enabled: Boolean,
  ): ZIO[Env, Nothing, Unit] = {
    val update = trigger match {
      case "session-end" =>
        ZIO.serviceWithZIO[ZIOClientRepositories](_.updateCheckpointPolicy(onSessionEnd = Some(enabled)))
      case "user-request" =>
        ZIO.serviceWithZIO[ZIOClientRepositories](_.updateCheckpointPolicy(onUserRequest = Some(enabled)))
      case "before-effect" =>
        ZIO.serviceWithZIO[ZIOClientRepositories](_.updateCheckpointPolicy(beforeExternalEffect = Some(enabled)))
      case other =>
        ZIO.fail(s"Unknown trigger '$other'. Use session-end, user-request, or before-effect.")
    }
    update.foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not update policy: $err")),
      _ => screen(_.addMessage(MessageKind.System, s"Trigger '$trigger' ${if (enabled) "enabled" else "disabled"}.")),
    )
  }

  private val showCapabilities: ZIO[Env, Nothing, Unit] =
    repo(_.permission.listCapabilities()).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not load capabilities: $err")),
      {
        case Nil    => screen(_.addMessage(MessageKind.System, "No capability grants found."))
        case grants =>
          val lines = grants
            .map { g =>
              val expiry = g.expiresAt.map(e => s" (expires: $e)").getOrElse("")
              s"  ${g.capability.value}  [${g.approvalMode}]$expiry"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Your capability grants:\n$lines"))
      },
    )

  private def listUsers(active: Option[Boolean]): ZIO[Env, Nothing, Unit] =
    repo(_.user.search(UserSearch(active = active))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list users: $err")),
      {
        case Nil   => screen(_.addMessage(MessageKind.System, "No users found."))
        case users =>
          val header = active match {
            case None        => "All users:"
            case Some(true)  => "Active users:"
            case Some(false) => "Inactive users:"
          }
          val lines = users
            .map { u =>
              val status = if (u.active) "active" else "inactive"
              s"  [${u.id.value}] ${u.displayName}  <${u.email}>  $status"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"$header\n$lines"))
      },
    )

  private def deactivateUser(id: UserId): ZIO[Env, Nothing, Unit] =
    repo(_.user.deactivate(id)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Deactivate failed: $err")),
      count =>
        if (count > 0) screen(_.addMessage(MessageKind.System, s"User ${id.value} deactivated."))
        else screen(_.addMessage(MessageKind.Error, s"User ${id.value} not found or already inactive.")),
    )

  private val validUserFields: Set[String] = Set("name", "email")

  private def updateUser(
    id:    UserId,
    field: String,
    value: String,
  ): ZIO[Env, Nothing, Unit] =
    if (!validUserFields.contains(field.toLowerCase)) {
      screen(
        _.addMessage(
          MessageKind.Error,
          s"Unknown field '$field'. Valid fields: ${validUserFields.mkString(", ")}",
        ),
      )
    } else {
      repo(_.user.getById(id)).foldZIO(
        err => screen(_.addMessage(MessageKind.Error, s"Could not fetch user: $err")),
        {
          case None           => screen(_.addMessage(MessageKind.Error, s"User ${id.value} not found."))
          case Some(existing) =>
            val updated = field.toLowerCase match {
              case "name"  => existing.copy(displayName = value)
              case "email" => existing.copy(email = value)
              case _       => existing
            }
            repo(_.user.upsert(updated)).foldZIO(
              err => screen(_.addMessage(MessageKind.Error, s"Update failed: $err")),
              u =>
                screen(
                  _.addMessage(
                    MessageKind.System,
                    s"User ${u.id.value} updated: ${u.displayName} <${u.email}> active=${u.active}",
                  ),
                ),
            )
        },
      )
    }

  private def createUser(
    displayName: String,
    email:       String,
  ): ZIO[Env, Nothing, Unit] = {
    import java.time.Instant
    repo(
      _.user.upsert(
        User(
          id = UserId.empty,
          displayName = displayName,
          email = email,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
          active = true,
        ),
      ),
    ).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Create user failed: $err")),
      u => screen(_.addMessage(MessageKind.System, s"Created user [${u.id.value}] ${u.displayName} <${u.email}>")),
    )
  }

  private def listUserCapabilities(userId: UserId): ZIO[Env, Nothing, Unit] =
    repo(_.permission.searchGrants(GrantSearch(userId = userId))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list capabilities: $err")),
      {
        case Nil    => screen(_.addMessage(MessageKind.System, s"No capability grants for user ${userId.value}."))
        case grants =>
          val lines = grants
            .map { g =>
              s"  [${g.id.value}] ${g.capability.value}  mode=${g.approvalMode}${g.expiresAt.map(t => s"  expires=$t").getOrElse("")}"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Capabilities for user ${userId.value}:\n$lines"))
      },
    )

  private def grantUserCapability(
    userId:       UserId,
    capability:   String,
    approvalMode: String,
  ): ZIO[Env, Nothing, Unit] = {
    import java.time.Instant
    val modeOpt = ApprovalMode.values.find(_.toString.equalsIgnoreCase(approvalMode))
    modeOpt match {
      case None =>
        screen(
          _.addMessage(
            MessageKind.Error,
            s"Unknown approval mode '$approvalMode'. Valid: ${ApprovalMode.values.mkString(", ")}",
          ),
        )
      case Some(mode) =>
        repo(
          _.permission.upsertCapabilityGrant(
            CapabilityGrant(
              id = CapabilityGrantId.empty,
              capability = CapabilityName(capability),
              scopeJson = None,
              granteeId = userId,
              grantorId = None,
              approvalMode = mode,
              expiresAt = None,
              resourceConstraints = None,
              createdAt = Instant.now(),
            ),
          ),
        ).foldZIO(
          err => screen(_.addMessage(MessageKind.Error, s"Grant failed: $err")),
          g =>
            screen(
              _.addMessage(
                MessageKind.System,
                s"Granted '${g.capability.value}' to user ${userId.value} [grant id=${g.id.value}]",
              ),
            ),
        )
    }
  }

  private def revokeCapabilityGrant(grantId: CapabilityGrantId): ZIO[Env, Nothing, Unit] =
    repo(_.permission.revokeGrant(grantId)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Revoke failed: $err")),
      count =>
        if (count > 0) screen(_.addMessage(MessageKind.System, s"Grant ${grantId.value} revoked."))
        else screen(_.addMessage(MessageKind.Error, s"Grant ${grantId.value} not found.")),
    )

  private def listUserRoles(userId: UserId): ZIO[Env, Nothing, Unit] =
    repo(_.permission.searchRoles(RoleSearch(userId = Some(userId)))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list roles: $err")),
      {
        case Nil   => screen(_.addMessage(MessageKind.System, s"No roles assigned to user ${userId.value}."))
        case roles =>
          val lines = roles
            .map(r => s"  [${r.id.value}] ${r.name}${r.description.map(d => s" — $d").getOrElse("")}").mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Roles for user ${userId.value}:\n$lines"))
      },
    )

  private def assignUserRole(
    userId: UserId,
    roleId: RoleId,
  ): ZIO[Env, Nothing, Unit] =
    repo(_.permission.assignRole(userId, roleId)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Assign role failed: $err")),
      _ => screen(_.addMessage(MessageKind.System, s"Role ${roleId.value} assigned to user ${userId.value}.")),
    )

  private def revokeUserRole(
    userId: UserId,
    roleId: RoleId,
  ): ZIO[Env, Nothing, Unit] =
    repo(_.permission.removeRole(userId, roleId)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Revoke role failed: $err")),
      _ => screen(_.addMessage(MessageKind.System, s"Role ${roleId.value} revoked from user ${userId.value}.")),
    )

  private def listUserIdentities(userId: UserId): ZIO[Env, Nothing, Unit] =
    repo(_.user.getChannelIdentities(userId)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list identities: $err")),
      {
        case Nil =>
          screen(_.addMessage(MessageKind.System, s"No channel identities for user ${userId.value}."))
        case ids =>
          val lines = ids
            .map { ci =>
              s"  [${ci.id.value}] ${ci.channelType} : ${ci.channelUserId}${if (ci.verified) " (verified)" else ""}"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Channel identities for user ${userId.value}:\n$lines"))
      },
    )

  private def linkUserIdentity(
    userId:        UserId,
    channelType:   String,
    channelUserId: String,
  ): ZIO[Env, Nothing, Unit] = {
    import java.time.Instant
    val chTypeOpt = ChannelType.values.find(_.toString.equalsIgnoreCase(channelType))
    chTypeOpt match {
      case None =>
        screen(
          _.addMessage(
            MessageKind.Error,
            s"Unknown channel type '$channelType'. Valid: ${ChannelType.values.mkString(", ")}",
          ),
        )
      case Some(chType) =>
        repo(
          _.user.upsertChannelIdentity(
            ChannelIdentity(
              id = ChannelIdentityId.empty,
              userId = userId,
              channelType = chType,
              channelUserId = channelUserId,
              verified = false,
              providerData = None,
              createdAt = Instant.now(),
            ),
          ),
        ).foldZIO(
          err => screen(_.addMessage(MessageKind.Error, s"Link identity failed: $err")),
          ci =>
            screen(
              _.addMessage(
                MessageKind.System,
                s"Linked ${ci.channelType}:${ci.channelUserId} to user ${userId.value} [id=${ci.id.value}]",
              ),
            ),
        )
    }
  }

  private def unlinkUserIdentity(identityId: ChannelIdentityId): ZIO[Env, Nothing, Unit] =
    repo(_.user.deleteChannelIdentity(identityId)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Unlink failed: $err")),
      count =>
        if (count > 0) screen(_.addMessage(MessageKind.System, s"Identity ${identityId.value} unlinked."))
        else screen(_.addMessage(MessageKind.Error, s"Identity ${identityId.value} not found.")),
    )

  private val listRoles: ZIO[Env, Nothing, Unit] =
    repo(_.permission.searchRoles(RoleSearch(userId = None))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list roles: $err")),
      {
        case Nil   => screen(_.addMessage(MessageKind.System, "No roles defined."))
        case roles =>
          val lines = roles
            .map(r => s"  [${r.id.value}] ${r.name}${r.description.map(d => s" — $d").getOrElse("")}").mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Roles:\n$lines"))
      },
    )

  private def createRole(
    name:        String,
    description: Option[String],
  ): ZIO[Env, Nothing, Unit] =
    repo(_.permission.upsertRole(Role(id = RoleId.empty, name = name, description = description))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Create role failed: $err")),
      r => screen(_.addMessage(MessageKind.System, s"Created role [${r.id.value}] ${r.name}")),
    )

  private val listSchedulerJobs: ZIO[Env, Nothing, Unit] =
    repo(_.scheduler.listJobs(None, 200)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list scheduler jobs: $err")),
      {
        case Nil  => screen(_.addMessage(MessageKind.System, "No scheduler jobs found."))
        case jobs =>
          val lines = jobs
            .map { j =>
              val result = j.status match {
                case JobStatus.Failed    => j.resultJson.map(r => s"  ERROR: ${r.take(200)}").getOrElse("")
                case JobStatus.Succeeded => j.resultJson.map(r => s"  result: ${r.take(100)}").getOrElse("")
                case _                   => ""
              }
              val promptPreview = if (j.prompt.nonEmpty) s"  prompt: ${j.prompt.take(80)}" else ""
              s"  [${j.id.value}] ${j.name}  status=${j.status}  retries=${j.retryCount}/${j.maxRetries}$promptPreview$result"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Scheduler jobs:\n$lines"))
      },
    )

  private def showSchedulerResult(id: SchedulerJobId): ZIO[Env, Nothing, Unit] =
    repo(_.scheduler.getJob(id)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not fetch job: $err")),
      {
        case None      => screen(_.addMessage(MessageKind.Error, s"Job ${id.value} not found."))
        case Some(job) =>
          val result = job.resultJson.getOrElse("(no result)")
          val lines = Seq(
            s"Job: ${job.name} [${job.id.value}]",
            s"Status: ${job.status}",
            s"Prompt: ${job.prompt}",
            s"Retries: ${job.retryCount}/${job.maxRetries}",
            s"Scheduled: ${job.scheduledAt}",
            job.startedAt.map(t => s"Started: $t").getOrElse(""),
            job.finishedAt.map(t => s"Finished: $t").getOrElse(""),
            s"Result:\n$result",
          ).filter(_.nonEmpty).mkString("\n")
          screen(_.addMessage(MessageKind.System, lines))
      },
    )

  private val listAgents: ZIO[Env, Nothing, Unit] =
    repo(_.agent.searchSessions(AgentSessionSearch())).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list sessions: $err")),
      {
        case Nil      => screen(_.addMessage(MessageKind.System, "No active sessions."))
        case sessions =>
          val lines = sessions
            .map { s =>
              s"  [${s.id.value}] status=${s.status}  model=${s.modelId.map(_.value).getOrElse("default")}"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Agent sessions:\n$lines"))
      },
    )

  private def stopAgent(id: AgentSessionId): ZIO[Env, Nothing, Unit] =
    repo(_.agent.terminateSession(id)).foldZIO(
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
    repo(_.permission.listApprovals()).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list approvals: $err")),
      {
        case Nil      => screen(_.addMessage(MessageKind.System, "No pending approval requests."))
        case requests =>
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
    repo(_.permission.decideApproval(id, approved)).foldZIO(
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
    repo(_.skill.listSkills()).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list skills: $err")),
      {
        case Nil    => screen(_.addMessage(MessageKind.System, "No skills registered."))
        case skills =>
          val lines = skills
            .map { s =>
              val status = if (s.enabled) "enabled" else "DISABLED"
              val toolLines = s.tools
                .map(t => s"    • ${t.name}  [${t.requiredCapabilities.mkString(", ")}]")
                .mkString("\n")
              s"  ${s.name}  (${s.tier})  [$status]\n$toolLines"
            }.mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Registered skills:\n$lines"))
      },
    )

  private def enableSkill(name: String): ZIO[Env, Nothing, Unit] =
    repo(_.skill.enableSkill(name)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not enable skill '$name': $err")),
      _ => screen(_.addMessage(MessageKind.System, s"Skill '$name' enabled.")),
    )

  private def disableSkill(name: String): ZIO[Env, Nothing, Unit] =
    repo(_.skill.disableSkill(name)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not disable skill '$name': $err")),
      _ => screen(_.addMessage(MessageKind.System, s"Skill '$name' disabled.")),
    )

  private def getSkillConfig(name: String): ZIO[Env, Nothing, Unit] =
    repo(_.skill.getSkillConfig(name)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not get config for skill '$name': $err")),
      {
        case None    => screen(_.addMessage(MessageKind.System, s"No configuration stored for skill '$name'."))
        case Some(j) => screen(_.addMessage(MessageKind.System, s"Config for '$name':\n$j"))
      },
    )

  private def setSkillConfig(
    name: String,
    json: String,
  ): ZIO[Env, Nothing, Unit] =
    repo(_.skill.updateSkillConfig(name, json)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not set config for skill '$name': $err")),
      _ =>
        screen(
          _.addMessage(MessageKind.System, s"Config for '$name' saved. Restart the server for changes to take effect."),
        ),
    )

  private def findContacts(name: String): ZIO[Env, Nothing, Unit] = {
    def strField(
      fields: Seq[(String, Json)],
      key:    String,
    ): String =
      fields.collectFirst { case (`key`, Json.Str(v)) => v }.getOrElse("?")

    def renderContact(fields: Seq[(String, Json)]): String = {
      val displayName = strField(fields, "displayName")
      val userId = fields
        .collectFirst { case ("userId", Json.Num(n)) => n.longValue.toString }
        .getOrElse("?")
      val ids = fields
        .collectFirst { case ("identities", Json.Arr(is)) =>
          is.collect { case Json.Obj(i) =>
            s"${strField(i.toSeq, "channelType")}:${strField(i.toSeq, "channelUserId")}"
          }.mkString(", ")
        }.getOrElse("")
      s"  [$userId] $displayName  $ids"
    }

    repo(_.user.findContacts(Some(name))).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"contacts.find failed: $err")),
      json => {
        val users: Seq[Json] = json match {
          case Json.Obj(root) =>
            root
              .collectFirst { case ("data", Json.Obj(data)) =>
                data.collectFirst { case ("contacts", Json.Arr(arr)) => arr.toSeq }.getOrElse(Seq.empty)
              }.getOrElse(Seq.empty)
          case _ => Seq.empty
        }
        if (users.isEmpty) {
          screen(_.addMessage(MessageKind.System, s"No contacts found matching '$name'."))
        } else {
          val lines = users
            .collect { case Json.Obj(fields) => renderContact(fields.toSeq) }
            .mkString("\n")
          screen(_.addMessage(MessageKind.System, s"Contacts matching '$name':\n$lines"))
        }
      },
    )
  }

  private def showOAuthStatus(provider: String): ZIO[Env, Nothing, Unit] =
    repo(_.extCredential.oauthStatus(provider)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not get OAuth status: $err")),
      {
        case None    => screen(_.addMessage(MessageKind.System, s"OAuth status for '$provider': not found"))
        case Some(s) =>
          val expiry = s.expiresAt.map(t => s"  expires: $t").getOrElse("")
          screen(_.addMessage(MessageKind.System, s"OAuth '$provider': connected=${s.connected}$expiry"))
      },
    )

  private val listOAuthProviders: ZIO[Env, Nothing, Unit] =
    repo(_.extCredential.listOAuthProviders()).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not list OAuth providers: $err")),
      {
        case Nil       => screen(_.addMessage(MessageKind.System, "No OAuth providers connected."))
        case providers =>
          screen(_.addMessage(MessageKind.System, s"Connected OAuth providers: ${providers.mkString(", ")}"))
      },
    )

  private def connectOAuth(provider: String): ZIO[Env, Nothing, Unit] =
    for {
      serverUrl <- cfg(_.serverUrl)
      _         <- repo(_.extCredential.startOAuth(provider)).foldZIO(
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
    repo(_.extCredential.revokeOAuth(provider)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"Could not revoke OAuth: $err")),
      _ => screen(_.addMessage(MessageKind.System, s"OAuth credentials for '$provider' revoked.")),
    )

  private def emailList(maxResults: Int): ZIO[Env, Nothing, Unit] =
    repo(_.skill.invokeTool("email.list", s"""{"maxResults":$maxResults}""")).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"email.list failed: $err")),
      {
        case None         => screen(_.addMessage(MessageKind.System, "No response from email.list"))
        case Some(result) => screen(_.addMessage(MessageKind.System, result))
      },
    )

  private def emailRead(messageId: String): ZIO[Env, Nothing, Unit] = {
    import zio.json.*
    val argsJson = Json.Obj("messageId" -> Json.Str(messageId)).toJson
    repo(_.skill.invokeTool("email.read", argsJson)).foldZIO(
      err => screen(_.addMessage(MessageKind.Error, s"email.read failed: $err")),
      {
        case None         => screen(_.addMessage(MessageKind.System, "No response from email.read"))
        case Some(result) => screen(_.addMessage(MessageKind.System, result))
      },
    )
  }

  private def emailSearch(query: String): ZIO[Env, Nothing, Unit] = {
    import zio.json.*
    val argsJson = Json.Obj("query" -> Json.Str(query)).toJson
    repo(_.skill.invokeTool("email.search", argsJson)).foldZIO(
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
    repo(
      _.skill.invokeTool(
        "calendar.listEvents",
        s"""{"timeMin":"$todayMin","timeMax":"$todayMax","maxResults":50}""",
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
        repo(
          _.skill.invokeTool(
            "calendar.listEvents",
            s"""{"timeMin":"$dayMin","timeMax":"$dayMax","maxResults":50}""",
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
