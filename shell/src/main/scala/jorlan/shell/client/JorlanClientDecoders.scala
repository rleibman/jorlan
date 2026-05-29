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

import caliban.client.{ArgEncoder, ScalarDecoder}
import caliban.client.CalibanClientError.DecodingError
import caliban.client.__Value.{__NumberValue, __StringValue}
import jorlan.domain.{
  AgentId,
  AgentSessionId,
  ApprovalRequestId,
  CapabilityName,
  EventLogId,
  PermissionId,
  RoleId,
  UserId,
}

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
  implicit val eventLogIdDecoder: ScalarDecoder[EventLogId] = longDecoder(EventLogId(_), "EventLogId")

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
  implicit val eventLogIdEncoder: ArgEncoder[EventLogId] = (id: EventLogId) => ArgEncoder.long.encode(id.value)

  implicit val capabilityNameDecoder: ScalarDecoder[CapabilityName] = {
    case __StringValue(v) => Right(CapabilityName(v))
    case other            => Left(DecodingError(s"Expected string for CapabilityName, got: $other"))
  }

  implicit val capabilityNameEncoder: ArgEncoder[CapabilityName] = (cn: CapabilityName) =>
    ArgEncoder.string.encode(cn.value)

}
