/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.stream.ZStream

/** In-process pub-sub for the approval lifecycle.
  *
  * Two responsibilities:
  *   1. Broadcast new [[ApprovalRequest]] objects to the GraphQL `approvalNotifications` subscription.
  *   2. Let agent fibers block on [[awaitDecision]] until a human calls [[completeDecision]].
  *
  * Race safety: [[awaitDecision]] registers its `Promise` *before* checking `preDecisions`, so even if
  * [[completeDecision]] fires between the check and the registration, the promise is completed and the await returns
  * immediately.
  */
class ApprovalHub private (
  pendingPromises: Ref[Map[ApprovalRequestId, Promise[Nothing, Boolean]]],
  preDecisions:    Ref[Map[ApprovalRequestId, Boolean]],
  subscribers:     Ref[List[Queue[ApprovalRequest]]],
) {

  /** Publish a newly-persisted approval request to all active subscriptions. */
  def notifyNewRequest(req: ApprovalRequest): UIO[Unit] =
    subscribers.get.flatMap(qs => ZIO.foreachDiscard(qs)(_.offer(req).unit))

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
            case Some(result) => (Some(result), pre - id)
            case None         => (None, pre)
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
    */
  def completeDecision(
    id:       ApprovalRequestId,
    approved: Boolean,
  ): UIO[Unit] =
    pendingPromises.get.flatMap { map =>
      map.get(id) match {
        case Some(promise) => promise.succeed(approved).unit
        case None          => preDecisions.update(_.updated(id, approved))
      }
    }

  /** Returns a new stream of [[ApprovalRequest]] objects; one independent subscription per call.
    *
    * Uses an eagerly-created bounded `Queue` so no messages are lost between subscription and first pull.
    */
  def subscribeToNewRequests: UIO[ZStream[Any, Nothing, ApprovalRequest]] =
    for {
      queue <- Queue.bounded[ApprovalRequest](256)
      _     <- subscribers.update(queue :: _)
    } yield ZStream
      .fromQueue(queue)
      .ensuring(
        queue.shutdown *> subscribers.update(_.filterNot(_ eq queue)),
      )

}

object ApprovalHub {

  val make: UIO[ApprovalHub] =
    for {
      pending     <- Ref.make(Map.empty[ApprovalRequestId, Promise[Nothing, Boolean]])
      pre         <- Ref.make(Map.empty[ApprovalRequestId, Boolean])
      subscribers <- Ref.make(List.empty[Queue[ApprovalRequest]])
    } yield ApprovalHub(pending, pre, subscribers)

  val live: ULayer[ApprovalHub] = ZLayer.fromZIO(make)

}
