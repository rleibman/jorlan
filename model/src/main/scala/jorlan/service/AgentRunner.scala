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

// Future: consider if this is an unecessary abstraction and we could use the ai facilities more directly
/** The per-session agent execution loop.
  *
  * Receives a user message, streams the model response token-by-token through the session hub, and records all
  * significant steps in the event log. The full ReAct planning loop (multi-step tool dispatch) is deferred to Phase 12.
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
    */
  def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): IO[JorlanError, Unit]

  /** Subscribe to the response stream for `sessionId`.
    *
    * The stream emits tokens until a `finished=true` [[ResponseChunk]] is received. This is the subscription entry
    * point used by the GraphQL `agentResponseStream` resolver — keeping it on `AgentRunner` prevents `SessionHub` from
    * leaking into the GraphQL layer.
    */
  def subscribeToSession(sessionId: AgentSessionId): ZStream[Any, Nothing, ResponseChunk]

}

object AgentRunner {

  def processMessage(
    sessionId: AgentSessionId,
    content:   String,
    actorId:   Option[UserId],
  ): ZIO[AgentRunner, JorlanError, Unit] =
    ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, content, actorId))

  def subscribeToSession(sessionId: AgentSessionId): ZStream[AgentRunner, Nothing, ResponseChunk] =
    ZStream.serviceWithStream[AgentRunner](_.subscribeToSession(sessionId))

}
