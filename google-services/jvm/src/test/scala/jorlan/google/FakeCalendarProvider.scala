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
import jorlan.service.CalendarProvider
import zio.*

import java.time.Instant

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
      val events = m.getOrElse(calendarId.value, List.empty)
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
        .fromOption(m.getOrElse(calendarId.value, List.empty).find(_.id == eventId))
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
      _ <- eventsRef.update(m => m.updated(calendarId.value, m.getOrElse(calendarId.value, List.empty) :+ saved))
    } yield saved

  override def updateEvent(
    userId:     UserId,
    calendarId: CalendarId,
    entry:      CalendarEntry,
  ): IO[JorlanError, CalendarEntry] =
    eventsRef.update { m =>
      val updated = m.getOrElse(calendarId.value, List.empty).map(e => if (e.id == entry.id) entry else e)
      m.updated(calendarId.value, updated)
    } *> ZIO.succeed(entry)

  override def deleteEvent(
    userId:     UserId,
    calendarId: CalendarId,
    eventId:    CalendarEventId,
  ): IO[JorlanError, Unit] =
    eventsRef.update { m =>
      val filtered = m.getOrElse(calendarId.value, List.empty).filterNot(_.id == eventId)
      m.updated(calendarId.value, filtered)
    }

}

object FakeCalendarProvider {

  def make(
    calendars: List[UserCalendar] = List.empty,
    events:    Map[String, List[CalendarEntry]] = Map.empty,
  ): UIO[FakeCalendarProvider] =
    for {
      cr <- Ref.make(calendars)
      er <- Ref.make(events)
      ir <- Ref.make(0L)
    } yield FakeCalendarProvider(cr, er, ir)

}
