/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.*

import java.time.Instant

enum EventLogOrder { case Id, OccurredAt }

/** Query parameters for searching the event log. All filters are optional. `pageSize` must be between 1 and
  * [[EventLogFilter.MaxLimit]].
  */
case class EventLogFilter(
  eventType: Option[EventType] = None,
  agentId:   Option[AgentId] = None,
  sessionId: Option[AgentSessionId] = None,
  from:      Option[Instant] = None,
  to:        Option[Instant] = None,
  page:      Int = 0,
  pageSize:  Int = 100,
  sorts:     Option[Sort[EventLogOrder]] = None,
) extends Search[EventLogOrder]

object EventLogFilter {

  val MaxLimit: Int = 10_000

  /** Validates `pageSize` is within the allowed range. Call before passing to the repository. */
  def validatePageSize(filter: EventLogFilter): Either[String, EventLogFilter] =
    if (filter.pageSize < 1 || filter.pageSize > MaxLimit)
      Left(s"pageSize must be between 1 and $MaxLimit, got ${filter.pageSize}")
    else Right(filter)

}
