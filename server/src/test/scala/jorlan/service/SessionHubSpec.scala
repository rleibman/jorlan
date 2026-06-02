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

import jorlan.domain.*
import zio.*
import zio.stream.ZStream
import zio.test.*

object SessionHubSpec extends ZIOSpecDefault {

  private val sid1 = AgentSessionId(1L)
  private val sid2 = AgentSessionId(2L)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SessionHub")(
      test("getOrCreate returns the same hub on repeated calls") {
        for {
          hub <- ZIO.service[SessionHub]
          h1  <- hub.getOrCreate(sid1)
          h2  <- hub.getOrCreate(sid1)
        } yield assertTrue(h1 eq h2)
      }.provide(SessionHub.live),
      test("publish delivers chunks to subscriber") {
        val chunks = List(
          ResponseChunk(sid1, "hello", false),
          ResponseChunk(sid1, " world", false),
          ResponseChunk(sid1, "", true),
        )
        for {
          hub      <- ZIO.service[SessionHub]
          _        <- hub.getOrCreate(sid1) // pre-create so publish doesn't race the forked stream
          fiber    <- hub.subscribe(sid1).runCollect.fork
          _        <- ZIO.foreachDiscard(chunks)(hub.publish)
          received <- fiber.join
        } yield assertTrue(received.toList == chunks)
      }.provide(SessionHub.live),
      test("late subscriber gets all buffered chunks (Queue semantics)") {
        val chunks = List(
          ResponseChunk(sid1, "a", false),
          ResponseChunk(sid1, "b", false),
          ResponseChunk(sid1, "", true),
        )
        for {
          hub <- ZIO.service[SessionHub]
          _   <- hub.getOrCreate(sid1)
          // Publish everything before subscribing — chunks must not be lost
          _        <- ZIO.foreachDiscard(chunks)(hub.publish)
          received <- hub.subscribe(sid1).runCollect
        } yield assertTrue(received.toList == chunks)
      }.provide(SessionHub.live),
      test("subscribe stream terminates on finished sentinel") {
        for {
          hub    <- ZIO.service[SessionHub]
          _      <- hub.getOrCreate(sid1)
          fiber  <- hub.subscribe(sid1).runCollect.fork
          _      <- hub.publish(ResponseChunk(sid1, "a", false))
          _      <- hub.publish(ResponseChunk(sid1, "", true))
          result <- fiber.join
        } yield assertTrue(result.length == 2, result.last.finished)
      }.provide(SessionHub.live),
      test("distinct sessions have independent hubs") {
        for {
          hub <- ZIO.service[SessionHub]
          h1  <- hub.getOrCreate(sid1)
          h2  <- hub.getOrCreate(sid2)
        } yield assertTrue(!(h1 eq h2))
      }.provide(SessionHub.live),
      test("remove clears the hub entry so a fresh hub is created next time") {
        for {
          hub <- ZIO.service[SessionHub]
          h1  <- hub.getOrCreate(sid1)
          _   <- hub.remove(sid1)
          h2  <- hub.getOrCreate(sid1)
        } yield assertTrue(!(h1 eq h2))
      }.provide(SessionHub.live),
      test("publish to non-existent session is a no-op (does not fail)") {
        for {
          hub <- ZIO.service[SessionHub]
          _   <- hub.publish(ResponseChunk(AgentSessionId(999L), "test", false))
        } yield assertTrue(true)
      }.provide(SessionHub.live),
      test("subscribe returns chunks in insertion order") {
        val chunks = List(
          ResponseChunk(sid2, "first", false),
          ResponseChunk(sid2, "second", false),
          ResponseChunk(sid2, "third", false),
          ResponseChunk(sid2, "", true),
        )
        for {
          hub      <- ZIO.service[SessionHub]
          _        <- hub.getOrCreate(sid2)
          fiber    <- hub.subscribe(sid2).runCollect.fork
          _        <- ZIO.foreachDiscard(chunks)(hub.publish)
          received <- fiber.join
        } yield assertTrue(received.toList == chunks)
      }.provide(SessionHub.live),
    )

}
