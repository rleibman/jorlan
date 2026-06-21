/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.client

import jorlan.{AgentSessionId, ResponseChunk}
import zio.*
import zio.stream.ZStream
import zio.test.*

object SubscriptionClientSpec extends ZIOSpecDefault {

  /** Stub implementation that returns a fixed sequence of chunks without a network connection. */
  private class StubSubscriptionClient(chunks: List[ResponseChunk]) extends SubscriptionClient {

    override def agentResponseStream(sessionId: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
      ZStream.fromIterable(chunks)

    override def toolEventsStream(
      sessionId: AgentSessionId,
    ): ZStream[Scope, String, jorlan.graphql.client.JorlanClient.ToolEventResult.ToolEventResultView] =
      ZStream.empty

  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SubscriptionClient")(
      test("companion agentResponseStream delegates to service") {
        val sessionId = AgentSessionId(1L)
        val expected = List(
          ResponseChunk(sessionId, "hello ", finished = false),
          ResponseChunk(sessionId, "world", finished = false),
          ResponseChunk(sessionId, "", finished = true),
        )
        val stub: SubscriptionClient = StubSubscriptionClient(expected)
        for {
          result <- ZIO.scoped(stub.agentResponseStream(sessionId).runCollect)
        } yield assertTrue(result.toList == expected)
      },
      test("agentResponseStream companion method type-checks correctly") {
        val stream: ZStream[SubscriptionClient & Scope, String, ResponseChunk] =
          SubscriptionClient.agentResponseStream(AgentSessionId(42L))
        assertCompletes
      },
      test("stub with no chunks yields an empty stream") {
        val stub: SubscriptionClient = StubSubscriptionClient(List.empty)
        for {
          result <- ZIO.scoped(stub.agentResponseStream(AgentSessionId(2L)).runCollect)
        } yield assertTrue(result.isEmpty)
      },
      test("stub preserves chunk order and finished flag") {
        val sid = AgentSessionId(3L)
        val chunks = List(
          ResponseChunk(sid, "a", finished = false),
          ResponseChunk(sid, "b", finished = false),
          ResponseChunk(sid, "", finished = true),
        )
        val stub: SubscriptionClient = StubSubscriptionClient(chunks)
        for {
          result <- ZIO.scoped(stub.agentResponseStream(sid).runCollect)
        } yield assertTrue(
          result.size == 3,
          result.last.finished,
          result.map(_.content).toList == List("a", "b", ""),
        )
      },
    )

}
