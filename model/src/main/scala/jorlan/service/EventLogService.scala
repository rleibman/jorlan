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
import zio.json.JsonEncoder
import zio.json.ast.Json

import java.time.Instant

enum EventLogOrder { case Id, OccurredAt }

/** Query parameters for searching the event log.
  *
  * All filters are optional — omitting them returns all events up to `pageSize`. `pageSize` must be between 1 and
  * [[EventLogFilter.MaxLimit]]; values outside that range are rejected by the service.
  */
case class EventLogFilter(
  eventType: Option[EventType] = None,
  agentId:   Option[AgentId] = None,
  sessionId: Option[AgentSessionId] = None,
  from:      Option[Instant] = None,
  to:        Option[Instant] = None,
  page:      Int = 0,
  pageSize:  Int = 100,
  sorts:     List[Sort[EventLogOrder]] = List.empty,
) extends Search[EventLogOrder]

object EventLogFilter {

  val MaxLimit: Int = 10_000

}

/** Append-only service for platform event logging.
  *
  * Every significant action in the system — agent start/stop, skill invocation, memory write, approval decision — must
  * call `log` so the platform maintains a complete, auditable history.
  *
  * Callers should surround logical units of work with [[CorrelationId.withNew]] so all events from that unit share a
  * correlation ID in the ZIO log annotations.
  *
  * TODO: Once `EventLog` gains a `correlationId` column and a Flyway migration is added, `log` should automatically
  * capture [[CorrelationId.get]] and persist it. See phase3-review.md M2.
  */
trait EventLogService {

  /** Append a new event to the log. The returned `EventLog` is identical to the input but with a generated `id`. */
  def log[R: JsonEncoder](event: EventLog[R]): IO[JorlanError, EventLog[R]]

  /** Query the event log. Results are ordered by `occurredAt` descending by default.
    *
    * Fails with [[JorlanError]] if `filter.pageSize` is outside `[1, EventLogFilter.MaxLimit]`.
    */
  def query(filter: EventLogFilter): IO[JorlanError, List[EventLog[Json]]]

  /** Replay all events for a specific agent session, ordered by `occurredAt` ascending. */
  def replay(sessionId: AgentSessionId): IO[JorlanError, List[EventLog[Json]]]

}

object EventLogService {

  def log[R: JsonEncoder](event: EventLog[R]): ZIO[EventLogService, JorlanError, EventLog[R]] =
    ZIO.serviceWithZIO[EventLogService](_.log(event))

  def query(filter: EventLogFilter): ZIO[EventLogService, JorlanError, List[EventLog[Json]]] =
    ZIO.serviceWithZIO[EventLogService](_.query(filter))

  def replay(sessionId: AgentSessionId): ZIO[EventLogService, JorlanError, List[EventLog[Json]]] =
    ZIO.serviceWithZIO[EventLogService](_.replay(sessionId))

}
