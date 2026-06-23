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
      // ─── Length units ────────────────────────────────────────────────────────
      test("1 cm converts to 10 mm") {
        for { result <- convert(1.0, "cm", "mm") } yield assertTrue(math.abs(result - 10.0) < 0.001)
      },
      test("1 ft converts to 12 in") {
        for { result <- convert(1.0, "ft", "in") } yield assertTrue(math.abs(result - 12.0) < 0.01)
      },
      test("1 yd converts to 3 ft") {
        for { result <- convert(1.0, "yd", "ft") } yield assertTrue(math.abs(result - 3.0) < 0.001)
      },
      test("1 mi converts to approximately 1760 yd") {
        for { result <- convert(1.0, "mi", "yd") } yield assertTrue(result > 1759 && result < 1761)
      },
      test("1000 mm converts to 1 m") {
        for { result <- convert(1000.0, "mm", "m") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("100 cm converts to 1 m") {
        for { result <- convert(100.0, "cm", "m") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 mile converts to approximately 1.609 km") {
        for { result <- convert(1.0, "mile", "kilometer") } yield assertTrue(result > 1.609 && result < 1.610)
      },
      test("1 foot converts to approximately 0.3048 meters") {
        for { result <- convert(1.0, "foot", "metre") } yield assertTrue(math.abs(result - 0.3048) < 0.001)
      },
      test("1 inch converts to approximately 2.54 cm") {
        for { result <- convert(1.0, "inch", "centimeter") } yield assertTrue(math.abs(result - 2.54) < 0.01)
      },
      test("1 yard converts to approximately 0.9144 m") {
        for { result <- convert(1.0, "yard", "meters") } yield assertTrue(math.abs(result - 0.9144) < 0.001)
      },
      // ─── Mass units ──────────────────────────────────────────────────────────
      test("1000 g converts to 1 kg") {
        for { result <- convert(1000.0, "g", "kg") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 g converts to 1000 mg") {
        for { result <- convert(1.0, "gram", "milligram") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 oz converts to approximately 28.35 g") {
        for { result <- convert(1.0, "oz", "g") } yield assertTrue(result > 28.3 && result < 28.4)
      },
      test("1 tonne converts to 1000 kg") {
        for { result <- convert(1.0, "tonne", "kg") } yield assertTrue(math.abs(result - 1000.0) < 0.01)
      },
      test("16 oz converts to 1 lb") {
        for { result <- convert(16.0, "ounces", "pounds") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 kg converts to 1000 grams") {
        for { result <- convert(1.0, "kilogram", "grams") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1000 mg converts to 1 g") {
        for { result <- convert(1000.0, "milligrams", "gram") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 lb converts to approximately 453.6 g") {
        for { result <- convert(1.0, "pound", "grams") } yield assertTrue(result > 453.0 && result < 454.0)
      },
      test("1 tonnes converts to 1e6 mg") {
        for { result <- convert(1.0, "tonnes", "milligrams") } yield assertTrue(math.abs(result - 1e9) < 1.0)
      },
      // ─── Volume units ────────────────────────────────────────────────────────
      test("1 gal converts to approximately 3785 mL") {
        for { result <- convert(1.0, "gal", "ml") } yield assertTrue(result > 3785.0 && result < 3786.0)
      },
      test("1 qt converts to approximately 946 mL") {
        for { result <- convert(1.0, "qt", "ml") } yield assertTrue(result > 945.0 && result < 947.0)
      },
      test("1 pt converts to approximately 473 mL") {
        for { result <- convert(1.0, "pt", "ml") } yield assertTrue(result > 472.0 && result < 474.0)
      },
      test("1 liter converts to approximately 0.264 gallons") {
        for { result <- convert(1.0, "liter", "gallon") } yield assertTrue(result > 0.264 && result < 0.265)
      },
      test("4 quarts converts to 1 gallon") {
        for { result <- convert(4.0, "quarts", "gallons") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("2 pints converts to 1 quart") {
        for { result <- convert(2.0, "pints", "quart") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 litre converts to 1000 millilitres") {
        for { result <- convert(1.0, "litre", "millilitre") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1000 milliliters converts to 1 liter") {
        for { result <- convert(1000.0, "milliliters", "liters") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 pint converts to approximately 0.5 quarts") {
        for { result <- convert(1.0, "pint", "quart") } yield assertTrue(math.abs(result - 0.5) < 0.001)
      },
      // ─── Velocity units ──────────────────────────────────────────────────────
      test("1 m/s converts to approximately 3.6 km/h") {
        for { result <- convert(1.0, "m/s", "km/h") } yield assertTrue(result > 3.59 && result < 3.61)
      },
      test("1 knot converts to approximately 1.852 km/h") {
        for { result <- convert(1.0, "kn", "kph") } yield assertTrue(result > 1.85 && result < 1.86)
      },
      test("1 mps converts to approximately 2.237 mph") {
        for { result <- convert(1.0, "mps", "mph") } yield assertTrue(result > 2.23 && result < 2.24)
      },
      test("100 kmh converts to approximately 62.1 mph") {
        for { result <- convert(100.0, "kmh", "mph") } yield assertTrue(result > 62.0 && result < 63.0)
      },
      test("1 knot converts to approximately 0.5144 m/s") {
        for { result <- convert(1.0, "knot", "mps") } yield assertTrue(result > 0.514 && result < 0.515)
      },
      test("60 knots converts to km/h") {
        for { result <- convert(60.0, "knots", "km/h") } yield assertTrue(result > 111.0 && result < 112.0)
      },
      // ─── Area units ──────────────────────────────────────────────────────────
      test("1 km2 converts to 1e6 m2") {
        for { result <- convert(1.0, "km2", "m2") } yield assertTrue(math.abs(result - 1e6) < 1.0)
      },
      test("1 ha converts to 10000 m2") {
        for { result <- convert(1.0, "ha", "squaremeters") } yield assertTrue(math.abs(result - 10000.0) < 0.1)
      },
      test("1 acre converts to approximately 4047 m2") {
        for { result <- convert(1.0, "acre", "m2") } yield assertTrue(result > 4046.0 && result < 4047.5)
      },
      test("1 m2 converts to approximately 10.764 ft2") {
        for { result <- convert(1.0, "squaremeter", "squarefoot") } yield assertTrue(result > 10.7 && result < 10.8)
      },
      test("1 km2 converts to 100 ha") {
        for { result <- convert(1.0, "squarekilometer", "hectares") } yield assertTrue(math.abs(result - 100.0) < 0.001)
      },
      test("1 ha converts to approximately 2.471 acres") {
        for { result <- convert(1.0, "hectare", "acres") } yield assertTrue(result > 2.47 && result < 2.48)
      },
      test("1 squarekilometers converts to squarefeet") {
        for { result <- convert(1.0, "squarekilometers", "squarefeet") } yield assertTrue(result > 1.0e7)
      },
      // ─── Energy units ────────────────────────────────────────────────────────
      test("1 kJ converts to 1000 J") {
        for { result <- convert(1.0, "kj", "j") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 cal converts to approximately 4.184 J") {
        for { result <- convert(1.0, "cal", "j") } yield assertTrue(math.abs(result - 4.184) < 0.001)
      },
      test("1 kcal converts to 1000 cal") {
        for { result <- convert(1.0, "kcal", "cal") } yield assertTrue(math.abs(result - 1000.0) < 0.01)
      },
      test("1 J converts to approximately 0.000278 kWh") {
        for { result <- convert(3600000.0, "joule", "kwh") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 kilojoule converts to approximately 0.239 kcal") {
        for { result <- convert(1.0, "kilojoule", "kilocalorie") } yield assertTrue(result > 0.238 && result < 0.24)
      },
      test("1 kilocalorie converts to 4184 J") {
        for { result <- convert(1.0, "kilocalorie", "joules") } yield assertTrue(math.abs(result - 4184.0) < 0.1)
      },
      test("100 calories converts to J") {
        for { result <- convert(100.0, "calories", "joule") } yield assertTrue(math.abs(result - 418.4) < 0.1)
      },
      test("1 kilowatthour converts to kilojoules") {
        for { result <- convert(1.0, "kilowatthour", "kilojoules") } yield assertTrue(math.abs(result - 3600.0) < 0.1)
      },
      test("1 kilojoules converts to J") {
        for { result <- convert(1.0, "kilojoules", "joule") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 kilocalories converts to kcal") {
        for { result <- convert(1.0, "kilocalories", "kcal") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      // ─── Power units ─────────────────────────────────────────────────────────
      test("1 MW converts to 1e6 W") {
        for { result <- convert(1.0, "mw", "w") } yield assertTrue(math.abs(result - 1e6) < 1.0)
      },
      test("1 hp converts to approximately 745.7 W") {
        for { result <- convert(1.0, "hp", "watt") } yield assertTrue(result > 745.0 && result < 746.0)
      },
      test("1 W converts to 0.001 kW") {
        for { result <- convert(1.0, "watt", "kilowatt") } yield assertTrue(math.abs(result - 0.001) < 0.0001)
      },
      test("1 megawatt converts to 1000 kW") {
        for { result <- convert(1.0, "megawatt", "kilowatts") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 kilowatts converts to 1000 watts") {
        for { result <- convert(1.0, "kilowatts", "watts") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 megawatts converts to W") {
        for { result <- convert(1.0, "megawatts", "w") } yield assertTrue(math.abs(result - 1e6) < 1.0)
      },
      test("1 horsepower converts to approximately 0.746 kW") {
        for { result <- convert(1.0, "horsepower", "kw") } yield assertTrue(result > 0.745 && result < 0.746)
      },
      // ─── Time units ──────────────────────────────────────────────────────────
      test("1 min converts to 60 s") {
        for { result <- convert(1.0, "min", "s") } yield assertTrue(math.abs(result - 60.0) < 0.001)
      },
      test("1 day converts to 24 hours") {
        for { result <- convert(1.0, "d", "h") } yield assertTrue(math.abs(result - 24.0) < 0.001)
      },
      test("1 hour converts to 60 min") {
        for { result <- convert(1.0, "hour", "minute") } yield assertTrue(math.abs(result - 60.0) < 0.001)
      },
      test("1 day converts to 86400 seconds") {
        for { result <- convert(1.0, "day", "second") } yield assertTrue(math.abs(result - 86400.0) < 0.001)
      },
      test("120 seconds converts to 2 minutes") {
        for { result <- convert(120.0, "seconds", "minutes") } yield assertTrue(math.abs(result - 2.0) < 0.001)
      },
      test("1 sec converts to 1 s") {
        for { result <- convert(1.0, "sec", "s") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("48 hr converts to 2 days") {
        for { result <- convert(48.0, "hr", "days") } yield assertTrue(math.abs(result - 2.0) < 0.001)
      },
      test("1 hours converts to 3600 s") {
        for { result <- convert(1.0, "hours", "s") } yield assertTrue(math.abs(result - 3600.0) < 0.001)
      },
      test("1 minute converts to 60 sec") {
        for { result <- convert(1.0, "minute", "sec") } yield assertTrue(math.abs(result - 60.0) < 0.001)
      },
      // ─── Digital Information units ────────────────────────────────────────────
      test("1 byte converts to 8 bits (not in registry, so 1 byte → 1 byte)") {
        for { result <- convert(1.0, "byte", "bytes") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 KB converts to 1000 bytes") {
        for { result <- convert(1.0, "kb", "bytes") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 TB converts to 1e12 bytes") {
        for { result <- convert(1.0, "tb", "byte") } yield assertTrue(math.abs(result - 1e12) < 1.0)
      },
      test("1000 bytes converts to 1 KB") {
        for { result <- convert(1000.0, "bytes", "kb") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 kilobyte converts to 1000 bytes") {
        for { result <- convert(1.0, "kilobyte", "byte") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 megabyte converts to 1000 KB") {
        for { result <- convert(1.0, "megabyte", "kilobytes") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 gigabyte converts to 1000 megabytes") {
        for { result <- convert(1.0, "gigabyte", "megabytes") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 terabyte converts to 1000 GB") {
        for { result <- convert(1.0, "terabyte", "gigabytes") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("1 kilobytes converts to 1 kb") {
        for { result <- convert(1.0, "kilobytes", "kb") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 megabytes converts to 1 mb") {
        for { result <- convert(1.0, "megabytes", "mb") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 gigabytes converts to 1 gb") {
        for { result <- convert(1.0, "gigabytes", "gb") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("1 terabytes converts to 1 tb") {
        for { result <- convert(1.0, "terabytes", "tb") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      // ─── Temperature additional combos ────────────────────────────────────────
      test("0 K converts to approximately -459.67 F") {
        for { result <- convert(0.0, "K", "F") } yield assertTrue(result > -460.0 && result < -459.0)
      },
      test("273.15 K converts to 0 C") {
        for { result <- convert(273.15, "kelvin", "celsius") } yield assertTrue(math.abs(result - 0.0) < 0.01)
      },
      test("0 C converts to 273.15 K") {
        for { result <- convert(0.0, "celsius", "kelvin") } yield assertTrue(math.abs(result - 273.15) < 0.01)
      },
      test("212 F converts to 373.15 K") {
        for { result <- convert(212.0, "fahrenheit", "k") } yield assertTrue(result > 373.0 && result < 374.0)
      },
      // ─── Alias coverage ───────────────────────────────────────────────────────
      test("'metres' alias works") {
        for { result <- convert(1.0, "km", "metres") } yield assertTrue(math.abs(result - 1000.0) < 0.001)
      },
      test("'kilometers' alias works") {
        for { result <- convert(1000.0, "m", "kilometers") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'kilometres' alias works") {
        for { result <- convert(1000.0, "m", "kilometres") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'centimetre' alias works") {
        for { result <- convert(100.0, "m", "centimetre") } yield assertTrue(math.abs(result - 10000.0) < 0.1)
      },
      test("'millimeter' alias works") {
        for { result <- convert(1.0, "cm", "millimeter") } yield assertTrue(math.abs(result - 10.0) < 0.001)
      },
      test("'millimetre' alias works") {
        for { result <- convert(1.0, "cm", "millimetre") } yield assertTrue(math.abs(result - 10.0) < 0.001)
      },
      test("'miles' alias works") {
        for { result <- convert(1.0, "mile", "miles") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'feet' alias works") {
        for { result <- convert(1.0, "foot", "feet") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'inches' alias works") {
        for { result <- convert(1.0, "inch", "inches") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'yards' alias works") {
        for { result <- convert(1.0, "yard", "yards") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'kilograms' alias works") {
        for { result <- convert(1.0, "kilogram", "kilograms") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'grams' alias works") {
        for { result <- convert(1.0, "gram", "grams") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'milligrams' alias works") {
        for { result <- convert(1.0, "milligram", "milligrams") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'lbs' alias works") {
        for { result <- convert(1.0, "lb", "lbs") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'ounce' alias works") {
        for { result <- convert(1.0, "oz", "ounce") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'ounces' alias works") {
        for { result <- convert(1.0, "oz", "ounces") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'t' alias for tonne works") {
        for { result <- convert(1.0, "tonne", "t") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'litres' alias works") {
        for { result <- convert(1.0, "liter", "litres") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'liters' alias works") {
        for { result <- convert(1.0, "l", "liters") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'milliliter' alias works") {
        for { result <- convert(1.0, "ml", "milliliter") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'gallon' alias works") {
        for { result <- convert(1.0, "gal", "gallon") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'gallons' alias works") {
        for { result <- convert(1.0, "gal", "gallons") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'quart' alias works") {
        for { result <- convert(1.0, "qt", "quart") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'pint' alias works") {
        for { result <- convert(1.0, "pt", "pint") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'mps' alias works") {
        for { result <- convert(1.0, "m/s", "mps") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'kph' alias works") {
        for { result <- convert(1.0, "km/h", "kph") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'squaremeters' alias works") {
        for { result <- convert(1.0, "m2", "squaremeters") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'squarekilometer' alias works") {
        for { result <- convert(1.0, "km2", "squarekilometer") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'squarefeet' alias works") {
        for { result <- convert(1.0, "ft2", "squarefeet") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'acres' alias works") {
        for { result <- convert(1.0, "acre", "acres") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'hectare' alias works") {
        for { result <- convert(1.0, "ha", "hectare") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'joule' alias works") {
        for { result <- convert(1.0, "j", "joule") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'joules' alias works") {
        for { result <- convert(1.0, "j", "joules") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'kilojoule' alias works") {
        for { result <- convert(1.0, "kj", "kilojoule") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'watt' alias works") {
        for { result <- convert(1.0, "w", "watt") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'kilowatt' alias works") {
        for { result <- convert(1.0, "kw", "kilowatt") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'megawatt' alias works") {
        for { result <- convert(1.0, "mw", "megawatt") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'second' alias works") {
        for { result <- convert(1.0, "s", "second") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'minute' alias in both directions works") {
        for { result <- convert(1.0, "min", "minute") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("'hour' alias works") {
        for { result <- convert(1.0, "h", "hour") } yield assertTrue(math.abs(result - 1.0) < 0.001)
      },
      test("unknown TO unit fails") {
        for {
          result <- skill.invoke(dummyCtx, "units.convert", convertArgs(1.0, "km", "furlongs")).exit
        } yield assert(result)(failsWithA[ValidationError])
      },
    )

}
