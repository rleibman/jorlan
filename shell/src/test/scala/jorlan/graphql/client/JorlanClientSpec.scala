/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.graphql.client

import caliban.client.__Value.{__NumberValue, __StringValue}
import jorlan.domain.{ApprovalStatus, EventType}
import jorlan.shell.client.JorlanClientDecoders
import zio.test.*
import zio.test.Assertion.*

/** P7-043: Tests for the client enum decoders and encoders in JorlanClientDecoders.
  *
  * The enums were migrated from generated caliban enum types to shared domain scalars so that the shell reuses the same
  * Scala enum definitions as the server. Decoders and encoders now live in JorlanClientDecoders.
  */
object JorlanClientSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Nothing] =
    suite("JorlanClient generated decoders")(
      suite("ApprovalStatus decoder")(
        test("decodes Pending") {
          assertTrue(
            JorlanClientDecoders.approvalStatusDecoder.decode(__StringValue("Pending")) == Right(ApprovalStatus.Pending),
          )
        },
        test("fails on unknown status") {
          val result = JorlanClientDecoders.approvalStatusDecoder.decode(__StringValue("UnknownStatus"))
          assertTrue(result.isLeft)
        },
        test("fails on numeric value") {
          val result = JorlanClientDecoders.approvalStatusDecoder.decode(__NumberValue(1))
          assertTrue(result.isLeft)
        },
      ),
      suite("ApprovalStatus encoder")(
        test("encodes Approved to __EnumValue") {
          import caliban.client.__Value.__EnumValue
          val encoded = JorlanClientDecoders.approvalStatusEncoder.encode(ApprovalStatus.Approved)
          assertTrue(encoded == __EnumValue("Approved"))
        },
      ),
      suite("EventType decoder")(
        test("decodes SkillInvoked") {
          assertTrue(
            JorlanClientDecoders.eventTypeDecoder.decode(__StringValue("SkillInvoked")) == Right(EventType.SkillInvoked),
          )
        },
        test("decodes UserCreated") {
          assertTrue(
            JorlanClientDecoders.eventTypeDecoder.decode(__StringValue("UserCreated")) == Right(EventType.UserCreated),
          )
        },
        test("fails on unknown event type") {
          val result = JorlanClientDecoders.eventTypeDecoder.decode(__StringValue("NotAnEvent"))
          assertTrue(result.isLeft)
        },
      ),
    )

}
