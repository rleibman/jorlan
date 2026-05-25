/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.Instant

case class OrchestratorIdentity(
  id:           OrchestratorId,
  name:         String,
  description:  Option[String],
  publicKeyPem: Option[String], // for inter-orchestrator trust
  trustLevel:   Int = 0,
  createdAt:    Instant,
  updatedAt:    Instant,
)
object OrchestratorIdentity {

  given JsonEncoder[OrchestratorIdentity] = DeriveJsonEncoder.gen[OrchestratorIdentity]
  given JsonDecoder[OrchestratorIdentity] = DeriveJsonDecoder.gen[OrchestratorIdentity]

}
