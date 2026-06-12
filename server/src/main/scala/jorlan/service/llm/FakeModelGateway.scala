/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.llm

import jorlan.{AgentSessionId, Message, ModelId}
import jorlan
.*
import jorlan.service.*
import zio.*
import zio.stream.ZStream

/** A deterministic [[ModelGateway]] for tests. Returns a fixed sequence of chunks with an optional delay between each.
  *
  * @param chunks
  *   The tokens to emit in order (used by [[streamedResponse]] and as fallback for [[chatStep]]).
  * @param chunkDelay
  *   Optional pause between tokens (simulates streaming latency).
  * @param stepsRef
  *   If provided, successive [[chatStep]] calls pop from this list. When exhausted returns
  *   `FinalAnswer(ZStream.fromIterable(chunks))`.
  */
class FakeModelGateway(
  chunks:     List[String],
  chunkDelay: Option[Duration] = None,
  stepsRef:   Option[Ref[List[ChatStep]]] = None,
) extends ModelGateway {

  override def streamedResponse(
    sessionId:    AgentSessionId,
    message:      String,
    systemPrompt: String = "",
  ): ZStream[Any, ModelError, String] = {
    val base = ZStream.fromIterable(chunks)
    chunkDelay.fold(base)(d => base.tap(_ => ZIO.sleep(d)))
  }

  override def chatStep(
    sessionId: AgentSessionId,
    messages:  List[AgentMessage],
    tools:     List[ToolSpec],
  ): IO[ModelError, ChatStep] =
    stepsRef.fold(ZIO.succeed(FinalAnswer(ZStream.fromIterable(chunks)): ChatStep)) { ref =>
      ref.modify {
        case head :: tail => (head, tail)
        case Nil          => (FinalAnswer(ZStream.fromIterable(chunks)), Nil)
      }
    }

  override def availableModels: UIO[List[ModelInfo]] =
    ZIO.succeed(
      List(
        ModelInfo(
          id = ModelId("fake-model"),
          provider = "fake",
          contextWindow = 4096,
          supportsStreaming = true,
        ),
      ),
    )

  override def seedHistory(
                            sessionId:    AgentSessionId,
                            messages:     List[Message],
                            systemPrompt: String,
  ): UIO[Unit] = ZIO.unit

  override def invalidateSession(sessionId: AgentSessionId): UIO[Unit] = ZIO.unit

}

object FakeModelGateway {

  def layer(
    chunks:     List[String],
    chunkDelay: Option[Duration] = None,
  ): ULayer[ModelGateway] =
    ZLayer.succeed(FakeModelGateway(chunks, chunkDelay))

  /** Factory for a failing model gateway (for testing error paths). */
  def failingLayer(error: ModelError): ULayer[ModelGateway] =
    ZLayer.succeed(FailingFakeModelGateway(error))

  /** Creates a gateway that records every system prompt passed to [[streamedResponse]] into a [[Ref]]. Useful for
    * asserting that memory context or personality is injected correctly.
    */
  def capturingLayer(
    chunks:          List[String],
    capturedPrompts: Ref[List[String]],
  ): ULayer[ModelGateway] =
    ZLayer.succeed(CapturingFakeModelGateway(chunks, capturedPrompts))

  /** Creates a gateway that returns the given [[ChatStep]] values from successive [[chatStep]] calls.
    *
    * Use in unit tests to simulate multi-turn tool-calling without a real LLM. After the list is exhausted each
    * subsequent call returns `FinalAnswer(ZStream.empty)`.
    */
  def stepsLayer(steps: List[ChatStep]): ULayer[ModelGateway] =
    ZLayer.fromZIO(
      Ref.make(steps).map(ref => FakeModelGateway(chunks = Nil, stepsRef = Some(ref))),
    )

  /** Creates a gateway that records whether [[seedHistory]] was called. Use to assert that prior conversation history
    * is seeded when available.
    */
  def seedTrackingLayer(
    chunks:     List[String],
    seedCalled: Ref[Boolean],
  ): ULayer[ModelGateway] =
    ZLayer.succeed(new FakeModelGateway(chunks) {
      override def seedHistory(
                                sessionId:    AgentSessionId,
                                messages:     List[Message],
                                systemPrompt: String,
      ): UIO[Unit] = seedCalled.set(true)
    })

}

/** A [[ModelGateway]] that captures system prompts for assertion in tests. */
private class CapturingFakeModelGateway(
  chunks:          List[String],
  capturedPrompts: Ref[List[String]],
) extends ModelGateway {

  override def streamedResponse(
    sessionId:    AgentSessionId,
    message:      String,
    systemPrompt: String = "",
  ): ZStream[Any, ModelError, String] =
    ZStream.fromZIO(capturedPrompts.update(_ :+ systemPrompt)).drain ++
      ZStream.fromIterable(chunks)

  override def chatStep(
    sessionId: AgentSessionId,
    messages:  List[AgentMessage],
    tools:     List[ToolSpec],
  ): IO[ModelError, ChatStep] = {
    val systemContent = messages.collectFirst { case SystemMsg(c) => c }.getOrElse("")
    capturedPrompts.update(_ :+ systemContent) *>
      ZIO.succeed(FinalAnswer(ZStream.fromIterable(chunks)))
  }

  override def availableModels: UIO[List[ModelInfo]] =
    ZIO.succeed(List(ModelInfo(ModelId("fake-model"), "fake", 4096, supportsStreaming = true)))

  override def seedHistory(
    sessionId:    AgentSessionId,
    messages:     List[Message],
    systemPrompt: String,
  ): UIO[Unit] =
    ZIO.unit

  override def invalidateSession(sessionId: AgentSessionId): UIO[Unit] = ZIO.unit

}

/** A [[ModelGateway]] that always fails with the specified error. Used for testing error handling. */
private class FailingFakeModelGateway(error: ModelError) extends ModelGateway {

  override def streamedResponse(
    sessionId:    AgentSessionId,
    message:      String,
    systemPrompt: String = "",
  ): ZStream[Any, ModelError, String] = ZStream.fail(error)

  override def chatStep(
    sessionId: AgentSessionId,
    messages:  List[AgentMessage],
    tools:     List[ToolSpec],
  ): IO[ModelError, ChatStep] = ZIO.fail(error)

  override def availableModels: UIO[List[ModelInfo]] =
    ZIO.succeed(
      List(
        ModelInfo(
          id = ModelId("fake-model"),
          provider = "fake",
          contextWindow = 4096,
          supportsStreaming = true,
        ),
      ),
    )

  override def seedHistory(
                            sessionId:    AgentSessionId,
                            messages:     List[Message],
                            systemPrompt: String,
  ): UIO[Unit] = ZIO.unit

  override def invalidateSession(sessionId: AgentSessionId): UIO[Unit] = ZIO.unit

}
