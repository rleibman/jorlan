/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

/** Visibility boundary for a [[MemoryRecord]].
  *
  *   - `User` — visible only to the owning user.
  *   - `Shared` — visible to all users in the system (use carefully).
  *   - `Workspace` — visible to all members of the associated workspace.
  *   - `Private` — visible only to the specific agent that created it.
  */
enum MemoryScope derives JsonCodec {

  case User, Shared, Workspace, Private

}

/** A key-value memory entry that an agent can store and later retrieve.
  *
  * @param recordKey
  *   Application-defined key identifying this memory within its scope (e.g. `"user.preference.timezone"`).
  * @param value
  *   The stored content — free-form text or structured JSON.
  * @param ttl
  *   If set, the record is eligible for automatic purging after this instant.
  */
/** Importance of a [[MemoryRecord]] on a 1–10 scale.
  *
  *   - 9–10: Critical — always injected into every prompt (language preference, timezone, etc.)
  *   - 7–8: Persistent fact — injected unless the prompt budget is already full
  *   - 5–6: Contextual — injected when space allows
  *   - 3–4: Session context — short-lived, gets a default TTL if none is set
  *   - 1–2: Transient — very short TTL, rarely injected
  */
case class MemoryRecord(
  id:          MemoryRecordId,
  scope:       MemoryScope,
  userId:      Option[UserId],
  workspaceId: Option[WorkspaceId],
  agentId:     Option[AgentId],
  recordKey:   String,
  value:       Json,
  ttl:         Option[Instant],
  createdAt:   Instant,
  updatedAt:   Instant,
  importance:  Int = 5,
) derives JsonCodec

/** A vector embedding of a [[MemoryRecord]], used for semantic (similarity) search.
  *
  * @param model
  *   Embedding model identifier that produced this vector (e.g. `"nomic-embed-text"`).
  * @param vector
  *   Native float array; serialised as a JSON number array for storage.
  */
case class MemoryEmbedding(
  id:             MemoryEmbeddingId,
  memoryRecordId: MemoryRecordId,
  model:          EmbeddingModelId,
  vector:         Vector[Float],
  createdAt:      Instant,
) derives JsonCodec
