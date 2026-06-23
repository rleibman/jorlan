/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.JsonCodec
import java.time.Instant

enum CalendarEventStatus derives JsonCodec {

  case Confirmed, Tentative, Cancelled

}

enum AttendeeResponse derives JsonCodec {

  case Accepted, Declined, Tentative, NeedsAction

}

case class CalendarAttendee(
  email:          String,
  displayName:    Option[String],
  responseStatus: AttendeeResponse,
) derives JsonCodec

case class CalendarEntry(
  id:          CalendarEventId,
  calendarId:  CalendarId,
  summary:     String,
  description: Option[String],
  location:    Option[String],
  start:       Instant,
  end:         Instant,
  allDay:      Boolean,
  attendees:   List[CalendarAttendee],
  organizer:   Option[String],
  status:      CalendarEventStatus,
) derives JsonCodec

case class UserCalendar(
  id:        CalendarId,
  summary:   String,
  isPrimary: Boolean,
  timeZone:  String,
) derives JsonCodec
