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

import java.time.{LocalDateTime, Period, ZoneId, ZonedDateTime, Duration as JDuration}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*
import scala.language.{postfixOps, unsafeNulls}

/** Tier-0 time/timezone skill — date, time, timezone conversion, duration arithmetic, and datetime differencing via
  * java.time.
  *
  * No external dependencies, no API key, no server_settings entry. Registers unconditionally. All four tools require
  * the `time.read` capability.
  */
class TimeSkill(config: TimeConfig = TimeConfig()) extends Skill with HasDashboardData with HasValidation {

  private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  // Cities that are NOT the representative city of their IANA timezone zone.
  // The IANA index covers cities that ARE (e.g. "los angeles" → America/Los_Angeles).
  // This map covers common cities that aren't, like Las Vegas (no America/Las_Vegas zone).
  private val cityToTimezone: Map[String, String] = Map(
    // US — Eastern (America/New_York)
    "jacksonville"   -> "America/New_York",
    "charlotte"      -> "America/New_York",
    "columbus"       -> "America/New_York",
    "baltimore"      -> "America/New_York",
    "washington"     -> "America/New_York",
    "washington dc"  -> "America/New_York",
    "boston"         -> "America/New_York",
    "philadelphia"   -> "America/New_York",
    "miami"          -> "America/New_York",
    "tampa"          -> "America/New_York",
    "atlanta"        -> "America/New_York",
    "raleigh"        -> "America/New_York",
    "virginia beach" -> "America/New_York",
    "richmond"       -> "America/New_York",
    "pittsburgh"     -> "America/New_York",
    "cleveland"      -> "America/New_York",
    "cincinnati"     -> "America/New_York",
    // US — Central (America/Chicago)
    "houston"       -> "America/Chicago",
    "san antonio"   -> "America/Chicago",
    "dallas"        -> "America/Chicago",
    "austin"        -> "America/Chicago",
    "nashville"     -> "America/Chicago",
    "kansas city"   -> "America/Chicago",
    "new orleans"   -> "America/Chicago",
    "tulsa"         -> "America/Chicago",
    "wichita"       -> "America/Chicago",
    "omaha"         -> "America/Chicago",
    "memphis"       -> "America/Chicago",
    "milwaukee"     -> "America/Chicago",
    "oklahoma city" -> "America/Chicago",
    "minneapolis"   -> "America/Chicago",
    "st. louis"     -> "America/Chicago",
    "saint louis"   -> "America/Chicago",
    "st louis"      -> "America/Chicago",
    "fort worth"    -> "America/Chicago",
    // US — Mountain (America/Denver)
    "albuquerque"      -> "America/Denver",
    "colorado springs" -> "America/Denver",
    "el paso"          -> "America/Denver",
    // US — Arizona (America/Phoenix — no DST)
    "tucson"     -> "America/Phoenix",
    "mesa"       -> "America/Phoenix",
    "scottsdale" -> "America/Phoenix",
    "tempe"      -> "America/Phoenix",
    "chandler"   -> "America/Phoenix",
    // US — Pacific (America/Los_Angeles)
    "las vegas"     -> "America/Los_Angeles",
    "henderson"     -> "America/Los_Angeles",
    "reno"          -> "America/Los_Angeles",
    "san diego"     -> "America/Los_Angeles",
    "san jose"      -> "America/Los_Angeles",
    "san francisco" -> "America/Los_Angeles",
    "fresno"        -> "America/Los_Angeles",
    "sacramento"    -> "America/Los_Angeles",
    "long beach"    -> "America/Los_Angeles",
    "oakland"       -> "America/Los_Angeles",
    "bakersfield"   -> "America/Los_Angeles",
    "anaheim"       -> "America/Los_Angeles",
    "portland"      -> "America/Los_Angeles",
    "seattle"       -> "America/Los_Angeles",
    "spokane"       -> "America/Los_Angeles",
    // Canada
    "toronto"   -> "America/Toronto",
    "ottawa"    -> "America/Toronto",
    "montreal"  -> "America/Toronto",
    "vancouver" -> "America/Vancouver",
    "calgary"   -> "America/Edmonton",
    "edmonton"  -> "America/Edmonton",
    "winnipeg"  -> "America/Winnipeg",
    // India — all use Asia/Kolkata
    "new delhi" -> "Asia/Kolkata",
    "delhi"     -> "Asia/Kolkata",
    "mumbai"    -> "Asia/Kolkata",
    "bangalore" -> "Asia/Kolkata",
    "hyderabad" -> "Asia/Kolkata",
    "chennai"   -> "Asia/Kolkata",
    "pune"      -> "Asia/Kolkata",
    "ahmedabad" -> "Asia/Kolkata",
    "surat"     -> "Asia/Kolkata",
    "jaipur"    -> "Asia/Kolkata",
    // Pakistan
    "islamabad" -> "Asia/Karachi",
    "lahore"    -> "Asia/Karachi",
    // UAE
    "abu dhabi" -> "Asia/Dubai",
    // Malaysia
    "kuala lumpur" -> "Asia/Kuala_Lumpur",
    // China — all use Asia/Shanghai
    "beijing"   -> "Asia/Shanghai",
    "guangzhou" -> "Asia/Shanghai",
    "shenzhen"  -> "Asia/Shanghai",
    "chongqing" -> "Asia/Shanghai",
    // Japan — Osaka is the only major city not in IANA directly
    "osaka"   -> "Asia/Tokyo",
    "nagoya"  -> "Asia/Tokyo",
    "sapporo" -> "Asia/Tokyo",
    // South Korea
    "busan" -> "Asia/Seoul",
    // Australia
    "sydney"    -> "Australia/Sydney",
    "melbourne" -> "Australia/Melbourne",
    "brisbane"  -> "Australia/Brisbane",
    "perth"     -> "Australia/Perth",
    "canberra"  -> "Australia/Sydney",
    // Mexico
    "mexico city" -> "America/Mexico_City",
    "guadalajara" -> "America/Mexico_City",
    "monterrey"   -> "America/Monterrey",
    // South America
    "sao paulo"      -> "America/Sao_Paulo",
    "rio de janeiro" -> "America/Sao_Paulo",
    "brasilia"       -> "America/Sao_Paulo",
    "buenos aires"   -> "America/Argentina/Buenos_Aires",
    "bogota"         -> "America/Bogota",
    "lima"           -> "America/Lima",
    "santiago"       -> "America/Santiago",
    "caracas"        -> "America/Caracas",
    // Europe — cities not in IANA directly
    "edinburgh" -> "Europe/London",
    "glasgow"   -> "Europe/London",
    "belfast"   -> "Europe/London",
    "cardiff"   -> "Europe/London",
    "milan"     -> "Europe/Rome",
    "bern"      -> "Europe/Zurich",
    "geneva"    -> "Europe/Zurich",
    "barcelona" -> "Europe/Madrid",
    "seville"   -> "Europe/Madrid",
    "munich"    -> "Europe/Berlin",
    "hamburg"   -> "Europe/Berlin",
    "frankfurt" -> "Europe/Berlin",
    "cologne"   -> "Europe/Berlin",
    "marseille" -> "Europe/Paris",
    "lyon"      -> "Europe/Paris",
    "kyiv"      -> "Europe/Kyiv",
    "kiev"      -> "Europe/Kyiv",
    // Africa
    "casablanca" -> "Africa/Casablanca",
    "lagos"      -> "Africa/Lagos",
    "nairobi"    -> "Africa/Nairobi",
    "cairo"      -> "Africa/Cairo",
  )

  // Auto-built index from the full IANA timezone database.
  // Key: city name extracted from zone ID (e.g. "America/Los_Angeles" → "los angeles").
  // Covers all cities that ARE the representative city of their timezone.
  lazy private val ianaIndex: Map[String, List[String]] =
    ZoneId.getAvailableZoneIds.asScala.toList.groupBy { id =>
      id.split('/').last.replace('_', ' ').toLowerCase
    }

  /** Parse a timezone string into a ZoneId, failing with JorlanError if the name is invalid. */
  private def parseZone(tz: String): IO[JorlanError, ZoneId] =
    ZIO
      .attempt(ZoneId.of(tz)).orElseFail {
        JorlanError(
          s"Invalid timezone '$tz'. " +
            "Use time.find_timezone to look up the IANA timezone ID for a city or location, " +
            "or time.list_timezones with a region prefix (e.g. 'America') to browse valid IDs.",
        )
      }

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
      ).orElseFail(JorlanError(s"Cannot parse datetime '$dtStr'; expected ISO 8601 format"))

  /** Parse an ISO 8601 duration string (`PT…` or `P…`), using Period for pure date-durations (no `T`). */
  private def parseDuration(durationStr: String): IO[JorlanError, Either[JDuration, Period]] =
    (if (durationStr.contains("T")) ZIO.attempt(Left(JDuration.parse(durationStr)))
    else
      ZIO
        .attempt(Right(Period.parse(durationStr)))
        // $COVERAGE-OFF$ JDuration.parse requires "PT" prefix so this orElse is never reachable for well-formed non-T durations
        .orElse(ZIO.attempt(Left(JDuration.parse(durationStr))))
      // $COVERAGE-ON$
      ).orElseFail {
      JorlanError(
        s"Cannot parse duration '$durationStr'; expected ISO 8601 duration (e.g. 'PT2H30M' or 'P1D')",
      )
    }

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

  private def timeFindTimezone(args: Json): IO[JorlanError, Json] = {
    val locationOpt = args match {
      case Json.Obj(fields) => fields.collectFirst { case ("location", Json.Str(v)) => v }
      case _                => return ZIO.fail(ValidationError("args must be a JSON object"))
    }
    locationOpt match {
      case None           => ZIO.fail(ValidationError("missing field 'location'"))
      case Some(location) =>
        val normalized = location.trim.toLowerCase

        // 1. Curated map: cities not in the IANA database directly
        val curatedMatches: List[Json] = cityToTimezone
          .get(normalized)
          .map(tz => List[Json](Json.Obj("timezone" -> Json.Str(tz), "source" -> Json.Str("city-lookup"))))
          .getOrElse(Nil)

        // 2. IANA index: cities that ARE the representative city (e.g. "tokyo" → Asia/Tokyo)
        val ianaExactMatches: List[Json] = ianaIndex
          .getOrElse(normalized, Nil)
          .map(tz => Json.Obj("timezone" -> Json.Str(tz), "source" -> Json.Str("iana-exact")))

        // 3. Partial match in IANA index when nothing found above
        val ianaPartialMatches: List[Json] =
          if (curatedMatches.nonEmpty || ianaExactMatches.nonEmpty) Nil
          else
            ianaIndex.keys.toList
              .filter(key => key.contains(normalized) || normalized.contains(key))
              .sorted
              .take(8)
              .flatMap(key =>
                ianaIndex
                  .getOrElse(key, Nil)
                  .map(tz => Json.Obj("timezone" -> Json.Str(tz), "source" -> Json.Str("iana-partial"))),
              )

        def tzKey(j: Json): Option[String] =
          j match {
            case Json.Obj(f) => f.collectFirst { case ("timezone", Json.Str(v)) => v }
            case _           => None
          }

        val allMatches: List[Json] = (curatedMatches ++ ianaExactMatches ++ ianaPartialMatches)
          .distinctBy(tzKey)
          .take(10)

        val found = allMatches.nonEmpty

        val extraFields: List[(String, Json)] =
          if (!found)
            List(
              "suggestion" -> Json.Str(
                "No timezone found for this location. " +
                  "Use time.list_timezones with a region prefix (e.g. 'America', 'Europe', 'Asia', 'Pacific') " +
                  "to browse available IANA timezone IDs.",
              ),
            )
          else Nil

        ZIO.succeed(
          Json.Obj(
            (List(
              "location" -> Json.Str(location),
              "found"    -> Json.Bool(found),
              "matches"  -> Json.Arr(allMatches*),
            ) ++ extraFields)*,
          ),
        )
    }
  }

  private def timeListTimezones(args: Json): IO[JorlanError, Json] = {
    val prefixOpt = args match {
      case Json.Obj(fields) => fields.collectFirst { case ("prefix", Json.Str(v)) => v }
      case _                => None
    }
    val allZones = ZoneId.getAvailableZoneIds.asScala.toList.sorted

    prefixOpt match {
      case None =>
        val regions = allZones.map(_.split('/').head).distinct.sorted
        ZIO.succeed(
          Json.Obj(
            "regions" -> Json.Arr(regions.map(Json.Str(_))*),
            "count"   -> Json.Num(allZones.size.toDouble),
            "hint"    -> Json.Str(
              "Provide a 'prefix' (e.g. 'America', 'Europe', 'Asia') to list all timezone IDs in that region.",
            ),
          ),
        )
      case Some(prefix) =>
        val filtered = allZones.filter(id => id == prefix || id.startsWith(prefix + "/"))
        if (filtered.isEmpty) {
          val regions = allZones.map(_.split('/').head).distinct.sorted
          ZIO.fail(
            JorlanError(
              s"No timezones found with prefix '$prefix'. Available regions: ${regions.mkString(", ")}",
            ),
          )
        } else
          ZIO.succeed(
            Json.Obj(
              "prefix"    -> Json.Str(prefix),
              "timezones" -> Json.Arr(filtered.map(Json.Str(_))*),
              "count"     -> Json.Num(filtered.size.toDouble),
            ),
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
      "find timezone",
      "city timezone",
      "location time",
    ),
    doc = Some(
      """|## Time Skill
         |
         |Provides current time, timezone conversion, duration arithmetic, and datetime differencing using Java's time library.
         |Also includes timezone lookup by city name and validation/enumeration of IANA timezone IDs.
         |
         |### Tools
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `time.now` | Current date/time in a timezone | `time.read` |
         || `time.convert` | Convert datetime between timezones | `time.read` |
         || `time.add_duration` | Add an ISO 8601 duration to a datetime | `time.read` |
         || `time.diff` | Calculate duration between two datetimes | `time.read` |
         || `time.find_timezone` | Look up the IANA timezone ID for a city or location | `time.read` |
         || `time.list_timezones` | List valid IANA timezone IDs by region | `time.read` |
         |
         |### Timezone lookup workflow
         |1. Always use valid IANA timezone IDs (e.g. `America/Los_Angeles`, not `America/Las Vegas`).
         |2. If you don't know the IANA ID for a location, call `time.find_timezone` first.
         |3. If `time.find_timezone` returns no match, call `time.list_timezones` with the appropriate region prefix.
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
        description =
          "Return the current date and time in a given IANA timezone (e.g. 'America/New_York', 'Europe/London', 'UTC'). " +
            "Always use a valid IANA timezone ID — if you only know a city name, call time.find_timezone first. " +
            "Defaults to UTC when timezone is omitted.",
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
        description =
          "Convert an ISO 8601 datetime string from one timezone to another. Accepts datetimes with or without a UTC offset. " +
            "Always use valid IANA timezone IDs — if unsure, call time.find_timezone first.",
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
      ToolDescriptor(
        name = "time.find_timezone",
        description =
          "Look up the IANA timezone ID for a city or location name (e.g. 'Las Vegas', 'Tokyo', 'New York', 'London'). " +
            "Use this before calling time.now or time.convert when you only know a city name, not its IANA ID. " +
            "Returns one or more candidate timezone IDs ranked by confidence.",
        inputSchema = json"""{"type":"object","properties":{"location":{"type":"string","description":"City or place name to look up, e.g. 'Las Vegas', 'New Delhi', 'São Paulo'"}},"required":["location"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("time.read")),
        examplePrompts = List(
          "What timezone is Las Vegas in?",
          "What is the IANA timezone for London?",
          "Find the timezone for New Delhi",
          "What is the correct timezone ID for São Paulo?",
        ),
      ),
      ToolDescriptor(
        name = "time.list_timezones",
        description =
          "List valid IANA timezone IDs. Without a prefix, returns the available region names (e.g. 'America', 'Europe', 'Asia'). " +
            "With a prefix, returns all timezone IDs in that region. " +
            "Use this to validate a timezone or discover the correct ID when time.find_timezone returns no match.",
        inputSchema = json"""{"type":"object","properties":{"prefix":{"type":"string","description":"IANA region prefix to filter by, e.g. 'America', 'Europe', 'Asia', 'Pacific'. Omit to list available regions."}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("time.read")),
        examplePrompts = List(
          "List all timezone IDs in Europe",
          "What are the valid America timezone IDs?",
          "Is 'America/Las_Vegas' a valid timezone?",
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
      case "time.now"            => timeNow(args)
      case "time.convert"        => timeConvert(args)
      case "time.add_duration"   => timeAddDuration(args)
      case "time.diff"           => timeDiff(args)
      case "time.find_timezone"  => timeFindTimezone(args)
      case "time.list_timezones" => timeListTimezones(args)
      case other                 => ZIO.fail(ValidationError(s"unknown tool '$other'"))
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
