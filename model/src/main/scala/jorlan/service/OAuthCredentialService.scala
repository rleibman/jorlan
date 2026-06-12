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

import jorlan.JorlanError
import jorlan.domain.*
import zio.IO
import zio.json.ast.Json

trait OAuthCredentialService {

  def store(userId: UserId, provider: String, plainJson: Json): IO[JorlanError, Unit]
  def load(userId: UserId, provider: String): IO[JorlanError, Option[Json]]
  def revoke(userId: UserId, provider: String): IO[JorlanError, Unit]
  def listProviders(userId: UserId): IO[JorlanError, List[String]]
  def refreshAccessToken(userId: UserId, provider: String): IO[JorlanError, String]

}
