/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.time

import jorlan.*
import jorlan.connector.{HasDashboardData, HasValidation, InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.json.ast.Json
import zio.json.literal.*

import java.time.{Duration as JDuration, LocalDateTime, Period, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.language.unsafeNulls

/** Tier-0 time/timezone skill — date, time, timezone conversion, duration arithmetic, and datetime differencing via
  * java.time.
  *
  * No external dependencies, no API key, no server_settings entry. Registers unconditionally. All four tools require
  * the `time.read` capability.
  */
class TimeSkill(config: TimeConfig = TimeConfig()) extends Skill with HasDashboardData with HasValidation {

  private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  /** Parse a timezone string into a ZoneId, failing with JorlanError if the name is invalid. */
  private def parseZone(tz: String): IO[JorlanError, ZoneId] =
    ZIO
      .attempt(ZoneId.of(tz))
      .mapError(e => JorlanError(s"Invalid timezone '$tz': ${e.getMessage}"))

  /** Parse an ISO 8601 datetime string into a ZonedDateTime using the given zone for bare LocalDateTime strings (those
    * without an offset).
    */
  private def parseDatetime(
    dtStr: String,
    zone:  ZoneId,
  ): IO[JorlanError, ZonedDateTime] =
    ZIO
      .attempt(ZonedDateTime.parse(dtStr, formatter))
      .orElse(
        ZIO.attempt(LocalDateTime.parse(dtStr).atZone(zone)),
      )
      .mapError(_ => JorlanError(s"Cannot parse datetime '$dtStr'; expected ISO 8601 format"))

  /** Parse an ISO 8601 duration string (`PT…` or `P…`), using Period for pure date-durations (no `T`). */
  private def parseDuration(durationStr: String): IO[JorlanError, Either[JDuration, Period]] =
    (if (durationStr.contains("T")) ZIO.attempt(Left(JDuration.parse(durationStr)))
     else
       ZIO
         .attempt(Right(Period.parse(durationStr)))
         // $COVERAGE-OFF$ JDuration.parse requires "PT" prefix so this orElse is never reachable for well-formed non-T durations
         .orElse(ZIO.attempt(Left(JDuration.parse(durationStr))))
       // $COVERAGE-ON$
    )
      .mapError(_ =>
        JorlanError(
          s"Cannot parse duration '$durationStr'; expected ISO 8601 duration (e.g. 'PT2H30M' or 'P1D')",
        ),
      )

  // ─────────────────────────────────────────────────────────────────────────
  // Tool implementations
  // ─────────────────────────────────────────────────────────────────────────

  private def timeNow(args: Json): IO[JorlanError, Json] =
    args match {
      case Json.Obj(fields) =>
        val tzName = fields.collectFirst { case ("timezone", Json.Str(v)) => v }.getOrElse(config.defaultTimezone)
        for {
          zone    <- parseZone(tzName)
          instant <- Clock.instant
          zdt = instant.atZone(zone)
        } yield Json.Obj(
          "datetime"  -> Json.Str(zdt.format(formatter)),
          "timezone"  -> Json.Str(tzName),
          "utcOffset" -> Json.Str(zdt.getOffset.getId),
          "dayOfWeek" -> Json.Str(
            zdt.getDayOfWeek.toString.toLowerCase.capitalize,
          ),
          "timestamp" -> Json.Num(instant.getEpochSecond.toDouble),
        )
      case _ =>
        ZIO.fail(ValidationError("args must be a JSON object"))
    }

  private def timeConvert(args: Json): IO[JorlanError, Json] = {
    args match {
      case Json.Obj(fields) =>
        val dtStrOpt = fields.collectFirst { case ("datetime", Json.Str(v)) => v }
        val fromTzOpt = fields.collectFirst { case ("fromTimezone", Json.Str(v)) => v }
        val toTzOpt = fields.collectFirst { case ("toTimezone", Json.Str(v)) => v }
        (dtStrOpt, fromTzOpt, toTzOpt) match {
          case (None, _, _) => ZIO.fail(ValidationError("missing field 'datetime'"))
          case (_, None, _) => ZIO.fail(ValidationError("missing field 'fromTimezone'"))
          case (_, _, None) => ZIO.fail(ValidationError("missing field 'toTimezone'"))
          case (Some(dtStr), Some(fromTzStr), Some(toTzStr)) =>
            for {
              fromZone <- parseZone(fromTzStr)
              toZone   <- parseZone(toTzStr)
              fromZdt  <- parseDatetime(dtStr, fromZone)
              converted = fromZdt.withZoneSameInstant(toZone)
            } yield Json.Obj(
              "original"     -> Json.Str(fromZdt.format(formatter)),
              "converted"    -> Json.Str(converted.format(formatter)),
              "fromTimezone" -> Json.Str(fromTzStr),
              "toTimezone"   -> Json.Str(toTzStr),
            )
        }
      case _ =>
        ZIO.fail(ValidationError("args must be a JSON object"))
    }
  }

  private def timeAddDuration(args: Json): IO[JorlanError, Json] = {
    val fields = args match {
      case Json.Obj(f) => f
      case _           => return ZIO.fail(ValidationError("args must be a JSON object"))
    }
    val dtStrOpt = fields.collectFirst { case ("datetime", Json.Str(v)) => v }
    val tzStrOpt = fields.collectFirst { case ("timezone", Json.Str(v)) => v }
    val durationStrOpt = fields.collectFirst { case ("duration", Json.Str(v)) => v }
    (dtStrOpt, durationStrOpt) match {
      case (None, _)                        => ZIO.fail(ValidationError("missing field 'datetime'"))
      case (_, None)                        => ZIO.fail(ValidationError("missing field 'duration'"))
      case (Some(dtStr), Some(durationStr)) =>
        val tzStr = tzStrOpt.getOrElse(config.defaultTimezone)
        for {
          zone      <- parseZone(tzStr)
          zdt       <- parseDatetime(dtStr, zone)
          durationE <- parseDuration(durationStr)
          result = durationE match {
            case Left(jdur)    => zdt.plus(jdur)
            case Right(period) => zdt.plus(period)
          }
        } yield Json.Obj(
          "original" -> Json.Str(zdt.format(formatter)),
          "result"   -> Json.Str(result.format(formatter)),
          "timezone" -> Json.Str(tzStr),
          "duration" -> Json.Str(durationStr),
        )
    }
  }

  private def timeDiff(args: Json): IO[JorlanError, Json] = {
    val fields = args match {
      case Json.Obj(f) => f
      case _           => return ZIO.fail(ValidationError("args must be a JSON object"))
    }
    val fromStrOpt = fields.collectFirst { case ("from", Json.Str(v)) => v }
    val toStrOpt = fields.collectFirst { case ("to", Json.Str(v)) => v }
    val fromTzStr = fields.collectFirst { case ("fromTimezone", Json.Str(v)) => v }.getOrElse(config.defaultTimezone)
    val toTzStr = fields.collectFirst { case ("toTimezone", Json.Str(v)) => v }.getOrElse(config.defaultTimezone)
    (fromStrOpt, toStrOpt) match {
      case (None, _)                    => ZIO.fail(ValidationError("missing field 'from'"))
      case (_, None)                    => ZIO.fail(ValidationError("missing field 'to'"))
      case (Some(fromStr), Some(toStr)) =>
        for {
          fromZone <- parseZone(fromTzStr)
          toZone   <- parseZone(toTzStr)
          fromZdt  <- parseDatetime(fromStr, fromZone)
          toZdt    <- parseDatetime(toStr, toZone)
          dur = JDuration.between(fromZdt.toInstant, toZdt.toInstant)
          totalSecs = dur.getSeconds
          absSecs = math.abs(totalSecs)
          days = absSecs / 86400
          remaining = absSecs % 86400
          hours = remaining / 3600
          minutes = (remaining % 3600) / 60
          seconds = remaining  % 60
          humanParts = List(
            if (days > 0) Some(s"$days day${if (days == 1) "" else "s"}") else None,
            if (hours > 0) Some(s"$hours hour${if (hours == 1) "" else "s"}") else None,
            if (minutes > 0) Some(s"$minutes minute${if (minutes == 1) "" else "s"}") else None,
            if (seconds > 0 || absSecs == 0) Some(s"$seconds second${if (seconds == 1) "" else "s"}") else None,
          ).flatten
          humanReadable = (if (totalSecs < 0) "negative " else "") + humanParts.mkString(" ")
        } yield Json.Obj(
          "from"          -> Json.Str(fromZdt.format(formatter)),
          "to"            -> Json.Str(toZdt.format(formatter)),
          "totalSeconds"  -> Json.Num(totalSecs.toDouble),
          "days"          -> Json.Num(days.toDouble),
          "hours"         -> Json.Num(hours.toDouble),
          "minutes"       -> Json.Num(minutes.toDouble),
          "seconds"       -> Json.Num(seconds.toDouble),
          "humanReadable" -> Json.Str(humanReadable),
        )
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Descriptor
  // ─────────────────────────────────────────────────────────────────────────

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "time",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    configKey = Some("skill.time"),
    configJsModule = Some("jorlan-time"),
    keywords = List(
      "time",
      "clock",
      "timezone",
      "datetime",
      "date",
      "when",
      "hours",
      "minutes",
      "seconds",
      "schedule",
      "UTC",
      "local time",
      "current time",
      "convert timezone",
      "duration",
      "elapsed",
      "difference",
      "daylight saving",
    ),
    doc = Some(
      """|## Time Skill
         |
         |Provides current time, timezone conversion, duration arithmetic, and datetime differencing using Java's time library.
         |
         |### Tools
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `time.now` | Current date/time in a timezone | `time.read` |
         || `time.convert` | Convert datetime between timezones | `time.read` |
         || `time.add_duration` | Add an ISO 8601 duration to a datetime | `time.read` |
         || `time.diff` | Calculate duration between two datetimes | `time.read` |
         |
         |### Configuration
         |Optionally configure a default timezone via `skill.time` in Server Settings:
         |- `defaultTimezone`: IANA timezone name (default `UTC`)
         |
         |Grant the `time.read` capability to agents.""".stripMargin,
    ),
    tools = List(
      ToolDescriptor(
        name = "time.now",
        description = "Return the current date and time in a given IANA timezone (e.g. 'America/New_York', 'Europe/London', 'UTC'). Defaults to UTC when timezone is omitted.",
        inputSchema = json"""{"type":"object","properties":{"timezone":{"type":"string","description":"IANA timezone name, e.g. 'America/New_York'. Defaults to UTC."}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("time.read")),
        examplePrompts = List(
          "What time is it?",
          "What time is it in Tokyo?",
          "What's the current time in New York?",
          "What day of the week is it?",
        ),
      ),
      ToolDescriptor(
        name = "time.convert",
        description = "Convert an ISO 8601 datetime string from one timezone to another. Accepts datetimes with or without a UTC offset.",
        inputSchema = json"""{"type":"object","properties":{"datetime":{"type":"string","description":"ISO 8601 datetime string, e.g. '2026-06-16T14:30:00' or '2026-06-16T14:30:00Z'"},"fromTimezone":{"type":"string","description":"IANA source timezone, e.g. 'America/New_York'"},"toTimezone":{"type":"string","description":"IANA target timezone, e.g. 'Asia/Tokyo'"}},"required":["datetime","fromTimezone","toTimezone"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("time.read")),
        examplePrompts = List(
          "Convert 3pm New York time to Tokyo time",
          "What is 09:00 London time in Los Angeles?",
        ),
      ),
      ToolDescriptor(
        name = "time.add_duration",
        description = "Add an ISO 8601 duration (e.g. 'PT2H30M', 'P1D', 'P1Y2M3DT4H5M6S') to a datetime and return the resulting datetime.",
        inputSchema = json"""{"type":"object","properties":{"datetime":{"type":"string","description":"ISO 8601 datetime string"},"timezone":{"type":"string","description":"IANA timezone for interpreting the datetime. Defaults to UTC."},"duration":{"type":"string","description":"ISO 8601 duration, e.g. 'PT2H30M' (2h 30m), 'P1D' (1 day)"}},"required":["datetime","duration"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("time.read")),
        examplePrompts = List(
          "What time is it 2 hours and 30 minutes from now?",
          "Add 1 day to 2026-06-16T14:30:00",
          "What date is 3 months from today?",
        ),
      ),
      ToolDescriptor(
        name = "time.diff",
        description = "Calculate the duration between two ISO 8601 datetimes and return the result in seconds, broken down into days/hours/minutes/seconds with a human-readable summary.",
        inputSchema = json"""{"type":"object","properties":{"from":{"type":"string","description":"ISO 8601 start datetime"},"to":{"type":"string","description":"ISO 8601 end datetime"},"fromTimezone":{"type":"string","description":"IANA timezone for the 'from' datetime. Defaults to UTC."},"toTimezone":{"type":"string","description":"IANA timezone for the 'to' datetime. Defaults to UTC."}},"required":["from","to"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("time.read")),
        examplePrompts = List(
          "How long between 9am and 5pm?",
          "How many hours between two dates?",
          "What is the difference between two timestamps?",
        ),
      ),
    ),
  )

  // ─────────────────────────────────────────────────────────────────────────
  // Dispatch
  // ─────────────────────────────────────────────────────────────────────────

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "time.now"          => timeNow(args)
      case "time.convert"      => timeConvert(args)
      case "time.add_duration" => timeAddDuration(args)
      case "time.diff"         => timeDiff(args)
      case other               => ZIO.fail(ValidationError(s"unknown tool '$other'"))
    }

  override def dashboardData(ctx: InvocationContext): IO[JorlanError, Json] = {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
    val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    for {
      zone <- ZIO
        .attempt(java.time.ZoneId.of(config.defaultTimezone)).mapError(e =>
          JorlanError(s"Invalid timezone: ${e.getMessage}"),
        )
      instant <- Clock.instant
      zdt = instant.atZone(zone)
    } yield Json.Obj(
      "datetime" -> Json.Str(zdt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
      "timezone" -> Json.Str(config.defaultTimezone),
      "date"     -> Json.Str(zdt.format(formatter)),
      "time"     -> Json.Str(zdt.format(timeFormatter)),
    )
  }

  override def validate(): IO[JorlanError, SkillValidationResult] =
    ZIO
      .attempt(java.time.ZoneId.of(config.defaultTimezone))
      .as(SkillValidationResult(ok = true, message = s"Timezone '${config.defaultTimezone}' is valid"))
      .catchAll(e =>
        ZIO.succeed(
          SkillValidationResult(ok = false, message = s"Invalid timezone '${config.defaultTimezone}': ${e.getMessage}"),
        ),
      )

}

object TimeSkill
