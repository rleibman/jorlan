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
import jorlan.db.repository.{ZIOEventLogRepository, ZIOMemoryRepository, ZIORepositories, ZIOServerSettingsRepository}
import jorlan.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.skills.SkillRegistry
import jorlan.testing.{FakeConfigurationService, InMemoryRepositories, NoOpMemoryService}
import zio.*
import zio.stream.ZStream
import zio.test.*

object AgentRunnerSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val sessionId = AgentSessionId(42L)
  private val userId = UserId(1L)

  private def layers(
    chunks: List[String],
    delay:  Option[Duration] = None,
  ): URLayer[ZIORepositories, AgentRunner & SessionHub] =
    ZLayer
      .makeSome[ZIORepositories, AgentRunner & SessionHub](
        FakeModelGateway.layer(chunks, delay),
        SessionHub.live,
        ToolEventHub.live,
        NoOpMemoryService.layer,
        SkillRegistry.live,
        FakeConfigurationService.layer,
        AgentRunnerImpl.live,
      ).orDie

  private val failingLayers: URLayer[ZIORepositories, AgentRunner & SessionHub] = {
    ZLayer
      .makeSome[ZIORepositories, AgentRunner & SessionHub](
        FakeModelGateway.failingLayer(ModelUnavailable("offline")),
        SessionHub.live,
        ToolEventHub.live,
        NoOpMemoryService.layer,
        SkillRegistry.live,
        FakeConfigurationService.layer,
        AgentRunnerImpl.live,
      ).orDie
  }

  /** Subscribe to the session (eagerly registering the queue), then fork the stream drain, then run processMessage.
    *
    * The eager `subscribeToSession` call ensures the queue exists before `processMessage` publishes any tokens.
    */
  private def runWithSubscription(
    chunks:  List[String],
    message: String,
  ): ZIO[AgentRunner & SessionHub & ZIORepositories, Any, Chunk[ResponseChunk]] =
    for {
      connId <- ConnectionId.randomZIO
      stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
      fiber  <- stream.takeUntil(_.finished).runCollect.fork
      _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, message, Some(userId)))
      result <- fiber.join
    } yield result

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("AgentRunner")(
      test("processMessage publishes all chunks then a finished sentinel") {
        val tokens = List("hello", " ", "world")
        for {
          received <- runWithSubscription(tokens, "hi")
        } yield {
          val contents = received.toList.filterNot(_.finished).map(_.content)
          val terminal = received.last
          assertTrue(
            contents == tokens,
            terminal.finished,
            terminal.content == "",
          )
        }
      }.provideSome[ZIORepositories](layers(List("hello", " ", "world"))),
      test("processMessage writes UserMessageReceived and AgentResponseCompleted events") {
        for {
          _      <- runWithSubscription(List("ok"), "test")
          events <- ZIO.serviceWithZIO[ZIORepositories](_.eventLog.search(EventLogFilter()))
        } yield assertTrue(
          events.exists(_.eventType == EventType.UserMessageReceived),
          events.exists(_.eventType == EventType.AgentResponseCompleted),
        )
      }.provideSome[ZIORepositories](layers(List("ok"))),
      test("processMessage sets sessionId on event log entries") {
        for {
          _      <- runWithSubscription(List("pong"), "ping")
          events <- ZIO.serviceWithZIO[ZIORepositories](_.eventLog.search(EventLogFilter()))
          msgEvents = events.filter(_.sessionId.contains(sessionId))
        } yield assertTrue(msgEvents.nonEmpty)
      }.provideSome[ZIORepositories](layers(List("pong"))),
      test("processMessage with empty chunk list still publishes finished sentinel") {
        for {
          received <- runWithSubscription(Nil, "hi")
        } yield assertTrue(received.length == 1, received.head.finished)
      }.provideSome[ZIORepositories](layers(Nil)),
      test("processMessage publishes finished sentinel on ModelGateway failure") {
        for {
          connId   <- ConnectionId.randomZIO
          stream   <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
          fiber    <- stream.takeUntil(_.finished).runCollect.fork
          _        <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "hi", Some(userId))).ignore
          received <- fiber.join
        } yield assertTrue(received.nonEmpty, received.last.finished, received.last.isError)
      }.provideSome[ZIORepositories](failingLayers),
      test("processMessage writes AgentResponseCompleted even on model failure") {
        for {
          _      <- runWithSubscription(Nil, "hi").catchAll(_ => ZIO.succeed(Chunk.empty))
          events <- ZIO.serviceWithZIO[ZIORepositories](_.eventLog.search(EventLogFilter()))
        } yield assertTrue(events.exists(_.eventType == EventType.AgentResponseCompleted))
      }.provideSome[ZIORepositories](failingLayers),
      test("FakeModelGateway.availableModels returns fake model list") {
        for {
          gw     <- ZIO.service[ModelGateway]
          models <- gw.availableModels
        } yield assertTrue(models.exists(_.id == ModelId("fake-model")))
      }.provide(FakeModelGateway.layer(Nil)),
      test("FailingFakeModelGateway.availableModels returns fake model list") {
        for {
          gw     <- ZIO.service[ModelGateway]
          models <- gw.availableModels
        } yield assertTrue(models.exists(_.id == ModelId("fake-model")))
      }.provide(FakeModelGateway.failingLayer(ModelUnavailable("offline"))),
      test("FakeModelGateway with chunkDelay emits chunks in order") {
        val tokens = List("a", "b", "c")
        for {
          received <- runWithSubscription(tokens, "hi")
        } yield {
          val contents = received.toList.filterNot(_.finished).map(_.content)
          assertTrue(contents == tokens)
        }
      }.provideSome[ZIORepositories](layers(List("a", "b", "c"), Some(5.millis))) @@ TestAspect.withLiveClock,
      suite("service methods")(
        test("processMessage delegates to implementation") {
          for {
            connId <- ConnectionId.randomZIO
            stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
            fiber  <- stream.takeUntil(_.finished).runCollect.fork
            _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "hello", Some(userId)))
            _      <- fiber.join
          } yield assertCompletes
        }.provideSome[ZIORepositories](layers(List("ok"))),
        test("subscribeToSession delegates to implementation") {
          for {
            connId <- ConnectionId.randomZIO
            stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
            _      <- stream.take(0).runDrain
          } yield assertCompletes
        }.provideSome[ZIORepositories](layers(Nil)),
      ),
      // P85-034: actorId = None path
      test("processMessage with actorId=None succeeds and publishes sentinel") {
        for {
          connId <- ConnectionId.randomZIO
          stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
          fiber  <- stream.takeUntil(_.finished).runCollect.fork
          _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "anonymous", actorId = None))
          result <- fiber.join
        } yield assertTrue(result.last.finished)
      }.provideSome[ZIORepositories](layers(List("resp"))),
      test("second processMessage reuses seeded session and conversation (cache hit)") {
        val twoChunks = List("a", "b")
        for {
          connId <- ConnectionId.randomZIO
          stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
          fiber  <- stream.takeUntil(_.finished).runCollect.fork
          _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "first", Some(userId)))
          _      <- fiber.join
          // Second call for same session — exercises seeded/activeConvs cache hits
          connId2 <- ConnectionId.randomZIO
          stream2 <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId2))
          fiber2  <- stream2.takeUntil(_.finished).runCollect.fork
          _       <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "second", Some(userId)))
          result2 <- fiber2.join
        } yield assertTrue(result2.last.finished)
      }.provideSome[ZIORepositories](layers(List("a", "b"))),
      test("processMessage with stored personality reads it from settings") {
        import zio.json.ast.Json
        for {
          _ <- ZIO.serviceWithZIO[ZIORepositories](
            _.setting.set(
              ZIOServerSettingsRepository.PersonalityKey,
              Json.Obj(
                "name"      -> Json.Str("TestBot"),
                "formality" -> Json.Str("Casual"),
                "languages" -> Json.Arr(Json.Str("en")),
                "expertise" -> Json.Arr(),
                "prompt"    -> Json.Str("Be concise."),
              ),
            ),
          )
          connId <- ConnectionId.randomZIO
          stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
          fiber  <- stream.takeUntil(_.finished).runCollect.fork
          _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "hello", Some(userId)))
          result <- fiber.join
        } yield assertTrue(result.last.finished)
      }.provide(
        InMemoryRepositories.live(),
        FakeModelGateway.layer(List("ok")),
        NoOpMemoryService.layer,
        SkillRegistry.live,
        FakeConfigurationService.layer,
        AgentRunnerImpl.live,
        SessionHub.live,
        ToolEventHub.live,
      ),
      // P12-027: ensureSeeded calls seedHistory when prior conversation messages exist
      test("ensureSeeded calls seedHistory when prior conversation history is non-empty") {
        import java.time.Instant
        for {
          seedCalled <- Ref.make(false)
          convRepo   <- InMemoryRepositories.InMemoryConversationRepo.make
          // Pre-populate: create a conversation + message for this session
          now = Instant.now()
          conv <- convRepo.create(Conversation(ConversationId.empty, sessionId, now)).orDie
          _    <- convRepo
            .addMessage(Message(MessageId.empty, conv.id, MessageRole.User, "prior message", None, now)).orDie
          repos = InMemoryRepositories.live(conversationRepoOpt = Some(convRepo))
          _ <- ZIO
            .serviceWithZIO[AgentRunner](_.processMessage(sessionId, "new message", Some(userId)))
            .provide(
              AgentRunnerImpl.live,
              FakeModelGateway.seedTrackingLayer(List("ok"), seedCalled),
              SessionHub.live,
              ToolEventHub.live,
              NoOpMemoryService.layer,
              SkillRegistry.live,
              FakeConfigurationService.layer,
              repos,
            )
          wasCalled <- seedCalled.get
        } yield assertTrue(wasCalled)
      },
      // P12-028: getOrCreateConversation reuses an existing DB conversation (cache-miss path)
      test("getOrCreateConversation reuses existing DB conversation on cache-miss") {
        import java.time.Instant
        for {
          convRepo <- InMemoryRepositories.InMemoryConversationRepo.make
          now = Instant.now()
          existing <- convRepo.create(Conversation(ConversationId.empty, sessionId, now)).orDie
          repos = InMemoryRepositories.live(conversationRepoOpt = Some(convRepo))
          _ <- ZIO
            .serviceWithZIO[AgentRunner](_.processMessage(sessionId, "hello", Some(userId)))
            .provide(
              AgentRunnerImpl.live,
              FakeModelGateway.layer(List("ok")),
              SessionHub.live,
              ToolEventHub.live,
              NoOpMemoryService.layer,
              SkillRegistry.live,
              FakeConfigurationService.layer,
              repos,
            )
          allConvs <- convRepo.search(ConversationSearch(sessionId = sessionId, pageSize = 10)).orDie
        } yield assertTrue(
          allConvs.exists(_.id == existing.id),
          allConvs.length == 1, // No new conversation was created
        )
      },
      test("processMessage injects pre-stored memory records into system prompt") {
        import jorlan.service.*
        import jorlan.testing.InMemoryRepositories
        import zio.json.ast.Json

        import java.time.Instant
        val memRecord = MemoryRecord(
          id = MemoryRecordId.empty,
          scope = MemoryScope.User,
          userId = Some(userId),
          workspaceId = None,
          agentId = Some(AgentId(1L)),
          recordKey = "user.lang",
          value = Json.Obj("text" -> Json.Str("User prefers Scala")),
          ttl = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for {
          capturedPrompts <- Ref.make(List.empty[String])
          memRepo         <- ZIO.serviceWithZIO[ZIORepositories](_.memory.upsert(memRecord))
          result          <- (for {
            connId  <- ConnectionId.randomZIO
            stream  <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
            fiber   <- stream.takeUntil(_.finished).runCollect.fork
            _       <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "Scala", Some(userId)))
            _       <- fiber.join
            prompts <- capturedPrompts.get
          } yield prompts).provideSome[ZIORepositories](
            FakeModelGateway.capturingLayer(List("ok"), capturedPrompts),
            SessionHub.live,
            ToolEventHub.live,
            SkillRegistry.live,
            FakeConfigurationService.layer,
            AgentRunnerImpl.live,
            MemoryServiceImpl.live,
          )
        } yield assertTrue(result.exists(_.contains("User prefers Scala")))
      },
    )

}
