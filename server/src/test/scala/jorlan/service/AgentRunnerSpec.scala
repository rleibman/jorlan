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
import jorlan.db.repository.{EventLogZIORepository, ServerSettingsRepository}
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.stream.ZStream
import zio.test.*

object AgentRunnerSpec extends ZIOSpecDefault {

  private val sessionId = AgentSessionId(42L)
  private val userId = UserId(1L)

  private def layers(chunks: List[String]): ULayer[AgentRunner & SessionHub & EventLogZIORepository] = {
    val fakeGateway = FakeModelGateway.layer(chunks)
    val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
    val settingsRepo = InMemoryRepositories.InMemoryServerSettingsRepo.layer
    val hub = SessionHub.live
    (fakeGateway ++ hub ++ eventLogRepo ++ settingsRepo) >>> AgentRunnerImpl.live ++ hub ++ eventLogRepo
  }

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
      }.provide {
        val fakeGateway = FakeModelGateway.failingLayer(ModelUnavailable("offline"))
        val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
        val settingsRepo = InMemoryRepositories.InMemoryServerSettingsRepo.layer
        val hub = SessionHub.live
        (fakeGateway ++ hub ++ eventLogRepo ++ settingsRepo) >>> AgentRunnerImpl.live ++ hub ++ eventLogRepo
      },
      test("processMessage writes AgentResponseCompleted even on model failure") {
        for {
          _      <- runWithSubscription(Nil, "hi").catchAll(_ => ZIO.succeed(Chunk.empty))
          events <- ZIO.serviceWithZIO[EventLogZIORepository](_.search(EventLogFilter()))
        } yield assertTrue(events.exists(_.eventType == EventType.AgentResponseCompleted))
      }.provide {
        val fakeGateway = FakeModelGateway.failingLayer(ModelUnavailable("offline"))
        val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
        val settingsRepo = InMemoryRepositories.InMemoryServerSettingsRepo.layer
        val hub = SessionHub.live
        (fakeGateway ++ hub ++ eventLogRepo ++ settingsRepo) >>> AgentRunnerImpl.live ++ hub ++ eventLogRepo
      },
      test("FakeModelGateway with chunkDelay emits chunks in order") {
        val tokens = List("a", "b", "c")
        for {
          received <- runWithSubscription(tokens, "hi")
        } yield {
          val contents = received.toList.filterNot(_.finished).map(_.content)
          assertTrue(contents == tokens)
        }
      }.provide {
        val tokens = List("a", "b", "c")
        val fakeGateway = FakeModelGateway.layer(tokens, Some(5.millis))
        val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
        val settingsRepo = InMemoryRepositories.InMemoryServerSettingsRepo.layer
        val hub = SessionHub.live
        (fakeGateway ++ hub ++ eventLogRepo ++ settingsRepo) >>> AgentRunnerImpl.live ++ hub ++ eventLogRepo
      } @@ TestAspect.withLiveClock,
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
    )

}
