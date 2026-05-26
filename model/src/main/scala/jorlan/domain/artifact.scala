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

import jorlan.Codecs.given
import zio.http.MediaType
import zio.json.{JsonDecoder, JsonEncoder}
import zio.json.ast.Json

import java.net.URI
import java.time.Instant

/** A shared collaboration area that groups artifacts, memory, and agent sessions for a team or project.
  */
case class Workspace(
  id:          WorkspaceId,
  ownerId:     UserId,
  name:        String,
  description: Option[String],
  createdAt:   Instant,
  updatedAt:   Instant,
) derives JsonEncoder, JsonDecoder

/** A file or document produced or consumed during an agent session.
  *
  * @param storageUri
  *   Content-addressed or path-based URI (scheme depends on the configured storage backend).
  * @param metadataJson
  *   Optional JSON carrying artifact-specific metadata (e.g. page count, image dimensions).
  */
case class Artifact(
  id:           ArtifactId,
  workspaceId:  Option[WorkspaceId],
  sessionId:    Option[AgentSessionId],
  name:         String,
  mimeType:     MediaType,
  sizeBytes:    Long,
  storageUri:   URI,
  metadataJson: Option[Json],
  createdAt:    Instant,
) derives JsonEncoder, JsonDecoder
