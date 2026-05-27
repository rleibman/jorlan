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
import jorlan.db.repository.MemoryZIORepository
import jorlan.domain.*
import zio.*

private class MemoryServiceImpl(
  repo:     MemoryZIORepository,
  eventLog: EventLogService,
) extends MemoryService {

  override def getById(id: MemoryRecordId): IO[JorlanError, Option[MemoryRecord]] = repo.getById(id)

  override def search(s: MemorySearch): IO[JorlanError, List[MemoryRecord]] = repo.search(s)

  override def delete(id: MemoryRecordId): IO[JorlanError, Long] = repo.delete(id)

  override def purgeExpired: IO[JorlanError, Long] = repo.purgeExpired

  override def upsert(
    record:  MemoryRecord,
    actorId: Option[UserId],
    agentId: Option[AgentId],
  ): IO[JorlanError, MemoryRecord] =
    for {
      now   <- Clock.instant
      saved <- repo.upsert(record)
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.MemoryWritten,
          actorId = actorId,
          agentId = agentId,
          sessionId = None,
          resource = Some(saved.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield saved

}

object MemoryServiceImpl {

  val live: URLayer[MemoryZIORepository & EventLogService, MemoryService] =
    ZLayer.fromFunction(
      (
        repo:     MemoryZIORepository,
        eventLog: EventLogService,
      ) => new MemoryServiceImpl(repo, eventLog),
    )

}
