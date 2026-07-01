/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

/** Tests the in-memory concurrency behaviour of [[ApprovalService]]: awaitDecision, completeDecision, subscription. */
object ApprovalServiceConcurrencySpec extends ZIOSpecDefault {

  private val serviceLayer: ULayer[ApprovalService] =
    ZLayer.make[ApprovalService](
      InMemoryRepositories.live(),
      CapabilityEvaluatorImpl.live,
      EventLogHub.live,
      ApprovalServiceImpl.live,
    )

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
    suite("ApprovalService concurrency")(
      test("awaitDecision resumes with true when approved concurrently") {
        for {
          svc <- ZIO.service[ApprovalService]
          req = makeRequest(1L)
          fiber  <- svc.awaitDecision(req.id, 5.seconds).fork
          _      <- ZIO.sleep(20.millis)
          _      <- svc.completeDecision(req.id, approved = true)
          result <- fiber.join
        } yield assertTrue(result == Some(true))
      }.provide(serviceLayer),
      test("awaitDecision resumes with false when denied concurrently") {
        for {
          svc <- ZIO.service[ApprovalService]
          req = makeRequest(2L)
          fiber  <- svc.awaitDecision(req.id, 5.seconds).fork
          _      <- ZIO.sleep(20.millis)
          _      <- svc.completeDecision(req.id, approved = false)
          result <- fiber.join
        } yield assertTrue(result == Some(false))
      }.provide(serviceLayer),
      test("race safety: completeDecision before awaitDecision stores pre-decision") {
        for {
          svc <- ZIO.service[ApprovalService]
          req = makeRequest(3L)
          _      <- svc.completeDecision(req.id, approved = true)
          result <- svc.awaitDecision(req.id, 5.seconds)
        } yield assertTrue(result == Some(true))
      }.provide(serviceLayer),
      test("awaitDecision times out when no decision arrives") {
        for {
          svc <- ZIO.service[ApprovalService]
          req = makeRequest(4L)
          result <- svc.awaitDecision(req.id, 50.millis)
        } yield assertTrue(result == None)
      }.provide(serviceLayer),
      test("subscribeToNewRequests receives published requests") {
        for {
          svc    <- ZIO.service[ApprovalService]
          stream <- svc.subscribeToNewRequests
          req = makeRequest(5L)
          fiber <- stream.take(1).runCollect.fork
          _     <- svc.notifyNewRequest(req)
          items <- fiber.join
        } yield assertTrue(items.toList == List(req))
      }.provide(serviceLayer),
      test("multiple subscribers each receive new requests") {
        for {
          svc     <- ZIO.service[ApprovalService]
          stream1 <- svc.subscribeToNewRequests
          stream2 <- svc.subscribeToNewRequests
          req = makeRequest(6L)
          fiber1 <- stream1.take(1).runCollect.fork
          fiber2 <- stream2.take(1).runCollect.fork
          _      <- svc.notifyNewRequest(req)
          items1 <- fiber1.join
          items2 <- fiber2.join
        } yield assertTrue(
          items1.toList == List(req),
          items2.toList == List(req),
        )
      }.provide(serviceLayer),
      test("subscriber stream cleanup: completed subscriber does not receive future requests") {
        for {
          svc    <- ZIO.service[ApprovalService]
          stream <- svc.subscribeToNewRequests
          _      <- stream.take(0).runDrain
          _      <- ZIO.sleep(20.millis)
          req = makeRequest(7L)
          stream2 <- svc.subscribeToNewRequests
          fiber   <- stream2.take(1).timeout(100.millis).runCollect.map(_.toList).fork
          _       <- svc.notifyNewRequest(req)
          items   <- fiber.join
        } yield assertTrue(items == List(req))
      }.provide(serviceLayer),
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)

}
