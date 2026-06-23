/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.email

import jorlan.{JorlanError, UserId}
import zio.*

trait PgpService {

  def verifySignature(
    body:        String,
    senderEmail: String,
  ): IO[JorlanError, Boolean]
  def signMessage(
    body:   String,
    userId: UserId,
  ): IO[JorlanError, (String, Boolean)]

}

object PgpService {

  /** Null-object implementation that always reports no signature and signs nothing. Used when PGP is not configured. */
  val noOp: PgpService = new PgpService {

    def verifySignature(
      body:        String,
      senderEmail: String,
    ): IO[JorlanError, Boolean] = ZIO.succeed(false)

    def signMessage(
      body:   String,
      userId: UserId,
    ): IO[JorlanError, (String, Boolean)] = ZIO.succeed((body, false))

  }

}
