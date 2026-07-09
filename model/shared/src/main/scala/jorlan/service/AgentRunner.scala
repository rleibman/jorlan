/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.stream.ZStream

// Future: consider if this is an unecessary abstraction and we could use the ai facilities more directly
/** The per-session agent execution loop.
  *
  * Receives a user message, streams the model response token-by-token through the session hub, and records all
  * significant steps in the event log.
  */
trait AgentRunner {

  /** Submit a user message to the agent for the given session.
    *
    * Streams the model response token-by-token through [[SessionHub]], then publishes a `finished=true` sentinel.
    * Writes [[EventType.UserMessageReceived]] before the model call and [[EventType.AgentResponseCompleted]] after
    * (unconditionally, including on error).
    *
    * @param sessionId
    *   The session to route the message to.
    * @param content
    *   The user's message text.
    * @param actorId
    *   The authenticated user submitting the message; attached to event log entries.
    * @param withMemory
    *   Whether to inject relevant [[MemoryRecord]]s into the system prompt before calling the model.
    * @param checkpoint
    *   Whether this turn participates in memory checkpointing (the post-response summarization that extracts durable
    *   user facts, plus the before-tool-call checkpoint inside the ReAct loop). Pipeline steps pass `false`: their
    *   prompts are templated instructions and their outputs are already persisted in the run's context, so
    *   checkpointing them floods the memory table with transient, redundant "facts" on every scheduled run. A pipeline
    *   that wants a durable memory should give a step the `memory.remember` tool and store it deliberately.
    * @param allowedToolPrefixes
    *   When set, the model sees only tools whose name matches one of these namespace prefixes (e.g. `"calendar"`
    *   matches `calendar.listEvents`), pinned for the whole turn — no embedding-based tool selection. Pipeline steps
    *   pass their declared `tools` list here: a small local model given dozens of tool specs is slow and picks wrong
    *   tools; given only the three it needs, it is fast and accurate. `None` keeps the default relevance-filtered
    *   selection used for interactive chat.
    */
  def processMessage(
    sessionId:           AgentSessionId,
    content:             String,
    actorId:             Option[UserId],
    withMemory:          Boolean = true,
    checkpoint:          Boolean = true,
    allowedToolPrefixes: Option[List[String]] = None,
  ): IO[JorlanError, Unit]

  /** Submit a message to the agent for the given session using a single LLM call — no tool loop.
    *
    * Suitable for pure-reasoning pipeline steps (`StepMode.SingleCall`) where tools are not needed. Streams the model
    * response token-by-token through [[SessionHub]], then publishes a `finished=true` sentinel.
    *
    * @param sessionId
    *   The session to route the message to.
    * @param systemPrompt
    *   Overrides the normal personality system prompt for this call.
    * @param content
    *   The user message.
    * @param actorId
    *   The authenticated user; attached to event log entries.
    * @param checkpoint
    *   Whether this turn participates in memory checkpointing. See [[processMessage]]'s parameter of the same name —
    *   `SingleCall` steps are always pipeline-driven, so callers should normally pass `false` here.
    */
  def processMessageSingleCall(
    sessionId:    AgentSessionId,
    systemPrompt: String,
    content:      String,
    actorId:      Option[UserId],
    checkpoint:   Boolean = true,
  ): IO[JorlanError, Unit]

  /** Eagerly registers a per-connection subscriber queue and returns a [[ZStream]] that drains it.
    *
    * The queue is created and registered in the returned [[UIO]] — callers must evaluate this effect before submitting
    * any message that would trigger publishing, otherwise tokens published before subscription are lost.
    *
    * The returned stream emits tokens until the `finished=true` [[ResponseChunk]] sentinel, then terminates. Cleanup of
    * the subscriber queue happens automatically when the stream ends.
    *
    * This is the subscription entry point used by the GraphQL `agentResponseStream` resolver — keeping it on
    * [[AgentRunner]] prevents [[SessionHub]] from leaking into the GraphQL layer.
    *
    * @param sessionId
    *   The session to subscribe to.
    * @param connectionId
    *   A unique identifier for this subscriber connection (e.g. one per browser tab or shell process).
    */
  def subscribeToSession(
    sessionId:    AgentSessionId,
    connectionId: ConnectionId,
  ): UIO[ZStream[Any, Nothing, ResponseChunk]]

}

object AgentRunner
