/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.ZIORepositories
import zio.*
import zio.json.ast.Json

/** Shared event-logging helper mixed into all built-in [[jorlan.connector.Skill]] implementations. */
@deprecated("Should not use, we need to log events generically and not all over the place. Skills in outside packages should have no say into which events get logged or not.")
trait SkillEventLogger {

  protected def repo: ZIORepositories

  protected def logEvent(
    ctx:       InvocationContext,
    eventType: EventType,
    payload:   Json,
  ): IO[JorlanError, Unit] =
    Clock.instant.flatMap { now =>
      repo.eventLog
        .append(
          EventLog(
            id = EventLogId.empty,
            eventType = eventType,
            actorId = Some(ctx.actorId),
            agentId = ctx.agentId,
            sessionId = ctx.sessionId,
            resource = Option.empty[String],
            payloadJson = Some(payload),
            occurredAt = now,
          ),
        ).unit
    }

}
