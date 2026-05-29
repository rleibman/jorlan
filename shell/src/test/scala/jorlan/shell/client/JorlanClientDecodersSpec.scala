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

import caliban.client.ScalarDecoder
import caliban.client.__Value.{__NumberValue, __StringValue}
import caliban.client.ArgEncoder
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
import zio.test.*
import zio.test.Assertion.*

/** Pure unit tests for [[JorlanClientDecoders]]. No ZIO runtime or network required.
  *
  * `ScalarDecoder[A]` is a SAM trait; use `.decode(value)` to invoke it.
  */
object JorlanClientDecodersSpec extends ZIOSpecDefault {

  import JorlanClientDecoders._

  override def spec: Spec[Any, Nothing] =
    suite("JorlanClientDecoders")(
      suite("longDecoder — UserId")(
        test("decodes a valid integer number value") {
          assertTrue(userIdDecoder.decode(__NumberValue(42)) == Right(UserId(42L)))
        },
        test("fails on a non-exact decimal") {
          val result = userIdDecoder.decode(__NumberValue(BigDecimal("1.5")))
          assertTrue(result.isLeft)
        },
        test("fails on overflow beyond Long.MaxValue") {
          val tooBig = BigDecimal(Long.MaxValue) + 1
          val result = userIdDecoder.decode(__NumberValue(tooBig))
          assertTrue(result.isLeft)
        },
        test("fails on a string value") {
          val result = userIdDecoder.decode(__StringValue("42"))
          assertTrue(result.isLeft)
        },
      ),
      suite("longDecoder — all ID types produce Right on valid input")(
        test("RoleId") {
          assertTrue(roleIdDecoder.decode(__NumberValue(1)) == Right(RoleId(1L)))
        },
        test("PermissionId") {
          assertTrue(permissionIdDecoder.decode(__NumberValue(2)) == Right(PermissionId(2L)))
        },
        test("AgentId") {
          assertTrue(agentIdDecoder.decode(__NumberValue(3)) == Right(AgentId(3L)))
        },
        test("AgentSessionId") {
          assertTrue(agentSessionIdDecoder.decode(__NumberValue(4)) == Right(AgentSessionId(4L)))
        },
        test("ApprovalRequestId") {
          assertTrue(approvalRequestIdDecoder.decode(__NumberValue(5)) == Right(ApprovalRequestId(5L)))
        },
        test("EventLogId") {
          assertTrue(eventLogIdDecoder.decode(__NumberValue(6)) == Right(EventLogId(6L)))
        },
      ),
      suite("CapabilityName decoder")(
        test("decodes a valid string value") {
          val result = capabilityNameDecoder.decode(__StringValue("read:files"))
          assertTrue(result == Right(CapabilityName("read:files")))
        },
        test("fails on a number value") {
          val result = capabilityNameDecoder.decode(__NumberValue(1))
          assertTrue(result.isLeft)
        },
      ),
      suite("ArgEncoder round-trips")(
        test("UserId encodes to the underlying long") {
          val encoded = userIdEncoder.encode(UserId(99L))
          val expected = ArgEncoder.long.encode(99L)
          assertTrue(encoded == expected)
        },
        test("RoleId encodes to the underlying long") {
          assertTrue(roleIdEncoder.encode(RoleId(7L)) == ArgEncoder.long.encode(7L))
        },
        test("CapabilityName encodes to the underlying string") {
          val encoded = capabilityNameEncoder.encode(CapabilityName("shell.execute"))
          assertTrue(encoded == ArgEncoder.string.encode("shell.execute"))
        },
      ),
    )

}
