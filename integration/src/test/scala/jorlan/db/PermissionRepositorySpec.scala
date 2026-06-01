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
import zio.*
import zio.test.*

object PermissionRepositorySpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PermissionRepository")(
      test("upsert and search capability grants") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          grantee  <- userRepo.upsert(User(UserId.empty, "Grantee1", None, T0, T0))
          grant = CapabilityGrant(
            CapabilityGrantId.empty,
            CapabilityName("shell.execute"),
            None,
            grantee.id,
            None,
            ApprovalMode.Once,
            None,
            None,
            T0,
          )
          saved  <- repo.upsertCapabilityGrant(grant)
          grants <- repo.searchGrants(GrantSearch(userId = grantee.id, pageSize = 20))
        } yield assertTrue(
          saved.id.value > 0L,
          grants.exists(_.id == saved.id),
          grants.exists(_.capability == CapabilityName("shell.execute")),
        )
      },
      test("searchGrants sorted by id desc") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          grantee  <- userRepo.upsert(User(UserId.empty, "Grantee2", None, T0, T0))
          _        <- repo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("memory.read"),
              None,
              grantee.id,
              None,
              ApprovalMode.Persistent,
              None,
              None,
              T0,
            ),
          )
          _ <- repo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("memory.write"),
              None,
              grantee.id,
              None,
              ApprovalMode.Persistent,
              None,
              None,
              T0,
            ),
          )
          desc <- repo.searchGrants(
            GrantSearch(userId = grantee.id, pageSize = 20, sorts = Some(Sort(GrantOrder.Id, OrderDirection.Desc))),
          )
        } yield assertTrue(desc.map(_.id.value) == desc.map(_.id.value).sorted.reverse)
      },
      test("searchGrants sorted by grantedAt asc and desc") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          grantee  <- userRepo.upsert(User(UserId.empty, "Grantee3", None, T0, T0))
          _        <- repo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("skill.invoke"),
              None,
              grantee.id,
              None,
              ApprovalMode.Session,
              None,
              None,
              T0.minusSeconds(10),
            ),
          )
          _ <- repo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("skill.read"),
              None,
              grantee.id,
              None,
              ApprovalMode.Session,
              None,
              None,
              T0.plusSeconds(10),
            ),
          )
          asc <- repo.searchGrants(
            GrantSearch(
              userId = grantee.id,
              pageSize = 20,
              sorts = Some(Sort(GrantOrder.GrantedAt, OrderDirection.Asc)),
            ),
          )
          desc <- repo.searchGrants(
            GrantSearch(
              userId = grantee.id,
              pageSize = 20,
              sorts = Some(Sort(GrantOrder.GrantedAt, OrderDirection.Desc)),
            ),
          )
        } yield assertTrue(
          asc.map(_.createdAt) == asc.map(_.createdAt).sorted,
          desc.map(_.createdAt) == desc.map(_.createdAt).sorted.reverse,
        )
      },
      test("revokeGrant removes the grant") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          grantee  <- userRepo.upsert(User(UserId.empty, "Grantee4", None, T0, T0))
          grant    <- repo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("revoke.me"),
              None,
              grantee.id,
              None,
              ApprovalMode.Once,
              None,
              None,
              T0,
            ),
          )
          count <- repo.revokeGrant(grant.id)
          after <- repo.searchGrants(GrantSearch(userId = grantee.id, pageSize = 20))
        } yield assertTrue(count == 1L, !after.exists(_.id == grant.id))
      },
      test("upsertCapabilityGrant updates mutable fields") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          grantee  <- userRepo.upsert(User(UserId.empty, "Grantee5", None, T0, T0))
          grant    <- repo.upsertCapabilityGrant(
            CapabilityGrant(
              CapabilityGrantId.empty,
              CapabilityName("upd.cap"),
              None,
              grantee.id,
              None,
              ApprovalMode.PerInvocation,
              None,
              None,
              T0,
            ),
          )
          _ <- repo.upsertCapabilityGrant(
            grant.copy(approvalMode = ApprovalMode.Persistent, expiresAt = Some(T0.plusSeconds(3600))),
          )
          grants <- repo.searchGrants(GrantSearch(userId = grantee.id, pageSize = 20))
        } yield assertTrue(grants.exists(g => g.id == grant.id && g.approvalMode == ApprovalMode.Persistent))
      },
      test("createApprovalRequest and getApprovalRequest") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          user     <- userRepo.upsert(User(UserId.empty, "Requestor1", None, T0, T0))
          req = ApprovalRequest(
            ApprovalRequestId.empty,
            CapabilityName("dangerous.op"),
            None,
            None,
            user.id,
            None,
            RiskClass.ExternalEffect,
            ApprovalStatus.Pending,
            T0,
            Some(T0.plusSeconds(300)),
          )
          saved   <- repo.createApprovalRequest(req)
          fetched <- repo.getApprovalRequest(saved.id)
        } yield assertTrue(
          saved.id.value > 0L,
          fetched.isDefined,
          fetched.exists(_.capability == CapabilityName("dangerous.op")),
          fetched.exists(_.status == ApprovalStatus.Pending),
        )
      },
      test("cancelApprovalRequest transitions status to Cancelled") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          user     <- userRepo.upsert(User(UserId.empty, "Requestor2", None, T0, T0))
          req      <- repo.createApprovalRequest(
            ApprovalRequest(
              ApprovalRequestId.empty,
              CapabilityName("cancel.op"),
              None,
              None,
              user.id,
              None,
              RiskClass.WorkspaceWrite,
              ApprovalStatus.Pending,
              T0,
              None,
            ),
          )
          count   <- repo.cancelApprovalRequest(req.id)
          fetched <- repo.getApprovalRequest(req.id)
        } yield assertTrue(
          count == 1L,
          fetched.exists(_.status == ApprovalStatus.Cancelled),
        )
      },
      test("recordApprovalDecision persists the decision") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          user     <- userRepo.upsert(User(UserId.empty, "Requestor3", None, T0, T0))
          approver <- userRepo.upsert(User(UserId.empty, "Approver1", None, T0, T0))
          req      <- repo.createApprovalRequest(
            ApprovalRequest(
              ApprovalRequestId.empty,
              CapabilityName("approve.op"),
              None,
              None,
              user.id,
              None,
              RiskClass.Destructive,
              ApprovalStatus.Pending,
              T0,
              None,
            ),
          )
          decision = ApprovalDecision(
            ApprovalDecisionId.empty,
            req.id,
            approver.id,
            ApprovalStatus.Approved,
            None,
            T0,
          )
          saved <- repo.recordApprovalDecision(decision)
        } yield assertTrue(
          saved.id.value > 0L,
          saved.decision == ApprovalStatus.Approved,
          saved.approvalRequestId == req.id,
        )
      },
      test("searchRoles returns empty list for user with no roles") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          user     <- userRepo.upsert(User(UserId.empty, "NoRoleUser", None, T0, T0))
          roles    <- repo.searchRoles(RoleSearch(userId = user.id, pageSize = 20))
        } yield assertTrue(roles.isEmpty)
      },
      test("searchPermissions returns empty list when no permissions exist for user") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          user     <- userRepo.upsert(User(UserId.empty, "NoPermUser", None, T0, T0))
          perms    <- repo.searchPermissions(PermissionSearch(userId = Some(user.id), pageSize = 20))
        } yield assertTrue(perms.isEmpty)
      },
      test("searchPermissions with sort variants returns empty gracefully") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          user     <- userRepo.upsert(User(UserId.empty, "SortPermUser", None, T0, T0))
          byIdDesc <- repo.searchPermissions(
            PermissionSearch(
              userId = Some(user.id),
              pageSize = 20,
              sorts = Some(Sort(PermissionOrder.Id, OrderDirection.Desc)),
            ),
          )
          byResourceAsc <- repo.searchPermissions(
            PermissionSearch(
              userId = Some(user.id),
              pageSize = 20,
              sorts = Some(Sort(PermissionOrder.Resource, OrderDirection.Asc)),
            ),
          )
          byResourceDesc <- repo.searchPermissions(
            PermissionSearch(
              userId = Some(user.id),
              pageSize = 20,
              sorts = Some(Sort(PermissionOrder.Resource, OrderDirection.Desc)),
            ),
          )
          byActionAsc <- repo.searchPermissions(
            PermissionSearch(
              userId = Some(user.id),
              pageSize = 20,
              sorts = Some(Sort(PermissionOrder.Action, OrderDirection.Asc)),
            ),
          )
          byActionDesc <- repo.searchPermissions(
            PermissionSearch(
              userId = Some(user.id),
              pageSize = 20,
              sorts = Some(Sort(PermissionOrder.Action, OrderDirection.Desc)),
            ),
          )
        } yield assertTrue(
          byIdDesc.isEmpty,
          byResourceAsc.isEmpty,
          byResourceDesc.isEmpty,
          byActionAsc.isEmpty,
          byActionDesc.isEmpty,
        )
      },
      test("getRole returns None for non-existent id") {
        for {
          repo   <- ZIO.service[PermissionZIORepository]
          result <- repo.getRole(RoleId(999999L))
        } yield assertTrue(result.isEmpty)
      },
      test("searchRoles with sort variants returns empty gracefully") {
        for {
          userRepo <- ZIO.service[UserZIORepository]
          repo     <- ZIO.service[PermissionZIORepository]
          user     <- userRepo.upsert(User(UserId.empty, "SortRoleUser", None, T0, T0))
          byName   <- repo.searchRoles(
            RoleSearch(userId = user.id, pageSize = 20, sorts = Some(Sort(RoleOrder.Name, OrderDirection.Asc))),
          )
          byNameDesc <- repo.searchRoles(
            RoleSearch(userId = user.id, pageSize = 20, sorts = Some(Sort(RoleOrder.Name, OrderDirection.Desc))),
          )
          byIdDesc <- repo.searchRoles(
            RoleSearch(userId = user.id, pageSize = 20, sorts = Some(Sort(RoleOrder.Id, OrderDirection.Desc))),
          )
        } yield assertTrue(byName.isEmpty, byNameDesc.isEmpty, byIdDesc.isEmpty)
      },
    ).provideLayerShared(JorlanContainer.repositoryLayer) @@ TestAspect.sequential

}
