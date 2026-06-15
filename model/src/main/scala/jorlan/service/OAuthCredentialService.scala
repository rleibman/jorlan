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

import java.time.Instant

/** Manages per-user OAuth credentials for external providers (e.g. Google).
  *
  * Credentials are stored encrypted (via [[jorlan.google.OAuthCredentialEncryptor]]) in the `externalCredential` DB
  * table. `store` encrypts before writing; `load` decrypts after reading. `refreshAccessToken` checks whether the
  * current access token is still valid and only calls the provider's token endpoint when the token is within 60 seconds
  * of expiry or already expired, then persists the new token.
  */
trait OAuthCredentialService {

  /** Encrypts `plainJson` and persists it under `(userId, provider)`. Replaces any existing credential. */
  def store(
    userId:    UserId,
    provider:  String,
    plainJson: Json,
  ): IO[JorlanError, Unit]

  /** Decrypts and returns the stored credential JSON, or `None` if not linked. */
  def load(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Option[Json]]

  /** Removes the stored credential for the given provider. */
  def revoke(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Unit]

  /** Returns the provider names for which this user has linked credentials. */
  def listProviders(userId: UserId): IO[JorlanError, List[String]]

  /** Returns a valid access token, refreshing via the provider's token endpoint if the stored token is near expiry. */
  def refreshAccessToken(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, String]

  /** Returns the stored expiry timestamp without decrypting the credential data. */
  def getExpiresAt(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Option[Instant]]

}
