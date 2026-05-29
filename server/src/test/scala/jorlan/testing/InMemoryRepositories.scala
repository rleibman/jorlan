/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.testing

import jorlan.*
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.EventLogFilter
import zio.*
import zio.json.ast.Json

import java.time.Instant

/** Shared in-memory repository implementations for unit tests.
  *
  * All implementations are purely functional, using `Ref` for state. They support the full interface so tests can mix
  * service layers without needing a real database.
  */
object InMemoryRepositories {

  // ─── User ─────────────────────────────────────────────────────────────────────

  class InMemoryUserRepo(
    idGen: Ref[Long],
    store: Ref[Map[Long, User]],
  ) extends UserZIORepository {

    override def getById(id: UserId): RepositoryTask[Option[User]] =
      store.get.map(_.get(id.value))

    override def search(s: UserSearch): RepositoryTask[List[User]] =
      store.get.map(_.values.toList.filter(u => s.active.forall(_ == u.active)))

    override def upsert(user: User): RepositoryTask[User] =
      for {
        id <- if (user.id == UserId.empty) idGen.updateAndGet(_ + 1) else ZIO.succeed(user.id.value)
        saved = user.copy(id = UserId(id))
        _ <- store.update(_.updated(id, saved))
      } yield saved

    override def deactivate(id: UserId): RepositoryTask[Long] =
      store.modify { m =>
        m.get(id.value).fold((0L, m))(u => (1L, m.updated(id.value, u.copy(active = false))))
      }

    override def getChannelIdentities(userId: UserId):       RepositoryTask[List[ChannelIdentity]] = ZIO.succeed(Nil)
    override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
      ZIO.die(new RuntimeException("not implemented"))
    override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
      ZIO.die(new RuntimeException("not implemented"))
    override def login(
      email:    String,
      password: String,
    ):                                       RepositoryTask[Option[User]] = ZIO.succeed(None)
    override def userByEmail(email: String): RepositoryTask[Option[User]] =
      store.get.map(_.values.find(_.email.contains(email)))
    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): RepositoryTask[Unit] = ZIO.unit
    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ): RepositoryTask[Option[User]] = ZIO.succeed(None)

  }

  object InMemoryUserRepo {

    def make: UIO[InMemoryUserRepo] =
      (Ref.make(0L) <*> Ref.make(Map.empty[Long, User])).map { case (idGen, store) =>
        new InMemoryUserRepo(idGen, store)
      }

    val layer: ULayer[UserZIORepository] = ZLayer(make.map(r => r: UserZIORepository))

  }

  // ─── Permission ───────────────────────────────────────────────────────────────

  class InMemoryPermissionRepo(
    roleIdGen:     Ref[Long],
    roles:         Ref[Map[Long, Role]],
    userRoles:     Ref[Map[(Long, Long), Unit]],
    permIdGen:     Ref[Long],
    permissions:   Ref[Map[Long, Permission]],
    grantIdGen:    Ref[Long],
    grants:        Ref[Map[Long, CapabilityGrant]],
    approvalIdGen: Ref[Long],
    approvals:     Ref[Map[Long, ApprovalRequest]],
  ) extends PermissionZIORepository {

    override def getRole(id: RoleId): RepositoryTask[Option[Role]] = roles.get.map(_.get(id.value))

    override def searchRoles(s: RoleSearch): RepositoryTask[List[Role]] =
      for {
        rm  <- userRoles.get
        rm2 <- roles.get
        myIds = rm.keys.filter(_._1 == s.userId.value).map(_._2).toSet
      } yield rm2.values.toList.filter(r => myIds.contains(r.id.value))

    override def upsertRole(role: Role): RepositoryTask[Role] =
      for {
        id <- if (role.id == RoleId.empty) roleIdGen.updateAndGet(_ + 1) else ZIO.succeed(role.id.value)
        saved = role.copy(id = RoleId(id))
        _ <- roles.update(_.updated(id, saved))
      } yield saved

    override def deleteRole(id: RoleId): RepositoryTask[Long] =
      roles.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
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
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
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
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
      }

    override def searchGrants(s: GrantSearch): RepositoryTask[List[CapabilityGrant]] =
      grants.get.map(_.values.toList.filter(_.granteeId == s.userId))

    override def createApprovalRequest(req: ApprovalRequest): RepositoryTask[ApprovalRequest] =
      for {
        id <- approvalIdGen.updateAndGet(_ + 1)
        saved = req.copy(id = ApprovalRequestId(id))
        _ <- approvals.update(_.updated(id, saved))
      } yield saved

    override def cancelApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
      approvals.modify { m =>
        m.get(id.value).fold((0L, m))(r => (1L, m.updated(id.value, r.copy(status = ApprovalStatus.Cancelled))))
      }

    override def expireApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
      approvals.modify { m =>
        m.get(id.value).fold((0L, m))(r => (1L, m.updated(id.value, r.copy(status = ApprovalStatus.Expired))))
      }

    override def expireAllStaleApprovalRequests(): RepositoryTask[Long] =
      approvals.modify { m =>
        val now = Instant.now()
        val expired = m.filter { case (_, r) =>
          r.status == ApprovalStatus.Pending && r.expiresAt.exists(_.isBefore(now))
        }
        (
          expired.size.toLong,
          expired.foldLeft(m) { case (acc, (k, r)) =>
            acc.updated(k, r.copy(status = ApprovalStatus.Expired))
          },
        )
      }

    override def recordApprovalDecision(decision: ApprovalDecision): RepositoryTask[ApprovalDecision] =
      for {
        id <- approvalIdGen.updateAndGet(_ + 1)
        saved = decision.copy(id = ApprovalDecisionId(id))
        _ <- approvals.update { m =>
          m.get(decision.approvalRequestId.value).fold(m)(r =>
              m.updated(decision.approvalRequestId.value, r.copy(status = decision.decision)),
            )
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
          r.capability == capability && r.requestorUserId == userId &&
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
        rm <- userRoles.get
        pm <- permissions.get
        rids = rm.keys.filter(_._1 == userId.value).map(_._2).toSet
      } yield pm.values.exists { p =>
        p.roleId.exists(rid => rids.contains(rid.value)) && p.resource == resource && p.action == action
      }

  }

  object InMemoryPermissionRepo {

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
      )

    val layer: ULayer[PermissionZIORepository] = ZLayer(make.map(r => r: PermissionZIORepository))

  }

  // ─── Event log ────────────────────────────────────────────────────────────────

  class InMemoryEventLogRepo(
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
    ): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(_.sessionId.contains(sessionId)).reverse.take(limit))

  }

  object InMemoryEventLogRepo {

    def make: UIO[InMemoryEventLogRepo] =
      (Ref.make(0L) <*> Ref.make(List.empty[EventLog[Json]])).map { case (idGen, store) =>
        new InMemoryEventLogRepo(idGen, store)
      }

    val layer: ULayer[EventLogZIORepository] = ZLayer(make.map(r => r: EventLogZIORepository))

  }

}
