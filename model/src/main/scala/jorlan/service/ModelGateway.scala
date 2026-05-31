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
import zio.*
import zio.stream.ZStream

/** Runtime metadata for a model known to the gateway.
  *
  * @param id
  *   Opaque model identifier (e.g. `"llama3.2:3b"`).
  * @param provider
  *   Provider name (e.g. `"ollama"`, `"openai"`).
  * @param contextWindow
  *   Maximum token context the model supports.
  * @param supportsStreaming
  *   Whether the provider exposes a streaming (token-by-token) response API.
  */
case class ModelInfo(
  id:                ModelId,
  provider:          String,
  contextWindow:     Int,
  supportsStreaming: Boolean,
)

/** Root error type for all model-call failures. */
sealed abstract class ModelError(
  override val msg:   String,
  override val cause: Option[Throwable] = None,
) extends JorlanError(msg, cause)

/** The LLM provider is unreachable or returned a non-retryable connection error. */
case class ModelUnavailable(override val msg: String) extends ModelError(msg)

/** The model call exceeded the configured timeout. */
case class ModelTimeout(override val msg: String) extends ModelError(msg)

/** The model returned a response that could not be parsed into the expected format. */
case class ModelResponseMalformed(override val msg: String) extends ModelError(msg)

/** Jorlan's boundary to LLM providers. Server code depends only on this trait — never on LangChain4j directly.
  *
  * The gateway maintains per-session in-memory chat history; calling [[streamedResponse]] for the same `sessionId`
  * continues the conversation.
  */
trait ModelGateway {

  /** Streams the model response token-by-token for the given session.
    *
    * @param sessionId
    *   The active session; chat history is keyed on this ID.
    * @param message
    *   The user message to send to the model.
    * @return
    *   A ZStream of string tokens. Callers treat the concatenation of all emitted tokens as the full response.
    */
  def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ): ZStream[Any, ModelError, String]

  /** Returns metadata for all models the gateway can currently route to. */
  def availableModels: UIO[List[ModelInfo]]

  /** Release all in-process resources (chat memory, connection pools) held for `sessionId`.
    *
    * Must be called on session termination to prevent unbounded growth of the per-session map.
    */
  def invalidateSession(sessionId: AgentSessionId): UIO[Unit]

}

object ModelGateway {

  def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ): ZStream[ModelGateway, ModelError, String] =
    ZStream.serviceWithStream[ModelGateway](_.streamedResponse(sessionId, message))

  def availableModels: URIO[ModelGateway, List[ModelInfo]] =
    ZIO.serviceWithZIO[ModelGateway](_.availableModels)

  def invalidateSession(sessionId: AgentSessionId): URIO[ModelGateway, Unit] =
    ZIO.serviceWithZIO[ModelGateway](_.invalidateSession(sessionId))

}
