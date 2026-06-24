/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.weather

import jorlan.*
import jorlan.connector.{HasDashboardData, InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Built-in skill for fetching weather data via the OpenWeatherMap API.
  *
  * Exposes three tools:
  *   - `weather.current` — current conditions for a named location
  *   - `weather.forecast` — simplified 5-day/3-hour forecast for a named location
  *   - `weather.alerts` — active weather alerts for a lat/lon coordinate
  *
  * All tools require the `weather.read` capability. Config (including the API key) is loaded from `server_settings`
  * under the key `"skill.weather"`.
  */
class WeatherSkill(
  config:  WeatherConfig,
  client:  Client,
  baseUrl: String = "https://api.openweathermap.org/data/2.5",
) extends Skill with HasDashboardData {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "weather",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "weather",
      "forecast",
      "temperature",
      "celsius",
      "farenheit",
      "rain",
      "humidity",
      "wind",
      "climate",
      "conditions",
      "outdoor",
      "sunny",
      "cloudy",
      "storm",
      "precipitation",
      "UV index",
      "feels like",
      "alert",
      "warning",
    ),
    configKey = Some("skill.weather"),
    configJsModule = Some("jorlan-weather"),
    tools = List(
      ToolDescriptor(
        name = "weather.current",
        description = "Fetch current weather conditions for a named location. Returns temperature, feels_like, humidity, description, wind_speed, and visibility.",
        inputSchema = json"""{"type":"object","properties":{"location":{"type":"string","description":"City name, e.g. 'London' or 'New York,US'. Omit to use the configured default location."},"units":{"type":"string","description":"Override units: metric | imperial | standard"}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("weather.read")),
        examplePrompts = List(
          "What is the current weather in London?",
          "How hot is it in New York right now?",
          "What is the humidity in Tokyo?",
        ),
      ),
      ToolDescriptor(
        name = "weather.forecast",
        description = "Fetch a simplified multi-day weather forecast for a named location. Returns a list of forecast entries with date, temp_min, temp_max, and description.",
        inputSchema = json"""{"type":"object","properties":{"location":{"type":"string","description":"City name, e.g. 'Paris'. Omit to use the configured default location."},"days":{"type":"integer","description":"Number of days to forecast (1–5); default 5"},"units":{"type":"string","description":"Override units: metric | imperial | standard"}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("weather.read")),
        examplePrompts = List(
          "What is the weather forecast for Paris this week?",
          "Will it rain in Seattle over the next 3 days?",
          "Give me a 5-day forecast for Sydney",
        ),
      ),
      ToolDescriptor(
        name = "weather.alerts",
        description = "Fetch active weather alerts for a geographic coordinate (latitude/longitude). Returns a list of alerts with event, start, end, and description.",
        inputSchema = json"""{"type":"object","properties":{"lat":{"type":"number","description":"Latitude"},"lon":{"type":"number","description":"Longitude"}},"required":["lat","lon"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("weather.read")),
        examplePrompts = List(
          "Are there any weather alerts near San Francisco?",
          "Check for weather warnings at coordinates 37.77,-122.41",
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
      case "weather.current"  => current(args)
      case "weather.forecast" => forecast(args)
      case "weather.alerts"   => alerts(args)
      case other              => ZIO.fail(ValidationError(s"WeatherSkill: unknown tool '$other'"))
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private def fieldStr(
    args: Json,
    name: String,
  ): IO[JorlanError, String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`name`, Json.Str(v)) => v }
          .fold(ZIO.fail(ValidationError(s"missing required field '$name'")): IO[JorlanError, String])(ZIO.succeed(_))
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  private def fieldStrOpt(
    args: Json,
    name: String,
  ): IO[JorlanError, Option[String]] =
    args match {
      case Json.Obj(fields) =>
        ZIO.succeed(fields.collectFirst { case (`name`, Json.Str(v)) => v })
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  private def fieldIntOpt(
    args: Json,
    name: String,
  ): IO[JorlanError, Option[Int]] =
    args match {
      case Json.Obj(fields) =>
        fields.collectFirst { case (`name`, v) => v } match {
          case None              => ZIO.none
          case Some(Json.Num(n)) =>
            ZIO
              .attempt(n.intValueExact()).mapBoth(_ => ValidationError(s"field '$name' must be an integer"), Some(_))
          case Some(_) =>
            ZIO.fail(ValidationError(s"field '$name' must be an integer"))
        }
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  private def fieldNum(
    args: Json,
    name: String,
  ): IO[JorlanError, Double] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`name`, Json.Num(v)) => v.doubleValue() }
          .fold(ZIO.fail(ValidationError(s"missing required field '$name'")): IO[JorlanError, Double])(ZIO.succeed(_))
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  private def fetchJson(url: String): IO[JorlanError, Json] =
    client
      .batched(Request.get(url))
      .mapError(e => JorlanError("HTTP error", Some(e)))
      .flatMap { resp =>
        resp.body.asString
          .mapError(e => JorlanError("Failed to read weather response body", Some(e)))
          .flatMap { body =>
            if (!resp.status.isSuccess)
              ZIO.fail(JorlanError(s"Weather API HTTP ${resp.status.code}: $body"))
            else
              ZIO
                .fromEither(Json.decoder.decodeJson(body))
                .mapError(err => JorlanError(s"Failed to parse weather response: $err"))
          }
      }

  // ── tools ────────────────────────────────────────────────────────────────────

  private def current(args: Json): IO[JorlanError, Json] =
    for {
      locationOpt <- fieldStrOpt(args, "location")
      location = normalizeLocation(locationOpt.getOrElse(config.defaultLocation))
      unitsOpt <- fieldStrOpt(args, "units")
      units = unitsOpt.getOrElse(config.units)
      url =
        s"$baseUrl/weather?q=${encode(location)}&appid=${config.apiKey}&units=${encode(units)}"
      _   <- ZIO.logDebug(s"Fetching current weather: $url")
      raw <- fetchJson(url)
    } yield raw match {
      case Json.Obj(fields) =>
        val mainFields: Map[String, Json] = fields
          .collectFirst { case ("main", Json.Obj(m)) =>
            m.toMap
          }.getOrElse(Map.empty)
        val windSpeed: Json = fields
          .collectFirst { case ("wind", Json.Obj(w)) =>
            w.collectFirst { case ("speed", v) => v }.getOrElse(Json.Num(0))
          }.getOrElse(Json.Num(0))
        val description: Json = fields
          .collectFirst { case ("weather", Json.Arr(arr)) =>
            arr
              .collectFirst { case Json.Obj(w) =>
                w.collectFirst { case ("description", v) => v }.getOrElse(Json.Str(""))
              }.getOrElse(Json.Str(""))
          }.getOrElse(Json.Str(""))
        val visibility: Json = fields.collectFirst { case ("visibility", v) => v }.getOrElse(Json.Num(0))
        Json.Obj(
          "temperature" -> mainFields.getOrElse("temp", Json.Num(0)),
          "feels_like"  -> mainFields.getOrElse("feels_like", Json.Num(0)),
          "humidity"    -> mainFields.getOrElse("humidity", Json.Num(0)),
          "description" -> description,
          "wind_speed"  -> windSpeed,
          "visibility"  -> visibility,
        )
      case other => other
    }

  private def forecast(args: Json): IO[JorlanError, Json] =
    for {
      locationOpt <- fieldStrOpt(args, "location")
      location = normalizeLocation(locationOpt.getOrElse(config.defaultLocation))
      daysOpt  <- fieldIntOpt(args, "days")
      unitsOpt <- fieldStrOpt(args, "units")
      days = daysOpt.getOrElse(5).max(1).min(5)
      units = unitsOpt.getOrElse(config.units)
      cnt = days * 8
      url =
        s"$baseUrl/forecast?q=${encode(location)}&appid=${config.apiKey}&units=${encode(units)}&cnt=$cnt"
      raw <- fetchJson(url)
    } yield raw match {
      case Json.Obj(fields) =>
        val items = fields.collectFirst { case ("list", Json.Arr(arr)) => arr }.getOrElse(Chunk.empty)
        Json.Arr(
          items.collect { case Json.Obj(item) =>
            val dtTxt:      Json = item.collectFirst { case ("dt_txt", v) => v }.getOrElse(Json.Str(""))
            val mainFields: Map[String, Json] = item
              .collectFirst { case ("main", Json.Obj(m)) =>
                m.toMap
              }.getOrElse(Map.empty)
            val descr: Json = item
              .collectFirst { case ("weather", Json.Arr(arr)) =>
                arr
                  .collectFirst { case Json.Obj(w) =>
                    w.collectFirst { case ("description", v) => v }.getOrElse(Json.Str(""))
                  }.getOrElse(Json.Str(""))
              }.getOrElse(Json.Str(""))
            Json.Obj(
              "date"        -> dtTxt,
              "temp_min"    -> mainFields.getOrElse("temp_min", Json.Num(0)),
              "temp_max"    -> mainFields.getOrElse("temp_max", Json.Num(0)),
              "description" -> descr,
            )
          }*,
        )
      case other => other
    }

  private def alerts(args: Json): IO[JorlanError, Json] =
    for {
      lat <- fieldNum(args, "lat")
      lon <- fieldNum(args, "lon")
      url =
        s"$baseUrl/onecall?lat=$lat&lon=$lon&exclude=current,minutely,hourly,daily&appid=${config.apiKey}"
      raw <- fetchJson(url)
    } yield raw match {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("alerts", Json.Arr(arr)) => arr } match {
          case None     => Json.Arr()
          case Some(as) =>
            Json.Arr(
              as.collect { case Json.Obj(a) =>
                Json.Obj(
                  "event"       -> a.collectFirst { case ("event", v) => v }.getOrElse(Json.Str("")),
                  "start"       -> a.collectFirst { case ("start", v) => v }.getOrElse(Json.Num(0)),
                  "end"         -> a.collectFirst { case ("end", v) => v }.getOrElse(Json.Num(0)),
                  "description" -> a.collectFirst { case ("description", v) => v }.getOrElse(Json.Str("")),
                )
              }*,
            )
        }
      case other => other
    }

  override def dashboardData(ctx: InvocationContext): IO[JorlanError, Json] = {
    if (config.apiKey.isBlank)
      ZIO.succeed(Json.Obj("error" -> Json.Str("No API key configured")))
    else {
      val location = normalizeLocation(config.defaultLocation)
      val url = s"$baseUrl/weather?q=${encode(location)}&appid=${config.apiKey}&units=${encode(config.units)}"
      fetchJson(url)
        .map {
          case Json.Obj(fields) =>
            val mainFields = fields.collectFirst { case ("main", Json.Obj(m)) => m.toMap }.getOrElse(Map.empty)
            val windSpeed = fields
              .collectFirst { case ("wind", Json.Obj(w)) =>
                w.collectFirst { case ("speed", v) => v }.getOrElse(Json.Num(0))
              }.getOrElse(Json.Num(0))
            val description = fields
              .collectFirst { case ("weather", Json.Arr(arr)) =>
                arr
                  .collectFirst { case Json.Obj(w) =>
                    w.collectFirst { case ("description", v) => v }.getOrElse(Json.Str(""))
                  }.getOrElse(Json.Str(""))
              }.getOrElse(Json.Str(""))
            Json.Obj(
              "location"    -> Json.Str(location),
              "temperature" -> mainFields.getOrElse("temp", Json.Num(0)),
              "feelsLike"   -> mainFields.getOrElse("feels_like", Json.Num(0)),
              "humidity"    -> mainFields.getOrElse("humidity", Json.Num(0)),
              "description" -> description,
              "windSpeed"   -> windSpeed,
              "units"       -> Json.Str(config.units),
            )
          case other => other
        }
        .catchAll(e => ZIO.succeed(Json.Obj("error" -> Json.Str(e.msg))))
    }
  }

  private def encode(s: String): String =
    java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)

  private def normalizeLocation(loc: String): String =
    loc.split(",").map(_.trim).filter(_.nonEmpty).mkString(",")

}

object WeatherSkill {}
