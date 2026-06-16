/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
