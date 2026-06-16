/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
          .collectFirst { case ("result", Json.Num(n)) => n.toDouble }
          .getOrElse(throw new RuntimeException("No 'result' field in response"))
      case other => throw new RuntimeException(s"Unexpected response: $other")
    }
  }

  /** Invoke and return the error string (if any). */
  private def convertError(
    value:    Double,
    fromUnit: String,
    toUnit:   String,
  ): ZIO[Any, JorlanError, String] = {
    val args = Json.Obj(
      "value"    -> Json.Num(value),
      "fromUnit" -> Json.Str(fromUnit),
      "toUnit"   -> Json.Str(toUnit),
    )
    skill.invoke(dummyCtx, "units.convert", args).map {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("error", Json.Str(e)) => e }
          .getOrElse("")
      case _ => ""
    }
  }

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
      test("1 km to kg returns incompatible units error") {
        for {
          err <- convertError(1.0, "km", "kg")
        } yield assertTrue(err.contains("Incompatible") || err.contains("incompatible"))
      },
      test("'metre' alias works the same as 'm'") {
        for {
          result1 <- convert(1.0, "km", "m")
          result2 <- convert(1.0, "km", "metre")
        } yield assertTrue(math.abs(result1 - result2) < 0.001)
      },
      test("unknown unit returns error") {
        for {
          err <- convertError(1.0, "furlongs", "m")
        } yield assertTrue(err.contains("Unknown unit") || err.contains("unknown unit"))
      },
      test("temperature to non-temperature returns error") {
        for {
          err <- convertError(100.0, "C", "kg")
        } yield assertTrue(err.nonEmpty)
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
    )

}
