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

/** A single tool-lifecycle event published to subscribers during a ReAct turn. */
sealed trait ToolEvent {

  def sessionId: AgentSessionId

}

object ToolEvent {

  /** Emitted when a skill tool is about to be invoked. */
  case class ToolInvokedEvent(
    sessionId: AgentSessionId,
    toolName:  String,
    argsJson:  String,
  ) extends ToolEvent

  /** Emitted when a skill tool completes (success or failure). */
  case class ToolResultEvent(
    sessionId:  AgentSessionId,
    toolName:   String,
    resultJson: String,
    succeeded:  Boolean,
  ) extends ToolEvent

}

/** Pub-sub hub for [[ToolEvent]]s, keyed by [[AgentSessionId]].
  *
  * Mirrors the design of [[SessionHub]]: each subscriber receives its own bounded [[Queue]]; publishing is
  * fire-and-forget with no drop semantics. Queues are cleaned up when the subscriber's stream terminates.
  */
class ToolEventHub private (subs: Ref[Map[AgentSessionId, List[Queue[ToolEvent]]]]) {

  def subscribe(sessionId: AgentSessionId): UIO[ZStream[Any, Nothing, ToolEvent]] =
    for {
      queue <- Queue.bounded[ToolEvent](256)
      _     <- subs.update { map =>
        val existing = map.getOrElse(sessionId, Nil)
        map + (sessionId -> (queue :: existing))
      }
    } yield ZStream
      .fromQueue(queue)
      .ensuring(
        queue.shutdown *>
          subs.update { map =>
            val updated = map.getOrElse(sessionId, Nil).filterNot(_ eq queue)
            if (updated.isEmpty) map - sessionId
            else map + (sessionId -> updated)
          },
      )

  def publish(event: ToolEvent): UIO[Unit] =
    subs.get.flatMap { map =>
      ZIO.foreachParDiscard(map.getOrElse(event.sessionId, Nil))(_.offer(event).unit)
    }

}

object ToolEventHub {

  val make: UIO[ToolEventHub] =
    Ref.make(Map.empty[AgentSessionId, List[Queue[ToolEvent]]]).map(ToolEventHub(_))

  val live: ULayer[ToolEventHub] = ZLayer.fromZIO(make)

}
