/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import java.time.Instant

/** Abstract calendar backend. `F[_]` is the effect type — typically `IO[JorlanError, *]` in production. The live
  * implementation is [[jorlan.google.GoogleCalendarProvider]].
  */
trait CalendarProvider[F[_]] {

  def listCalendars(userId: UserId): F[List[UserCalendar]]
  def listEvents(
    userId:     UserId,
    calendarId: CalendarId,
    maxResults: Int,
    timeMin:    Option[Instant],
    timeMax:    Option[Instant],
  ): F[List[CalendarEntry]]
  def getEvent(
    userId:     UserId,
    calendarId: CalendarId,
    eventId:    CalendarEventId,
  ): F[CalendarEntry]
  def createEvent(
    userId:     UserId,
    calendarId: CalendarId,
    entry:      CalendarEntry,
  ): F[CalendarEntry]
  def updateEvent(
    userId:     UserId,
    calendarId: CalendarId,
    entry:      CalendarEntry,
  ): F[CalendarEntry]
  def deleteEvent(
    userId:     UserId,
    calendarId: CalendarId,
    eventId:    CalendarEventId,
  ): F[Unit]

}
