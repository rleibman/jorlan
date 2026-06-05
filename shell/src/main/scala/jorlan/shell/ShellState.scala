/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import jorlan.domain.{AgentSessionId, ResponseChunk}
import jorlan.shell.client.SubscriptionClient
import zio.*

/** Holds the state of a live agent session from the shell's perspective.
  *
  * @param sessionId
  *   The durable [[AgentSessionId]] for the active LLM conversation (the "what").
  * @param tokenQueue
  *   A queue fed by the long-lived WebSocket subscription fiber. `handleMessage` drains this until `Right(None)`
  *   (finished) or `Left(error)` for each user message.
  * @param subscriptionFiber
  *   The forked fiber that keeps the WebSocket subscription alive for the duration of the session. Interrupted when the
  *   user starts a new session with `/new`.
  */
case class LiveSession(
  sessionId:         AgentSessionId,
  tokenQueue:        Queue[Either[String, Option[ResponseChunk]]],
  subscriptionFiber: Fiber[Nothing, Unit],
)

/** Holds ephemeral shell session state. Tracks which agent session is active and owns the long-lived WebSocket
  * subscription fiber that streams tokens back from the server.
  */
class ShellState private (
  liveSessionRef:   Ref[Option[LiveSession]],
  drainingFiberRef: Ref[Option[Fiber[Nothing, Unit]]],
) {

  /** Returns the currently active [[LiveSession]], or `None` if no session has been started. */
  def getLiveSession: UIO[Option[LiveSession]] = liveSessionRef.get

  /** Sets the active session. Called after a successful `createSession` mutation and subscription setup. */
  def setLiveSession(ls: LiveSession): UIO[Unit] = liveSessionRef.set(Some(ls))

  /** Clears the active session. Called on session termination or shell reconnect. */
  def clearLiveSession: UIO[Unit] = liveSessionRef.set(None)

  /** Convenience accessor for the active session ID, used by display commands such as `/model`. */
  def getSessionId: UIO[Option[AgentSessionId]] = liveSessionRef.get.map(_.map(_.sessionId))

  /** Returns any currently-running token-drain fiber (LLM response in progress). */
  def getDrainingFiber: UIO[Option[Fiber[Nothing, Unit]]] = drainingFiberRef.get

  /** Registers the active drain fiber. Pass `None` when the drain completes. */
  def setDrainingFiber(f: Option[Fiber[Nothing, Unit]]): UIO[Unit] = drainingFiberRef.set(f)

  /** Interrupt any in-progress drain fiber and clear it. */
  def interruptDrain: UIO[Unit] =
    drainingFiberRef.getAndSet(None).flatMap(f => ZIO.foreachDiscard(f)(_.interrupt).unit)

}

object ShellState {

  val make: UIO[ShellState] =
    (Ref.make(Option.empty[LiveSession]) <*> Ref.make(Option.empty[Fiber[Nothing, Unit]]))
      .map { case (lr, df) => ShellState(lr, df) }

  val live: ULayer[ShellState] = ZLayer.fromZIO(make)

}

object LiveSession {

  /** Creates a token queue, forks the long-lived subscription fiber, and registers the resulting [[LiveSession]] in
    * [[ShellState]]. Call this before submitting any message — tokens published before subscription are buffered.
    *
    * The caller is responsible for UI updates (status bar, system message) after this completes.
    */
  def start(sessionId: AgentSessionId): ZIO[ShellState & SubscriptionClient, Nothing, LiveSession] =
    for {
      tokenQueue <- Queue.bounded[Either[String, Option[ResponseChunk]]](1024)
      // Fork before setLiveSession is safe: the queue is already in scope so early tokens are buffered.
      fiber <- ZIO
        .scoped(
          SubscriptionClient
            .agentResponseStream(sessionId)
            .ensuring(ZIO.logInfo(s"[Shell] subscription stream ended for session=${sessionId.value}"))
            .foreach { chunk =>
              val offerChunk =
                if (chunk.content.nonEmpty || chunk.isError || !chunk.finished)
                  tokenQueue.offer(Right(Some(chunk))).unit
                else ZIO.unit
              offerChunk *> ZIO.when(chunk.finished)(tokenQueue.offer(Right(None)).unit)
            }
            .foldZIO(
              err =>
                ZIO.logError(s"[Shell] subscription error for session=${sessionId.value}: $err") *>
                  tokenQueue.offer(Left(s"$err — type /new to reconnect")).unit,
              _ =>
                ZIO.logInfo(s"[Shell] subscription completed normally for session=${sessionId.value}") *>
                  tokenQueue.offer(Left("Connection to server was lost — type /new to reconnect")).unit,
            ),
        )
        .ensuring(ZIO.serviceWithZIO[ShellState](_.clearLiveSession))
        .fork
      ls = LiveSession(sessionId, tokenQueue, fiber)
      _ <- ZIO.serviceWithZIO[ShellState](_.setLiveSession(ls))
    } yield ls

}
