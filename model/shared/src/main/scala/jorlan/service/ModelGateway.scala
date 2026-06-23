/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.stream.ZStream

/** A tool the model may call, expressed as raw JSON Schema (serialized to string at the model boundary).
  *
  * This type lives in the `model` module so the `ModelGateway` trait can reference it without creating a circular
  * dependency on `connector-api`. Callers in `server` convert [[jorlan.connector.ToolDescriptor]] → `ToolSpec`.
  */
case class ToolSpec(
  name:            String,
  description:     String,
  inputSchemaJson: String,
)

/** A message in an explicit-history conversation passed to [[ModelGateway.chatStep]].
  *
  * Unlike the implicit history managed by [[ModelGateway.streamedResponse]] (via LangChain4j AiServices), the ReAct
  * loop passes the full conversation explicitly so the gateway does not maintain per-session in-process state.
  */
sealed trait AgentMessage
case class SystemMsg(content: String) extends AgentMessage
case class UserMsg(content: String) extends AgentMessage
case class AssistantMsg(content: String) extends AgentMessage
case class ToolCallMsg(
  id:       String,
  name:     String,
  argsJson: String,
) extends AgentMessage
case class ToolResultMsg(
  id:         String,
  name:       String,
  resultJson: String,
) extends AgentMessage

/** The result of one [[ModelGateway.chatStep]] invocation.
  *
  *   - [[FinalAnswer]] — the model produced a text reply; stream its tokens to the user.
  *   - [[ToolCallRequested]] — the model wants to invoke a skill; dispatch the tool, append the result to the message
  *     list, and call chatStep again.
  */
sealed trait ChatStep
case class FinalAnswer(stream: ZStream[Any, ModelError, String]) extends ChatStep
case class ToolCallRequested(
  id:       String,
  name:     String,
  argsJson: String,
) extends ChatStep

/** Root error type for all model-call failures. */
sealed abstract class ModelError(
  override val msg:         String,
  override val cause:       Option[Throwable] = None,
  override val isTransient: Boolean = false,
) extends JorlanError(msg, cause)

/** The LLM provider is unreachable or returned a non-retryable connection error. */
case class ModelUnavailable(override val msg: String) extends ModelError(msg)

/** The model call exceeded the configured timeout. */
case class ModelTimeout(override val msg: String) extends ModelError(msg, isTransient = true)

/** The model returned a response that could not be parsed into the expected format. */
case class ModelResponseMalformed(override val msg: String) extends ModelError(msg)

/** Jorlan's boundary to LLM providers. Server code depends only on this trait — never on LangChain4j directly.
  *
  * Two interaction modes:
  *   - [[streamedResponse]] — implicit session history managed by the gateway (used by CheckpointSummarizer and legacy
  *     callers).
  *   - [[chatStep]] — explicit message list for the ReAct tool-calling loop; no internal history maintained.
  */
trait ModelGateway {

  /** Streams the model response token-by-token for the given session.
    *
    * The gateway maintains per-session in-memory chat history; calling [[streamedResponse]] for the same `sessionId`
    * continues the conversation.
    */
  def streamedResponse(
    sessionId:    AgentSessionId,
    message:      String,
    systemPrompt: String = "",
  ): ZStream[Any, JorlanError, String]

  /** Single step in the ReAct tool-calling loop.
    *
    * The caller is responsible for maintaining the full message history (including system prompt, all prior turns, and
    * tool call/result pairs). The gateway submits the messages with the available tool descriptors and returns either a
    * [[FinalAnswer]] (stream of text tokens) or a [[ToolCallRequested]] (the model wants to invoke a tool).
    *
    * This does NOT interact with the implicit session history used by [[streamedResponse]].
    */
  def chatStep(
    sessionId: AgentSessionId,
    messages:  List[AgentMessage],
    tools:     List[ToolSpec],
  ): IO[JorlanError, ChatStep]

  /** Returns metadata for all models the gateway can currently route to. */
  def availableModels: IO[JorlanError, List[ModelInfo]]

  /** Pre-populate the in-memory chat history for `sessionId` from persisted [[Message]] records.
    *
    * Called by [[AgentRunner]] on the first message of a resumed session so the model sees prior conversation context.
    * A no-op if the session already has an in-memory entry (avoids double-seeding on concurrent requests).
    */
  def seedHistory(
    sessionId:    AgentSessionId,
    messages:     List[Message],
    systemPrompt: String = "",
  ): IO[JorlanError, Unit]

  /** Release all in-process resources (chat memory, connection pools) held for `sessionId`.
    *
    * Must be called on session termination to prevent unbounded growth of the per-session map.
    */
  def invalidateSession(sessionId: AgentSessionId): IO[JorlanError, Unit]

}

object ModelGateway {}
