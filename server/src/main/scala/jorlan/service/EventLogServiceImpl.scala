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
import jorlan.db.repository.EventLogZIORepository
import jorlan.domain.*
import zio.*
import zio.json.JsonEncoder
import zio.json.ast.Json

private class EventLogServiceImpl(repo: EventLogZIORepository) extends EventLogService {

  override def log[R: JsonEncoder](event: EventLog[R]): IO[JorlanError, EventLog[R]] = repo.append(event)

  override def query(filter: EventLogFilter): IO[JorlanError, List[EventLog[Json]]] =
    repo
      .search(filter.eventType, filter.agentId, filter.sessionId, filter.from, filter.to, filter.limit)

  override def replay(sessionId: AgentSessionId): IO[JorlanError, List[EventLog[Json]]] =
    repo
      .search(None, None, Some(sessionId), None, None, Int.MaxValue)
      .map(_.sortBy(_.occurredAt))

}

object EventLogServiceImpl {

  val live: URLayer[EventLogZIORepository, EventLogService] =
    ZLayer.fromFunction(new EventLogServiceImpl(_))

}
