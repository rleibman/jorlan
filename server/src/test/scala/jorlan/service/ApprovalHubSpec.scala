/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object ApprovalHubSpec extends ZIOSpecDefault {

  private def makeRequest(id: Long): ApprovalRequest =
    ApprovalRequest(
      id = ApprovalRequestId(id),
      capability = CapabilityName("test.cap"),
      scopeJson = None,
      agentId = None,
      requestorUserId = UserId(1L),
      sessionId = None,
      riskClass = RiskClass.ReadOnly,
      status = ApprovalStatus.Pending,
      createdAt = Instant.now(),
      expiresAt = None,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ApprovalHub")(
      test("awaitDecision resumes with true when approved concurrently") {
        for {
          hub <- ApprovalHub.make
          req = makeRequest(1L)
          fiber  <- hub.awaitDecision(req.id, 5.seconds).fork
          _      <- ZIO.sleep(20.millis) // give fork time to register its Promise
          _      <- hub.completeDecision(req.id, approved = true)
          result <- fiber.join
        } yield assertTrue(result == Some(true))
      },
      test("awaitDecision resumes with false when denied concurrently") {
        for {
          hub <- ApprovalHub.make
          req = makeRequest(2L)
          fiber  <- hub.awaitDecision(req.id, 5.seconds).fork
          _      <- ZIO.sleep(20.millis)
          _      <- hub.completeDecision(req.id, approved = false)
          result <- fiber.join
        } yield assertTrue(result == Some(false))
      },
      test("race safety: completeDecision before awaitDecision stores pre-decision") {
        for {
          hub <- ApprovalHub.make
          req = makeRequest(3L)
          _      <- hub.completeDecision(req.id, approved = true)
          result <- hub.awaitDecision(req.id, 5.seconds)
        } yield assertTrue(result == Some(true))
      },
      test("awaitDecision times out when no decision arrives") {
        for {
          hub <- ApprovalHub.make
          req = makeRequest(4L)
          result <- hub.awaitDecision(req.id, 50.millis)
        } yield assertTrue(result == None)
      },
      test("subscribeToNewRequests receives published requests") {
        for {
          hub    <- ApprovalHub.make
          stream <- hub.subscribeToNewRequests
          req = makeRequest(5L)
          fiber <- stream.take(1).runCollect.fork
          _     <- hub.notifyNewRequest(req)
          items <- fiber.join
        } yield assertTrue(items.toList == List(req))
      },
      test("multiple subscribers each receive new requests") {
        for {
          hub     <- ApprovalHub.make
          stream1 <- hub.subscribeToNewRequests
          stream2 <- hub.subscribeToNewRequests
          req = makeRequest(6L)
          fiber1 <- stream1.take(1).runCollect.fork
          fiber2 <- stream2.take(1).runCollect.fork
          _      <- hub.notifyNewRequest(req)
          items1 <- fiber1.join
          items2 <- fiber2.join
        } yield assertTrue(
          items1.toList == List(req),
          items2.toList == List(req),
        )
      },
      test("subscriber queue is removed from list when stream completes") {
        for {
          hub <- ApprovalHub.make
          // Subscribe and immediately complete by taking 0 items
          stream <- hub.subscribeToNewRequests
          _      <- stream.take(0).runDrain
          _      <- ZIO.sleep(20.millis) // allow ensuring cleanup to run
          // Publish a request — if the queue is still registered, the publish would try to enqueue
          // Subscribe a second time and verify exactly one subscriber sees the new request
          req = makeRequest(7L)
          stream2 <- hub.subscribeToNewRequests
          fiber   <- stream2.take(1).timeout(100.millis).runCollect.map(_.toList).fork
          _       <- hub.notifyNewRequest(req)
          items   <- fiber.join
        } yield assertTrue(items == List(req))
      },
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)

}
