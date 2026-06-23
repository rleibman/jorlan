/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

case class ExternalCredential(
  id:       ExternalCredentialId,
  userId:   UserId,
  provider: String,
  /** Stored encrypted via [[jorlan.google.OAuthCredentialEncryptor]]. Never log or transmit in plaintext. */
  credentialData: Json,
  expiresAt:      Option[Instant],
  scopes:         Option[String],
  createdAt:      Instant,
  updatedAt:      Instant,
) derives JsonCodec
