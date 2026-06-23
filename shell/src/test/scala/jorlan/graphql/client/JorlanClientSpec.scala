/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.graphql.client

import caliban.client.__Value.{__NumberValue, __StringValue}
import caliban.client.{ArgEncoder, ScalarDecoder}
import jorlan.graphql.client.JorlanClientDecoders.given
import jorlan.{ApprovalStatus, EventType}
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
            summon[ScalarDecoder[ApprovalStatus]].decode(__StringValue("Pending")) == Right(ApprovalStatus.Pending),
          )
        },
        test("fails on unknown status") {
          val result = summon[ScalarDecoder[ApprovalStatus]].decode(__StringValue("UnknownStatus"))
          assertTrue(result.isLeft)
        },
        test("fails on numeric value") {
          val result = summon[ScalarDecoder[ApprovalStatus]].decode(__NumberValue(1))
          assertTrue(result.isLeft)
        },
      ),
      suite("ApprovalStatus encoder")(
        test("encodes Approved to __EnumValue") {
          import caliban.client.__Value.__EnumValue
          val encoded = summon[ArgEncoder[ApprovalStatus]].encode(ApprovalStatus.Approved)
          assertTrue(encoded == __EnumValue("Approved"))
        },
      ),
      suite("EventType decoder")(
        test("decodes SkillInvoked") {
          assertTrue(
            summon[ScalarDecoder[EventType]].decode(__StringValue("SkillInvoked")) == Right(EventType.SkillInvoked),
          )
        },
        test("decodes UserCreated") {
          assertTrue(
            summon[ScalarDecoder[EventType]].decode(__StringValue("UserCreated")) == Right(EventType.UserCreated),
          )
        },
        test("fails on unknown event type") {
          val result = summon[ScalarDecoder[EventType]].decode(__StringValue("NotAnEvent"))
          assertTrue(result.isLeft)
        },
      ),
    )

}
