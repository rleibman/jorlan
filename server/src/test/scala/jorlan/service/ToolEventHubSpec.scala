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
import zio.*
import zio.test.*
import zio.test.Assertion.*

object ToolEventHubSpec extends ZIOSpecDefault {

  private val sessionA = AgentSessionId(1L)
  private val sessionB = AgentSessionId(2L)

  private def invokedEvent(
    sessionId: AgentSessionId,
    toolName:  String,
  ): ToolEvent =
    ToolEvent.ToolInvokedEvent(sessionId, toolName, "{}")

  private def resultEvent(
    sessionId: AgentSessionId,
    toolName:  String,
    ok:        Boolean,
  ): ToolEvent =
    ToolEvent.ToolResultEvent(sessionId, toolName, "\"result\"", ok)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ToolEventHubSpec")(
      test("subscriber receives event for its session") {
        for {
          hub    <- ToolEventHub.make
          stream <- hub.subscribe(sessionA)
          event = invokedEvent(sessionA, "units.convert")
          fiber  <- stream.take(1).runCollect.fork
          _      <- hub.publish(event)
          result <- fiber.join
        } yield assert(result.toList)(hasSize(equalTo(1))) &&
          (result.head match {
            case ToolEvent.ToolInvokedEvent(sid, name, _) =>
              assertTrue(sid == sessionA && name == "units.convert")
            case _ => assertTrue(false)
          })
      },
      test("subscriber does not receive event for a different session") {
        for {
          hub     <- ToolEventHub.make
          streamA <- hub.subscribe(sessionA)
          // Subscribe to A, publish to B
          eventForB = invokedEvent(sessionB, "calendar.listEvents")
          eventForA = invokedEvent(sessionA, "units.convert")
          // start consuming from A
          fiber <- streamA.take(1).runCollect.fork
          // publish to B first (should not trigger A's stream)
          _ <- hub.publish(eventForB)
          // publish to A (should trigger A's stream)
          _      <- hub.publish(eventForA)
          result <- fiber.join
        } yield result.toList match {
          case List(ToolEvent.ToolInvokedEvent(_, "units.convert", _)) => assertCompletes
          case other                                                   => assert(other)(isEmpty)
        }
      },
      test("publish with no subscriber is a no-op") {
        for {
          hub <- ToolEventHub.make
          event = invokedEvent(sessionA, "noop.tool")
          _ <- hub.publish(event)
        } yield assertCompletes
      },
      test("multiple subscribers for same session both receive events") {
        for {
          hub     <- ToolEventHub.make
          stream1 <- hub.subscribe(sessionA)
          stream2 <- hub.subscribe(sessionA)
          event = resultEvent(sessionA, "calc.add", ok = true)
          fiber1  <- stream1.take(1).runCollect.fork
          fiber2  <- stream2.take(1).runCollect.fork
          _       <- hub.publish(event)
          result1 <- fiber1.join
          result2 <- fiber2.join
        } yield assertTrue(result1.head.sessionId == sessionA) &&
          assertTrue(result2.head.sessionId == sessionA)
      },
    )

}
