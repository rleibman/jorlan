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

/** A deterministic [[ModelGateway]] for tests. Returns a fixed sequence of chunks with an optional delay between each.
  *
  * @param chunks
  *   The tokens to emit in order.
  * @param chunkDelay
  *   Optional pause between tokens (simulates streaming latency).
  */
class FakeModelGateway(
  chunks:     List[String],
  chunkDelay: Option[Duration] = None,
) extends ModelGateway {

  override def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ): ZStream[Any, ModelError, String] = {
    val base = ZStream.fromIterable(chunks)
    chunkDelay.fold(base)(d => base.tap(_ => ZIO.sleep(d)))
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

  override def invalidateSession(sessionId: AgentSessionId): UIO[Unit] = ZIO.unit

}

object FakeModelGateway {

  def layer(
    chunks:     List[String],
    chunkDelay: Option[Duration] = None,
  ): ULayer[ModelGateway] =
    ZLayer.succeed(new FakeModelGateway(chunks, chunkDelay))

  /** Factory for a failing model gateway (for testing error paths). */
  def failingLayer(error: ModelError): ULayer[ModelGateway] =
    ZLayer.succeed(new FailingFakeModelGateway(error))

}

/** A [[ModelGateway]] that always fails with the specified error. Used for testing error handling. */
private class FailingFakeModelGateway(error: ModelError) extends ModelGateway {

  override def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ): ZStream[Any, ModelError, String] = ZStream.fail(error)

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

  override def invalidateSession(sessionId: AgentSessionId): UIO[Unit] = ZIO.unit

}
