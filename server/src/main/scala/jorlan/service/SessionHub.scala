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
  * Each subscriber gets its own independent `Queue.bounded(1024)` queue. When full, `offer` back-pressures the
  * publisher until the subscriber catches up (it does not drop items like `Queue.sliding`). This bound exists to cap
  * per-subscriber heap usage; in practice the shell consumer drains much faster than LLM token rates.
  *
  * Because callers subscribe before submitting the message mutation, no tokens are lost due to subscription timing.
  * Tokens published before the queue is registered are the only ones that can be missed — callers must ensure
  * `subscribe` completes before sending the message.
  *
  * Multiple connections subscribed to the same [[AgentSessionId]] each receive their own independent copy of the
  * stream. Termination of one subscriber (normal or interrupted) does not affect others.
  *
  * [[AgentRunner]] publishes chunks via [[publish]]; Caliban subscriptions consume them via [[subscribe]].
  */
class SessionHub private (subs: Ref[Map[AgentSessionId, List[SubscriberEntry]]]) {

  /** Creates a per-connection bounded queue, registers it under `(sessionId, connectionId)`, and returns a [[ZStream]]
    * that drains it until the `finished` sentinel.
    *
    * The queue is registered atomically as part of the returned [[UIO]], before any streaming begins. Callers must call
    * this and obtain the stream before submitting the message that triggers publishing — otherwise tokens published
    * before the queue is registered are lost.
    *
    * The stream's `ensuring` block removes just this connection's queue entry when the stream terminates (normally or
    * via interruption), leaving other subscribers for the same session unaffected.
    */
  def subscribe(
    sessionId:    AgentSessionId,
    connectionId: ConnectionId,
  ): UIO[ZStream[Any, Nothing, ResponseChunk]] =
    for {
      queue <- Queue.bounded[ResponseChunk](1024)
      count <- subs
        .updateAndGet { map =>
          val existing = map.getOrElse(sessionId, Nil)
          map + (sessionId -> (SubscriberEntry(connectionId, queue) :: existing))
        }.map(_.getOrElse(sessionId, Nil).size)
      _ <- ZIO.logDebug(s"[SessionHub] subscriber ADDED: session=$sessionId conn=$connectionId totalForSession=$count")
    } yield ZStream
      .fromQueue(queue)
      .ensuring(
        ZIO.logDebug(s"[SessionHub] subscriber REMOVED: session=$sessionId conn=$connectionId") *>
          queue.shutdown *>
          subs.update { map =>
            val updated = map.getOrElse(sessionId, Nil).filterNot(_.connectionId == connectionId)
            if (updated.isEmpty) map - sessionId
            else map + (sessionId -> updated)
          },
      )

  /** Broadcasts `chunk` to all queues currently subscribed to `chunk.sessionId` in parallel.
    *
    * No-op if no subscribers exist for that session.
    */
  def publish(chunk: ResponseChunk): UIO[Unit] =
    subs.get.flatMap { map =>
      val entries = map.getOrElse(chunk.sessionId, Nil)
      ZIO.when(entries.isEmpty)(
        ZIO.logInfo(s"[SessionHub] publish with NO subscribers: session=${chunk.sessionId} finished=${chunk.finished}"),
      ) *>
        ZIO.foreachParDiscard(entries)(_.queue.offer(chunk).unit)
    }

}

object SessionHub {

  val live: ULayer[SessionHub] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[AgentSessionId, List[SubscriberEntry]]).map(new SessionHub(_)),
    )

}
