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

private case class SubscriberEntry(
  connectionId: ConnectionId,
  queue:        Queue[ResponseChunk],
)

/** Maintains per-connection [[Queue]]s of [[ResponseChunk]] tokens, keyed by [[AgentSessionId]].
  *
  * Each subscriber gets its own independent queue, so multiple connections (e.g. two browser tabs) subscribed to the
  * same session each receive a complete copy of the token stream. [[AgentRunner]] publishes chunks via [[publish]];
  * Caliban subscriptions consume them via [[subscribe]].
  *
  * Unlike a [[Hub]], a [[Queue]] buffers all published chunks. Because the shell subscribes before it submits the
  * message mutation, no tokens are lost due to subscription timing.
  */
class SessionHub private (subs: Ref[Map[AgentSessionId, List[SubscriberEntry]]]) {

  /** Creates a per-connection queue, registers it under `(sessionId, connectionId)`, and returns a [[ZStream]] that
    * drains it until the `finished` sentinel.
    *
    * The queue is registered synchronously as part of the returned [[UIO]], before any streaming begins. Callers should
    * call this and obtain the stream before submitting the message that triggers publishing — otherwise tokens
    * published before the queue is registered are lost.
    *
    * The stream's `ensuring` block removes just this connection's queue entry when the stream terminates (normally or
    * via interruption), leaving other subscribers for the same session unaffected.
    */
  def subscribe(
    sessionId:    AgentSessionId,
    connectionId: ConnectionId,
  ): UIO[ZStream[Any, Nothing, ResponseChunk]] =
    for {
      queue <- Queue.sliding[ResponseChunk](1024)
      _     <- subs.update { map =>
        val existing = map.getOrElse(sessionId, Nil)
        map + (sessionId -> (SubscriberEntry(connectionId, queue) :: existing))
      }
      _ <- subs.get.map(_.getOrElse(sessionId, Nil).size).flatMap { count =>
        ZIO.logInfo(s"[SessionHub] subscriber ADDED: session=$sessionId conn=$connectionId totalForSession=$count")
      }
    } yield ZStream
      .fromQueue(queue)
      .ensuring(
        ZIO.logInfo(s"[SessionHub] subscriber REMOVED: session=$sessionId conn=$connectionId") *>
          queue.shutdown *>
          subs.update { map =>
            val updated = map.getOrElse(sessionId, Nil).filterNot(_.connectionId == connectionId)
            if (updated.isEmpty) map - sessionId
            else map + (sessionId -> updated)
          },
      )

  /** Broadcasts `chunk` to all queues currently subscribed to `chunk.sessionId`.
    *
    * No-op if no subscribers exist for that session.
    */
  def publish(chunk: ResponseChunk): UIO[Unit] =
    subs.get.flatMap { map =>
      val entries = map.getOrElse(chunk.sessionId, Nil)
      ZIO.when(entries.isEmpty)(
        ZIO.logInfo(s"[SessionHub] publish with NO subscribers: session=${chunk.sessionId} finished=${chunk.finished}"),
      ) *>
        ZIO.foreachDiscard(entries)(_.queue.offer(chunk).unit)
    }

}

object SessionHub {

  val live: ULayer[SessionHub] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[AgentSessionId, List[SubscriberEntry]]).map(new SessionHub(_)),
    )

}
