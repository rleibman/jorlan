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
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*
import zio.test.Assertion.*

object HumanApprovalNotifierSpec extends ZIOSpecDefault {

  private val freshLayers: ULayer[HumanApprovalNotifier & EventLogService] = {
    val eventLogRepo = InMemoryRepositories.InMemoryEventLogRepo.layer
    val eventLogLayer = eventLogRepo >>> EventLogServiceImpl.live
    eventLogLayer >>> (HumanApprovalNotifierImpl.live ++ eventLogLayer)
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("HumanApprovalNotifier")(
      test("notifyApprovalRequired logs ApprovalRequested event") {
        for {
          notifier <- ZIO.service[HumanApprovalNotifier]
          request = ApprovalRequest(
            id = ResourceId("test-approval"),
            requiredLevel = TrustLevel.High,
            createdAt = java.time.Instant.now(),
          )
          _      <- notifier.notifyApprovalRequired(request)
          events <- ZIO.serviceWithZIO[EventLogService](
            _.query(
              EventLogFilter(eventType = Some(EventType.ApprovalRequested)),
            ),
          )
        } yield assertTrue(
          events.nonEmpty,
          events.head.resource.contains(request.id),
          events.head.eventType == EventType.ApprovalRequested,
        )
      }.provide(freshLayers),
      test("notifyApprovalRequired succeeds for valid requests") {
        for {
          notifier <- ZIO.service[HumanApprovalNotifier]
          request = ApprovalRequest(
            id = ResourceId("another-approval"),
            requiredLevel = TrustLevel.Medium,
            createdAt = java.time.Instant.now(),
          )
          result <- notifier.notifyApprovalRequired(request)
        } yield assertTrue(result == (()))
      }.provide(freshLayers),
    )

}
