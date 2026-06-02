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

/** Maintains a per-session [[Queue]] of [[ResponseChunk]] tokens.
  *
  * Unlike a [[Hub]], a [[Queue]] buffers all published chunks until they are consumed. This means a subscriber that
  * connects after the LLM has already finished generating will still receive every token — which is the correct
  * behaviour for the request-response model used by the shell client.
  *
  * [[AgentRunner]] publishes tokens; Caliban subscriptions consume them.
  */
class SessionHub private (queues: Ref[Map[AgentSessionId, Queue[ResponseChunk]]]) {

  /** Returns the queue for `sessionId`, creating an unbounded queue if none exists yet. */
  def getOrCreate(sessionId: AgentSessionId): UIO[Queue[ResponseChunk]] =
    Queue.unbounded[ResponseChunk].flatMap { fresh =>
      queues.modify { map =>
        map.get(sessionId) match {
          case Some(existing) => (existing, map)
          case None           => (fresh, map + (sessionId -> fresh))
        }
      }
    }

  /** Publishes a chunk to the queue for `sessionId`. No-op if no queue exists yet. */
  def publish(chunk: ResponseChunk): UIO[Unit] =
    queues.get.flatMap { map =>
      map.get(chunk.sessionId) match {
        case Some(q) => q.offer(chunk).unit
        case None    => ZIO.unit
      }
    }

  /** Returns a [[ZStream]] that emits all buffered and future chunks for `sessionId` until the `finished` sentinel.
    *
    * Because chunks are stored in a queue, this stream replays any tokens already published before the subscriber
    * started — no chunks are lost due to subscription timing.
    */
  def subscribe(sessionId: AgentSessionId): ZStream[Any, Nothing, ResponseChunk] =
    ZStream.fromZIO(getOrCreate(sessionId)).flatMap { queue =>
      ZStream.fromQueue(queue).takeUntil(_.finished)
    }

  /** Removes the queue for `sessionId`. Called on session termination. */
  def remove(sessionId: AgentSessionId): UIO[Unit] =
    queues.update(_ - sessionId)

}

object SessionHub {

  val live: ULayer[SessionHub] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[AgentSessionId, Queue[ResponseChunk]]).map(new SessionHub(_)),
    )

}
