/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.google

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object GoogleCalendarSkillSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = userId,
    agentId = None,
    sessionId = None,
  )

  private val calId = "primary"

  private val sampleCalendar: UserCalendar = UserCalendar(
    id = CalendarId(calId),
    summary = "My Calendar",
    isPrimary = true,
    timeZone = "America/Chicago",
  )

  private val t0: Instant = Instant.parse("2026-06-01T10:00:00Z")
  private val t1: Instant = Instant.parse("2026-06-01T11:00:00Z")
  private val t2: Instant = Instant.parse("2026-06-01T12:00:00Z")

  private val sampleEvent: CalendarEntry = CalendarEntry(
    id = CalendarEventId("evt-1"),
    calendarId = CalendarId(calId),
    summary = "Team Standup",
    description = Some("Daily standup meeting"),
    location = Some("Conference Room A"),
    start = t0,
    end = t1,
    allDay = false,
    attendees = List.empty,
    organizer = None,
    status = CalendarEventStatus.Confirmed,
  )

  private def makeSkill(
    calendars: List[UserCalendar] = List(sampleCalendar),
    events:    Map[String, List[CalendarEntry]] = Map(calId -> List(sampleEvent)),
  ): ZIO[Any, Nothing, GoogleCalendarSkill] =
    FakeCalendarProvider.make(calendars, events).map(new GoogleCalendarSkill(_))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GoogleCalendarSkillSpec")(
      test("calendar.listCalendars returns available calendars") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "calendar.listCalendars", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val cals = fields.collectFirst { case ("calendars", Json.Arr(items)) => items }.getOrElse(Chunk.empty)
            assert(cals.length)(equalTo(1)) &&
            assertTrue(cals.head.asInstanceOf[Json.Obj].fields.exists {
              case ("summary", Json.Str("My Calendar")) => true; case _ => false
            })
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.listCalendars returns empty list when no calendars") {
        for {
          skill  <- makeSkill(calendars = List.empty, events = Map.empty)
          result <- skill.invoke(dummyCtx, "calendar.listCalendars", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val cals = fields.collectFirst { case ("calendars", Json.Arr(items)) => items }.getOrElse(Chunk.empty)
            assert(cals.length)(equalTo(0))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.listEvents returns events for a calendar") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(
            dummyCtx,
            "calendar.listEvents",
            Json.Obj("calendarId" -> Json.Str(calId)),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val events = fields.collectFirst { case ("events", Json.Arr(items)) => items }.getOrElse(Chunk.empty)
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue() }.getOrElse(0)
            assert(events.length)(equalTo(1)) &&
            assert(count)(equalTo(1))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.listEvents uses 'primary' as default calendarId") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "calendar.listEvents", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue() }.getOrElse(0)
            assert(count)(equalTo(1))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.listEvents with timeMin filters out past events") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(
            dummyCtx,
            "calendar.listEvents",
            Json.Obj(
              "calendarId" -> Json.Str(calId),
              "timeMin"    -> Json.Str("2026-06-01T11:30:00Z"),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue() }.getOrElse(-1)
            assert(count)(equalTo(0))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.getEvent returns event by id") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(
            dummyCtx,
            "calendar.getEvent",
            Json.Obj("calendarId" -> Json.Str(calId), "eventId" -> Json.Str("evt-1")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("id"))(isSome(equalTo(Json.Str("evt-1")))) &&
            assert(fieldMap.get("summary"))(isSome(equalTo(Json.Str("Team Standup"))))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.getEvent fails when event not found") {
        for {
          skill  <- makeSkill()
          result <- skill
            .invoke(
              dummyCtx,
              "calendar.getEvent",
              Json.Obj("calendarId" -> Json.Str(calId), "eventId" -> Json.Str("nonexistent")),
            ).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("calendar.getEvent fails when calendarId missing") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "calendar.getEvent", Json.Obj("eventId" -> Json.Str("evt-1"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("calendar.createEvent creates and returns new event") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(
            dummyCtx,
            "calendar.createEvent",
            Json.Obj(
              "calendarId" -> Json.Str(calId),
              "summary"    -> Json.Str("New Meeting"),
              "start"      -> Json.Str("2026-06-02T09:00:00Z"),
              "end"        -> Json.Str("2026-06-02T10:00:00Z"),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("summary"))(isSome(equalTo(Json.Str("New Meeting")))) &&
            assertTrue(fieldMap.contains("id"))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.createEvent with optional description and location") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(
            dummyCtx,
            "calendar.createEvent",
            Json.Obj(
              "calendarId"  -> Json.Str(calId),
              "summary"     -> Json.Str("Meeting with description"),
              "description" -> Json.Str("Detailed agenda"),
              "location"    -> Json.Str("Room 101"),
              "start"       -> Json.Str("2026-06-03T14:00:00Z"),
              "end"         -> Json.Str("2026-06-03T15:00:00Z"),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("description"))(isSome(equalTo(Json.Str("Detailed agenda")))) &&
            assert(fieldMap.get("location"))(isSome(equalTo(Json.Str("Room 101"))))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.createEvent fails when required fields missing") {
        for {
          skill  <- makeSkill()
          result <- skill
            .invoke(
              dummyCtx,
              "calendar.createEvent",
              Json.Obj("calendarId" -> Json.Str(calId), "summary" -> Json.Str("Incomplete")),
            ).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("calendar.createEvent fails with invalid start date") {
        for {
          skill  <- makeSkill()
          result <- skill
            .invoke(
              dummyCtx,
              "calendar.createEvent",
              Json.Obj(
                "calendarId" -> Json.Str(calId),
                "summary"    -> Json.Str("Bad date"),
                "start"      -> Json.Str("not-a-date"),
                "end"        -> Json.Str("2026-06-02T10:00:00Z"),
              ),
            ).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("calendar.updateEvent updates summary and returns updated event") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(
            dummyCtx,
            "calendar.updateEvent",
            Json.Obj(
              "calendarId" -> Json.Str(calId),
              "eventId"    -> Json.Str("evt-1"),
              "summary"    -> Json.Str("Updated Standup"),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("summary"))(isSome(equalTo(Json.Str("Updated Standup"))))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.updateEvent fails when calendarId missing") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "calendar.updateEvent", Json.Obj("eventId" -> Json.Str("evt-1"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("calendar.deleteEvent deletes and returns confirmation") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(
            dummyCtx,
            "calendar.deleteEvent",
            Json.Obj("calendarId" -> Json.Str(calId), "eventId" -> Json.Str("evt-1")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("deleted"))(isSome(equalTo(Json.Bool(true)))) &&
            assert(fieldMap.get("eventId"))(isSome(equalTo(Json.Str("evt-1"))))
          case _ => assert(false)(isTrue)
        }
      },
      test("calendar.deleteEvent fails when calendarId missing") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "calendar.deleteEvent", Json.Obj("eventId" -> Json.Str("evt-1"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("unknown tool fails with JorlanError") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "calendar.unknown", Json.Obj()).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
    )

}
