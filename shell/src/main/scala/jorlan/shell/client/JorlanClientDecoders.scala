/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.client

import caliban.client.CalibanClientError.DecodingError
import caliban.client.__Value.{__NumberValue, __StringValue}
import caliban.client.{ArgEncoder, ScalarDecoder}
import jorlan.domain.*

import scala.util.Try

/** ScalarDecoder and ArgEncoder instances for the Jorlan opaque ID types. Wildcard-imported by the generated
  * JorlanClient via --imports.
  *
  * Uses `implicit val` (not `given`) so that `import JorlanClientDecoders._` picks them up. In Scala 3, `given`
  * instances are NOT imported by wildcard `._` — only by `import given`. The generated Caliban client uses Scala
  * 2-style implicit resolution via `._` imports.
  *
  * Note: Instant, Long, and Unit already have instances in ScalarDecoder / ArgEncoder companions so they are not
  * repeated here.
  */
object JorlanClientDecoders {

  private def longDecoder[A](
    wrap: Long => A,
    name: String,
  ): ScalarDecoder[A] = {
    case __NumberValue(v) =>
      Try(v.toLongExact).toEither.left
        .map(e => DecodingError(s"Can't build $name from $v", Some(e)))
        .map(wrap)
    case other => Left(DecodingError(s"Expected number for $name, got: $other"))
  }

  implicit val userIdDecoder:         ScalarDecoder[UserId] = longDecoder(UserId(_), "UserId")
  implicit val roleIdDecoder:         ScalarDecoder[RoleId] = longDecoder(RoleId(_), "RoleId")
  implicit val permissionIdDecoder:   ScalarDecoder[PermissionId] = longDecoder(PermissionId(_), "PermissionId")
  implicit val agentIdDecoder:        ScalarDecoder[AgentId] = longDecoder(AgentId(_), "AgentId")
  implicit val agentSessionIdDecoder: ScalarDecoder[AgentSessionId] = longDecoder(AgentSessionId(_), "AgentSessionId")
  implicit val approvalRequestIdDecoder: ScalarDecoder[ApprovalRequestId] =
    longDecoder(ApprovalRequestId(_), "ApprovalRequestId")
  implicit val eventLogIdDecoder:  ScalarDecoder[EventLogId] = longDecoder(EventLogId(_), "EventLogId")
  implicit val workspaceIdDecoder: ScalarDecoder[WorkspaceId] = longDecoder(WorkspaceId(_), "WorkspaceId")

  implicit val modelIdDecoder: ScalarDecoder[ModelId] = {
    case __StringValue(v) => Right(ModelId(v))
    case other            => Left(DecodingError(s"Expected string for ModelId, got: $other"))
  }

  // ArgEncoder instances mirror ScalarDecoder above.
  // P7-030: A generic helper is not possible because opaque types do not expose a shared `.value`
  // typeclass — each type must be listed explicitly. A cross-module `HasLongValue[T]` typeclass
  // in the `model` module could eliminate this repetition in a future phase.
  implicit val userIdEncoder:         ArgEncoder[UserId] = (id: UserId) => ArgEncoder.long.encode(id.value)
  implicit val roleIdEncoder:         ArgEncoder[RoleId] = (id: RoleId) => ArgEncoder.long.encode(id.value)
  implicit val permissionIdEncoder:   ArgEncoder[PermissionId] = (id: PermissionId) => ArgEncoder.long.encode(id.value)
  implicit val agentIdEncoder:        ArgEncoder[AgentId] = (id: AgentId) => ArgEncoder.long.encode(id.value)
  implicit val agentSessionIdEncoder: ArgEncoder[AgentSessionId] = (id: AgentSessionId) =>
    ArgEncoder.long.encode(id.value)
  implicit val approvalRequestIdEncoder: ArgEncoder[ApprovalRequestId] = (id: ApprovalRequestId) =>
    ArgEncoder.long.encode(id.value)
  implicit val eventLogIdEncoder:  ArgEncoder[EventLogId] = (id: EventLogId) => ArgEncoder.long.encode(id.value)
  implicit val workspaceIdEncoder: ArgEncoder[WorkspaceId] = (id: WorkspaceId) => ArgEncoder.long.encode(id.value)
  implicit val modelIdEncoder:     ArgEncoder[ModelId] = (id: ModelId) => ArgEncoder.string.encode(id.value)

  implicit val memoryRecordIdDecoder: ScalarDecoder[MemoryRecordId] = longDecoder(MemoryRecordId(_), "MemoryRecordId")
  implicit val memoryRecordIdEncoder: ArgEncoder[MemoryRecordId] = (id: MemoryRecordId) =>
    ArgEncoder.long.encode(id.value)

  implicit val capabilityGrantIdDecoder: ScalarDecoder[CapabilityGrantId] =
    longDecoder(CapabilityGrantId(_), "CapabilityGrantId")
  implicit val capabilityGrantIdEncoder: ArgEncoder[CapabilityGrantId] = (id: CapabilityGrantId) =>
    ArgEncoder.long.encode(id.value)

  implicit val capabilityNameDecoder: ScalarDecoder[CapabilityName] = {
    case __StringValue(v) => Right(CapabilityName(v))
    case other            => Left(DecodingError(s"Expected string for CapabilityName, got: $other"))
  }

  implicit val capabilityNameEncoder: ArgEncoder[CapabilityName] = (cn: CapabilityName) =>
    ArgEncoder.string.encode(cn.value)

  // ─── Enum decoders / encoders ─────────────────────────────────────────────────

  private def enumDecoder[A](
    valueOf: String => A,
    name:    String,
  ): ScalarDecoder[A] = {
    case __StringValue(v) =>
      Try(valueOf(v)).toEither.left.map(e => DecodingError(s"Can't build $name from '$v'", Some(e)))
    case other => Left(DecodingError(s"Expected string for $name, got: $other"))
  }

  private def enumEncoder[A](stringify: A => String): ArgEncoder[A] =
    (a: A) => caliban.client.__Value.__EnumValue(stringify(a))

  implicit val eventTypeDecoder:      ScalarDecoder[EventType] = enumDecoder(EventType.valueOf, "EventType")
  implicit val sessionStatusDecoder:  ScalarDecoder[SessionStatus] = enumDecoder(SessionStatus.valueOf, "SessionStatus")
  implicit val approvalStatusDecoder: ScalarDecoder[ApprovalStatus] =
    enumDecoder(ApprovalStatus.valueOf, "ApprovalStatus")
  implicit val approvalModeDecoder: ScalarDecoder[ApprovalMode] = enumDecoder(ApprovalMode.valueOf, "ApprovalMode")
  implicit val channelTypeDecoder:  ScalarDecoder[ChannelType] = enumDecoder(ChannelType.valueOf, "ChannelType")
  implicit val riskClassDecoder:    ScalarDecoder[RiskClass] = enumDecoder(RiskClass.valueOf, "RiskClass")

  implicit val eventTypeEncoder:      ArgEncoder[EventType] = enumEncoder(_.toString)
  implicit val sessionStatusEncoder:  ArgEncoder[SessionStatus] = enumEncoder(_.toString)
  implicit val approvalStatusEncoder: ArgEncoder[ApprovalStatus] = enumEncoder(_.toString)
  implicit val approvalModeEncoder:   ArgEncoder[ApprovalMode] = enumEncoder(_.toString)
  implicit val channelTypeEncoder:    ArgEncoder[ChannelType] = enumEncoder(_.toString)
  implicit val riskClassEncoder:      ArgEncoder[RiskClass] = enumEncoder(_.toString)

}
