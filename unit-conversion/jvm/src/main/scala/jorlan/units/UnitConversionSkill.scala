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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import squants.energy.*
import squants.information.*
import squants.mass.*
import squants.motion.*
import squants.space.*
import squants.thermal.*
import squants.time.*
import zio.*
import zio.json.ast.Json

/** Tier 0 unit conversion skill.
  *
  * Exposes a single `units.convert` tool that converts a numeric value between compatible units of measure using the
  * squants library.
  */
class UnitConversionSkill extends Skill {

  // ─────────────────────────────────────────────────────────────────────────
  // Each unit is represented as a pair of functions:
  //   toBase  : value in this unit → value in the dimension's canonical base unit
  //   fromBase: value in canonical base unit → value in this unit
  // For Length  : base = Meters
  // For Mass    : base = Grams
  // For Volume  : base = Millilitres
  // For Velocity: base = MetersPerSecond
  // For Area    : base = SquareMeters
  // For Energy  : base = Joules
  // For Power   : base = Watts
  // For Time    : base = Seconds
  // For Info    : base = Bytes
  // Temperature is handled specially via squants.
  // ─────────────────────────────────────────────────────────────────────────

  private case class UomEntry(
    dimension: String,
    toBase:    Double => Double,
    fromBase:  Double => Double,
  )

  // Helpers that build UomEntry via squants for non-temperature units
  private def lengthEntry(
    toMeters:   Double => Double,
    fromMeters: Double => Double,
  ): UomEntry = UomEntry("Length", toMeters, fromMeters)

  private def massEntry(
    toGrams:   Double => Double,
    fromGrams: Double => Double,
  ): UomEntry = UomEntry("Mass", toGrams, fromGrams)

  private def volumeEntry(
    toMl:   Double => Double,
    fromMl: Double => Double,
  ): UomEntry = UomEntry("Volume", toMl, fromMl)

  private def velocityEntry(
    toMps:   Double => Double,
    fromMps: Double => Double,
  ): UomEntry = UomEntry("Velocity", toMps, fromMps)

  private def areaEntry(
    toSqM:   Double => Double,
    fromSqM: Double => Double,
  ): UomEntry = UomEntry("Area", toSqM, fromSqM)

  private def energyEntry(
    toJoules:   Double => Double,
    fromJoules: Double => Double,
  ): UomEntry = UomEntry("Energy", toJoules, fromJoules)

  private def powerEntry(
    toWatts:   Double => Double,
    fromWatts: Double => Double,
  ): UomEntry = UomEntry("Power", toWatts, fromWatts)

  private def timeEntry(
    toSeconds:   Double => Double,
    fromSeconds: Double => Double,
  ): UomEntry = UomEntry("Time", toSeconds, fromSeconds)

  private def infoEntry(
    toBytes:   Double => Double,
    fromBytes: Double => Double,
  ): UomEntry = UomEntry("Information", toBytes, fromBytes)

  // ─────────────────────────────────────────────────────────────────────────
  // Use squants to compute accurate conversion factors at init time
  // ─────────────────────────────────────────────────────────────────────────

  // Length: base = Meters
  private val metersPerKm = Kilometers(1).to(Meters) // 1000
  private val metersPerCm = Centimeters(1).to(Meters) // 0.01
  private val metersPerMm = Millimeters(1).to(Meters) // 0.001
  private val metersPerMi = UsMiles(1).to(Meters) // 1609.344
  private val metersPerFt = Feet(1).to(Meters) // 0.3048
  private val metersPerIn = Inches(1).to(Meters) // 0.0254
  private val metersPerYd = Yards(1).to(Meters) // 0.9144

  // Mass: base = Grams
  private val gramsPerKg = Kilograms(1).to(Grams) // 1000
  private val gramsPerMg = Milligrams(1).to(Grams) // 0.001
  private val gramsPerLb = Pounds(1).to(Grams) // 453.59237
  private val gramsPerOz = Ounces(1).to(Grams) // 28.349523125
  private val gramsPerTonne = Tonnes(1).to(Grams) // 1_000_000

  // Volume: base = Millilitres
  private val mlPerLitre = Litres(1).to(Millilitres) // 1000
  private val mlPerGallon = UsGallons(1).to(Millilitres) // 3785.411784
  private val mlPerQuart = UsQuarts(1).to(Millilitres) // 946.352946
  private val mlPerPint = UsPints(1).to(Millilitres) // 473.176473

  // Velocity: base = MetersPerSecond
  private val mpsPerKmh = KilometersPerHour(1).to(MetersPerSecond) // 0.27778
  private val mpsPerMph = UsMilesPerHour(1).to(MetersPerSecond) // 0.44704
  private val mpsPerKnot = Knots(1).to(MetersPerSecond) // 0.514444

  // Area: base = SquareMeters
  private val sqmPerKm2 = SquareKilometers(1).to(SquareMeters) // 1_000_000
  private val sqmPerFt2 = SquareFeet(1).to(SquareMeters) // 0.0929
  private val sqmPerAcre = Acres(1).to(SquareMeters) // 4046.856422
  private val sqmPerHa = Hectares(1).to(SquareMeters) // 10_000

  // Energy: base = Joules
  private val joulesPerKj = Kilojoules(1).to(Joules) // 1000
  private val joulesPerKwh = KilowattHours(1).to(Joules) // 3_600_000
  // Manual: 1 cal = 4.184 J, 1 kcal = 4184 J
  private val joulesPerCal = 4.184
  private val joulesPerKcal = 4184.0

  // Power: base = Watts
  private val wattsPerKw = Kilowatts(1).to(Watts) // 1000
  private val wattsPerMw = Megawatts(1).to(Watts) // 1_000_000
  // Manual: 1 mechanical hp = 745.69987 W
  private val wattsPerHp = 745.69987

  // Time: base = Seconds
  private val secPerMin = Minutes(1).to(Seconds) // 60
  private val secPerHour = Hours(1).to(Seconds) // 3600
  private val secPerDay = Days(1).to(Seconds) // 86400

  // Information: base = Bytes
  private val bytesPerKb = Kilobytes(1).to(Bytes) // 1000
  private val bytesPerMb = Megabytes(1).to(Bytes) // 1_000_000
  private val bytesPerGb = Gigabytes(1).to(Bytes) // 1_000_000_000
  private val bytesPerTb = Terabytes(1).to(Bytes) // 1_000_000_000_000

  // ─────────────────────────────────────────────────────────────────────────
  // Unit registry
  // ─────────────────────────────────────────────────────────────────────────

  private val unitMap: Map[String, UomEntry] = Map(
    // ── Length ──────────────────────────────────────────────────────────
    "m"          -> lengthEntry(v => v, v => v),
    "meter"      -> lengthEntry(v => v, v => v),
    "metre"      -> lengthEntry(v => v, v => v),
    "meters"     -> lengthEntry(v => v, v => v),
    "metres"     -> lengthEntry(v => v, v => v),
    "km"         -> lengthEntry(v => v * metersPerKm, v => v / metersPerKm),
    "kilometer"  -> lengthEntry(v => v * metersPerKm, v => v / metersPerKm),
    "kilometre"  -> lengthEntry(v => v * metersPerKm, v => v / metersPerKm),
    "kilometers" -> lengthEntry(v => v * metersPerKm, v => v / metersPerKm),
    "kilometres" -> lengthEntry(v => v * metersPerKm, v => v / metersPerKm),
    "cm"         -> lengthEntry(v => v * metersPerCm, v => v / metersPerCm),
    "centimeter" -> lengthEntry(v => v * metersPerCm, v => v / metersPerCm),
    "centimetre" -> lengthEntry(v => v * metersPerCm, v => v / metersPerCm),
    "mm"         -> lengthEntry(v => v * metersPerMm, v => v / metersPerMm),
    "millimeter" -> lengthEntry(v => v * metersPerMm, v => v / metersPerMm),
    "millimetre" -> lengthEntry(v => v * metersPerMm, v => v / metersPerMm),
    "mi"         -> lengthEntry(v => v * metersPerMi, v => v / metersPerMi),
    "mile"       -> lengthEntry(v => v * metersPerMi, v => v / metersPerMi),
    "miles"      -> lengthEntry(v => v * metersPerMi, v => v / metersPerMi),
    "ft"         -> lengthEntry(v => v * metersPerFt, v => v / metersPerFt),
    "foot"       -> lengthEntry(v => v * metersPerFt, v => v / metersPerFt),
    "feet"       -> lengthEntry(v => v * metersPerFt, v => v / metersPerFt),
    "in"         -> lengthEntry(v => v * metersPerIn, v => v / metersPerIn),
    "inch"       -> lengthEntry(v => v * metersPerIn, v => v / metersPerIn),
    "inches"     -> lengthEntry(v => v * metersPerIn, v => v / metersPerIn),
    "yd"         -> lengthEntry(v => v * metersPerYd, v => v / metersPerYd),
    "yard"       -> lengthEntry(v => v * metersPerYd, v => v / metersPerYd),
    "yards"      -> lengthEntry(v => v * metersPerYd, v => v / metersPerYd),
    // ── Mass ────────────────────────────────────────────────────────────
    "kg"         -> massEntry(v => v * gramsPerKg, v => v / gramsPerKg),
    "kilogram"   -> massEntry(v => v * gramsPerKg, v => v / gramsPerKg),
    "kilograms"  -> massEntry(v => v * gramsPerKg, v => v / gramsPerKg),
    "g"          -> massEntry(v => v, v => v),
    "gram"       -> massEntry(v => v, v => v),
    "grams"      -> massEntry(v => v, v => v),
    "mg"         -> massEntry(v => v * gramsPerMg, v => v / gramsPerMg),
    "milligram"  -> massEntry(v => v * gramsPerMg, v => v / gramsPerMg),
    "milligrams" -> massEntry(v => v * gramsPerMg, v => v / gramsPerMg),
    "lb"         -> massEntry(v => v * gramsPerLb, v => v / gramsPerLb),
    "pound"      -> massEntry(v => v * gramsPerLb, v => v / gramsPerLb),
    "pounds"     -> massEntry(v => v * gramsPerLb, v => v / gramsPerLb),
    "lbs"        -> massEntry(v => v * gramsPerLb, v => v / gramsPerLb),
    "oz"         -> massEntry(v => v * gramsPerOz, v => v / gramsPerOz),
    "ounce"      -> massEntry(v => v * gramsPerOz, v => v / gramsPerOz),
    "ounces"     -> massEntry(v => v * gramsPerOz, v => v / gramsPerOz),
    "t"          -> massEntry(v => v * gramsPerTonne, v => v / gramsPerTonne),
    "tonne"      -> massEntry(v => v * gramsPerTonne, v => v / gramsPerTonne),
    "tonnes"     -> massEntry(v => v * gramsPerTonne, v => v / gramsPerTonne),
    // ── Volume ──────────────────────────────────────────────────────────
    "l"           -> volumeEntry(v => v * mlPerLitre, v => v / mlPerLitre),
    "liter"       -> volumeEntry(v => v * mlPerLitre, v => v / mlPerLitre),
    "litre"       -> volumeEntry(v => v * mlPerLitre, v => v / mlPerLitre),
    "liters"      -> volumeEntry(v => v * mlPerLitre, v => v / mlPerLitre),
    "litres"      -> volumeEntry(v => v * mlPerLitre, v => v / mlPerLitre),
    "ml"          -> volumeEntry(v => v, v => v),
    "milliliter"  -> volumeEntry(v => v, v => v),
    "millilitre"  -> volumeEntry(v => v, v => v),
    "milliliters" -> volumeEntry(v => v, v => v),
    "millilitres" -> volumeEntry(v => v, v => v),
    "gal"         -> volumeEntry(v => v * mlPerGallon, v => v / mlPerGallon),
    "gallon"      -> volumeEntry(v => v * mlPerGallon, v => v / mlPerGallon),
    "gallons"     -> volumeEntry(v => v * mlPerGallon, v => v / mlPerGallon),
    "qt"          -> volumeEntry(v => v * mlPerQuart, v => v / mlPerQuart),
    "quart"       -> volumeEntry(v => v * mlPerQuart, v => v / mlPerQuart),
    "quarts"      -> volumeEntry(v => v * mlPerQuart, v => v / mlPerQuart),
    "pt"          -> volumeEntry(v => v * mlPerPint, v => v / mlPerPint),
    "pint"        -> volumeEntry(v => v * mlPerPint, v => v / mlPerPint),
    "pints"       -> volumeEntry(v => v * mlPerPint, v => v / mlPerPint),
    // ── Velocity ────────────────────────────────────────────────────────
    "m/s"   -> velocityEntry(v => v, v => v),
    "mps"   -> velocityEntry(v => v, v => v),
    "km/h"  -> velocityEntry(v => v * mpsPerKmh, v => v / mpsPerKmh),
    "kph"   -> velocityEntry(v => v * mpsPerKmh, v => v / mpsPerKmh),
    "kmh"   -> velocityEntry(v => v * mpsPerKmh, v => v / mpsPerKmh),
    "mph"   -> velocityEntry(v => v * mpsPerMph, v => v / mpsPerMph),
    "kn"    -> velocityEntry(v => v * mpsPerKnot, v => v / mpsPerKnot),
    "knot"  -> velocityEntry(v => v * mpsPerKnot, v => v / mpsPerKnot),
    "knots" -> velocityEntry(v => v * mpsPerKnot, v => v / mpsPerKnot),
    // ── Area ────────────────────────────────────────────────────────────
    "m2"               -> areaEntry(v => v, v => v),
    "squaremeter"      -> areaEntry(v => v, v => v),
    "squaremeters"     -> areaEntry(v => v, v => v),
    "km2"              -> areaEntry(v => v * sqmPerKm2, v => v / sqmPerKm2),
    "squarekilometer"  -> areaEntry(v => v * sqmPerKm2, v => v / sqmPerKm2),
    "squarekilometers" -> areaEntry(v => v * sqmPerKm2, v => v / sqmPerKm2),
    "ft2"              -> areaEntry(v => v * sqmPerFt2, v => v / sqmPerFt2),
    "squarefoot"       -> areaEntry(v => v * sqmPerFt2, v => v / sqmPerFt2),
    "squarefeet"       -> areaEntry(v => v * sqmPerFt2, v => v / sqmPerFt2),
    "acre"             -> areaEntry(v => v * sqmPerAcre, v => v / sqmPerAcre),
    "acres"            -> areaEntry(v => v * sqmPerAcre, v => v / sqmPerAcre),
    "ha"               -> areaEntry(v => v * sqmPerHa, v => v / sqmPerHa),
    "hectare"          -> areaEntry(v => v * sqmPerHa, v => v / sqmPerHa),
    "hectares"         -> areaEntry(v => v * sqmPerHa, v => v / sqmPerHa),
    // ── Energy ──────────────────────────────────────────────────────────
    "j"            -> energyEntry(v => v, v => v),
    "joule"        -> energyEntry(v => v, v => v),
    "joules"       -> energyEntry(v => v, v => v),
    "kj"           -> energyEntry(v => v * joulesPerKj, v => v / joulesPerKj),
    "kilojoule"    -> energyEntry(v => v * joulesPerKj, v => v / joulesPerKj),
    "kilojoules"   -> energyEntry(v => v * joulesPerKj, v => v / joulesPerKj),
    "kwh"          -> energyEntry(v => v * joulesPerKwh, v => v / joulesPerKwh),
    "kilowatthour" -> energyEntry(v => v * joulesPerKwh, v => v / joulesPerKwh),
    "cal"          -> energyEntry(v => v * joulesPerCal, v => v / joulesPerCal),
    "calorie"      -> energyEntry(v => v * joulesPerCal, v => v / joulesPerCal),
    "calories"     -> energyEntry(v => v * joulesPerCal, v => v / joulesPerCal),
    "kcal"         -> energyEntry(v => v * joulesPerKcal, v => v / joulesPerKcal),
    "kilocalorie"  -> energyEntry(v => v * joulesPerKcal, v => v / joulesPerKcal),
    "kilocalories" -> energyEntry(v => v * joulesPerKcal, v => v / joulesPerKcal),
    // ── Power ───────────────────────────────────────────────────────────
    "w"          -> powerEntry(v => v, v => v),
    "watt"       -> powerEntry(v => v, v => v),
    "watts"      -> powerEntry(v => v, v => v),
    "kw"         -> powerEntry(v => v * wattsPerKw, v => v / wattsPerKw),
    "kilowatt"   -> powerEntry(v => v * wattsPerKw, v => v / wattsPerKw),
    "kilowatts"  -> powerEntry(v => v * wattsPerKw, v => v / wattsPerKw),
    "mw"         -> powerEntry(v => v * wattsPerMw, v => v / wattsPerMw),
    "megawatt"   -> powerEntry(v => v * wattsPerMw, v => v / wattsPerMw),
    "megawatts"  -> powerEntry(v => v * wattsPerMw, v => v / wattsPerMw),
    "hp"         -> powerEntry(v => v * wattsPerHp, v => v / wattsPerHp),
    "horsepower" -> powerEntry(v => v * wattsPerHp, v => v / wattsPerHp),
    // ── Time ────────────────────────────────────────────────────────────
    "s"       -> timeEntry(v => v, v => v),
    "sec"     -> timeEntry(v => v, v => v),
    "second"  -> timeEntry(v => v, v => v),
    "seconds" -> timeEntry(v => v, v => v),
    "min"     -> timeEntry(v => v * secPerMin, v => v / secPerMin),
    "minute"  -> timeEntry(v => v * secPerMin, v => v / secPerMin),
    "minutes" -> timeEntry(v => v * secPerMin, v => v / secPerMin),
    "h"       -> timeEntry(v => v * secPerHour, v => v / secPerHour),
    "hr"      -> timeEntry(v => v * secPerHour, v => v / secPerHour),
    "hour"    -> timeEntry(v => v * secPerHour, v => v / secPerHour),
    "hours"   -> timeEntry(v => v * secPerHour, v => v / secPerHour),
    "d"       -> timeEntry(v => v * secPerDay, v => v / secPerDay),
    "day"     -> timeEntry(v => v * secPerDay, v => v / secPerDay),
    "days"    -> timeEntry(v => v * secPerDay, v => v / secPerDay),
    // ── Digital Information ─────────────────────────────────────────────
    "byte"      -> infoEntry(v => v, v => v),
    "bytes"     -> infoEntry(v => v, v => v),
    "kb"        -> infoEntry(v => v * bytesPerKb, v => v / bytesPerKb),
    "kilobyte"  -> infoEntry(v => v * bytesPerKb, v => v / bytesPerKb),
    "kilobytes" -> infoEntry(v => v * bytesPerKb, v => v / bytesPerKb),
    "mb"        -> infoEntry(v => v * bytesPerMb, v => v / bytesPerMb),
    "megabyte"  -> infoEntry(v => v * bytesPerMb, v => v / bytesPerMb),
    "megabytes" -> infoEntry(v => v * bytesPerMb, v => v / bytesPerMb),
    "gb"        -> infoEntry(v => v * bytesPerGb, v => v / bytesPerGb),
    "gigabyte"  -> infoEntry(v => v * bytesPerGb, v => v / bytesPerGb),
    "gigabytes" -> infoEntry(v => v * bytesPerGb, v => v / bytesPerGb),
    "tb"        -> infoEntry(v => v * bytesPerTb, v => v / bytesPerTb),
    "terabyte"  -> infoEntry(v => v * bytesPerTb, v => v / bytesPerTb),
    "terabytes" -> infoEntry(v => v * bytesPerTb, v => v / bytesPerTb),
  )

  /** Map of lowercase alias → TemperatureScale for temperature conversions. */
  private val tempMap: Map[String, TemperatureScale] = Map(
    "c"          -> Celsius,
    "celsius"    -> Celsius,
    "f"          -> Fahrenheit,
    "fahrenheit" -> Fahrenheit,
    "k"          -> Kelvin,
    "kelvin"     -> Kelvin,
  )

  private def normalizeKey(s: String): String =
    s.toLowerCase.trim.replaceAll("\\s+", "").replaceAll("[²]", "2")

  private def convertUnits(
    value:       Double,
    fromUnitStr: String,
    toUnitStr:   String,
  ): Either[String, Double] = {
    val fromKey = normalizeKey(fromUnitStr)
    val toKey = normalizeKey(toUnitStr)

    val fromTemp = tempMap.get(fromKey)
    val toTemp = tempMap.get(toKey)

    (fromTemp, toTemp) match {
      case (Some(fromScale), Some(toScale)) =>
        Right(Temperature(value, fromScale).toScale(toScale))
      case (Some(_), None) =>
        Left(s"Incompatible units: '$fromUnitStr' is a temperature unit but '$toUnitStr' is not")
      case (None, Some(_)) =>
        Left(s"Incompatible units: '$toUnitStr' is a temperature unit but '$fromUnitStr' is not")
      case (None, None) =>
        unitMap.get(fromKey) match {
          case None            => Left(s"Unknown unit: '$fromUnitStr'")
          case Some(fromEntry) =>
            unitMap.get(toKey) match {
              case None          => Left(s"Unknown unit: '$toUnitStr'")
              case Some(toEntry) =>
                if (fromEntry.dimension != toEntry.dimension) {
                  Left(
                    s"Incompatible units: '$fromUnitStr' (${fromEntry.dimension}) and '$toUnitStr' (${toEntry.dimension}) are different dimensions",
                  )
                } else {
                  val baseValue = fromEntry.toBase(value)
                  Right(toEntry.fromBase(baseValue))
                }
            }
        }
    }
  }

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "units",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "units.convert",
        description = "Convert a numeric value from one unit of measure to another. Supports length, mass, temperature, volume, speed, area, energy, power, time, and digital storage units.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"value":{"type":"number","description":"The numeric value to convert"},"fromUnit":{"type":"string","description":"The source unit (e.g. 'km', 'C', 'kg', 'mph')"},"toUnit":{"type":"string","description":"The target unit (e.g. 'm', 'F', 'lb', 'km/h')"}},"required":["value","fromUnit","toUnit"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List.empty,
        examplePrompts = List(
          "Convert 100 kilometers to miles",
          "What is 37 degrees Celsius in Fahrenheit?",
          "How many pounds is 5 kilograms?",
          "Convert 1 hour to seconds",
          "How many megabytes is 2 gigabytes?",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    tool match {
      case "units.convert" =>
        args match {
          case Json.Obj(fields) =>
            val valueOpt = fields.collectFirst { case ("value", Json.Num(n)) => n.doubleValue() }
            val fromUnitOpt = fields.collectFirst { case ("fromUnit", Json.Str(s)) => s }
            val toUnitOpt = fields.collectFirst { case ("toUnit", Json.Str(s)) => s }

            (valueOpt, fromUnitOpt, toUnitOpt) match {
              case (Some(value), Some(fromUnit), Some(toUnit)) =>
                convertUnits(value, fromUnit, toUnit) match {
                  case Right(result) =>
                    ZIO.succeed(
                      Json.Obj(
                        "result"     -> Json.Num(result: Double),
                        "fromUnit"   -> Json.Str(fromUnit),
                        "toUnit"     -> Json.Str(toUnit),
                        "inputValue" -> Json.Num(value: Double),
                      ),
                    )
                  case Left(error) =>
                    ZIO.fail(ValidationError(error))
                }

              case (None, _, _) =>
                ZIO.fail(ValidationError("missing field 'value'"))
              case (_, None, _) =>
                ZIO.fail(ValidationError("missing field 'fromUnit'"))
              case (_, _, None) =>
                ZIO.fail(ValidationError("missing field 'toUnit'"))
            }

          case _ =>
            ZIO.fail(ValidationError("args must be a JSON object"))
        }

      case other =>
        ZIO.fail(ValidationError(s"unknown tool '$other'"))
    }
  }

}

object UnitConversionSkill
