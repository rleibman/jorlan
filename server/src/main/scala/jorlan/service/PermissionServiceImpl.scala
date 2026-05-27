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
import jorlan.db.repository.PermissionZIORepository
import jorlan.domain.*
import zio.*

private class PermissionServiceImpl(
  repo:     PermissionZIORepository,
  eventLog: EventLogService,
) extends PermissionService {

  override def searchRoles(s: RoleSearch): IO[JorlanError, List[Role]] = repo.searchRoles(s)

  override def upsertRole(role: Role): IO[JorlanError, Role] = repo.upsertRole(role)

  override def deleteRole(id: RoleId): IO[JorlanError, Long] = repo.deleteRole(id)

  override def assignRole(
    userId: UserId,
    roleId: RoleId,
  ): IO[JorlanError, Unit] =
    for {
      now <- Clock.instant
      _   <- repo.assignRole(userId, roleId)
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.RoleAssigned,
          actorId = None,
          agentId = None,
          sessionId = None,
          resource = Some(roleId),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield ()

  override def removeRole(
    userId: UserId,
    roleId: RoleId,
  ): IO[JorlanError, Unit] =
    for {
      now <- Clock.instant
      _   <- repo.removeRole(userId, roleId)
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.RoleRevoked,
          actorId = None,
          agentId = None,
          sessionId = None,
          resource = Some(roleId),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield ()

  override def searchPermissions(s: PermissionSearch): IO[JorlanError, List[Permission]] = repo.searchPermissions(s)

  override def upsertPermission(permission: Permission): IO[JorlanError, Permission] = repo.upsertPermission(permission)

  override def deletePermission(id: PermissionId): IO[JorlanError, Long] = repo.deletePermission(id)

  override def upsertCapabilityGrant(grant: CapabilityGrant): IO[JorlanError, CapabilityGrant] =
    repo.upsertCapabilityGrant(grant)

  override def revokeGrant(id: CapabilityGrantId): IO[JorlanError, Long] = repo.revokeGrant(id)

  override def searchGrants(s: GrantSearch): IO[JorlanError, List[CapabilityGrant]] = repo.searchGrants(s)

  override def requestApproval(
    req:     ApprovalRequest,
    actorId: Option[UserId],
  ): IO[JorlanError, ApprovalRequest] =
    for {
      now   <- Clock.instant
      saved <- repo.createApprovalRequest(req)
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.ApprovalRequested,
          actorId = actorId,
          agentId = None,
          sessionId = None,
          resource = Some(saved.id),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield saved

  override def cancelApprovalRequest(id: ApprovalRequestId): IO[JorlanError, Long] = repo.cancelApprovalRequest(id)

  override def getApprovalRequest(id: ApprovalRequestId): IO[JorlanError, Option[ApprovalRequest]] =
    repo.getApprovalRequest(id)

  override def recordApprovalDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision] =
    for {
      now   <- Clock.instant
      saved <- repo.recordApprovalDecision(decision)
      eventType =
        if (saved.decision == ApprovalStatus.Approved) EventType.ApprovalGranted
        else EventType.ApprovalDenied
      _ <- eventLog.log(
        EventLog(
          id = EventLogId.empty,
          eventType = eventType,
          actorId = Some(saved.decidedBy),
          agentId = None,
          sessionId = None,
          resource = Some(saved.approvalRequestId),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield saved

}

object PermissionServiceImpl {

  val live: URLayer[PermissionZIORepository & EventLogService, PermissionService] =
    ZLayer.fromFunction(
      (
        repo:     PermissionZIORepository,
        eventLog: EventLogService,
      ) => new PermissionServiceImpl(repo, eventLog),
    )

}
