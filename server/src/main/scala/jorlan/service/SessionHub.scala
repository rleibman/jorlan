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

/** Maintains a [[Hub]] per active agent session. [[AgentRunner]] publishes response chunks; Caliban subscriptions
  * subscribe and stream them to clients.
  */
class SessionHub private (hubs: Ref[Map[AgentSessionId, Hub[ResponseChunk]]]) {

  /** Returns the hub for `sessionId`, creating a new bounded hub if none exists yet. */
  def getOrCreate(sessionId: AgentSessionId): UIO[Hub[ResponseChunk]] =
    hubs.get.flatMap { map =>
      map.get(sessionId) match {
        case Some(h) => ZIO.succeed(h)
        case None    =>
          Hub.bounded[ResponseChunk](256).flatMap { h =>
            hubs.update(_ + (sessionId -> h)).as(h)
          }
      }
    }

  /** Publishes a chunk to the hub for `sessionId`. No-op if no hub exists yet. */
  def publish(chunk: ResponseChunk): UIO[Unit] =
    hubs.get.flatMap { map =>
      map.get(chunk.sessionId) match {
        case Some(h) => h.publish(chunk).unit
        case None    => ZIO.unit
      }
    }

  /** Returns a [[ZStream]] that emits chunks for `sessionId` until the `finished` sentinel arrives.
    *
    * The hub subscription is scoped inside the stream — it is released when the stream ends or is interrupted.
    */
  def subscribe(sessionId: AgentSessionId): ZStream[Any, Nothing, ResponseChunk] =
    ZStream.unwrapScoped(
      getOrCreate(sessionId).flatMap { hub =>
        hub.subscribe.map { subscription =>
          ZStream
            .fromQueue(subscription)
            .takeUntil(_.finished)
        }
      },
    )

  def remove(sessionId: AgentSessionId): UIO[Unit] =
    hubs.update(_ - sessionId)

}

object SessionHub {

  val live: ULayer[SessionHub] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[AgentSessionId, Hub[ResponseChunk]]).map(new SessionHub(_)),
    )

}
