/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.{ZIOEventLogRepository, ZIORepositories}
import jorlan.*
import zio.*

/** Phase 8 stub — writes an [[EventType.ApprovalRequested]] event to the audit log. Real delivery (Telegram, in-app) is
  * wired in Phase 11.
  */
class HumanApprovalNotifierImpl(eventLogRepo: ZIOEventLogRepository) extends HumanApprovalNotifier {

  override def notifyApprovalRequired(request: ApprovalRequest): IO[JorlanError, Unit] =
    Clock.instant.flatMap { now =>
      eventLogRepo
        .append(
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

object HumanApprovalNotifierImpl {

  val live: URLayer[ZIORepositories, HumanApprovalNotifier] =
    ZLayer.fromFunction((repo: ZIORepositories) => HumanApprovalNotifierImpl(repo.eventLog))

}
