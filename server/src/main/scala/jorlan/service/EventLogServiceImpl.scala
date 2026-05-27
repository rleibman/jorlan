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
import jorlan.db.repository.{EventLogZIORepository, RepositoryTask}
import jorlan.domain.*
import zio.*
import zio.json.JsonEncoder
import zio.json.ast.Json

private class EventLogServiceImpl(repo: EventLogRepository[RepositoryTask]) extends EventLogService {

  override def log[R: JsonEncoder](event: EventLog[R]): IO[JorlanError, EventLog[R]] = repo.append(event)

  override def query(filter: EventLogFilter): IO[JorlanError, List[EventLog[Json]]] =
    if (filter.pageSize < 1 || filter.pageSize > EventLogFilter.MaxLimit)
      ZIO.fail(JorlanError(s"pageSize must be between 1 and ${EventLogFilter.MaxLimit}, got ${filter.pageSize}"))
    else
      repo.search(filter)

  override def replay(sessionId: AgentSessionId): IO[JorlanError, List[EventLog[Json]]] = repo.replaySession(sessionId)

}

object EventLogServiceImpl {

  /** Requires [[EventLogZIORepository]] for layer wiring; the implementation class uses the abstract interface. */
  val live: URLayer[EventLogZIORepository, EventLogService] =
    ZLayer.fromFunction((repo: EventLogZIORepository) => new EventLogServiceImpl(repo))

}
