/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.email

import jorlan.JorlanError
import jorlan.domain.UserId
import zio.*

trait PgpService {

  def verifySignature(body: String, senderEmail: String): IO[JorlanError, Boolean]
  def signMessage(body: String, userId: UserId): IO[JorlanError, (String, Boolean)]

}

object PgpService {

  val noOp: PgpService = new PgpService {

    def verifySignature(body: String, senderEmail: String): IO[JorlanError, Boolean] = ZIO.succeed(false)

    def signMessage(body: String, userId: UserId): IO[JorlanError, (String, Boolean)] = ZIO.succeed((body, false))

  }

}
