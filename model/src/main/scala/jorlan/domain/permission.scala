/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.Instant

case class Role(
  id:          RoleId,
  name:        String,
  description: Option[String],
)
object Role {

  given JsonEncoder[Role] = DeriveJsonEncoder.gen[Role]
  given JsonDecoder[Role] = DeriveJsonDecoder.gen[Role]

}

// Direct permission on a resource, attached to either a role or a user.
case class Permission(
  id:       RoleId, // PK
  roleId:   Option[RoleId],
  userId:   Option[UserId],
  resource: String,
  action:   String,
  scope:    Option[String],
)
object Permission {

  given JsonEncoder[Permission] = DeriveJsonEncoder.gen[Permission]
  given JsonDecoder[Permission] = DeriveJsonDecoder.gen[Permission]

}

enum ApprovalMode {

  case Denied, PerInvocation, Once, Session, Timed, Persistent

}
object ApprovalMode {

  given JsonEncoder[ApprovalMode] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[ApprovalMode] =
    JsonDecoder[String].mapOrFail { s =>
      ApprovalMode.values.find(_.toString == s).toRight(s"Unknown ApprovalMode: $s")
    }

}

case class CapabilityGrant(
  id:                  CapabilityGrantId,
  capability:          String,
  scopeJson:           Option[String],
  granteeId:           UserId,
  grantorId:           Option[UserId],
  approvalMode:        ApprovalMode,
  expiresAt:           Option[Instant],
  resourceConstraints: Option[String],
  createdAt:           Instant,
)
object CapabilityGrant {

  given JsonEncoder[CapabilityGrant] = DeriveJsonEncoder.gen[CapabilityGrant]
  given JsonDecoder[CapabilityGrant] = DeriveJsonDecoder.gen[CapabilityGrant]

}

enum ApprovalStatus {

  case Pending, Approved, Denied, Expired, Cancelled

}
object ApprovalStatus {

  given JsonEncoder[ApprovalStatus] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[ApprovalStatus] =
    JsonDecoder[String].mapOrFail { s =>
      ApprovalStatus.values.find(_.toString == s).toRight(s"Unknown ApprovalStatus: $s")
    }

}

case class ApprovalRequest(
  id:              ApprovalRequestId,
  capability:      String,
  scopeJson:       Option[String],
  agentId:         Option[AgentId],
  requestorUserId: UserId,
  sessionId:       Option[AgentSessionId],
  riskClass:       Int,
  status:          ApprovalStatus,
  createdAt:       Instant,
  expiresAt:       Option[Instant],
)
object ApprovalRequest {

  given JsonEncoder[ApprovalRequest] = DeriveJsonEncoder.gen[ApprovalRequest]
  given JsonDecoder[ApprovalRequest] = DeriveJsonDecoder.gen[ApprovalRequest]

}

case class ApprovalDecision(
  id:                ApprovalDecisionId,
  approvalRequestId: ApprovalRequestId,
  decidedBy:         UserId,
  decision:          ApprovalStatus,
  scopeOverride:     Option[String],
  decidedAt:         Instant,
)
object ApprovalDecision {

  given JsonEncoder[ApprovalDecision] = DeriveJsonEncoder.gen[ApprovalDecision]
  given JsonDecoder[ApprovalDecision] = DeriveJsonDecoder.gen[ApprovalDecision]

}
