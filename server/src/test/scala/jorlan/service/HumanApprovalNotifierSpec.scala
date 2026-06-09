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
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

object HumanApprovalNotifierSpec extends ZIOSpec[HumanApprovalNotifier & ZIORepositories] {

  override def bootstrap: ULayer[HumanApprovalNotifier & ZIORepositories] =
    ZLayer.make[HumanApprovalNotifier & ZIORepositories](
      InMemoryRepositories.live(),
      HumanApprovalNotifierImpl.live,
    )

  private def makeRequest(id: Long): ApprovalRequest =
    ApprovalRequest(
      id = ApprovalRequestId(id),
      capability = CapabilityName("test.capability"),
      scopeJson = None,
      agentId = None,
      requestorUserId = UserId(1L),
      sessionId = None,
      riskClass = RiskClass.ReadOnly,
      status = ApprovalStatus.Pending,
      createdAt = java.time.Instant.now(),
      expiresAt = None,
    )

  override def spec: Spec[HumanApprovalNotifier & ZIORepositories & TestEnvironment & Scope, Any] =
    suite("HumanApprovalNotifier")(
      test("notifyApprovalRequired logs ApprovalRequested event") {
        val request = makeRequest(1L)
        for {
          notifier <- ZIO.service[HumanApprovalNotifier]
          _        <- notifier.notifyApprovalRequired(request)
          events   <- ZIO.serviceWithZIO[ZIORepositories](
            _.eventLog.search(EventLogFilter(eventType = Some(EventType.ApprovalRequested))),
          )
        } yield assertTrue(
          events.nonEmpty,
          events.head.eventType == EventType.ApprovalRequested,
        )
      },
      test("notifyApprovalRequired succeeds for a second request") {
        val request = makeRequest(2L)
        for {
          notifier <- ZIO.service[HumanApprovalNotifier]
          result   <- notifier.notifyApprovalRequired(request)
        } yield assertTrue(result == ())
      },
    )

}
