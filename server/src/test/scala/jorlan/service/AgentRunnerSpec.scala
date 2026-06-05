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
import jorlan.db.repository.{EventLogZIORepository, MemoryZIORepository, ServerSettingsRepository}
import jorlan.domain.*
import jorlan.testing.{InMemoryRepositories, NoOpMemoryService}
import zio.*
import zio.stream.ZStream
import zio.test.*

object AgentRunnerSpec extends ZIOSpecDefault {

  private val sessionId = AgentSessionId(42L)
  private val userId = UserId(1L)

  private def layers(
    chunks: List[String],
    delay:  Option[Duration] = None,
  ): ULayer[AgentRunner & SessionHub & EventLogZIORepository] =
    ZLayer.make[AgentRunner & SessionHub & EventLogZIORepository](
      FakeModelGateway.layer(chunks, delay),
      SessionHub.live,
      InMemoryRepositories.InMemoryEventLogRepo.layer,
      InMemoryRepositories.InMemoryServerSettingsRepo.layer,
      InMemoryRepositories.InMemoryConversationRepo.layer,
      InMemoryRepositories.InMemoryAgentRepo.layer,
      NoOpMemoryService.layer,
      AgentRunnerImpl.live,
    )

  private val failingLayers: ULayer[AgentRunner & SessionHub & EventLogZIORepository] =
    ZLayer.make[AgentRunner & SessionHub & EventLogZIORepository](
      FakeModelGateway.failingLayer(ModelUnavailable("offline")),
      SessionHub.live,
      InMemoryRepositories.InMemoryEventLogRepo.layer,
      InMemoryRepositories.InMemoryServerSettingsRepo.layer,
      InMemoryRepositories.InMemoryConversationRepo.layer,
      InMemoryRepositories.InMemoryAgentRepo.layer,
      NoOpMemoryService.layer,
      AgentRunnerImpl.live,
    )

  /** Subscribe to the session (eagerly registering the queue), then fork the stream drain, then run processMessage.
    *
    * The eager `subscribeToSession` call ensures the queue exists before `processMessage` publishes any tokens.
    */
  private def runWithSubscription(
    chunks:  List[String],
    message: String,
  ): ZIO[AgentRunner & SessionHub & EventLogZIORepository, Any, Chunk[ResponseChunk]] =
    for {
      connId <- ConnectionId.randomZIO
      stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
      fiber  <- stream.takeUntil(_.finished).runCollect.fork
      _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, message, Some(userId)))
      result <- fiber.join
    } yield result

  override def spec: Spec[TestEnvironment & Scope, Any] =
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
      }.provide(layers(List("hello", " ", "world"))),
      test("processMessage writes UserMessageReceived and AgentResponseCompleted events") {
        for {
          _      <- runWithSubscription(List("ok"), "test")
          events <- ZIO.serviceWithZIO[EventLogZIORepository](_.search(EventLogFilter()))
        } yield assertTrue(
          events.exists(_.eventType == EventType.UserMessageReceived),
          events.exists(_.eventType == EventType.AgentResponseCompleted),
        )
      }.provide(layers(List("ok"))),
      test("processMessage sets sessionId on event log entries") {
        for {
          _      <- runWithSubscription(List("pong"), "ping")
          events <- ZIO.serviceWithZIO[EventLogZIORepository](_.search(EventLogFilter()))
          msgEvents = events.filter(_.sessionId.contains(sessionId))
        } yield assertTrue(msgEvents.nonEmpty)
      }.provide(layers(List("pong"))),
      test("processMessage with empty chunk list still publishes finished sentinel") {
        for {
          received <- runWithSubscription(Nil, "hi")
        } yield assertTrue(received.length == 1, received.head.finished)
      }.provide(layers(Nil)),
      test("processMessage publishes finished sentinel on ModelGateway failure") {
        for {
          connId   <- ConnectionId.randomZIO
          stream   <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
          fiber    <- stream.takeUntil(_.finished).runCollect.fork
          _        <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "hi", Some(userId))).ignore
          received <- fiber.join
        } yield assertTrue(received.nonEmpty, received.last.finished, received.last.isError)
      }.provide(failingLayers),
      test("processMessage writes AgentResponseCompleted even on model failure") {
        for {
          _      <- runWithSubscription(Nil, "hi").catchAll(_ => ZIO.succeed(Chunk.empty))
          events <- ZIO.serviceWithZIO[EventLogZIORepository](_.search(EventLogFilter()))
        } yield assertTrue(events.exists(_.eventType == EventType.AgentResponseCompleted))
      }.provide(failingLayers),
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
      }.provide(layers(List("a", "b", "c"), Some(5.millis))) @@ TestAspect.withLiveClock,
      suite("service methods")(
        test("processMessage delegates to implementation") {
          for {
            connId <- ConnectionId.randomZIO
            stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
            fiber  <- stream.takeUntil(_.finished).runCollect.fork
            _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "hello", Some(userId)))
            _      <- fiber.join
          } yield assertCompletes
        }.provide(layers(List("ok"))),
        test("subscribeToSession delegates to implementation") {
          for {
            connId <- ConnectionId.randomZIO
            stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
            _      <- stream.take(0).runDrain
          } yield assertCompletes
        }.provide(layers(Nil)),
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
      }.provide(layers(List("resp"))),
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
      }.provide(layers(List("a", "b"))),
      test("processMessage with stored personality reads it from settings") {
        import jorlan.db.repository.ServerSettingsRepository
        import zio.json.ast.Json
        for {
          _ <- ZIO.serviceWithZIO[ServerSettingsRepository](
            _.set(
              ServerSettingsRepository.PersonalityKey,
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
      }.provide {
        val fakeGateway = FakeModelGateway.layer(List("ok"))
        val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
        val settingsRepo = InMemoryRepositories.InMemoryServerSettingsRepo.layer
        val convRepo = InMemoryRepositories.InMemoryConversationRepo.layer
        val agentRepo = InMemoryRepositories.InMemoryAgentRepo.layer
        val memService = NoOpMemoryService.layer
        val hub = SessionHub.live
        (fakeGateway ++ hub ++ eventLogRepo ++ settingsRepo ++ convRepo ++ agentRepo ++ memService) >>>
          AgentRunnerImpl.live ++ hub ++ eventLogRepo ++ settingsRepo
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
          memRepo         <- InMemoryRepositories.InMemoryMemoryRepo.make
          _               <- memRepo.upsert(memRecord)
          memSvc = {
            val policy = ZLayer.succeed(MemoryAccessPolicyImpl(): MemoryAccessPolicy)
            val summarizer = ZLayer.succeed(new CheckpointSummarizer {
              override def summarize(
                msgs: List[Message],
                uid:  UserId,
                aid:  AgentId,
              ) = ZIO.succeed(Nil)
            }: CheckpointSummarizer)
            val classifier = ZLayer.succeed(MemoryClassifierImpl(): MemoryClassifier)
            val cpPolicy = ZLayer.succeed(CheckpointPolicy.onSessionEnd)
            val repo = ZLayer.succeed(memRepo: MemoryZIORepository)
            (repo ++ policy ++ summarizer ++ classifier ++ cpPolicy) >>> MemoryServiceImpl.live
          }
          eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
          settingsRepo = InMemoryRepositories.InMemoryServerSettingsRepo.layer
          convRepo = InMemoryRepositories.InMemoryConversationRepo.layer
          agentRepo = InMemoryRepositories.InMemoryAgentRepo.layer
          hub = SessionHub.live
          capturingGw = FakeModelGateway.capturingLayer(List("ok"), capturedPrompts)
          runnerLayer = (capturingGw ++ hub ++ eventLogRepo ++ settingsRepo ++ convRepo ++ agentRepo ++ memSvc) >>>
            AgentRunnerImpl.live ++ hub
          result <- (for {
            connId  <- ConnectionId.randomZIO
            stream  <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
            fiber   <- stream.takeUntil(_.finished).runCollect.fork
            _       <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "Scala", Some(userId)))
            _       <- fiber.join
            prompts <- capturedPrompts.get
          } yield prompts).provide(runnerLayer)
        } yield assertTrue(result.exists(_.contains("User prefers Scala")))
      },
    )

}
