/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{JsonDecoder, JsonEncoder}
import java.time.Instant

enum CalendarEventStatus derives JsonEncoder, JsonDecoder {

  case Confirmed, Tentative, Cancelled

}

enum AttendeeResponse derives JsonEncoder, JsonDecoder {

  case Accepted, Declined, Tentative, NeedsAction

}

case class CalendarAttendee(
  email:          String,
  displayName:    Option[String],
  responseStatus: AttendeeResponse,
) derives JsonEncoder, JsonDecoder

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
) derives JsonEncoder, JsonDecoder

case class UserCalendar(
  id:        CalendarId,
  summary:   String,
  isPrimary: Boolean,
  timeZone:  String,
) derives JsonEncoder, JsonDecoder
