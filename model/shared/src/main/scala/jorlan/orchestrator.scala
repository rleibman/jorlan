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

import jorlan.Codecs.given
import zio.json.{JsonDecoder, JsonEncoder}

import java.security.PublicKey
import java.time.Instant

/** Identity record for a remote orchestrator participating in the federation protocol.
  *
  * Jorlan can receive work requests from external orchestrators (e.g. a Paperclip-style planner). This record stores
  * their registered identity and the trust level they have been granted by an administrator.
  *
  * @param publicKeyPem
  *   RSA/EC public key used to verify signed inter-orchestrator requests. `None` means the orchestrator is
  *   authenticated by other means (e.g. shared secret or JWT).
  * @param trustLevel
  *   Governs which capabilities the orchestrator may invoke without additional user approval.
  */
case class OrchestratorIdentity(
  id:           OrchestratorId,
  name:         String,
  description:  Option[String],
  publicKeyPem: Option[PublicKey],
  trustLevel:   Int = 0,
  createdAt:    Instant,
  updatedAt:    Instant,
) derives JsonEncoder, JsonDecoder
