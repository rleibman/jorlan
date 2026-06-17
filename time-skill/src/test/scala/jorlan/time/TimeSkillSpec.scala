/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.time

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object TimeSkillSpec extends ZIOSpecDefault {

  private val skill = new TimeSkill()

  /** A minimal invocation context — TimeSkill doesn't use any field of it. */
  private val ctx = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  /** Helper: extract a String field from a Json.Obj result. */
  private def strField(
    json: Json,
    key:  String,
  ): Option[String] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  /** Helper: extract a numeric field as Double from a Json.Obj result. */
  private def numField(
    json: Json,
    key:  String,
  ): Option[Double] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Num(n)) => n.doubleValue() }
      case _                => None
    }

  // A fixed instant: 2026-06-16T12:00:00Z (Monday, noon UTC)
  private val fixedInstant: Instant = Instant.parse("2026-06-16T12:00:00Z")

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TimeSkillSpec")(
      test("time.now with no timezone returns UTC") {
        for {
          _      <- TestClock.setTime(fixedInstant)
          result <- skill.invoke(ctx, "time.now", Json.Obj())
        } yield {
          val tz = strField(result, "timezone")
          val offset = strField(result, "utcOffset")
          val dt = strField(result, "datetime")
          val dow = strField(result, "dayOfWeek")
          assertTrue(tz.contains("UTC")) &&
          assertTrue(offset.contains("Z") || offset.contains("+00:00")) &&
          assertTrue(dt.exists(_.startsWith("2026-06-16"))) &&
          assertTrue(dow.exists(_.equalsIgnoreCase("monday")))
        }
      },
      test("time.now with America/New_York returns eastern offset") {
        for {
          _      <- TestClock.setTime(fixedInstant)
          result <- skill.invoke(ctx, "time.now", Json.Obj("timezone" -> Json.Str("America/New_York")))
        } yield {
          val tz = strField(result, "timezone")
          val offset = strField(result, "utcOffset")
          assertTrue(tz.contains("America/New_York")) &&
          // EDT = -04:00, EST = -05:00
          assertTrue(offset.exists(o => o == "-04:00" || o == "-05:00"))
        }
      },
      test("time.now with invalid timezone fails with JorlanError") {
        for {
          exit <- skill.invoke(ctx, "time.now", Json.Obj("timezone" -> Json.Str("Not/A/Zone"))).exit
        } yield assert(exit)(fails(isSubtype[JorlanError](anything)))
      },
      test("time.convert converts UTC to Asia/Tokyo (+9h)") {
        val args = Json.Obj(
          "datetime"     -> Json.Str("2026-06-16T00:00:00"),
          "fromTimezone" -> Json.Str("UTC"),
          "toTimezone"   -> Json.Str("Asia/Tokyo"),
        )
        for {
          result <- skill.invoke(ctx, "time.convert", args)
        } yield {
          val converted = strField(result, "converted")
          // Midnight UTC = 09:00 Tokyo (UTC+9)
          assertTrue(converted.exists(_.contains("09:00:00"))) &&
          assertTrue(converted.exists(_.contains("+09:00")))
        }
      },
      test("time.convert with invalid fromTimezone fails") {
        val args = Json.Obj(
          "datetime"     -> Json.Str("2026-06-16T12:00:00"),
          "fromTimezone" -> Json.Str("Bogus/Zone"),
          "toTimezone"   -> Json.Str("UTC"),
        )
        for {
          exit <- skill.invoke(ctx, "time.convert", args).exit
        } yield assert(exit)(fails(isSubtype[JorlanError](anything)))
      },
      test("time.convert with invalid datetime string fails") {
        val args = Json.Obj(
          "datetime"     -> Json.Str("not-a-date"),
          "fromTimezone" -> Json.Str("UTC"),
          "toTimezone"   -> Json.Str("Asia/Tokyo"),
        )
        for {
          exit <- skill.invoke(ctx, "time.convert", args).exit
        } yield assert(exit)(fails(isSubtype[JorlanError](anything)))
      },
      test("time.add_duration adds PT2H30M correctly") {
        val args = Json.Obj(
          "datetime" -> Json.Str("2026-06-16T10:00:00"),
          "timezone" -> Json.Str("UTC"),
          "duration" -> Json.Str("PT2H30M"),
        )
        for {
          result <- skill.invoke(ctx, "time.add_duration", args)
        } yield {
          val res = strField(result, "result")
          // 10:00 + 2h30m = 12:30
          assertTrue(res.exists(_.contains("12:30:00")))
        }
      },
      test("time.add_duration adds P1D (one day) correctly") {
        val args = Json.Obj(
          "datetime" -> Json.Str("2026-06-16T12:00:00"),
          "timezone" -> Json.Str("UTC"),
          "duration" -> Json.Str("P1D"),
        )
        for {
          result <- skill.invoke(ctx, "time.add_duration", args)
        } yield {
          val res = strField(result, "result")
          assertTrue(res.exists(_.contains("2026-06-17")))
        }
      },
      test("time.diff returns correct totalSeconds hours and humanReadable") {
        val args = Json.Obj(
          "from"         -> Json.Str("2026-06-16T10:00:00"),
          "to"           -> Json.Str("2026-06-16T11:30:00"),
          "fromTimezone" -> Json.Str("UTC"),
          "toTimezone"   -> Json.Str("UTC"),
        )
        for {
          result <- skill.invoke(ctx, "time.diff", args)
        } yield {
          val totalSecs = numField(result, "totalSeconds")
          val hours = numField(result, "hours")
          val minutes = numField(result, "minutes")
          val humanReadable = strField(result, "humanReadable")
          assertTrue(totalSecs.contains(5400.0)) &&
          assertTrue(hours.contains(1.0)) &&
          assertTrue(minutes.contains(30.0)) &&
          assertTrue(humanReadable.exists(_.contains("hour"))) &&
          assertTrue(humanReadable.exists(_.contains("30 minute")))
        }
      },
      test("time.diff where to is before from returns negative totalSeconds") {
        val args = Json.Obj(
          "from" -> Json.Str("2026-06-16T12:00:00"),
          "to"   -> Json.Str("2026-06-16T11:00:00"),
        )
        for {
          result <- skill.invoke(ctx, "time.diff", args)
        } yield {
          val totalSecs = numField(result, "totalSeconds")
          assertTrue(totalSecs.exists(_ < 0))
        }
      },
      test("time.convert missing datetime field fails with ValidationError") {
        val args = Json.Obj(
          "fromTimezone" -> Json.Str("UTC"),
          "toTimezone"   -> Json.Str("Asia/Tokyo"),
        )
        for {
          exit <- skill.invoke(ctx, "time.convert", args).exit
        } yield assert(exit)(fails(isSubtype[ValidationError](anything)))
      },
      test("time.add_duration missing duration field fails with ValidationError") {
        val args = Json.Obj(
          "datetime" -> Json.Str("2026-06-16T10:00:00"),
          "timezone" -> Json.Str("UTC"),
        )
        for {
          exit <- skill.invoke(ctx, "time.add_duration", args).exit
        } yield assert(exit)(fails(isSubtype[ValidationError](anything)))
      },
      test("time.diff missing from field fails with ValidationError") {
        val args = Json.Obj(
          "to" -> Json.Str("2026-06-16T12:00:00"),
        )
        for {
          exit <- skill.invoke(ctx, "time.diff", args).exit
        } yield assert(exit)(fails(isSubtype[ValidationError](anything)))
      },
      test("unknown tool fails with ValidationError") {
        for {
          exit <- skill.invoke(ctx, "time.unknown", Json.Obj()).exit
        } yield assert(exit)(fails(isSubtype[ValidationError](anything)))
      },
    )

}
