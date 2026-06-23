/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import jorlan.Codecs.given
import zio.http.MediaType
import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

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
) derives JsonCodec

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
) derives JsonCodec
