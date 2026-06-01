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
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.stream.ZStream
import zio.test.*

object AgentRunnerSpec extends ZIOSpecDefault {

  private val sessionId = AgentSessionId(42L)
  private val userId = UserId(1L)

  private def layers(chunks: List[String]): ULayer[AgentRunner & SessionHub & EventLogService] = {
    val fakeGateway = FakeModelGateway.layer(chunks)
    val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
    val eventLog = eventLogRepo >>> EventLogServiceImpl.live
    val hub = SessionHub.live
    (fakeGateway ++ hub ++ eventLog) >>> AgentRunnerImpl.live ++ hub ++ eventLog
  }

  /** Pre-creates the hub subscription BEFORE triggering processMessage to avoid publish-before-subscribe races. */
  private def runWithSubscription(
    chunks:  List[String],
    message: String,
  ): ZIO[AgentRunner & SessionHub & EventLogService, Any, Chunk[ResponseChunk]] =
    ZIO.scoped {
      for {
        hub      <- ZIO.service[SessionHub]
        innerHub <- hub.getOrCreate(sessionId)
        dequeue  <- innerHub.subscribe
        fiber    <- ZStream.fromQueue(dequeue).takeUntil(_.finished).runCollect.forkScoped
        _        <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, message, Some(userId)))
        result   <- fiber.join
      } yield result
    }

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
          events <- ZIO.serviceWithZIO[EventLogService](_.query(EventLogFilter()))
        } yield assertTrue(
          events.exists(_.eventType == EventType.UserMessageReceived),
          events.exists(_.eventType == EventType.AgentResponseCompleted),
        )
      }.provide(layers(List("ok"))),
      test("processMessage sets sessionId on event log entries") {
        for {
          _      <- runWithSubscription(List("pong"), "ping")
          events <- ZIO.serviceWithZIO[EventLogService](_.query(EventLogFilter()))
          msgEvents = events.filter(_.sessionId.contains(sessionId))
        } yield assertTrue(msgEvents.nonEmpty)
      }.provide(layers(List("pong"))),
      test("processMessage with empty chunk list still publishes finished sentinel") {
        for {
          received <- runWithSubscription(Nil, "hi")
        } yield assertTrue(received.length == 1, received.head.finished)
      }.provide(layers(Nil)),
      test("processMessage publishes finished sentinel on ModelGateway failure") {
        // Must NOT use runWithSubscription here: forkScoped would be interrupted when the
        // scope exits on processMessage failure, before the fiber reads the sentinel.
        // Instead, use .ignore so the for-comprehension continues to fiber.join.
        ZIO.scoped {
          for {
            hub      <- ZIO.service[SessionHub]
            innerHub <- hub.getOrCreate(sessionId)
            dequeue  <- innerHub.subscribe
            fiber    <- ZStream.fromQueue(dequeue).takeUntil(_.finished).runCollect.forkScoped
            _        <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, "hi", Some(userId))).ignore
            received <- fiber.join
          } yield assertTrue(received.nonEmpty, received.last.finished, received.last.isError)
        }
      }.provide {
        val fakeGateway = FakeModelGateway.failingLayer(ModelUnavailable("offline"))
        val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
        val eventLog = eventLogRepo >>> EventLogServiceImpl.live
        val hub = SessionHub.live
        (fakeGateway ++ hub ++ eventLog) >>> AgentRunnerImpl.live ++ hub ++ eventLog
      },
      test("processMessage writes AgentResponseCompleted even on model failure") {
        for {
          _      <- runWithSubscription(Nil, "hi").catchAll(_ => ZIO.succeed(Chunk.empty))
          events <- ZIO.serviceWithZIO[EventLogService](_.query(EventLogFilter()))
        } yield assertTrue(events.exists(_.eventType == EventType.AgentResponseCompleted))
      }.provide {
        val fakeGateway = FakeModelGateway.failingLayer(ModelUnavailable("offline"))
        val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
        val eventLog = eventLogRepo >>> EventLogServiceImpl.live
        val hub = SessionHub.live
        (fakeGateway ++ hub ++ eventLog) >>> AgentRunnerImpl.live ++ hub ++ eventLog
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
        // Use withLiveClock so ZIO.sleep inside FakeModelGateway uses real time,
        // not the frozen TestClock (which would cause this test to hang forever).
        val tokens = List("a", "b", "c")
        val fakeGateway = FakeModelGateway.layer(tokens, Some(5.millis))
        val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
        val eventLog = eventLogRepo >>> EventLogServiceImpl.live
        val hub = SessionHub.live
        (fakeGateway ++ hub ++ eventLog) >>> AgentRunnerImpl.live ++ hub ++ eventLog
      } @@ TestAspect.withLiveClock,
    )

}
