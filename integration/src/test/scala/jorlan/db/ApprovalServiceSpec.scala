/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import jorlan.*
import jorlan.db.TestFixtures.*
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object ApprovalServiceSpec extends ZIOSpecDefault {

  private val appLayer =
    JorlanContainer.repositoryLayer >+>
      EventLogServiceImpl.live >+>
      PermissionServiceImpl.live >+>
      RiskClassifierImpl.live >+>
      CapabilityEvaluatorImpl.live >+>
      ApprovalPolicyEngineImpl.live >+>
      ApprovalServiceImpl.live

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

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ApprovalService integration")(
      test("Persistent grant → Allowed and CapabilityAllowed event written") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          permRepo <- ZIO.service[PermissionZIORepository]
          eventSvc <- ZIO.service[EventLogService]
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser1", None, T0, T0))
          _ <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("memory.write"),
              None,
              user.id,
              None,
              ApprovalMode.Persistent,
              None,
              None,
              T0,
            ),
          )
          result <- svc.authorize(capReq(user.id, "memory.write"))
          events <- eventSvc.query(EventLogFilter(eventType = Some(EventType.CapabilityAllowed), pageSize = 100))
        } yield assertTrue(
          result == AuthorizationResult.Allowed,
          events.exists(_.actorId.contains(user.id)),
        )
      },
      test("Denied grant → Denied and CapabilityDenied event written") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          permRepo <- ZIO.service[PermissionZIORepository]
          eventSvc <- ZIO.service[EventLogService]
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser2", None, T0, T0))
          _ <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("shell.execute"),
              None,
              user.id,
              None,
              ApprovalMode.Denied,
              None,
              None,
              T0,
            ),
          )
          result <- svc.authorize(capReq(user.id, "shell.execute"))
          events <- eventSvc.query(EventLogFilter(eventType = Some(EventType.CapabilityDenied), pageSize = 100))
        } yield assertTrue(
          result.isInstanceOf[AuthorizationResult.Denied],
          events.exists(_.actorId.contains(user.id)),
        )
      },
      test("PerInvocation grant → PendingApproval with real DB-assigned id") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          permRepo <- ZIO.service[PermissionZIORepository]
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser3", None, T0, T0))
          _ <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("filesystem.write"),
              None,
              user.id,
              None,
              ApprovalMode.PerInvocation,
              None,
              None,
              T0,
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
          userRepo <- ZIO.service[UserZIORepository]
          permRepo <- ZIO.service[PermissionZIORepository]
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser4", None, T0, T0))
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
          userRepo <- ZIO.service[UserZIORepository]
          permRepo <- ZIO.service[PermissionZIORepository]
          svc      <- ZIO.service[ApprovalService]
          user     <- userRepo.upsert(User(UserId.empty, "ASUser5", None, T0, T0))
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
    ).provideLayerShared(appLayer) @@ TestAspect.sequential

}
