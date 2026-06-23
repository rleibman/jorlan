/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.units

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object UnitConversionSkillSpec extends ZIOSpecDefault {

  private val skill = new UnitConversionSkill()

  private val dummyCtx = InvocationContext(
    actorId = UserId(0L),
    agentId = None,
    sessionId = None,
  )

  /** Invoke the skill's units.convert tool and return the numeric "result" field. */
  private def convert(
    value:    Double,
    fromUnit: String,
    toUnit:   String,
  ): ZIO[Any, JorlanError, Double] = {
    val args = Json.Obj(
      "value"    -> Json.Num(value),
      "fromUnit" -> Json.Str(fromUnit),
      "toUnit"   -> Json.Str(toUnit),
    )
    skill.invoke(dummyCtx, "units.convert", args).map {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("result", Json.Num(n)) => n.doubleValue() }
          .getOrElse(throw new RuntimeException("No 'result' field in response"))
      case other => throw new RuntimeException(s"Unexpected response: $other")
    }
  }

  private def convertArgs(
    value:    Double,
    fromUnit: String,
    toUnit:   String,
  ): Json =
    Json.Obj(
      "value"    -> Json.Num(value),
      "fromUnit" -> Json.Str(fromUnit),
      "toUnit"   -> Json.Str(toUnit),
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("UnitConversionSkillSpec")(
      test("1 km converts to 1000 m") {
        for {
          result <- convert(1.0, "km", "m")
        } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("100 C converts to 212 F") {
        for {
          result <- convert(100.0, "C", "F")
        } yield assertTrue(math.abs(result - 212.0) < 0.001)
      },
      test("0 C converts to 32 F") {
        for {
          result <- convert(0.0, "C", "F")
        } yield assertTrue(math.abs(result - 32.0) < 0.001)
      },
      test("1 kg converts to approximately 2.2046 lb") {
        for {
          result <- convert(1.0, "kg", "lb")
        } yield assertTrue(result > 2.2 && result < 2.21)
      },
      test("1 km to kg fails with ValidationError for incompatible units") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", convertArgs(1.0, "km", "kg")).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("'metre' alias works the same as 'm'") {
        for {
          result1 <- convert(1.0, "km", "m")
          result2 <- convert(1.0, "km", "metre")
        } yield assertTrue(math.abs(result1 - result2) < 0.001)
      },
      test("unknown unit fails with ValidationError") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", convertArgs(1.0, "furlongs", "m")).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("temperature to non-temperature fails with ValidationError") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", convertArgs(100.0, "C", "kg")).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("0 K converts to -273.15 C") {
        for {
          result <- convert(0.0, "K", "C")
        } yield assertTrue(math.abs(result - -273.15) < 0.01)
      },
      test("1024 MB converts to approximately 1.024 GB") {
        for {
          result <- convert(1024.0, "MB", "GB")
        } yield assertTrue(math.abs(result - 1.024) < 0.0001)
      },
      test("1 hour converts to 3600 seconds") {
        for {
          result <- convert(1.0, "h", "s")
        } yield assertTrue(math.abs(result - 3600.0) < 0.001)
      },
      test("1 liter converts to 1000 milliliters") {
        for {
          result <- convert(1.0, "L", "mL")
        } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("60 mph converts to approximately 96.56 km/h") {
        for {
          result <- convert(60.0, "mph", "km/h")
        } yield assertTrue(result > 96.0 && result < 97.0)
      },
      test("1 square meter converts to approximately 10.764 square feet") {
        for {
          result <- convert(1.0, "m2", "ft2")
        } yield assertTrue(result > 10.7 && result < 10.8)
      },
      test("1 kilowatt-hour converts to 3600000 joules") {
        for {
          result <- convert(1.0, "kWh", "J")
        } yield assertTrue(math.abs(result - 3600000.0) < 1.0)
      },
      test("1 kilowatt converts to 1000 watts") {
        for {
          result <- convert(1.0, "kW", "W")
        } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("missing value field fails with ValidationError") {
        for {
          result <- skill
            .invoke(dummyCtx, "units.convert", Json.Obj("fromUnit" -> Json.Str("km"), "toUnit" -> Json.Str("m"))).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("missing fromUnit field fails with ValidationError") {
        for {
          result <- skill
            .invoke(dummyCtx, "units.convert", Json.Obj("value" -> Json.Num(1.0), "toUnit" -> Json.Str("m"))).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("missing toUnit field fails with ValidationError") {
        for {
          result <- skill
            .invoke(dummyCtx, "units.convert", Json.Obj("value" -> Json.Num(1.0), "fromUnit" -> Json.Str("km"))).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("args not a JSON object fails with ValidationError") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", Json.Arr()).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("unknown tool name fails with ValidationError") {
        for {
          result <- skill.invoke(dummyCtx, "units.unknown", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("conversion result includes fromUnit, toUnit, and inputValue fields") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", convertArgs(5.0, "km", "m"))
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("fromUnit"))(isSome(equalTo(Json.Str("km")))) &&
            assert(fieldMap.get("toUnit"))(isSome(equalTo(Json.Str("m")))) &&
            assert(fieldMap.get("inputValue"))(isSome(equalTo(Json.Num(5.0))))
          case _ => assert(false)(isTrue)
        }
      },
      test("temperature to non-temperature fails for F to kg") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", convertArgs(100.0, "F", "kg")).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("non-temperature to temperature fails for km to C") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", convertArgs(1.0, "km", "C")).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
      test("32 F converts to 0 C") {
        for {
          result <- convert(32.0, "F", "C")
        } yield assertTrue(math.abs(result - 0.0) < 0.01)
      },
      test("1 GB converts to 1000 MB (SI)") {
        for {
          result <- convert(1.0, "GB", "MB")
        } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
    )

}
