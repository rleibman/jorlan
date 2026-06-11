/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.json.*
import zio.stream.ZStream

/** Immutable environment threaded through the [[AgentRunnerImpl.reactLoop]] without changing across recursive calls. */
private case class ReactLoopEnv(
  sessionId: AgentSessionId,
  tools:     List[ToolSpec],
  actorId:   Option[UserId],
  agentId:   AgentId,
  ctx:       InvocationContext,
  errorRef:  Ref[Option[String]],
  chunksRef: Ref[Vector[String]],
)

/** Mutable per-runner [[Ref]] bundle: seeded sessions, active conversation IDs, cached agent IDs, and cached
  * personality.
  */
private class AgentRunnerState(
  val seeded:            Ref[Set[AgentSessionId]],
  val activeConvs:       Ref[Map[AgentSessionId, ConversationId]],
  val cachedAgentIds:    Ref[Map[AgentSessionId, AgentId]],
  val cachedPersonality: Ref[Option[Personality]],
)

private object AgentRunnerState {

  val make: UIO[AgentRunnerState] =
    for {
      seeded            <- Ref.make(Set.empty[AgentSessionId])
      activeConvs       <- Ref.make(Map.empty[AgentSessionId, ConversationId])
      cachedAgentIds    <- Ref.make(Map.empty[AgentSessionId, AgentId])
      cachedPersonality <- Ref.make(Option.empty[Personality])
    } yield AgentRunnerState(seeded, activeConvs, cachedAgentIds, cachedPersonality)

}

/** [[AgentRunner]] implementation. On each [[processMessage]] call it:
  *   1. Seeds the [[ModelGateway]] with persisted conversation history if this is the first call for the session.
  *   2. Reads the current personality from [[ServerSettingsRepository]].
  *   3. Injects relevant [[MemoryRecord]]s from [[MemoryService]] into the system prompt.
  *   4. Runs the ReAct (Reason+Act) tool-calling loop via [[ModelGateway.chatStep]]:
  *      - Streams model output token-by-token through [[SessionHub]].
  *      - Invokes registered [[SkillRegistry]] tools when the model requests them.
  *      - Loops until the model emits a final text answer or `maxToolSteps` is exceeded.
  *   5. Persists the user message and assistant response to [[ZIOConversationRepository]].
  *   6. Evaluates [[CheckpointPolicy]] and runs the checkpoint pipeline when triggered.
  */
class AgentRunnerImpl(
  modelGateway:  ModelGateway,
  sessionHub:    SessionHub,
  toolEventHub:  ToolEventHub,
  repo:          ZIORepositories,
  memoryService: MemoryService,
  skillRegistry: SkillRegistry,
  runnerState:   AgentRunnerState,
  maxToolSteps:  Int,
) extends AgentRunner {

  private val seeded = runnerState.seeded
  private val activeConvs = runnerState.activeConvs
  private val cachedAgentIds = runnerState.cachedAgentIds

  override def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): IO[JorlanError, Unit] =
    for {
      errorRef    <- Ref.make(Option.empty[String])
      chunksRef   <- Ref.make(Vector.empty[String])
      agentId     <- resolveAgentId(sessionId)
      _           <- ensureSeeded(sessionId, actorId, agentId)
      convId      <- getOrCreateConversation(sessionId)
      personality <- loadPersonality
      work =
        for {
          _      <- ZIO.logDebug(s"[session:$sessionId] incoming message (${content.length} chars)")
          _      <- ConversationLogger.logUserMessage(sessionId, actorId, content)
          memCtx <- buildMemoryContext(sessionId, actorId, agentId, content)
          systemPrompt = Personality.buildSystemPrompt(personality) + memCtx
          _     <- logEvent(sessionId, EventType.UserMessageReceived, actorId)
          tools <- skillRegistry.allToolSpecs
          initialMessages = buildInitialMessages(systemPrompt, content)
          ctx = InvocationContext(
            actorId = actorId.getOrElse(UserId.empty),
            agentId = Option.when(agentId != AgentId.empty)(agentId),
            sessionId = Some(sessionId),
          )
          env = ReactLoopEnv(sessionId, tools, actorId, agentId, ctx, errorRef, chunksRef)
          _ <- reactLoop(env, initialMessages, stepsLeft = maxToolSteps)
        } yield ()
      _ <- work
        .tapError(e => errorRef.update(_.orElse(Some(e.getMessage))))
        .ensuring(finaliseResponse(convId, sessionId, content, actorId, agentId, personality, errorRef, chunksRef))
    } yield ()

  private def buildInitialMessages(
    systemPrompt: String,
    userContent:  String,
  ): List[AgentMessage] = {
    val systemMsgs: List[AgentMessage] =
      if (systemPrompt.nonEmpty) List(SystemMsg(systemPrompt)) else Nil
    systemMsgs :+ UserMsg(userContent)
  }

  private def reactLoop(
    env:       ReactLoopEnv,
    messages:  List[AgentMessage],
    stepsLeft: Int,
  ): IO[JorlanError, Unit] = {
    import env.*
    if (stepsLeft <= 0) {
      val errMsg = "\n[Tool call limit reached — stopping]\n"
      logEvent(sessionId, EventType.ToolLoopExceeded, actorId, agentId) *>
        sessionHub.publish(ResponseChunk(sessionId, errMsg, finished = false)).unit
    } else {
      modelGateway
        .chatStep(sessionId, messages, tools)
        .mapError(e => JorlanError(e.msg, Some(e)))
        .flatMap {
          case FinalAnswer(stream) =>
            ZIO.scoped {
              stream
                .mapError(e => JorlanError(e.msg, Some(e)))
                .tapError(e => errorRef.set(Some(e.getMessage)))
                .foreach { chunk =>
                  chunksRef.update(_ :+ chunk) *>
                    sessionHub.publish(ResponseChunk(sessionId, chunk, finished = false))
                }
            }

          case ToolCallRequested(id, name, argsJson) =>
            val toolFeedback = s"⟳ calling $name…\n"
            for {
              _ <- sessionHub.publish(ResponseChunk(sessionId, toolFeedback, finished = false))
              _ <- toolEventHub.publish(ToolEvent.ToolInvokedEvent(sessionId, name, argsJson))
              _ <- logEvent(
                sessionId,
                EventType.SkillInvoked,
                actorId,
                agentId,
                Some(
                  zio.json.ast.Json
                    .Obj("tool" -> zio.json.ast.Json.Str(name), "payload" -> zio.json.ast.Json.Str(argsJson)),
                ),
              )
              resultJson <- skillRegistry.invoke(name, argsJson, ctx)
              _          <- toolEventHub.publish(
                ToolEvent.ToolResultEvent(sessionId, name, resultJson.toString, succeeded = true),
              )
              _ <- logEvent(
                sessionId,
                EventType.SkillSucceeded,
                actorId,
                agentId,
                Some(
                  zio.json.ast.Json
                    .Obj("tool" -> zio.json.ast.Json.Str(name), "payload" -> zio.json.ast.Json.Str(resultJson.toString)),
                ),
              )
              newMessages = messages ++ List(
                ToolCallMsg(id, name, argsJson),
                ToolResultMsg(id, name, resultJson.toString),
              )
              _ <- reactLoop(env, newMessages, stepsLeft - 1)
            } yield ()
        }
    }
  }

  private def logEvent(
    sessionId:   AgentSessionId,
    eventType:   EventType,
    actorId:     Option[UserId],
    agentId:     AgentId = AgentId.empty,
    payloadJson: Option[zio.json.ast.Json] = None,
  ): UIO[Unit] =
    Clock.instant.flatMap { now =>
      repo.eventLog
        .append(
          EventLog(
            id = EventLogId.empty,
            eventType = eventType,
            actorId = actorId,
            agentId = Option.when(agentId != AgentId.empty)(agentId),
            sessionId = Some(sessionId),
            resource = Some(sessionId),
            payloadJson = payloadJson,
            occurredAt = now,
          ),
        )
        .ignore
    }

  private def finaliseResponse(
    convId:      ConversationId,
    sessionId:   AgentSessionId,
    userContent: String,
    actorId:     Option[UserId],
    agentId:     AgentId,
    personality: Personality,
    errorRef:    Ref[Option[String]],
    chunksRef:   Ref[Vector[String]],
  ): UIO[Unit] =
    for {
      errMsg <- errorRef.get
      chunks <- chunksRef.get
      fullResponse = chunks.mkString
      sentinel = errMsg match {
        case Some(msg) => ResponseChunk(sessionId, msg, finished = true, isError = true)
        case None      => ResponseChunk(sessionId, "", finished = true)
      }
      _ <- ZIO.logDebug(
        s"[session:$sessionId] response finished${errMsg.map(e => s" with error: $e").getOrElse("")}",
      )
      _ <- sessionHub.publish(sentinel)
      _ <- ConversationLogger.logAgentResponse(sessionId, fullResponse, isError = errMsg.isDefined)
      _ <- persistMessages(convId, sessionId, userContent, fullResponse, actorId, errMsg)
      _ <- runCheckpoint(sessionId, actorId, agentId, personality, userContent, fullResponse, errMsg)
      _ <- logEvent(sessionId, EventType.AgentResponseCompleted, actorId)
    } yield ()

  override def subscribeToSession(
    sessionId:    AgentSessionId,
    connectionId: ConnectionId,
  ): UIO[ZStream[Any, Nothing, ResponseChunk]] =
    sessionHub.subscribe(sessionId, connectionId)

  private def resolveAgentId(sessionId: AgentSessionId): IO[JorlanError, AgentId] =
    cachedAgentIds.get.flatMap { cache =>
      cache.get(sessionId) match {
        case Some(id) => ZIO.succeed(id)
        case None     =>
          repo.agent
            .getSession(sessionId)
            .mapError(JorlanError(_))
            .flatMap {
              case Some(session) =>
                cachedAgentIds.update(_ + (sessionId -> session.agentId)).as(session.agentId)
              case None => ZIO.succeed(AgentId.empty)
            }
      }
    }

  private def ensureSeeded(
    sessionId: AgentSessionId,
    actorId:   Option[UserId],
    agentId:   AgentId,
  ): IO[JorlanError, Unit] =
    seeded
      .modify { s =>
        if (s.contains(sessionId)) (true, s) else (false, s + sessionId)
      }.flatMap { alreadySeeded =>
        loadConversationHistory(sessionId)
          .flatMap { messages =>
            if (messages.isEmpty) ZIO.unit
            else {
              loadPersonality.flatMap { p =>
                val systemPrompt = Personality.buildSystemPrompt(p)
                modelGateway.seedHistory(sessionId, messages, systemPrompt)
              }
            }
          }.unless(alreadySeeded).unit
      }

  private def loadConversationHistory(sessionId: AgentSessionId): IO[JorlanError, List[Message]] =
    repo.conversation
      .search(
        ConversationSearch(
          sessionId = sessionId,
          pageSize = 1,
          sorts = Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)),
        ),
      )
      .mapError(JorlanError(_))
      .flatMap {
        case Nil    => ZIO.succeed(Nil)
        case c :: _ =>
          repo.conversation
            .searchMessages(
              MessageSearch(
                conversationId = c.id,
                pageSize = 100,
                sorts = Some(Sort(MessageOrder.CreatedAt, OrderDirection.Asc)),
              ),
            )
            .mapError(JorlanError(_))
      }

  private def getOrCreateConversation(sessionId: AgentSessionId): IO[JorlanError, ConversationId] =
    activeConvs
      .modify { map =>
        map.get(sessionId) match {
          case Some(id) => (Right(id), map)
          case None     => (Left(()), map)
        }
      }.flatMap {
        case Right(id) => ZIO.succeed(id)
        case Left(_)   =>
          repo.conversation
            .search(
              ConversationSearch(
                sessionId = sessionId,
                pageSize = 1,
                sorts = Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)),
              ),
            )
            .mapError(JorlanError(_))
            .flatMap {
              case c :: _ =>
                activeConvs.update(_ + (sessionId -> c.id)).as(c.id)
              case Nil =>
                Clock.instant.flatMap { now =>
                  repo.conversation
                    .create(Conversation(id = ConversationId.empty, sessionId = sessionId, startedAt = now))
                    .mapError(JorlanError(_))
                    .flatMap(c => activeConvs.update(_ + (sessionId -> c.id)).as(c.id))
                }
            }
      }

  private def persistMessages(
    convId:        ConversationId,
    sessionId:     AgentSessionId,
    userText:      String,
    assistantText: String,
    actorId:       Option[UserId],
    errMsg:        Option[String],
  ): UIO[Unit] = {
    Clock.instant
      .flatMap { now =>
        val userMsg = Message(MessageId.empty, convId, MessageRole.User, userText, None, now)
        val asstMsg = Message(MessageId.empty, convId, MessageRole.Assistant, assistantText, None, now)
        (repo.conversation.addMessage(userMsg) *> repo.conversation.addMessage(asstMsg))
          .mapError(JorlanError(_))
          .tapError(err => ZIO.logWarning(s"[session:$sessionId] Failed to persist messages: $err"))
          .ignore
      }.unless(errMsg.isDefined && assistantText.isBlank).unit
  }

  private def runCheckpoint(
    sessionId:     AgentSessionId,
    actorId:       Option[UserId],
    agentId:       AgentId,
    personality:   Personality,
    userText:      String,
    assistantText: String,
    errMsg:        Option[String],
  ): UIO[Unit] =
    actorId.fold(ZIO.unit) { userId =>
      Clock.instant
        .flatMap { now =>
          val userMsg = Message(MessageId.empty, ConversationId.empty, MessageRole.User, userText, None, now)
          val asstMsg =
            Message(MessageId.empty, ConversationId.empty, MessageRole.Assistant, assistantText, None, now)
          memoryService
            .checkpoint(sessionId, List(userMsg, asstMsg), userId, agentId, CheckpointTrigger.SessionEnd)
            .tapError(err => ZIO.logWarning(s"[session:$sessionId] Checkpoint failed: $err"))
            .ignore
        }.unless(errMsg.isDefined && assistantText.isBlank).unit
    }

  private def buildMemoryContext(
    sessionId:   AgentSessionId,
    actorId:     Option[UserId],
    agentId:     AgentId,
    userMessage: String,
  ): UIO[String] =
    actorId.fold(ZIO.succeed("")) { userId =>
      val queryScopes = List(MemoryScope.User, MemoryScope.Shared)
      ZIO
        .foreach(queryScopes) { scope =>
          memoryService.query(scope, userId, agentId, Some(userMessage))
        }
        .map { results =>
          val records = results.flatten
          if (records.isEmpty) ""
          else {
            val lines = records
              .map { r =>
                r.value.asObject.flatMap(_.get("text")).flatMap(_.asString).getOrElse(r.value.toString)
              }.mkString("\n- ")
            s"\n\n[Memory context]\n- $lines"
          }
        }
        .catchAll { err =>
          ZIO.logWarning(s"[session:$sessionId] Memory context unavailable: ${err.msg}") *> ZIO.succeed("")
        }
    }

  private val loadPersonality: ZIO[Any, RepositoryError, Personality] =
    runnerState.cachedPersonality.get.flatMap {
      case Some(p) => ZIO.succeed(p)
      case None    =>
        repo.setting.get(ZIOServerSettingsRepository.PersonalityKey).flatMap {
          case Some(json) =>
            json.as[Personality] match {
              case Right(p)  => runnerState.cachedPersonality.set(Some(p)).as(p)
              case Left(err) =>
                ZIO.logWarning(s"Corrupt personality in server_settings: $err. Using default.") *>
                  ZIO.succeed(Personality.default)
            }
          case None => ZIO.succeed(Personality.default)
        }
    }

}

object AgentRunnerImpl {

  val defaultMaxToolSteps: Int = 10

  val live: URLayer[
    ModelGateway & SessionHub & ToolEventHub & ZIORepositories & MemoryService & SkillRegistry & ConfigurationService,
    AgentRunner,
  ] =
    ZLayer.fromZIO(
      for {
        modelGateway  <- ZIO.service[ModelGateway]
        sessionHub    <- ZIO.service[SessionHub]
        toolEventHub  <- ZIO.service[ToolEventHub]
        repo          <- ZIO.service[ZIORepositories]
        memoryService <- ZIO.service[MemoryService]
        skillRegistry <- ZIO.service[SkillRegistry]
        settings      <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.agent).orDie
        runnerState   <- AgentRunnerState.make
      } yield AgentRunnerImpl(
        modelGateway = modelGateway,
        sessionHub = sessionHub,
        toolEventHub = toolEventHub,
        repo = repo,
        memoryService = memoryService,
        skillRegistry = skillRegistry,
        runnerState = runnerState,
        maxToolSteps = settings.maxToolSteps,
      ),
    )

}
