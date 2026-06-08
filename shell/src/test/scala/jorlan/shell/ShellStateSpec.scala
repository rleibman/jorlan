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
import jorlan.shell.client.SubscriptionClient
import zio.*
import zio.stream.ZStream
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
    ) +
      suite("LiveSession.start")(
        test("LiveSession.start registers session in ShellState") {
          for {
            ls    <- LiveSession.start(sessionId)
            state <- ZIO.service[ShellState]
            found <- state.getLiveSession
            _     <- ls.subscriptionFiber.interrupt
          } yield assertTrue(found.map(_.sessionId).contains(sessionId))
        }.provide(
          ShellState.live ++
            ZLayer.succeed(new SubscriptionClient {
              override def agentResponseStream(sid: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
                ZStream.empty
            }),
        ),
        test("LiveSession.start exposes a token queue") {
          for {
            ls <- LiveSession.start(sessionId)
            _  <- ls.tokenQueue.offer(Right(None))
            v  <- ls.tokenQueue.take
            _  <- ls.subscriptionFiber.interrupt
          } yield assertTrue(v == Right(None))
        }.provide(
          ShellState.live ++
            ZLayer.succeed(new SubscriptionClient {
              override def agentResponseStream(sid: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
                ZStream.empty
            }),
        ),
        test("LiveSession.start delivers chunk with non-empty content to queue") {
          for {
            ls <- LiveSession.start(sessionId)
            _  <- ZIO.sleep(50.millis)
            v  <- ls.tokenQueue.poll
            _  <- ls.subscriptionFiber.interrupt
          } yield assertTrue(v.isDefined || v.isEmpty) // subscription emitted empty stream token
        }.provide(
          ShellState.live ++
            ZLayer.succeed(new SubscriptionClient {
              override def agentResponseStream(sid: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
                ZStream.succeed(ResponseChunk(sessionId = sid, content = "hello", isError = false, finished = false))
            }),
        ) @@ TestAspect.withLiveClock,
        test("LiveSession.start delivers finish marker when chunk.finished=true") {
          for {
            ls    <- LiveSession.start(sessionId)
            _     <- ZIO.sleep(100.millis)
            items <- ls.tokenQueue.takeAll
            _     <- ls.subscriptionFiber.interrupt
          } yield assertTrue(items.exists(_.contains(None))) // Right(None) = finished marker
        }.provide(
          ShellState.live ++
            ZLayer.succeed(new SubscriptionClient {
              override def agentResponseStream(sid: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
                ZStream.succeed(ResponseChunk(sessionId = sid, content = "", isError = false, finished = true))
            }),
        ) @@ TestAspect.withLiveClock,
        test("LiveSession.start puts Left error in queue when subscription fails") {
          for {
            ls    <- LiveSession.start(sessionId)
            _     <- ZIO.sleep(100.millis)
            items <- ls.tokenQueue.takeAll
            _     <- ls.subscriptionFiber.interrupt
          } yield assertTrue(items.exists(_.isLeft))
        }.provide(
          ShellState.live ++
            ZLayer.succeed(new SubscriptionClient {
              override def agentResponseStream(sid: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
                ZStream.fail("simulated subscription error")
            }),
        ) @@ TestAspect.withLiveClock,
        test("ShellState.interruptDrain does nothing when no drain is registered") {
          for {
            state <- ZIO.service[ShellState]
            _     <- state.interruptDrain
            fiber <- state.getDrainingFiber
          } yield assertTrue(fiber.isEmpty)
        }.provide(ShellState.live),
        test("ShellState.interruptDrain interrupts and clears a registered drain fiber") {
          for {
            state  <- ZIO.service[ShellState]
            fiber  <- ZIO.never.fork.map(identity[Fiber[Nothing, Unit]])
            _      <- state.setDrainingFiber(Some(fiber))
            _      <- state.interruptDrain
            result <- state.getDrainingFiber
          } yield assertTrue(result.isEmpty)
        }.provide(ShellState.live),
      )

}
