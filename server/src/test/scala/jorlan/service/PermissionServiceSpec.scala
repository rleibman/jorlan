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
import jorlan.db.repository.{EventLogZIORepository, PermissionZIORepository, RepositoryTask}
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object PermissionServiceSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  // ─── In-memory permission repository ─────────────────────────────────────────

  private class InMemoryPermissionRepo(
    roleIdGen:     Ref[Long],
    roles:         Ref[Map[Long, Role]],
    userRoles:     Ref[Map[(Long, Long), Unit]],
    permIdGen:     Ref[Long],
    permissions:   Ref[Map[Long, Permission]],
    grantIdGen:    Ref[Long],
    grants:        Ref[Map[Long, CapabilityGrant]],
    approvalIdGen: Ref[Long],
    approvals:     Ref[Map[Long, ApprovalRequest]],
    decisions:     Ref[Map[Long, ApprovalDecision]],
  ) extends PermissionZIORepository {

    override def getRole(id: RoleId): RepositoryTask[Option[Role]] =
      roles.get.map(_.get(id.value))

    override def searchRoles(s: RoleSearch): RepositoryTask[List[Role]] =
      roles.get.map(_.values.toList)

    override def upsertRole(role: Role): RepositoryTask[Role] =
      for {
        id <- if (role.id == RoleId.empty) roleIdGen.updateAndGet(_ + 1) else ZIO.succeed(role.id.value)
        saved = role.copy(id = RoleId(id))
        _ <- roles.update(_.updated(id, saved))
      } yield saved

    override def deleteRole(id: RoleId): RepositoryTask[Long] =
      roles.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value))
        else (0L, m)
      }

    override def assignRole(
      userId: UserId,
      roleId: RoleId,
    ): RepositoryTask[Unit] = userRoles.update(_.updated((userId.value, roleId.value), ()))

    override def removeRole(
      userId: UserId,
      roleId: RoleId,
    ): RepositoryTask[Unit] = userRoles.update(_.removed((userId.value, roleId.value)))

    override def searchPermissions(s: PermissionSearch): RepositoryTask[List[Permission]] =
      permissions.get.map { ps =>
        ps.values.toList
          .filter(p => s.roleId.forall(id => p.roleId.contains(id)))
          .filter(p => s.userId.forall(id => p.userId.contains(id)))
      }

    override def upsertPermission(permission: Permission): RepositoryTask[Permission] =
      for {
        id <-
          if (permission.id == PermissionId.empty) permIdGen.updateAndGet(_ + 1)
          else ZIO.succeed(permission.id.value)
        saved = permission.copy(id = PermissionId(id))
        _ <- permissions.update(_.updated(id, saved))
      } yield saved

    override def deletePermission(id: PermissionId): RepositoryTask[Long] =
      permissions.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value))
        else (0L, m)
      }

    override def upsertCapabilityGrant(grant: CapabilityGrant): RepositoryTask[CapabilityGrant] =
      for {
        id <-
          if (grant.id == CapabilityGrantId.empty) grantIdGen.updateAndGet(_ + 1)
          else ZIO.succeed(grant.id.value)
        saved = grant.copy(id = CapabilityGrantId(id))
        _ <- grants.update(_.updated(id, saved))
      } yield saved

    override def revokeGrant(id: CapabilityGrantId): RepositoryTask[Long] =
      grants.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value))
        else (0L, m)
      }

    override def searchGrants(s: GrantSearch): RepositoryTask[List[CapabilityGrant]] =
      grants.get.map(_.values.toList.filter(g => g.granteeId == s.userId))

    override def createApprovalRequest(req: ApprovalRequest): RepositoryTask[ApprovalRequest] =
      for {
        id <- approvalIdGen.updateAndGet(_ + 1)
        saved = req.copy(id = ApprovalRequestId(id))
        _ <- approvals.update(_.updated(id, saved))
      } yield saved

    override def cancelApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
      approvals.modify { m =>
        m.get(id.value) match {
          case None    => (0L, m)
          case Some(r) => (1L, m.updated(id.value, r.copy(status = ApprovalStatus.Cancelled)))
        }
      }

    override def expireApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
      approvals.modify { m =>
        m.get(id.value) match {
          case None    => (0L, m)
          case Some(r) => (1L, m.updated(id.value, r.copy(status = ApprovalStatus.Expired)))
        }
      }

    override def expireAllStaleApprovalRequests(): RepositoryTask[Long] =
      approvals.modify { m =>
        val now = Instant.now()
        val toExpire = m.filter { case (_, r) =>
          r.status == ApprovalStatus.Pending && r.expiresAt.exists(_.isBefore(now))
        }
        val updated = toExpire.foldLeft(m) { case (acc, (k, r)) =>
          acc.updated(k, r.copy(status = ApprovalStatus.Expired))
        }
        (toExpire.size.toLong, updated)
      }

    override def recordApprovalDecision(decision: ApprovalDecision): RepositoryTask[ApprovalDecision] =
      for {
        id <-
          if (decision.id == ApprovalDecisionId.empty) approvalIdGen.updateAndGet(_ + 1)
          else ZIO.succeed(decision.id.value)
        saved = decision.copy(id = ApprovalDecisionId(id))
        _ <- decisions.update(_.updated(id, saved))
        _ <- approvals.update { m =>
          m.get(decision.approvalRequestId.value) match {
            case None    => m
            case Some(r) => m.updated(decision.approvalRequestId.value, r.copy(status = decision.decision))
          }
        }
      } yield saved

    override def getApprovalRequest(id: ApprovalRequestId): RepositoryTask[Option[ApprovalRequest]] =
      approvals.get.map(_.get(id.value))

    override def getExpiredApprovalRequests: RepositoryTask[List[ApprovalRequest]] =
      approvals.get.map(_.values.toList.filter(_.status == ApprovalStatus.Expired))

    override def findApprovedRequest(
      capability: CapabilityName,
      userId:     UserId,
      sessionId:  Option[AgentSessionId],
    ): RepositoryTask[Option[ApprovalRequest]] =
      approvals.get.map {
        _.values.find { r =>
          r.capability == capability &&
          r.requestorUserId == userId &&
          r.status == ApprovalStatus.Approved &&
          sessionId.forall(sid => r.sessionId.contains(sid))
        }
      }

    override def getGrantsForCapability(
      userId:     UserId,
      capability: CapabilityName,
    ): RepositoryTask[List[CapabilityGrant]] =
      grants.get.map(_.values.toList.filter(g => g.granteeId == userId && g.capability == capability))

    override def hasDirectPermission(
      userId:   UserId,
      resource: String,
      action:   String,
    ): RepositoryTask[Boolean] =
      permissions.get.map(
        _.values.exists(p => p.userId.contains(userId) && p.resource == resource && p.action == action),
      )

    override def hasRolePermission(
      userId:   UserId,
      resource: String,
      action:   String,
    ): RepositoryTask[Boolean] =
      for {
        roleMap  <- userRoles.get
        permsMap <- permissions.get
        myRoleIds = roleMap.keys.filter(_._1 == userId.value).map(_._2).toSet
      } yield permsMap.values.exists { p =>
        p.roleId.exists(rid => myRoleIds.contains(rid.value)) && p.resource == resource && p.action == action
      }

  }

  private object InMemoryPermissionRepo {

    def make: UIO[InMemoryPermissionRepo] =
      for {
        roleIdGen     <- Ref.make(0L)
        roles         <- Ref.make(Map.empty[Long, Role])
        userRoles     <- Ref.make(Map.empty[(Long, Long), Unit])
        permIdGen     <- Ref.make(0L)
        permissions   <- Ref.make(Map.empty[Long, Permission])
        grantIdGen    <- Ref.make(0L)
        grants        <- Ref.make(Map.empty[Long, CapabilityGrant])
        approvalIdGen <- Ref.make(0L)
        approvals     <- Ref.make(Map.empty[Long, ApprovalRequest])
        decisions     <- Ref.make(Map.empty[Long, ApprovalDecision])
      } yield new InMemoryPermissionRepo(
        roleIdGen,
        roles,
        userRoles,
        permIdGen,
        permissions,
        grantIdGen,
        grants,
        approvalIdGen,
        approvals,
        decisions,
      )

  }

  // ─── Minimal event log repo (full search support) ────────────────────────────

  private class InMemoryEventLogRepo(
    idGen: Ref[Long],
    store: Ref[List[EventLog[Json]]],
  ) extends EventLogZIORepository {

    override def append[R: zio.json.JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] =
      for {
        nextId <- idGen.updateAndGet(_ + 1)
        saved = event.copy(id = EventLogId(nextId))
        generic = EventLog[Json](
          id = saved.id,
          eventType = saved.eventType,
          actorId = saved.actorId,
          agentId = saved.agentId,
          sessionId = saved.sessionId,
          resource = None,
          payloadJson = saved.payloadJson,
          occurredAt = saved.occurredAt,
        )
        _ <- store.update(generic :: _)
      } yield saved

    override def search(filter: EventLogFilter): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(e => filter.eventType.forall(_ == e.eventType)))

    override def replaySession(
      sessionId: AgentSessionId,
      limit:     Int,
    ): RepositoryTask[List[EventLog[Json]]] = ZIO.succeed(Nil)

  }

  private object InMemoryEventLogRepo {

    def make: UIO[InMemoryEventLogRepo] =
      for {
        idGen <- Ref.make(0L)
        store <- Ref.make(List.empty[EventLog[Json]])
      } yield new InMemoryEventLogRepo(idGen, store)

  }

  // ─── Layer helpers ────────────────────────────────────────────────────────────

  private def freshLayers: ULayer[PermissionService & EventLogService] = {
    val permRepoLayer: ULayer[PermissionZIORepository] =
      ZLayer(InMemoryPermissionRepo.make.map(r => r: PermissionZIORepository))
    val logRepoLayer: ULayer[EventLogZIORepository] =
      ZLayer(InMemoryEventLogRepo.make.map(r => r: EventLogZIORepository))
    val eventLogLayer: ULayer[EventLogService] = logRepoLayer >>> EventLogServiceImpl.live
    (permRepoLayer ++ eventLogLayer) >>> PermissionServiceImpl.live ++ eventLogLayer
  }

  // ─── Fixtures ─────────────────────────────────────────────────────────────────

  private val role1: Role = Role(RoleId.empty, "admin", Some("Administrator role"))

  private val perm1: Permission = Permission(
    id = PermissionId.empty,
    roleId = None,
    userId = Some(UserId(1L)),
    resource = "shell",
    action = "execute",
    scope = None,
  )

  private val grant1: CapabilityGrant = CapabilityGrant(
    id = CapabilityGrantId.empty,
    capability = CapabilityName("shell.execute"),
    scopeJson = None,
    granteeId = UserId(1L),
    grantorId = Some(UserId(99L)),
    approvalMode = ApprovalMode.Persistent,
    expiresAt = None,
    resourceConstraints = None,
    createdAt = T0,
  )

  private def approvalRequest(capability: String = "shell.execute"): ApprovalRequest =
    ApprovalRequest(
      id = ApprovalRequestId.empty,
      capability = CapabilityName(capability),
      scopeJson = None,
      agentId = None,
      requestorUserId = UserId(1L),
      sessionId = None,
      riskClass = RiskClass.ExternalEffect,
      status = ApprovalStatus.Pending,
      createdAt = T0,
      expiresAt = None,
    )

  // ─── Tests ───────────────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PermissionService")(
      roleSuite,
      permissionSuite,
      grantSuite,
      approvalSuite,
    )

  private val roleSuite = suite("roles")(
    test("upsertRole assigns a generated id") {
      for {
        svc   <- ZIO.service[PermissionService]
        saved <- svc.upsertRole(role1)
      } yield assertTrue(saved.id.value > 0L, saved.name == "admin")
    }.provide(freshLayers),
    test("getRole returns the role after upsert") {
      for {
        svc   <- ZIO.service[PermissionService]
        saved <- svc.upsertRole(role1)
        found <- svc.getRole(saved.id)
      } yield assertTrue(found.contains(saved))
    }.provide(freshLayers),
    test("deleteRole removes the role") {
      for {
        svc   <- ZIO.service[PermissionService]
        saved <- svc.upsertRole(role1)
        count <- svc.deleteRole(saved.id)
        found <- svc.getRole(saved.id)
      } yield assertTrue(count == 1L, found.isEmpty)
    }.provide(freshLayers),
    test("assignRole and removeRole log events") {
      val userId = UserId(1L)
      for {
        svc          <- ZIO.service[PermissionService]
        log          <- ZIO.service[EventLogService]
        saved        <- svc.upsertRole(role1)
        _            <- svc.assignRole(userId, saved.id, Some(userId))
        assignEvents <- log.query(EventLogFilter(eventType = Some(EventType.RoleAssigned)))
        _            <- svc.removeRole(userId, saved.id, Some(userId))
        revokeEvents <- log.query(EventLogFilter(eventType = Some(EventType.RoleRevoked)))
      } yield assertTrue(
        assignEvents.nonEmpty,
        assignEvents.head.actorId.contains(userId),
        revokeEvents.nonEmpty,
      )
    }.provide(freshLayers),
  )

  private val permissionSuite = suite("permissions")(
    test("upsertPermission assigns a generated id and logs PermissionGranted") {
      for {
        svc    <- ZIO.service[PermissionService]
        log    <- ZIO.service[EventLogService]
        saved  <- svc.upsertPermission(perm1)
        events <- log.query(EventLogFilter(eventType = Some(EventType.PermissionGranted)))
      } yield assertTrue(
        saved.id.value > 0L,
        saved.resource == "shell",
        events.nonEmpty,
      )
    }.provide(freshLayers),
    test("deletePermission removes permission and logs PermissionRevoked") {
      for {
        svc    <- ZIO.service[PermissionService]
        log    <- ZIO.service[EventLogService]
        saved  <- svc.upsertPermission(perm1)
        count  <- svc.deletePermission(saved.id)
        events <- log.query(EventLogFilter(eventType = Some(EventType.PermissionRevoked)))
      } yield assertTrue(count == 1L, events.nonEmpty)
    }.provide(freshLayers),
    test("searchPermissions filters by userId") {
      val uid1 = UserId(1L)
      val uid2 = UserId(2L)
      for {
        svc <- ZIO.service[PermissionService]
        _   <- svc.upsertPermission(perm1.copy(userId = Some(uid1)))
        _   <- svc.upsertPermission(perm1.copy(userId = Some(uid2)))
        res <- svc.searchPermissions(PermissionSearch(userId = Some(uid1)))
      } yield assertTrue(res.length == 1, res.head.userId.contains(uid1))
    }.provide(freshLayers),
  )

  private val grantSuite = suite("capability grants")(
    test("upsertCapabilityGrant assigns a generated id and logs CapabilityGranted") {
      for {
        svc    <- ZIO.service[PermissionService]
        log    <- ZIO.service[EventLogService]
        saved  <- svc.upsertCapabilityGrant(grant1)
        events <- log.query(EventLogFilter(eventType = Some(EventType.CapabilityGranted)))
      } yield assertTrue(
        saved.id.value > 0L,
        saved.capability == CapabilityName("shell.execute"),
        events.nonEmpty,
        events.head.actorId.contains(UserId(99L)),
      )
    }.provide(freshLayers),
    test("revokeGrant removes grant and logs CapabilityRevoked") {
      for {
        svc    <- ZIO.service[PermissionService]
        log    <- ZIO.service[EventLogService]
        saved  <- svc.upsertCapabilityGrant(grant1)
        count  <- svc.revokeGrant(saved.id)
        events <- log.query(EventLogFilter(eventType = Some(EventType.CapabilityRevoked)))
      } yield assertTrue(count == 1L, events.nonEmpty)
    }.provide(freshLayers),
    test("searchGrants returns grants for the user") {
      val uid = UserId(1L)
      for {
        svc <- ZIO.service[PermissionService]
        _   <- svc.upsertCapabilityGrant(grant1)
        _   <- svc.upsertCapabilityGrant(grant1.copy(granteeId = UserId(2L)))
        res <- svc.searchGrants(GrantSearch(userId = uid))
      } yield assertTrue(res.length == 1, res.head.granteeId == uid)
    }.provide(freshLayers),
  )

  private val approvalSuite = suite("approval requests")(
    test("requestApproval creates a request and logs ApprovalRequested") {
      val req = approvalRequest()
      for {
        svc    <- ZIO.service[PermissionService]
        log    <- ZIO.service[EventLogService]
        saved  <- svc.requestApproval(req, actorId = Some(UserId(1L)))
        events <- log.query(EventLogFilter(eventType = Some(EventType.ApprovalRequested)))
      } yield assertTrue(saved.id.value > 0L, events.nonEmpty)
    }.provide(freshLayers),
    test("getApprovalRequest returns the request after creation") {
      val req = approvalRequest()
      for {
        svc   <- ZIO.service[PermissionService]
        saved <- svc.requestApproval(req, None)
        found <- svc.getApprovalRequest(saved.id)
      } yield assertTrue(found.contains(saved))
    }.provide(freshLayers),
    test("recordApprovalDecision Approved logs ApprovalGranted") {
      val req = approvalRequest()
      for {
        svc   <- ZIO.service[PermissionService]
        log   <- ZIO.service[EventLogService]
        saved <- svc.requestApproval(req, None)
        decision = ApprovalDecision(
          ApprovalDecisionId.empty,
          saved.id,
          UserId(99L),
          ApprovalStatus.Approved,
          None,
          T0,
        )
        _      <- svc.recordApprovalDecision(decision)
        events <- log.query(EventLogFilter(eventType = Some(EventType.ApprovalGranted)))
      } yield assertTrue(events.nonEmpty, events.head.actorId.contains(UserId(99L)))
    }.provide(freshLayers),
    test("recordApprovalDecision Rejected logs ApprovalDenied") {
      val req = approvalRequest()
      for {
        svc   <- ZIO.service[PermissionService]
        log   <- ZIO.service[EventLogService]
        saved <- svc.requestApproval(req, None)
        decision = ApprovalDecision(
          ApprovalDecisionId.empty,
          saved.id,
          UserId(99L),
          ApprovalStatus.Rejected,
          None,
          T0,
        )
        _      <- svc.recordApprovalDecision(decision)
        events <- log.query(EventLogFilter(eventType = Some(EventType.ApprovalDenied)))
      } yield assertTrue(events.nonEmpty)
    }.provide(freshLayers),
    test("recordApprovalDecision Pending fails with JorlanError") {
      val req = approvalRequest()
      for {
        svc   <- ZIO.service[PermissionService]
        saved <- svc.requestApproval(req, None)
        decision = ApprovalDecision(
          ApprovalDecisionId.empty,
          saved.id,
          UserId(99L),
          ApprovalStatus.Pending,
          None,
          T0,
        )
        result <- svc.recordApprovalDecision(decision).exit
      } yield assertTrue(result.isFailure)
    }.provide(freshLayers),
    test("cancelApprovalRequest updates status to Cancelled") {
      val req = approvalRequest()
      for {
        svc   <- ZIO.service[PermissionService]
        saved <- svc.requestApproval(req, None)
        count <- svc.cancelApprovalRequest(saved.id)
        found <- svc.getApprovalRequest(saved.id)
      } yield assertTrue(count == 1L, found.exists(_.status == ApprovalStatus.Cancelled))
    }.provide(freshLayers),
    test("expireApprovalRequest updates status to Expired") {
      val req = approvalRequest()
      for {
        svc   <- ZIO.service[PermissionService]
        saved <- svc.requestApproval(req, None)
        count <- svc.expireApprovalRequest(saved.id)
        found <- svc.getApprovalRequest(saved.id)
      } yield assertTrue(count == 1L, found.exists(_.status == ApprovalStatus.Expired))
    }.provide(freshLayers),
    test("getExpiredApprovalRequests returns only expired") {
      for {
        svc      <- ZIO.service[PermissionService]
        pending  <- svc.requestApproval(approvalRequest("shell.read"), None)
        toExpire <- svc.requestApproval(approvalRequest("shell.write"), None)
        _        <- svc.expireApprovalRequest(toExpire.id)
        expired  <- svc.getExpiredApprovalRequests
      } yield assertTrue(
        expired.exists(_.id == toExpire.id),
        !expired.exists(_.id == pending.id),
      )
    }.provide(freshLayers),
  )

}
