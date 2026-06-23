/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.weather

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.weather.WeatherConfig
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object WeatherSkillSpec extends ZIOSpecDefault {

  private val dummyCfg: WeatherConfig = WeatherConfig(apiKey = "test-key", units = "metric")

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  private val sampleCurrentBody: String =
    """|{
       |  "weather": [{ "description": "clear sky" }],
       |  "main": {
       |    "temp": 22.5,
       |    "feels_like": 21.0,
       |    "humidity": 55
       |  },
       |  "wind": { "speed": 4.2 },
       |  "visibility": 10000
       |}""".stripMargin

  private val sampleForecastBody: String =
    """|{
       |  "list": [
       |    {
       |      "dt_txt": "2024-01-15 12:00:00",
       |      "main": { "temp_min": 10.0, "temp_max": 18.0 },
       |      "weather": [{ "description": "partly cloudy" }]
       |    },
       |    {
       |      "dt_txt": "2024-01-15 15:00:00",
       |      "main": { "temp_min": 12.0, "temp_max": 20.0 },
       |      "weather": [{ "description": "sunny" }]
       |    }
       |  ]
       |}""".stripMargin

  private val sampleAlertsBody: String =
    """|{
       |  "alerts": [
       |    {
       |      "event": "Wind Advisory",
       |      "start": 1705276800,
       |      "end": 1705320000,
       |      "description": "Strong winds expected"
       |    }
       |  ]
       |}""".stripMargin

  private val noAlertsBody: String =
    """|{ "lat": 37.77, "lon": -122.41 }""".stripMargin

  private val http401Body: String =
    """|{ "cod": 401, "message": "Invalid API key." }""".stripMargin

  private val invalidJsonBody: String = "not json at all"

  private def fixedBodyRoutes(body: String): Routes[Any, Nothing] =
    Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          _: Request,
        ) =>
          Response(
            status = Status.Ok,
            headers = Headers(Header.ContentType(MediaType.application.json).untyped),
            body = Body.fromString(body),
          )
      },
    )

  private def fixedStatusRoutes(
    status: Status,
    body:   String,
  ): Routes[Any, Nothing] =
    Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          _: Request,
        ) =>
          Response(
            status = status,
            headers = Headers(Header.ContentType(MediaType.application.json).untyped),
            body = Body.fromString(body),
          )
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("WeatherSkillSpec")(
      test("weather.current returns structured result for valid location") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleCurrentBody))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client, s"http://localhost:$port")
          result <- skill.invoke(dummyCtx, "weather.current", Json.Obj("location" -> Json.Str("London")))
        } yield {
          val fields = result match {
            case Json.Obj(fs) => fs.toMap
            case _            => Map.empty
          }
          assert(fields.get("temperature"))(isSome(equalTo(Json.Num(22.5)))) &&
          assert(fields.get("feels_like"))(isSome(equalTo(Json.Num(21.0)))) &&
          assert(fields.get("humidity"))(isSome(equalTo(Json.Num(55)))) &&
          assert(fields.get("description"))(isSome(equalTo(Json.Str("clear sky")))) &&
          assert(fields.get("wind_speed"))(isSome(equalTo(Json.Num(4.2)))) &&
          assert(fields.get("visibility"))(isSome(equalTo(Json.Num(10000))))
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("weather.forecast returns forecast list for valid location") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleForecastBody))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client, s"http://localhost:$port")
          result <- skill.invoke(
            dummyCtx,
            "weather.forecast",
            Json.Obj("location" -> Json.Str("Paris"), "days" -> Json.Num(1)),
          )
        } yield result match {
          case Json.Arr(items) =>
            assert(items.length)(equalTo(2)) &&
            assert(items.head)(
              equalTo(
                Json.Obj(
                  "date"        -> Json.Str("2024-01-15 12:00:00"),
                  "temp_min"    -> Json.Num(10.0),
                  "temp_max"    -> Json.Num(18.0),
                  "description" -> Json.Str("partly cloudy"),
                ),
              ),
            )
          case _ => assert(false)(isTrue)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("weather.alerts returns alerts list for lat/lon") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleAlertsBody))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client, s"http://localhost:$port")
          result <- skill.invoke(
            dummyCtx,
            "weather.alerts",
            Json.Obj("lat" -> Json.Num(37.77), "lon" -> Json.Num(-122.41)),
          )
        } yield result match {
          case Json.Arr(items) =>
            assert(items.length)(equalTo(1)) &&
            assert(items.head)(
              equalTo(
                Json.Obj(
                  "event"       -> Json.Str("Wind Advisory"),
                  "start"       -> Json.Num(1705276800),
                  "end"         -> Json.Num(1705320000),
                  "description" -> Json.Str("Strong winds expected"),
                ),
              ),
            )
          case _ => assert(false)(isTrue)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("weather.alerts returns empty array when no alerts field") {
        for {
          port   <- Server.install(fixedBodyRoutes(noAlertsBody))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client, s"http://localhost:$port")
          result <- skill.invoke(
            dummyCtx,
            "weather.alerts",
            Json.Obj("lat" -> Json.Num(37.77), "lon" -> Json.Num(-122.41)),
          )
        } yield assert(result)(equalTo(Json.Arr()))
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("weather.current missing required arg fails with ValidationError") {
        for {
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client)
          result <- skill.invoke(dummyCtx, "weather.current", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Client.default),
      test("weather.alerts missing lat fails with ValidationError") {
        for {
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client)
          result <- skill.invoke(dummyCtx, "weather.alerts", Json.Obj("lon" -> Json.Num(-122.41))).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Client.default),
      test("HTTP 401 (bad API key) fails with JorlanError") {
        for {
          port   <- Server.install(fixedStatusRoutes(Status.Unauthorized, http401Body))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client, s"http://localhost:$port")
          result <- skill.invoke(dummyCtx, "weather.current", Json.Obj("location" -> Json.Str("London"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("invalid JSON response fails with JorlanError") {
        for {
          port   <- Server.install(fixedBodyRoutes(invalidJsonBody))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client, s"http://localhost:$port")
          result <- skill.invoke(dummyCtx, "weather.current", Json.Obj("location" -> Json.Str("London"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("unknown tool name fails with ValidationError") {
        for {
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client)
          result <- skill.invoke(dummyCtx, "weather.unknown", Json.Obj()).exit
        } yield assert(result)(failsWithA[ValidationError])
      }.provide(Client.default),
      test("dashboardData returns weather for default location") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleCurrentBody))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg.copy(defaultLocation = "London"), client, s"http://localhost:$port")
          result <- skill.dashboardData(dummyCtx)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("temperature", _) => true; case _ => false }) &&
            assertTrue(fields.exists { case ("location", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("dashboardData returns error JSON when apiKey is blank") {
        for {
          client <- ZIO.service[Client]
          skill = new WeatherSkill(WeatherConfig(apiKey = "", defaultLocation = "London"), client)
          result <- skill.dashboardData(dummyCtx)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("error", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Client.default),
      test("weather.current with units override succeeds and returns temperature") {
        for {
          port   <- Server.install(fixedBodyRoutes(sampleCurrentBody))
          client <- ZIO.service[Client]
          skill = new WeatherSkill(dummyCfg, client, s"http://localhost:$port")
          result <- skill.invoke(
            dummyCtx,
            "weather.current",
            Json.Obj("location" -> Json.Str("London"), "units" -> Json.Str("imperial")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("temperature", _) => true; case _ => false }) &&
            assertTrue(fields.exists { case ("description", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
    ) @@ TestAspect.withLiveClock

}
