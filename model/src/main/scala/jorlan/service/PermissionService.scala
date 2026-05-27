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

  def searchRoles(s:       RoleSearch):       IO[JorlanError, List[Role]]
  def searchPermissions(s: PermissionSearch): IO[JorlanError, List[Permission]]

  def upsertCapabilityGrant(grant: CapabilityGrant):   IO[JorlanError, CapabilityGrant]
  def revokeGrant(id:              CapabilityGrantId): IO[JorlanError, Long]
  def searchGrants(s:              GrantSearch):       IO[JorlanError, List[CapabilityGrant]]

  /** Create a pending approval request. Writes an [[EventType.ApprovalRequested]] event. */
  def requestApproval(
    req:     ApprovalRequest,
    actorId: Option[UserId],
  ): IO[JorlanError, ApprovalRequest]

  def cancelApprovalRequest(id: ApprovalRequestId): IO[JorlanError, Long]

  def getApprovalRequest(id: ApprovalRequestId): IO[JorlanError, Option[ApprovalRequest]]

  /** Record an approval decision. Writes [[EventType.ApprovalGranted]] or [[EventType.ApprovalDenied]] event. */
  def recordApprovalDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision]

}

object PermissionService {

  def searchRoles(s: RoleSearch): ZIO[PermissionService, JorlanError, List[Role]] =
    ZIO.serviceWithZIO[PermissionService](_.searchRoles(s))

  def searchPermissions(s: PermissionSearch): ZIO[PermissionService, JorlanError, List[Permission]] =
    ZIO.serviceWithZIO[PermissionService](_.searchPermissions(s))

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

  def getApprovalRequest(id: ApprovalRequestId): ZIO[PermissionService, JorlanError, Option[ApprovalRequest]] =
    ZIO.serviceWithZIO[PermissionService](_.getApprovalRequest(id))

  def recordApprovalDecision(decision: ApprovalDecision): ZIO[PermissionService, JorlanError, ApprovalDecision] =
    ZIO.serviceWithZIO[PermissionService](_.recordApprovalDecision(decision))

}
