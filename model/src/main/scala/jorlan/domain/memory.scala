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

enum MemoryScope {

  case User, Shared, Workspace, Private

}
object MemoryScope {

  given JsonEncoder[MemoryScope] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[MemoryScope] =
    JsonDecoder[String].mapOrFail { s =>
      MemoryScope.values.find(_.toString == s).toRight(s"Unknown MemoryScope: $s")
    }

}

case class MemoryRecord(
  id:          MemoryRecordId,
  scope:       MemoryScope,
  userId:      Option[UserId],
  workspaceId: Option[WorkspaceId],
  agentId:     Option[AgentId],
  key:         String,
  value:       String,
  ttl:         Option[Instant],
  createdAt:   Instant,
  updatedAt:   Instant,
)
object MemoryRecord {

  given JsonEncoder[MemoryRecord] = DeriveJsonEncoder.gen[MemoryRecord]
  given JsonDecoder[MemoryRecord] = DeriveJsonDecoder.gen[MemoryRecord]

}

case class MemoryEmbedding(
  id:             MemoryEmbeddingId,
  memoryRecordId: MemoryRecordId,
  model:          String,
  vector:         String, // JSON-serialized float array
  createdAt:      Instant,
)
object MemoryEmbedding {

  given JsonEncoder[MemoryEmbedding] = DeriveJsonEncoder.gen[MemoryEmbedding]
  given JsonDecoder[MemoryEmbedding] = DeriveJsonDecoder.gen[MemoryEmbedding]

}
