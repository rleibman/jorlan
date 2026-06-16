/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import zio.json.{JsonDecoder, JsonEncoder}
import zio.json.ast.Json
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
) derives JsonEncoder, JsonDecoder
