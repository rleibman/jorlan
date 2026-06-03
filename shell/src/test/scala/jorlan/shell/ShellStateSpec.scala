/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import jorlan.domain.{AgentSessionId, ResponseChunk}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object ShellStateSpec extends ZIOSpecDefault {

  private val sessionId = AgentSessionId(42L)

  private def makeLiveSession(sid: AgentSessionId): UIO[LiveSession] =
    for {
      queue <- Queue.bounded[Either[String, Option[ResponseChunk]]](1)
      fiber <- ZIO.never.fork.map(identity[Fiber[Nothing, Unit]])
    } yield LiveSession(sid, queue, fiber)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ShellState")(
      test("getLiveSession returns None initially") {
        for {
          state  <- ZIO.service[ShellState]
          result <- state.getLiveSession
        } yield assertTrue(result.isEmpty)
      }.provide(ShellState.live),
      test("setLiveSession makes the session available") {
        for {
          state  <- ZIO.service[ShellState]
          ls     <- makeLiveSession(sessionId)
          _      <- state.setLiveSession(ls)
          result <- state.getLiveSession
          _      <- ls.subscriptionFiber.interrupt
        } yield assertTrue(result.map(_.sessionId).contains(sessionId))
      }.provide(ShellState.live),
      test("getSessionId returns None when no session is active") {
        for {
          state  <- ZIO.service[ShellState]
          result <- state.getSessionId
        } yield assertTrue(result.isEmpty)
      }.provide(ShellState.live),
      test("getSessionId returns the sessionId after setLiveSession") {
        for {
          state  <- ZIO.service[ShellState]
          ls     <- makeLiveSession(sessionId)
          _      <- state.setLiveSession(ls)
          result <- state.getSessionId
          _      <- ls.subscriptionFiber.interrupt
        } yield assertTrue(result.contains(sessionId))
      }.provide(ShellState.live),
      test("clearLiveSession removes the active session") {
        for {
          state  <- ZIO.service[ShellState]
          ls     <- makeLiveSession(sessionId)
          _      <- state.setLiveSession(ls)
          _      <- ls.subscriptionFiber.interrupt
          _      <- state.clearLiveSession
          result <- state.getLiveSession
        } yield assertTrue(result.isEmpty)
      }.provide(ShellState.live),
    )

}
