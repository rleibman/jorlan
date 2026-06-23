/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db

import jorlan.{AgentId, AgentSessionId, EventLog, EventLogId, EventType, UserId}
import jorlan.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant

/** Shared test helpers for constructing domain fixtures without repetitive boilerplate. */
object TestFixtures {

  given JsonEncoder[Unit] = JsonEncoder[Json].contramap(_ => Json.Null)

  /** A fixed reference timestamp for deterministic tests. */
  val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  /** Construct an [[EventLog]] with no resource and sensible defaults. */
  def testEvent(
    eventType:   EventType,
    actorId:     Option[UserId] = None,
    agentId:     Option[AgentId] = None,
    sessionId:   Option[AgentSessionId] = None,
    payloadJson: Option[Json] = None,
    occurredAt:  Instant = T0,
  ): EventLog[Unit] = EventLog(EventLogId.empty, eventType, actorId, agentId, sessionId, None, payloadJson, occurredAt)

  /** Construct an [[EventLog]] with an explicit resource value (no default args to avoid overload conflict). */
  def testEvent[R](
    eventType: EventType,
    resource:  Option[R],
  ): EventLog[R] = EventLog(EventLogId.empty, eventType, None, None, None, resource, None, T0)

}
