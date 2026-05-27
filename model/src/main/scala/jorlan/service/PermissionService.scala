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
import jorlan.domain.*
import zio.*

/** Application service for capability grants and the approval request/decision lifecycle.
  *
  * Each approval state transition writes to the event log so the capability model is fully auditable.
  */
trait PermissionService {

  def searchRoles(s:   RoleSearch): IO[JorlanError, List[Role]]
  def upsertRole(role: Role):       IO[JorlanError, Role]
  def deleteRole(id:   RoleId):     IO[JorlanError, Long]
  def assignRole(
    userId:  UserId,
    roleId:  RoleId,
    actorId: Option[UserId] = None,
  ): IO[JorlanError, Unit]
  def removeRole(
    userId:  UserId,
    roleId:  RoleId,
    actorId: Option[UserId] = None,
  ):                                                  IO[JorlanError, Unit]
  def searchPermissions(s:         PermissionSearch): IO[JorlanError, List[Permission]]
  def upsertPermission(permission: Permission):       IO[JorlanError, Permission]
  def deletePermission(id:         PermissionId):     IO[JorlanError, Long]

  def upsertCapabilityGrant(grant: CapabilityGrant):   IO[JorlanError, CapabilityGrant]
  def revokeGrant(id:              CapabilityGrantId): IO[JorlanError, Long]
  def searchGrants(s:              GrantSearch):       IO[JorlanError, List[CapabilityGrant]]

  /** Create a pending approval request. Writes an [[EventType.ApprovalRequested]] event. */
  def requestApproval(
    req:     ApprovalRequest,
    actorId: Option[UserId],
  ): IO[JorlanError, ApprovalRequest]

  def cancelApprovalRequest(id: ApprovalRequestId): IO[JorlanError, Long]

  /** Mark a pending [[ApprovalRequest]] as [[ApprovalStatus.Expired]]. */
  def expireApprovalRequest(id: ApprovalRequestId): IO[JorlanError, Long]

  /** Mark all `Pending` [[ApprovalRequest]] rows whose `expiresAt` has passed as `Expired` in a single bulk update. */
  def expireAllStaleApprovalRequests(): IO[JorlanError, Long]

  def getApprovalRequest(id: ApprovalRequestId): IO[JorlanError, Option[ApprovalRequest]]

  /** Return all `Pending` [[ApprovalRequest]] rows whose `expiresAt` has passed. */
  def getExpiredApprovalRequests: IO[JorlanError, List[ApprovalRequest]]

  /** Find an already-approved [[ApprovalRequest]] for a capability + user, optionally scoped to a session. */
  def findApprovedRequest(
    capability: CapabilityName,
    userId:     UserId,
    sessionId:  Option[AgentSessionId],
  ): IO[JorlanError, Option[ApprovalRequest]]

  /** Record an approval decision. Writes [[EventType.ApprovalGranted]] or [[EventType.ApprovalDenied]] event. */
  def recordApprovalDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision]

}

object PermissionService {

  def searchRoles(s: RoleSearch): ZIO[PermissionService, JorlanError, List[Role]] =
    ZIO.serviceWithZIO[PermissionService](_.searchRoles(s))

  def upsertRole(role: Role): ZIO[PermissionService, JorlanError, Role] =
    ZIO.serviceWithZIO[PermissionService](_.upsertRole(role))

  def deleteRole(id: RoleId): ZIO[PermissionService, JorlanError, Long] =
    ZIO.serviceWithZIO[PermissionService](_.deleteRole(id))

  def assignRole(
    userId:  UserId,
    roleId:  RoleId,
    actorId: Option[UserId] = None,
  ): ZIO[PermissionService, JorlanError, Unit] =
    ZIO.serviceWithZIO[PermissionService](_.assignRole(userId, roleId, actorId))

  def removeRole(
    userId:  UserId,
    roleId:  RoleId,
    actorId: Option[UserId] = None,
  ): ZIO[PermissionService, JorlanError, Unit] =
    ZIO.serviceWithZIO[PermissionService](_.removeRole(userId, roleId, actorId))

  def searchPermissions(s: PermissionSearch): ZIO[PermissionService, JorlanError, List[Permission]] =
    ZIO.serviceWithZIO[PermissionService](_.searchPermissions(s))

  def upsertPermission(permission: Permission): ZIO[PermissionService, JorlanError, Permission] =
    ZIO.serviceWithZIO[PermissionService](_.upsertPermission(permission))

  def deletePermission(id: PermissionId): ZIO[PermissionService, JorlanError, Long] =
    ZIO.serviceWithZIO[PermissionService](_.deletePermission(id))

  def upsertCapabilityGrant(grant: CapabilityGrant): ZIO[PermissionService, JorlanError, CapabilityGrant] =
    ZIO.serviceWithZIO[PermissionService](_.upsertCapabilityGrant(grant))

  def revokeGrant(id: CapabilityGrantId): ZIO[PermissionService, JorlanError, Long] =
    ZIO.serviceWithZIO[PermissionService](_.revokeGrant(id))

  def searchGrants(s: GrantSearch): ZIO[PermissionService, JorlanError, List[CapabilityGrant]] =
    ZIO.serviceWithZIO[PermissionService](_.searchGrants(s))

  def requestApproval(
    req:     ApprovalRequest,
    actorId: Option[UserId],
  ): ZIO[PermissionService, JorlanError, ApprovalRequest] =
    ZIO.serviceWithZIO[PermissionService](_.requestApproval(req, actorId))

  def cancelApprovalRequest(id: ApprovalRequestId): ZIO[PermissionService, JorlanError, Long] =
    ZIO.serviceWithZIO[PermissionService](_.cancelApprovalRequest(id))

  def expireApprovalRequest(id: ApprovalRequestId): ZIO[PermissionService, JorlanError, Long] =
    ZIO.serviceWithZIO[PermissionService](_.expireApprovalRequest(id))

  def expireAllStaleApprovalRequests(): ZIO[PermissionService, JorlanError, Long] =
    ZIO.serviceWithZIO[PermissionService](_.expireAllStaleApprovalRequests())

  def getApprovalRequest(id: ApprovalRequestId): ZIO[PermissionService, JorlanError, Option[ApprovalRequest]] =
    ZIO.serviceWithZIO[PermissionService](_.getApprovalRequest(id))

  def getExpiredApprovalRequests: ZIO[PermissionService, JorlanError, List[ApprovalRequest]] =
    ZIO.serviceWithZIO[PermissionService](_.getExpiredApprovalRequests)

  def findApprovedRequest(
    capability: CapabilityName,
    userId:     UserId,
    sessionId:  Option[AgentSessionId],
  ): ZIO[PermissionService, JorlanError, Option[ApprovalRequest]] =
    ZIO.serviceWithZIO[PermissionService](_.findApprovedRequest(capability, userId, sessionId))

  def recordApprovalDecision(decision: ApprovalDecision): ZIO[PermissionService, JorlanError, ApprovalDecision] =
    ZIO.serviceWithZIO[PermissionService](_.recordApprovalDecision(decision))

}
