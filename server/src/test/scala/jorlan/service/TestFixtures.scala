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

import jorlan.domain.*
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
