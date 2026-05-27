/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.domain.*
import zio.*

/** Application service for agent memory records.
  *
  * Memory writes log [[EventType.MemoryWritten]] so the audit trail captures what information the agent retained.
  */
trait MemoryService {

  def getById(id: MemoryRecordId): IO[JorlanError, Option[MemoryRecord]]
  def search(s:   MemorySearch):   IO[JorlanError, List[MemoryRecord]]
  def delete(id:  MemoryRecordId): IO[JorlanError, Long]
  def purgeExpired:                IO[JorlanError, Long]

  /** Upsert a memory record. Writes a [[EventType.MemoryWritten]] event. */
  def upsert(
    record:  MemoryRecord,
    actorId: Option[UserId],
    agentId: Option[AgentId],
  ): IO[JorlanError, MemoryRecord]

}

object MemoryService {

  def getById(id: MemoryRecordId): ZIO[MemoryService, JorlanError, Option[MemoryRecord]] =
    ZIO.serviceWithZIO[MemoryService](_.getById(id))

  def search(s: MemorySearch): ZIO[MemoryService, JorlanError, List[MemoryRecord]] =
    ZIO.serviceWithZIO[MemoryService](_.search(s))

  def upsert(
    record:  MemoryRecord,
    actorId: Option[UserId],
    agentId: Option[AgentId],
  ): ZIO[MemoryService, JorlanError, MemoryRecord] =
    ZIO.serviceWithZIO[MemoryService](_.upsert(record, actorId, agentId))

  def delete(id: MemoryRecordId): ZIO[MemoryService, JorlanError, Long] =
    ZIO.serviceWithZIO[MemoryService](_.delete(id))

  def purgeExpired: ZIO[MemoryService, JorlanError, Long] = ZIO.serviceWithZIO[MemoryService](_.purgeExpired)

}
