/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.stream.ZStream

/** A single tool-lifecycle event published to subscribers during a ReAct turn.
  *
  * @param sessionId
  *   identifies the agent session that produced the event
  */
sealed trait ToolEvent {

  def sessionId: AgentSessionId

}

object ToolEvent {

  /** Emitted just before a skill tool is invoked.
    *
    * @param sessionId
    *   identifies the agent session
    * @param toolName
    *   fully-qualified tool name (e.g. `"telegram.send_message"`)
    * @param argsJson
    *   JSON-serialised tool arguments
    */
  case class ToolInvokedEvent(
    sessionId: AgentSessionId,
    toolName:  String,
    argsJson:  String,
  ) extends ToolEvent

  /** Emitted after a skill tool completes (success or failure).
    *
    * @param sessionId
    *   identifies the agent session
    * @param toolName
    *   fully-qualified tool name
    * @param resultJson
    *   JSON-serialised tool result (or error message on failure)
    * @param succeeded
    *   `true` if the tool returned without error
    */
  case class ToolResultEvent(
    sessionId:  AgentSessionId,
    toolName:   String,
    resultJson: String,
    succeeded:  Boolean,
  ) extends ToolEvent

}

/** Pub-sub hub for [[ToolEvent]]s, keyed by [[AgentSessionId]].
  *
  * Delegates subscriber management to [[KeyedPubSubHub]]. Each subscriber receives its own dropping [[Queue]] (capacity
  * 256). Publishing never back-pressures the agent fiber — events are silently dropped for a slow subscriber when its
  * queue is full. Queues are cleaned up via `ZStream.ensuring` when the subscriber's stream terminates.
  */
class ToolEventHub private (hub: KeyedPubSubHub[AgentSessionId, ToolEvent]) {

  def subscribe(sessionId: AgentSessionId): UIO[ZStream[Any, Nothing, ToolEvent]] =
    hub.subscribe(sessionId)

  def publish(event: ToolEvent): UIO[Unit] =
    hub.publish(event.sessionId, event)

}

object ToolEventHub {

  val make: UIO[ToolEventHub] =
    KeyedPubSubHub.make[AgentSessionId, ToolEvent].map(new ToolEventHub(_))

  val live: ULayer[ToolEventHub] = ZLayer.fromZIO(make)

}
