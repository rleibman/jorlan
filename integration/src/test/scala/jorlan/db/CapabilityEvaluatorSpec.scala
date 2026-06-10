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

object CapabilityEvaluatorSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

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
    (suite("CapabilityEvaluator integration")(
      test("default deny when user has no permissions or grants") {
        for {
          userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
          evaluator <- ZIO.service[CapabilityEvaluator]
          user      <- userRepo.upsert(User(UserId.empty, "CEUser1", "CEUser1@test.local", T0, T0))
          result    <- evaluator.evaluate(capReq(user.id, "shell.execute"))
        } yield assertTrue(result == EvaluationResult.DefaultDeny)
      },
      test("direct user permission → ResourcePermissionAllows") {
        for {
          userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo  <- ZIO.serviceWith[ZIORepositories](_.permission)
          evaluator <- ZIO.service[CapabilityEvaluator]
          user      <- userRepo.upsert(User(UserId.empty, "CEUser2", "CEUser2@test.local", T0, T0))
          _         <- permRepo.upsertPermission(
            Permission(PermissionId.empty, None, Some(user.id), "shell", "execute", None),
          )
          result <- evaluator.evaluate(capReq(user.id, "shell.execute"))
        } yield assertTrue(result == EvaluationResult.ResourcePermissionAllows)
      },
      test("role-derived permission → RolePermissionAllows") {
        for {
          userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo  <- ZIO.serviceWith[ZIORepositories](_.permission)
          evaluator <- ZIO.service[CapabilityEvaluator]
          user      <- userRepo.upsert(User(UserId.empty, "CEUser3", "CEUser3@test.local", T0, T0))
          role      <- permRepo.upsertRole(Role(RoleId.empty, "shell-operator", None))
          _         <- permRepo.assignRole(user.id, role.id)
          _         <- permRepo.upsertPermission(
            Permission(PermissionId.empty, Some(role.id), None, "memory", "read", None),
          )
          result <- evaluator.evaluate(capReq(user.id, "memory.read"))
        } yield assertTrue(result == EvaluationResult.RolePermissionAllows)
      },
      test("explicit deny grant wins over direct permission") {
        for {
          userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo  <- ZIO.serviceWith[ZIORepositories](_.permission)
          evaluator <- ZIO.service[CapabilityEvaluator]
          user      <- userRepo.upsert(User(UserId.empty, "CEUser4", "CEUser4@test.local", T0, T0))
          _         <- permRepo.upsertPermission(
            Permission(PermissionId.empty, None, Some(user.id), "shell", "execute", None),
          )
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
          result <- evaluator.evaluate(capReq(user.id, "shell.execute"))
        } yield assertTrue(result == EvaluationResult.ExplicitDeny)
      },
      test("active non-denied grant → CapabilityGrantAllows") {
        for {
          userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo  <- ZIO.serviceWith[ZIORepositories](_.permission)
          evaluator <- ZIO.service[CapabilityEvaluator]
          user      <- userRepo.upsert(User(UserId.empty, "CEUser5", "CEUser5@test.local", T0, T0))
          grant     <- permRepo.upsertCapabilityGrant(
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
          result <- evaluator.evaluate(capReq(user.id, "memory.write"))
        } yield assertTrue(result match {
          case EvaluationResult.CapabilityGrantAllows(g) => g.id == grant.id
          case _                                         => false
        })
      },
      test("multi-grant with one Denied among non-denied → ExplicitDeny") {
        for {
          userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo  <- ZIO.serviceWith[ZIORepositories](_.permission)
          evaluator <- ZIO.service[CapabilityEvaluator]
          user      <- userRepo.upsert(User(UserId.empty, "CEUser6", "CEUser6@test.local", T0, T0))
          _         <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("skill.invoke"),
              None,
              user.id,
              None,
              ApprovalMode.Persistent,
              None,
              None,
              T0,
            ),
          )
          _ <- permRepo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("skill.invoke"),
              None,
              user.id,
              None,
              ApprovalMode.Denied,
              None,
              None,
              T0,
            ),
          )
          result <- evaluator.evaluate(capReq(user.id, "skill.invoke"))
        } yield assertTrue(result == EvaluationResult.ExplicitDeny)
      },
      test("3-part capability name uses first-dot split for permission matching") {
        for {
          userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
          permRepo  <- ZIO.serviceWith[ZIORepositories](_.permission)
          evaluator <- ZIO.service[CapabilityEvaluator]
          user      <- userRepo.upsert(User(UserId.empty, "CEUser7", "CEUser7@test.local", T0, T0))
          // shell.sudo.execute → resource="shell", action="sudo.execute"
          _ <- permRepo.upsertPermission(
            Permission(PermissionId.empty, None, Some(user.id), "shell", "sudo.execute", None),
          )
          result <- evaluator.evaluate(capReq(user.id, "shell.sudo.execute"))
        } yield assertTrue(result == EvaluationResult.ResourcePermissionAllows)
      },
    ).provideSomeLayer[ZIORepositories](CapabilityEvaluatorImpl.live)) @@ TestAspect.sequential

}
