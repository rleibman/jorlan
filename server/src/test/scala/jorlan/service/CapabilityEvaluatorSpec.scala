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
import jorlan
.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

import java.time.Instant

object CapabilityEvaluatorSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)
  private val now = Instant.now()

  private def makeGrant(
    capability:   CapabilityName,
    approvalMode: ApprovalMode,
  ): CapabilityGrant =
    CapabilityGrant(
      id = CapabilityGrantId.empty,
      capability = capability,
      scopeJson = None,
      granteeId = userId,
      grantorId = None,
      approvalMode = approvalMode,
      expiresAt = None,
      resourceConstraints = None,
      createdAt = now,
    )

  private def seedGrant(
    capability:   CapabilityName,
    approvalMode: ApprovalMode,
  ): ZIO[ZIORepositories, Nothing, Unit] =
    ZIO
      .serviceWithZIO[ZIORepositories](
        _.permission.upsertCapabilityGrant(makeGrant(capability, approvalMode)),
      ).orDie.unit

  private def seedDirectPermission(
    resource: String,
    action:   String,
  ): ZIO[ZIORepositories, Nothing, Unit] =
    ZIO
      .serviceWithZIO[ZIORepositories](
        _.permission.upsertPermission(
          Permission(
            id = PermissionId.empty,
            roleId = None,
            userId = Some(userId),
            resource = resource,
            action = action,
            scope = None,
          ),
        ),
      ).orDie.unit

  private def mkRequest(capability: String): CapabilityRequest =
    CapabilityRequest(
      capability = CapabilityName(capability),
      requestorId = userId,
      agentId = None,
      sessionId = None,
      resourceConstraints = None,
    )

  private val testLayers: ULayer[ZIORepositories & CapabilityEvaluator] =
    ZLayer.make[ZIORepositories & CapabilityEvaluator](
      InMemoryRepositories.live(),
      CapabilityEvaluatorImpl.live,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CapabilityEvaluator")(
      test("ExplicitDeny when a Denied grant exists") {
        for {
          _      <- seedGrant(CapabilityName("shell.execute"), ApprovalMode.Denied)
          eval   <- ZIO.service[CapabilityEvaluator]
          result <- eval.evaluate(mkRequest("shell.execute"))
        } yield assertTrue(result == EvaluationResult.ExplicitDeny)
      }.provide(testLayers),
      test("ResourcePermissionAllows when direct user permission exists") {
        for {
          _      <- seedDirectPermission("shell", "execute")
          eval   <- ZIO.service[CapabilityEvaluator]
          result <- eval.evaluate(mkRequest("shell.execute"))
        } yield assertTrue(result == EvaluationResult.ResourcePermissionAllows)
      }.provide(testLayers),
      test("CapabilityGrantAllows when a Persistent grant exists (no permission row)") {
        for {
          _      <- seedGrant(CapabilityName("memory.write"), ApprovalMode.Persistent)
          eval   <- ZIO.service[CapabilityEvaluator]
          result <- eval.evaluate(mkRequest("memory.write"))
        } yield result match {
          case EvaluationResult.CapabilityGrantAllows(grant) =>
            assertTrue(grant.capability == CapabilityName("memory.write"))
          case _ => assertTrue(false)
        }
      }.provide(testLayers),
      test("DefaultDeny when no grants or permissions exist") {
        for {
          eval   <- ZIO.service[CapabilityEvaluator]
          result <- eval.evaluate(mkRequest("shell.execute"))
        } yield assertTrue(result == EvaluationResult.DefaultDeny)
      }.provide(testLayers),
      test("capability with no dot uses full name as resource and 'use' as action") {
        for {
          _      <- seedDirectPermission("admin", "use")
          eval   <- ZIO.service[CapabilityEvaluator]
          result <- eval.evaluate(mkRequest("admin"))
        } yield assertTrue(result == EvaluationResult.ResourcePermissionAllows)
      }.provide(testLayers),
      test("ExplicitDeny takes precedence over Persistent grant for same capability") {
        for {
          _      <- seedGrant(CapabilityName("workspace.write"), ApprovalMode.Persistent)
          _      <- seedGrant(CapabilityName("workspace.write"), ApprovalMode.Denied)
          eval   <- ZIO.service[CapabilityEvaluator]
          result <- eval.evaluate(mkRequest("workspace.write"))
        } yield assertTrue(result == EvaluationResult.ExplicitDeny)
      }.provide(testLayers),
      test("RolePermissionAllows when user has a role with the matching permission") {
        for {
          perm <- ZIO.serviceWith[ZIORepositories](_.permission)
          role <- perm.upsertRole(Role(RoleId.empty, "tester", None)).orDie
          _    <- perm.assignRole(userId, role.id).orDie
          _    <- perm
            .upsertPermission(
              Permission(
                id = PermissionId.empty,
                roleId = Some(role.id),
                userId = None,
                resource = "files",
                action = "read",
                scope = None,
              ),
            ).orDie
          eval   <- ZIO.service[CapabilityEvaluator]
          result <- eval.evaluate(mkRequest("files.read"))
        } yield assertTrue(result == EvaluationResult.RolePermissionAllows)
      }.provide(testLayers),
    )

}
