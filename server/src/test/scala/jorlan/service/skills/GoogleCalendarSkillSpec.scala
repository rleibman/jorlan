/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.ZIORepositories
import jorlan.*
import jorlan.google.GoogleCalendarSkill
import jorlan.service.CalendarProvider
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.*

import java.time.Instant

object GoogleCalendarSkillSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val sampleCalendar = UserCalendar(
    id = CalendarId("primary"),
    summary = "My Calendar",
    isPrimary = true,
    timeZone = "UTC",
  )

  private val sampleEvent = CalendarEntry(
    id = CalendarEventId("evt-1"),
    calendarId = CalendarId("primary"),
    summary = "Team Meeting",
    description = Some("Weekly sync"),
    location = Some("Conference Room"),
    start = Instant.parse("2026-06-15T10:00:00Z"),
    end = Instant.parse("2026-06-15T11:00:00Z"),
    allDay = false,
    attendees = List(CalendarAttendee("alice@example.com", Some("Alice"), AttendeeResponse.Accepted)),
    organizer = Some("bob@example.com"),
    status = CalendarEventStatus.Confirmed,
  )

  private def makeProvider(
    calendars: List[UserCalendar] = List(sampleCalendar),
    events:    Map[String, List[CalendarEntry]] = Map("primary" -> List(sampleEvent)),
  ): UIO[FakeCalendarProvider] =
    for {
      cr <- Ref.make(calendars)
      er <- Ref.make(events)
      ir <- Ref.make(0L)
    } yield FakeCalendarProvider(cr, er, ir)

  private def makeSkill(provider: CalendarProvider[[A] =>> IO[JorlanError, A]])
    : URIO[ZIORepositories, GoogleCalendarSkill] =
    ZIO.serviceWith[ZIORepositories](new GoogleCalendarSkill(provider, _))

  private def strField(
    json: Json,
    key:  String,
  ): Option[String] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("GoogleCalendarSkill")(
      test("calendar.listCalendars returns calendars") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "calendar.listCalendars", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val cals = fields.collectFirst { case ("calendars", Json.Arr(cs)) => cs }.getOrElse(Chunk.empty)
            assertTrue(cals.length == 1)
          case _ => assertTrue(false)
        }
      },
      test("calendar.listEvents returns events for calendar") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "calendar.listEvents",
            Json.Obj("calendarId" -> Json.Str("primary")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue }
            assertTrue(count.contains(1))
          case _ => assertTrue(false)
        }
      },
      test("calendar.listEvents uses 'primary' as default calendarId") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "calendar.listEvents", Json.Obj()).either
        } yield assertTrue(result.isRight)
      },
      test("calendar.getEvent returns specific event") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "calendar.getEvent",
            Json.Obj(
              "calendarId" -> Json.Str("primary"),
              "eventId"    -> Json.Str("evt-1"),
            ),
          )
        } yield assertTrue(
          strField(result, "id").contains("evt-1"),
          strField(result, "summary").contains("Team Meeting"),
        )
      },
      test("calendar.getEvent fails for unknown event") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill
            .invoke(
              ctx,
              "calendar.getEvent",
              Json.Obj(
                "calendarId" -> Json.Str("primary"),
                "eventId"    -> Json.Str("no-such-event"),
              ),
            ).either
        } yield assertTrue(result.isLeft)
      },
      test("calendar.createEvent creates a new event") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          args = Json.Obj(
            "calendarId" -> Json.Str("primary"),
            "summary"    -> Json.Str("New Event"),
            "start"      -> Json.Str("2026-06-20T14:00:00Z"),
            "end"        -> Json.Str("2026-06-20T15:00:00Z"),
          )
          result <- skill.invoke(ctx, "calendar.createEvent", args)
        } yield assertTrue(strField(result, "summary").contains("New Event"))
      },
      test("calendar.createEvent fails without required fields") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill
            .invoke(
              ctx,
              "calendar.createEvent",
              Json.Obj("calendarId" -> Json.Str("primary")),
            ).either
        } yield assertTrue(result.isLeft)
      },
      test("calendar.deleteEvent deletes an event") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "calendar.deleteEvent",
            Json.Obj(
              "calendarId" -> Json.Str("primary"),
              "eventId"    -> Json.Str("evt-1"),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val deleted = fields.collectFirst { case ("deleted", Json.Bool(v)) => v }
            assertTrue(deleted.contains(true))
          case _ => assertTrue(false)
        }
      },
      test("calendar.updateEvent updates event fields") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          args = Json.Obj(
            "calendarId" -> Json.Str("primary"),
            "eventId"    -> Json.Str("evt-1"),
            "summary"    -> Json.Str("Updated Meeting"),
            "start"      -> Json.Str("2026-06-15T10:00:00Z"),
            "end"        -> Json.Str("2026-06-15T11:00:00Z"),
          )
          result <- skill.invoke(ctx, "calendar.updateEvent", args)
        } yield assertTrue(strField(result, "summary").contains("Updated Meeting"))
      },
      test("unknown tool returns JorlanError") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "calendar.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
    )

}

class FakeCalendarProvider(
  calendarsRef: Ref[List[UserCalendar]],
  eventsRef:    Ref[Map[String, List[CalendarEntry]]],
  idGenRef:     Ref[Long],
) extends CalendarProvider[[A] =>> IO[JorlanError, A]] {

  override def listCalendars(userId: UserId): IO[JorlanError, List[UserCalendar]] =
    calendarsRef.get

  override def listEvents(
    userId:     UserId,
    calendarId: CalendarId,
    maxResults: Int,
    timeMin:    Option[Instant],
    timeMax:    Option[Instant],
  ): IO[JorlanError, List[CalendarEntry]] =
    eventsRef.get.map { m =>
      val events = m.getOrElse(calendarId.value, Nil)
      val filtered = events
        .filter(e => timeMin.forall(min => !e.start.isBefore(min)))
        .filter(e => timeMax.forall(max => !e.end.isAfter(max)))
      filtered.take(maxResults)
    }

  override def getEvent(
    userId:     UserId,
    calendarId: CalendarId,
    eventId:    CalendarEventId,
  ): IO[JorlanError, CalendarEntry] =
    eventsRef.get.flatMap { m =>
      ZIO
        .fromOption(m.getOrElse(calendarId.value, Nil).find(_.id == eventId))
        .orElseFail(JorlanError(s"Event not found: ${eventId.value}"))
    }

  override def createEvent(
    userId:     UserId,
    calendarId: CalendarId,
    entry:      CalendarEntry,
  ): IO[JorlanError, CalendarEntry] =
    for {
      newId <- idGenRef.updateAndGet(_ + 1).map(n => CalendarEventId(s"fake-event-$n"))
      saved = entry.copy(id = newId)
      _ <- eventsRef.update(m => m.updated(calendarId.value, m.getOrElse(calendarId.value, Nil) :+ saved))
    } yield saved

  override def updateEvent(
    userId:     UserId,
    calendarId: CalendarId,
    entry:      CalendarEntry,
  ): IO[JorlanError, CalendarEntry] =
    eventsRef.update { m =>
      val updated = m.getOrElse(calendarId.value, Nil).map(e => if (e.id == entry.id) entry else e)
      m.updated(calendarId.value, updated)
    } *> ZIO.succeed(entry)

  override def deleteEvent(
    userId:     UserId,
    calendarId: CalendarId,
    eventId:    CalendarEventId,
  ): IO[JorlanError, Unit] =
    eventsRef.update { m =>
      val filtered = m.getOrElse(calendarId.value, Nil).filterNot(_.id == eventId)
      m.updated(calendarId.value, filtered)
    }

}
