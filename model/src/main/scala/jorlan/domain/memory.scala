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

import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** Visibility boundary for a [[MemoryRecord]].
  *
  *   - `User` — visible only to the owning user.
  *   - `Shared` — visible to all users in the system (use carefully).
  *   - `Workspace` — visible to all members of the associated workspace.
  *   - `Private` — visible only to the specific agent that created it.
  */
enum MemoryScope derives JsonEncoder, JsonDecoder {

  case User, Shared, Workspace, Private

}

/** A key-value memory entry that an agent can store and later retrieve.
  *
  * @param recordKey
  *   Application-defined key identifying this memory within its scope (e.g. `"user.preference.timezone"`).
  * @param value
  *   The stored content — free-form text or JSON.
  * @param ttl
  *   If set, the record is eligible for automatic purging after this instant.
  */
case class MemoryRecord(
  id:          MemoryRecordId,
  scope:       MemoryScope,
  userId:      Option[UserId],
  workspaceId: Option[WorkspaceId],
  agentId:     Option[AgentId],
  recordKey:   String,
  value:       String,
  ttl:         Option[Instant],
  createdAt:   Instant,
  updatedAt:   Instant,
) derives JsonEncoder, JsonDecoder

/** A vector embedding of a [[MemoryRecord]], used for semantic (similarity) search.
  *
  * @param model
  *   Embedding model identifier that produced this vector (e.g. `"nomic-embed-text"`).
  * @param vector
  *   JSON-serialized `float[]`. The embedding dimension is implicit in the model identifier.
  */
case class MemoryEmbedding(
  id:             MemoryEmbeddingId,
  memoryRecordId: MemoryRecordId,
  model:          String,
  vector:         String, // JSON-serialized float array
  createdAt:      Instant,
) derives JsonEncoder, JsonDecoder
