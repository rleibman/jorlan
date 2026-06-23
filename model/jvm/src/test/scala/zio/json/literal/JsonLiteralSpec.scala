/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package zio.json.literal

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*
import scala.compiletime.testing.typeChecks

object JsonLiteralSpec extends ZIOSpecDefault {

  // ── helpers ──────────────────────────────────────────────────────────────────

  private def parseUnsafe(s: String): Json =
    s.fromJson[Json].fold(e => throw new RuntimeException(e), identity)

  // ── spec ─────────────────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("json string interpolator")(
      suite("static literals (no interpolation)")(
        test("null literal") {
          assertTrue(json"null" == Json.Null)
        },
        test("true literal") {
          assertTrue(json"true" == Json.Bool(true))
        },
        test("false literal") {
          assertTrue(json"false" == Json.Bool(false))
        },
        test("integer number") {
          assertTrue(json"42" == Json.Num(new java.math.BigDecimal("42")))
        },
        test("decimal number") {
          assertTrue(json"3.14" == Json.Num(new java.math.BigDecimal("3.14")))
        },
        test("string literal") {
          assertTrue(json""""hello"""" == Json.Str("hello"))
        },
        test("empty object") {
          assertTrue(json"{}" == Json.Obj(zio.Chunk.empty))
        },
        test("empty array") {
          assertTrue(json"[]" == Json.Arr(zio.Chunk.empty))
        },
        test("complex nested object without interpolation") {
          val result = json"""{"name":"Alice","age":30,"active":true,"score":null}"""
          val expected = parseUnsafe("""{"name":"Alice","age":30,"active":true,"score":null}""")
          assertTrue(result == expected)
        },
        test("nested array") {
          val result = json"""[1, 2, [3, 4]]"""
          assertTrue(result == parseUnsafe("[1,2,[3,4]]"))
        },
        test("deeply nested object") {
          val result = json"""{"a":{"b":{"c":42}}}"""
          assertTrue(result == parseUnsafe("""{"a":{"b":{"c":42}}}"""))
        },
      ),
      suite("value-position interpolation")(
        test("interpolate a String value") {
          val name = "Alice"
          assertTrue(json"""{"name": $name}""" == parseUnsafe("""{"name":"Alice"}"""))
        },
        test("interpolate an Int value") {
          val age = 30
          assertTrue(json"""{"age": $age}""" == parseUnsafe("""{"age":30}"""))
        },
        test("interpolate a Boolean value") {
          val active = true
          assertTrue(json"""{"active": $active}""" == parseUnsafe("""{"active":true}"""))
        },
        test("interpolate a Long value") {
          val big = 9999999999L
          assertTrue(json"""{"id": $big}""" == parseUnsafe("""{"id":9999999999}"""))
        },
        test("interpolate a Double value") {
          val ratio = 1.5
          assertTrue(json"""{"ratio": $ratio}""" == parseUnsafe("""{"ratio":1.5}"""))
        },
        test("interpolate a Json value directly") {
          val inner: Json = Json.Str("nested")
          assertTrue(json"""{"x": $inner}""" == parseUnsafe("""{"x":"nested"}"""))
        },
        test("interpolate multiple values") {
          val name = "Bob"
          val age  = 25
          assertTrue(json"""{"name": $name, "age": $age}""" == parseUnsafe("""{"name":"Bob","age":25}"""))
        },
        test("interpolate a value into an array") {
          val n = 99
          assertTrue(json"""[1, $n, 3]""" == parseUnsafe("[1,99,3]"))
        },
        test("interpolate an Option[String] — Some") {
          val maybeVal: Option[String] = Some("present")
          assertTrue(json"""{"v": $maybeVal}""" == parseUnsafe("""{"v":"present"}"""))
        },
        test("interpolate an Option[String] — None") {
          val maybeVal: Option[String] = None
          assertTrue(json"""{"v": $maybeVal}""" == parseUnsafe("""{"v":null}"""))
        },
        test("interpolate a List[Int]") {
          val nums = List(1, 2, 3)
          assertTrue(json"""{"nums": $nums}""" == parseUnsafe("""{"nums":[1,2,3]}"""))
        },
        test("inline literal value in value position") {
          assertTrue(json"""{"x": ${42}}""" == parseUnsafe("""{"x":42}"""))
        },
      ),
      suite("key-position interpolation")(
        test("interpolate a String as object key") {
          val key = "dynamic"
          assertTrue(json"""{$key: "value"}""" == parseUnsafe("""{"dynamic":"value"}"""))
        },
        test("interpolate an Int as object key (via toString)") {
          val idx = 7
          assertTrue(json"""{$idx: "item"}""" == parseUnsafe("""{"7":"item"}"""))
        },
        test("inline literal Int as key") {
          assertTrue(json"""{${1}: "one"}""" == parseUnsafe("""{"1":"one"}"""))
        },
      ),
      suite("mixed key and value interpolation")(
        test("key and value both interpolated") {
          val k = "score"
          val v = 100
          assertTrue(json"""{$k: $v}""" == parseUnsafe("""{"score":100}"""))
        },
        test("multiple pairs with mixed static and dynamic") {
          val user = "carol"
          val rank = 3
          val result = json"""{"user": $user, "rank": $rank, "verified": true}"""
          assertTrue(result == parseUnsafe("""{"user":"carol","rank":3,"verified":true}"""))
        },
      ),
      suite("compile-time validation")(
        test("mismatched brackets are rejected at compile time") {
          assertTrue(!typeChecks("""import zio.json.literal.*; json"[1,}""""))
        },
        test("unclosed brace is rejected at compile time") {
          assertTrue(!typeChecks("""import zio.json.literal.*; json"{" """))
        },
        test("unknown type without JsonEncoder is rejected at compile time") {
          assertTrue(
            !typeChecks(
              """
              import zio.json.literal.*
              class NoEncoder(x: Int)
              val v = new NoEncoder(1)
              json\"\"\"{"v": $v}\"\"\"
              """,
            ),
          )
        },
      ),
    )

}
