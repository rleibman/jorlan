/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

object KeyedPubSubHubSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("KeyedPubSubHubSpec")(
      test("subscriber receives published event for its key") {
        for {
          hub    <- KeyedPubSubHub.make[String, Int]
          stream <- hub.subscribe("a")
          fiber  <- stream.take(1).runCollect.fork
          _      <- hub.publish("a", 42)
          events <- fiber.join
        } yield assert(events.toList)(equalTo(List(42)))
      },
      test("subscriber does not receive events published to a different key") {
        for {
          hub     <- KeyedPubSubHub.make[String, Int]
          streamA <- hub.subscribe("a")
          fiberA  <- streamA.take(1).timeout(100.millis).runCollect.fork
          _       <- hub.publish("b", 99)
          eventsA <- fiberA.join
        } yield assert(eventsA.toList)(isEmpty)
      } @@ TestAspect.withLiveClock,
      test("multiple subscribers for same key all receive the event") {
        for {
          hub     <- KeyedPubSubHub.make[String, String]
          stream1 <- hub.subscribe("x")
          stream2 <- hub.subscribe("x")
          fiber1  <- stream1.take(1).runCollect.fork
          fiber2  <- stream2.take(1).runCollect.fork
          _       <- hub.publish("x", "hello")
          r1      <- fiber1.join
          r2      <- fiber2.join
        } yield assert(r1.toList)(equalTo(List("hello"))) &&
          assert(r2.toList)(equalTo(List("hello")))
      },
      test("publish does not fail when there are no subscribers") {
        for {
          hub <- KeyedPubSubHub.make[Int, String]
          _   <- hub.publish(1, "dropped")
        } yield assertCompletes
      },
      test("subscriber receives multiple events in order") {
        for {
          hub    <- KeyedPubSubHub.make[Unit, Int]
          stream <- hub.subscribe(())
          fiber  <- stream.take(3).runCollect.fork
          _      <- hub.publish((), 1)
          _      <- hub.publish((), 2)
          _      <- hub.publish((), 3)
          events <- fiber.join
        } yield assert(events.toList)(equalTo(List(1, 2, 3)))
      },
    )

}
