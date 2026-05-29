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

/** Runtime metadata for a model known to the gateway. */
case class ModelInfo(
  id:                ModelId,
  provider:          String,
  contextWindow:     Int,
  supportsStreaming: Boolean,
)

sealed abstract class ModelError(
  override val msg:   String,
  override val cause: Option[Throwable] = None,
) extends JorlanError(msg, cause)

case class ModelUnavailable(override val msg: String) extends ModelError(msg)
case class ModelTimeout(override val msg: String) extends ModelError(msg)
case class ModelResponseMalformed(override val msg: String) extends ModelError(msg)

/** Jorlan's boundary to LLM providers. Server code depends only on this trait — never on LangChain4j directly.
  *
  * The gateway maintains per-session in-memory chat history; calling [[streamedResponse]] for the same `sessionId`
  * continues the conversation.
  */
trait ModelGateway {

  def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ):                   ZStream[Any, ModelError, String]
  def availableModels: UIO[List[ModelInfo]]

}

object ModelGateway {

  def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ): ZStream[ModelGateway, ModelError, String] =
    ZStream.serviceWithStream[ModelGateway](_.streamedResponse(sessionId, message))

  def availableModels: URIO[ModelGateway, List[ModelInfo]] =
    ZIO.serviceWithZIO[ModelGateway](_.availableModels)

}
