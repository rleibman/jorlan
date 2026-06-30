/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.stream.ZStream

import java.time.Instant

/** In-process pub-sub for the approval lifecycle.
  *
  * Two responsibilities:
  *   1. Broadcast new [[ApprovalRequest]] objects to the GraphQL `approvalNotifications` subscription. The broadcast
  *      uses a [[KeyedPubSubHub]][Unit, [[ApprovalRequest]]] so subscriber management (dropping queues, cleanup via
  *      `ZStream.ensuring`, parallel fan-out) is shared with [[ToolEventHub]].
  *   2. Let agent fibers block on [[awaitDecision]] until a human calls [[completeDecision]].
  *
  * Race safety: [[awaitDecision]] registers its `Promise` *before* checking `preDecisions`, so even if
  * [[completeDecision]] fires between the check and the registration, the promise is completed and the await returns
  * immediately.
  *
  * TTL: `preDecisions` entries carry an expiry `Instant`. Call [[purgeExpiredPreDecisions]] periodically (e.g. from
  * `ApprovalServiceImpl.expireStaleRequests`) to reclaim memory for decisions that were never consumed by
  * `awaitDecision` (orphaned because the agent fiber was interrupted).
  */
class ApprovalHub private (
  pendingPromises: Ref[Map[ApprovalRequestId, Promise[Nothing, Boolean]]],
  preDecisions:    Ref[Map[ApprovalRequestId, (Boolean, Instant)]],
  broadcastHub:    KeyedPubSubHub[Unit, ApprovalRequest],
) {

  /** Publish a newly-persisted approval request to all active subscriptions. */
  def notifyNewRequest(req: ApprovalRequest): UIO[Unit] =
    broadcastHub.publish((), req)

  /** Block the calling fiber until a human approves or denies `id`, or until `timeout` elapses.
    *
    * Returns `Some(true)` = approved, `Some(false)` = denied/rejected, `None` = timed out.
    *
    * Race-safe: the Promise is registered before checking `preDecisions`. If [[completeDecision]] ran between the
    * registration and the check, the Promise is completed immediately and the fiber unblocks.
    */
  def awaitDecision(
    id:      ApprovalRequestId,
    timeout: Duration,
  ): UIO[Option[Boolean]] =
    for {
      promise <- Promise.make[Nothing, Boolean]
      // Register promise first so completeDecision can find it if it fires after this line.
      _ <- pendingPromises.update(_.updated(id, promise))
      // Now check if completeDecision already ran before we registered the promise.
      _ <- preDecisions
        .modify { pre =>
          pre.get(id) match {
            case Some((result, _)) => (Some(result), pre - id)
            case None              => (None, pre)
          }
        }.flatMap {
          // Complete our own promise so promise.await returns without timing out.
          case Some(result) => promise.succeed(result).unit
          case None         => ZIO.unit
        }
      result <- promise.await.timeout(timeout)
      _      <- pendingPromises.update(_ - id)
    } yield result

  /** Complete a pending [[awaitDecision]] call, or stash the result for a future call.
    *
    * `approved = true` means the human approved; `false` means rejected/denied.
    *
    * If no fiber is currently waiting, the result is stored as a pre-decision with a TTL of 10 minutes. Call
    * [[purgeExpiredPreDecisions]] periodically to reclaim orphaned entries.
    */
  def completeDecision(
    id:       ApprovalRequestId,
    approved: Boolean,
  ): UIO[Unit] =
    pendingPromises.get.flatMap { map =>
      map.get(id) match {
        case Some(promise) => promise.succeed(approved).unit
        case None          =>
          Clock.instant.flatMap { now =>
            preDecisions.update(_.updated(id, (approved, now.plusSeconds(600))))
          }
      }
    }

  /** Remove pre-decision entries whose TTL has elapsed.
    *
    * Intended to be called periodically from `ApprovalServiceImpl.expireStaleRequests` to prevent the `preDecisions`
    * map from growing unbounded when agent fibers are interrupted before calling `awaitDecision`.
    *
    * @return
    *   the number of entries removed
    */
  def purgeExpiredPreDecisions(): UIO[Long] =
    Clock.instant.flatMap { now =>
      preDecisions.modify { pre =>
        val (stale, fresh) = pre.partition { case (_, (_, expiry)) => expiry.isBefore(now) }
        (stale.size.toLong, fresh)
      }
    }

  /** Returns a new stream of [[ApprovalRequest]] objects; one independent subscription per call.
    *
    * Uses a dropping `Queue` — a slow or disconnected subscriber never back-pressures the publishing fiber. Messages
    * published when the queue is at capacity (256) are silently dropped for that subscriber.
    */
  def subscribeToNewRequests: UIO[ZStream[Any, Nothing, ApprovalRequest]] =
    broadcastHub.subscribe(())

}

object ApprovalHub {

  val make: UIO[ApprovalHub] =
    for {
      pending      <- Ref.make(Map.empty[ApprovalRequestId, Promise[Nothing, Boolean]])
      pre          <- Ref.make(Map.empty[ApprovalRequestId, (Boolean, Instant)])
      broadcastHub <- KeyedPubSubHub.make[Unit, ApprovalRequest]
    } yield ApprovalHub(pending, pre, broadcastHub)

  val live: ULayer[ApprovalHub] = ZLayer.fromZIO(make)

}
