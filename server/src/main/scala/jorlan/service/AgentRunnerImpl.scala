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
import jorlan.db.repository.{
  AgentZIORepository,
  ConversationZIORepository,
  EventLogZIORepository,
  ServerSettingsRepository,
}
import jorlan.domain.*
import zio.*
import zio.json.*
import zio.stream.ZStream

import java.time.Instant

/** [[AgentRunner]] implementation. On each [[processMessage]] call it:
  *   1. Seeds the [[ModelGateway]] with persisted conversation history if this is the first call for the session.
  *   2. Reads the current personality from [[ServerSettingsRepository]].
  *   3. Injects relevant [[MemoryRecord]]s from [[MemoryService]] into the system prompt.
  *   4. Streams the model response token-by-token through [[SessionHub]].
  *   5. Persists the user message and assistant response to [[ConversationZIORepository]].
  *   6. Evaluates [[CheckpointPolicy]] and runs the checkpoint pipeline when triggered.
  */
private class AgentRunnerState(
  val seeded:         Ref[Set[AgentSessionId]],
  val activeConvs:    Ref[Map[AgentSessionId, ConversationId]],
  val cachedAgentIds: Ref[Map[AgentSessionId, AgentId]],
)

private object AgentRunnerState {

  val make: UIO[AgentRunnerState] =
    for {
      seeded         <- Ref.make(Set.empty[AgentSessionId])
      activeConvs    <- Ref.make(Map.empty[AgentSessionId, ConversationId])
      cachedAgentIds <- Ref.make(Map.empty[AgentSessionId, AgentId])
    } yield new AgentRunnerState(seeded, activeConvs, cachedAgentIds)

}

class AgentRunnerImpl(
  modelGateway:  ModelGateway,
  sessionHub:    SessionHub,
  eventLogRepo:  EventLogZIORepository,
  settingsRepo:  ServerSettingsRepository,
  convRepo:      ConversationZIORepository,
  agentRepo:     AgentZIORepository,
  memoryService: MemoryService,
  runnerState:   AgentRunnerState,
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
          _ <- logSessionEvent(sessionId, EventType.UserMessageReceived, actorId)
          _ <- ZIO.scoped {
            modelGateway
              .streamedResponse(sessionId, content, systemPrompt)
              .mapError(e => JorlanError(e.msg, Some(e)))
              .tapError(e => errorRef.set(Some(e.getMessage)))
              .foreach { chunk =>
                chunksRef.update(_ :+ chunk) *>
                  sessionHub.publish(ResponseChunk(sessionId, chunk, finished = false))
              }
          }
        } yield ()
      _ <- work
        .tapError(e => errorRef.update(_.orElse(Some(e.getMessage))))
        .ensuring(finaliseResponse(convId, sessionId, content, actorId, agentId, personality, errorRef, chunksRef))
    } yield ()

  private def logSessionEvent(
    sessionId: AgentSessionId,
    eventType: EventType,
    actorId:   Option[UserId],
  ): UIO[Unit] =
    Clock.instant.flatMap { now =>
      eventLogRepo
        .append(
          EventLog(
            id = EventLogId.empty,
            eventType = eventType,
            actorId = actorId,
            agentId = None,
            sessionId = Some(sessionId),
            resource = Some(sessionId),
            payloadJson = None,
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
    errorRef.get.flatMap { errMsg =>
      chunksRef.get.flatMap { chunks =>
        val fullResponse = chunks.mkString
        val sentinel = errMsg match {
          case Some(msg) => ResponseChunk(sessionId, msg, finished = true, isError = true)
          case None      => ResponseChunk(sessionId, "", finished = true)
        }
        ZIO.logDebug(
          s"[session:$sessionId] response finished${errMsg.map(e => s" with error: $e").getOrElse("")}",
        ) *>
          sessionHub.publish(sentinel) *>
          ConversationLogger.logAgentResponse(sessionId, fullResponse, isError = errMsg.isDefined) *>
          persistMessages(convId, sessionId, userContent, fullResponse, actorId, errMsg) *>
          runCheckpoint(sessionId, actorId, agentId, personality, userContent, fullResponse, errMsg) *>
          logSessionEvent(sessionId, EventType.AgentResponseCompleted, actorId)
      }
    }

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
          agentRepo
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
        if (alreadySeeded) ZIO.unit
        else
          loadConversationHistory(sessionId).flatMap { messages =>
            if (messages.isEmpty) ZIO.unit
            else {
              loadPersonality.flatMap { p =>
                val systemPrompt = Personality.buildSystemPrompt(p)
                modelGateway.seedHistory(sessionId, messages, systemPrompt)
              }
            }
          }
      }

  private def loadConversationHistory(sessionId: AgentSessionId): IO[JorlanError, List[Message]] =
    convRepo
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
          convRepo
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
          convRepo
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
                  convRepo
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
    if (errMsg.isDefined && assistantText.isBlank) ZIO.unit
    else
      Clock.instant.flatMap { now =>
        val userMsg = Message(MessageId.empty, convId, MessageRole.User, userText, None, now)
        val asstMsg = Message(MessageId.empty, convId, MessageRole.Assistant, assistantText, None, now)
        (convRepo.addMessage(userMsg) *> convRepo.addMessage(asstMsg))
          .mapError(JorlanError(_))
          .tapError(err => ZIO.logWarning(s"[session:$sessionId] Failed to persist messages: $err"))
          .ignore
      }
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
      if (errMsg.isDefined && assistantText.isBlank) ZIO.unit
      else {
        val now = java.time.Instant.now()
        val userMsg = Message(MessageId.empty, ConversationId.empty, MessageRole.User, userText, None, now)
        val asstMsg =
          Message(MessageId.empty, ConversationId.empty, MessageRole.Assistant, assistantText, None, now)
        memoryService
          .checkpoint(sessionId, List(userMsg, asstMsg), userId, agentId, CheckpointTrigger.SessionEnd)
          .tapError(err => ZIO.logWarning(s"[session:$sessionId] Checkpoint failed: $err"))
          .ignore
      }
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

  private val loadPersonality: UIO[Personality] =
    settingsRepo.get(ServerSettingsRepository.PersonalityKey).flatMap {
      case Some(json) =>
        json.as[Personality] match {
          case Right(p)  => ZIO.succeed(p)
          case Left(err) =>
            ZIO.logWarning(s"Corrupt personality in server_settings: $err. Using default.") *>
              ZIO.succeed(Personality.default)
        }
      case None => ZIO.succeed(Personality.default)
    }

}

object AgentRunnerImpl {

  val live: URLayer[
    ModelGateway & SessionHub & EventLogZIORepository & ServerSettingsRepository & ConversationZIORepository &
      AgentZIORepository & MemoryService,
    AgentRunner,
  ] =
    ZLayer.fromZIO(
      for {
        modelGateway  <- ZIO.service[ModelGateway]
        sessionHub    <- ZIO.service[SessionHub]
        eventLogRepo  <- ZIO.service[EventLogZIORepository]
        settingsRepo  <- ZIO.service[ServerSettingsRepository]
        convRepo      <- ZIO.service[ConversationZIORepository]
        agentRepo     <- ZIO.service[AgentZIORepository]
        memoryService <- ZIO.service[MemoryService]
        runnerState   <- AgentRunnerState.make
      } yield new AgentRunnerImpl(
        modelGateway,
        sessionHub,
        eventLogRepo,
        settingsRepo,
        convRepo,
        agentRepo,
        memoryService,
        runnerState,
      ),
    )

}
