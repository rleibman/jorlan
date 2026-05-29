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

/** Stub notifier for Phase 8. Writes an [[EventType.ApprovalRequested]] event to the audit log.
  *
  * Real delivery (Telegram, in-app) is wired in Phase 11.
  */
class HumanApprovalNotifier(eventLog: EventLogService) {

  def notifyApprovalRequired(
    request: ApprovalRequest,
  ): IO[JorlanError, Unit] =
    Clock.instant.flatMap { now =>
      eventLog
        .log(
          EventLog(
            id = EventLogId.empty,
            eventType = EventType.ApprovalRequested,
            actorId = None,
            agentId = None,
            sessionId = None,
            resource = Some(request.id),
            payloadJson = None,
            occurredAt = now,
          ),
        )
        .unit
    }

}

object HumanApprovalNotifier {

  val live: URLayer[EventLogService, HumanApprovalNotifier] =
    ZLayer.fromFunction((eventLog: EventLogService) => new HumanApprovalNotifier(eventLog))

}
