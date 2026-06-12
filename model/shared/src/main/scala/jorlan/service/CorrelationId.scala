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

import zio.*

/** Helpers for propagating a correlation ID through a chain of ZIO effects.
  *
  * Correlation IDs are stored in ZIO's log-annotation map (key `"correlationId"`), which is fibre-local and
  * automatically inherited by forked child fibres. Any ZIO log statement emitted inside a `withNew` or `withId` block
  * will carry the same ID, making it easy to trace a single request across multiple service calls.
  *
  * Usage:
  * {{{
  *   CorrelationId.withNew {
  *     for {
  *       _ <- EventLogService.log(...)
  *       _ <- someOtherService.doWork(...)
  *     } yield ()
  *   }
  * }}}
  */
object CorrelationId {

  val key: String = "correlationId"

  /** Wrap `zio` with a freshly generated UUID correlation ID. */
  def withNew[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.randomWith(_.nextUUID).flatMap(id => ZIO.logAnnotate(key, id.toString)(zio))

  /** Wrap `zio` with an explicit correlation ID. Useful when propagating an ID received from an upstream caller. */
  def withId[R, E, A](
    id: String,
  )(
    zio: ZIO[R, E, A],
  ): ZIO[R, E, A] = ZIO.logAnnotate(key, id)(zio)

  /** Retrieve the current correlation ID from the fibre's log annotations, if one has been set. */
  val get: UIO[Option[String]] = ZIO.logAnnotations.map(_.get(key))

}
