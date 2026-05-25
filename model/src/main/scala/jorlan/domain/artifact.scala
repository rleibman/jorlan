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

case class Workspace(
  id:          WorkspaceId,
  ownerId:     UserId,
  name:        String,
  description: Option[String],
  createdAt:   Instant,
  updatedAt:   Instant,
)
object Workspace {

  given JsonEncoder[Workspace] = DeriveJsonEncoder.gen[Workspace]
  given JsonDecoder[Workspace] = DeriveJsonDecoder.gen[Workspace]

}

case class Artifact(
  id:           ArtifactId,
  workspaceId:  Option[WorkspaceId],
  sessionId:    Option[AgentSessionId],
  name:         String,
  mimeType:     String,
  sizeBytes:    Long,
  storageUri:   String,
  metadataJson: Option[String],
  createdAt:    Instant,
)
object Artifact {

  given JsonEncoder[Artifact] = DeriveJsonEncoder.gen[Artifact]
  given JsonDecoder[Artifact] = DeriveJsonDecoder.gen[Artifact]

}
