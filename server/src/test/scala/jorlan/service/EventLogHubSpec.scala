/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object EventLogHubSpec extends ZIOSpecDefault {

  private def makeEvent(eventType: EventType): EventLog[Json] =
    EventLog[Json](
      id = EventLogId.empty,
      eventType = eventType,
      actorId = None,
      agentId = None,
      sessionId = None,
      resource = None,
      payloadJson = None,
      occurredAt = Instant.now(),
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EventLogHubSpec")(
      test("subscriber receives published events") {
        for {
          hub    <- EventLogHub.make
          stream <- hub.subscribe
          event = makeEvent(EventType.AgentStarted)
          fiber  <- stream.take(1).runCollect.fork
          _      <- hub.publish(event)
          result <- fiber.join
        } yield assert(result.toList)(hasSize(equalTo(1))) &&
          assertTrue(result.head.eventType == EventType.AgentStarted)
      },
      test("publish with no subscribers is a no-op") {
        for {
          hub <- EventLogHub.make
          event = makeEvent(EventType.AgentStarted)
          _ <- hub.publish(event)
        } yield assertCompletes
      },
      test("multiple subscribers each receive the event") {
        for {
          hub     <- EventLogHub.make
          stream1 <- hub.subscribe
          stream2 <- hub.subscribe
          event = makeEvent(EventType.SessionCreated)
          fiber1  <- stream1.take(1).runCollect.fork
          fiber2  <- stream2.take(1).runCollect.fork
          _       <- hub.publish(event)
          result1 <- fiber1.join
          result2 <- fiber2.join
        } yield assertTrue(result1.head.eventType == EventType.SessionCreated) &&
          assertTrue(result2.head.eventType == EventType.SessionCreated)
      },
      test("publishTyped converts resource via JsonEncoder") {
        given JsonEncoder[String] = JsonEncoder.string
        for {
          hub    <- EventLogHub.make
          stream <- hub.subscribe
          typedEvent = EventLog[String](
            id = EventLogId.empty,
            eventType = EventType.MemoryWritten,
            actorId = None,
            agentId = None,
            sessionId = None,
            resource = Some("hello"),
            payloadJson = None,
            occurredAt = Instant.now(),
          )
          fiber  <- stream.take(1).runCollect.fork
          _      <- hub.publishTyped(typedEvent)
          result <- fiber.join
        } yield assertTrue(result.head.eventType == EventType.MemoryWritten) &&
          assertTrue(result.head.resource.contains(Json.Str("hello")))
      },
    )

}
