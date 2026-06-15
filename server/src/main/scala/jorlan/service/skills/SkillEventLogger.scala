/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.ZIORepositories
import jorlan.*
import zio.*
import zio.json.ast.Json

/** Shared event-logging helper mixed into all built-in [[jorlan.connector.Skill]] implementations. */
private[service] trait SkillEventLogger {

  protected def repo: ZIORepositories

  /** Parse a hard-coded JSON schema literal, failing at startup if the literal is malformed. */
  protected def parseSchema(literal: String): Json =
    Json.decoder
      .decodeJson(literal).fold(
        err => throw new IllegalArgumentException(s"Malformed tool schema literal: $err"),
        identity,
      )

  protected def logEvent(
    ctx:       InvocationContext,
    eventType: EventType,
    payload:   Json,
  ): UIO[Unit] =
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
        ).orDie.unit
    }

}
