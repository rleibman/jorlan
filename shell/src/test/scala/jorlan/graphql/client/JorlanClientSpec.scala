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
import zio.test.*
import zio.test.Assertion.*

/** P7-043: Tests for the generated client's enum decoders and encoders. These are pure unit tests — no ZIO runtime or
  * network required.
  */
object JorlanClientSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Nothing] =
    suite("JorlanClient generated decoders")(
      suite("ApprovalStatus decoder")(
        test("decodes Pending") {
          assertTrue(
            JorlanClient.ApprovalStatus.decoder.decode(__StringValue("Pending")) == Right(
              JorlanClient.ApprovalStatus.Pending,
            ),
          )
        },
        test("fails on unknown status") {
          val result = JorlanClient.ApprovalStatus.decoder.decode(__StringValue("UnknownStatus"))
          assertTrue(result.isLeft)
        },
        test("fails on numeric value") {
          val result = JorlanClient.ApprovalStatus.decoder.decode(__NumberValue(1))
          assertTrue(result.isLeft)
        },
      ),
      suite("ApprovalStatus encoder")(
        test("encodes Approved to __EnumValue") {
          import caliban.client.__Value.__EnumValue
          val encoded = JorlanClient.ApprovalStatus.encoder.encode(JorlanClient.ApprovalStatus.Approved)
          assertTrue(encoded == __EnumValue("Approved"))
        },
      ),
      suite("EventType decoder")(
        test("decodes SkillInvoked") {
          assertTrue(
            JorlanClient.EventType.decoder.decode(__StringValue("SkillInvoked")) == Right(
              JorlanClient.EventType.SkillInvoked,
            ),
          )
        },
        test("decodes UserCreated") {
          assertTrue(
            JorlanClient.EventType.decoder.decode(__StringValue("UserCreated")) == Right(
              JorlanClient.EventType.UserCreated,
            ),
          )
        },
        test("fails on unknown event type") {
          val result = JorlanClient.EventType.decoder.decode(__StringValue("NotAnEvent"))
          assertTrue(result.isLeft)
        },
      ),
    )

}
