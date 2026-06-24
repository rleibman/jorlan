/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db

import jorlan.*
import jorlan.db.TestFixtures.*
import jorlan.db.repository.*
import jorlan.*
import jorlan.service.*
import zio.*
import zio.test.*

import java.time.Instant

object ApprovalServiceSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: TaskLayer[ZIORepositories] = JorlanContainer.repositoryLayer
  private def capReq(
    userId: UserId,
    cap:    String,
  ): CapabilityRequest =
    CapabilityRequest(
      capability = CapabilityName(cap),
      requestorId = userId,
      agentId = None,
      sessionId = None,
      resourceConstraints = None,
    )

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    (suite("ApprovalService integration")(
      test("Persistent grant → Allowed and CapabilityAllowed event written") {
        for {
          userRepo     <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo     <- ZIO.serviceWith[ZIORepositories](_.permission)
          eventLogRepo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          svc          <- ZIO.service[ApprovalService]
          user         <- userRepo.upsert(User(UserId.empty, "ASUser1", "ASUser1@test.local", T0, T0))
          _            <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              id = CapabilityGrantId.empty,
              capability = CapabilityName("memory.write"),
              scopeJson = None,
              granteeId = user.id.value,
              granteeType = GranteeType.User,
              grantorId = None,
              approvalMode = ApprovalMode.Persistent,
              expiresAt = None,
              resourceConstraints = None,
              createdAt = T0,
            ),
          )
          result <- svc.authorize(capReq(user.id, "memory.write"))
          events <- eventLogRepo.search(EventLogFilter(eventType = Some(EventType.CapabilityAllowed), pageSize = 100))
        } yield assertTrue(
          result == AuthorizationResult.Allowed,
          events.exists(_.actorId.contains(user.id)),
        )
      },
      test("Denied grant → Denied and CapabilityDenied event written") {
        for {
          userRepo     <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo     <- ZIO.serviceWith[ZIORepositories](_.permission)
          eventLogRepo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
          svc          <- ZIO.service[ApprovalService]
          user         <- userRepo.upsert(User(UserId.empty, "ASUser2", "ASUser2@test.local", T0, T0))
          _            <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              id = CapabilityGrantId.empty,
              capability = CapabilityName("shell.execute"),
              scopeJson = None,
              granteeId = user.id.value,
              granteeType = GranteeType.User,
              grantorId = None,
              approvalMode = ApprovalMode.Denied,
              expiresAt = None,
              resourceConstraints = None,
              createdAt = T0,
            ),
          )
          result <- svc.authorize(capReq(user.id, "shell.execute"))
          events <- eventLogRepo.search(EventLogFilter(eventType = Some(EventType.CapabilityDenied), pageSize = 100))
        } yield assertTrue(
          result.isInstanceOf[AuthorizationResult.Denied],
          events.exists(_.actorId.contains(user.id)),
        )
      },
      test("PerInvocation grant → PendingApproval with real DB-assigned id") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser3", "ASUser3@test.local", T0, T0))
          _        <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              id = CapabilityGrantId.empty,
              capability = CapabilityName("filesystem.write"),
              scopeJson = None,
              granteeId = user.id.value,
              granteeType = GranteeType.User,
              grantorId = None,
              approvalMode = ApprovalMode.PerInvocation,
              expiresAt = None,
              resourceConstraints = None,
              createdAt = T0,
            ),
          )
          result <- svc.authorize(capReq(user.id, "filesystem.write"))
        } yield assertTrue(result match {
          case AuthorizationResult.PendingApproval(req, ApprovalMode.PerInvocation) =>
            req.id != ApprovalRequestId.empty && req.id.value > 0L
          case _ => false
        })
      },
      test("expireStaleRequests returns count of expired requests") {
        for {
          _        <- TestClock.setTime(T0)
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser4", "ASUser4@test.local", T0, T0))
          // Two pending requests with past expiresAt (truly in the past)
          _ <- permRepo.createApprovalRequest(
            ApprovalRequest(
              ApprovalRequestId.empty,
              CapabilityName("stale.cap1"),
              None,
              None,
              user.id,
              None,
              RiskClass.ExternalEffect,
              ApprovalStatus.Pending,
              T0,
              Some(T0.minusSeconds(60)),
            ),
          )
          _ <- permRepo.createApprovalRequest(
            ApprovalRequest(
              ApprovalRequestId.empty,
              CapabilityName("stale.cap2"),
              None,
              None,
              user.id,
              None,
              RiskClass.ExternalEffect,
              ApprovalStatus.Pending,
              T0,
              Some(T0.minusSeconds(120)),
            ),
          )
          count <- svc.expireStaleRequests()
        } yield assertTrue(count >= 2L)
      },
      test("expireStaleRequests returns 0 when no stale requests") {
        for {
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo <- ZIO.serviceWith[ZIORepositories](_.permission)
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser5", "ASUser5@test.local", T0, T0))
          // A pending request with future expiresAt
          _ <- permRepo.createApprovalRequest(
            ApprovalRequest(
              ApprovalRequestId.empty,
              CapabilityName("future.cap"),
              None,
              None,
              user.id,
              None,
              RiskClass.ExternalEffect,
              ApprovalStatus.Pending,
              T0,
              Some(Instant.parse("2030-01-01T00:00:00Z")),
            ),
          )
          count <- svc.expireStaleRequests()
        } yield assertTrue(count == 0L)
      },
    ).provideSomeLayer[ZIORepositories](
      ZLayer.makeSome[ZIORepositories, CapabilityEvaluator & ApprovalService](
        CapabilityEvaluatorImpl.live,
        EventLogHub.live,
        ApprovalServiceImpl.live,
      ),
    )) @@ TestAspect.sequential

}
