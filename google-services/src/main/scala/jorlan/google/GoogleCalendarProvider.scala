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

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.{Event, EventAttendee, EventDateTime}
import jorlan.JorlanError
import jorlan.domain.*
import jorlan.service.{CalendarProvider, OAuthCredentialService}
import zio.{IO, Ref, ZIO}

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

/** Google Calendar-backed [[jorlan.service.CalendarProvider]] using the Google Java API client library.
  *
  * Access tokens are refreshed lazily via [[OAuthCredentialService.refreshAccessToken]] (only when near expiry).
  */
class GoogleCalendarProvider private (
  credentials: OAuthCredentialService,
  transport:   com.google.api.client.http.HttpTransport,
  jsonFactory: com.google.api.client.json.JsonFactory,
  clientCache: Ref[Map[UserId, (String, Calendar)]],
) extends GoogleApiProvider[Calendar](credentials, transport, jsonFactory, clientCache)
    with CalendarProvider[[A] =>> IO[JorlanError, A]] {

  override protected val apiName: String = "Calendar"

  override protected def makeClient(accessToken: String): Calendar =
    new Calendar.Builder(transport, jsonFactory, buildAdapter(accessToken))
      .setApplicationName("Jorlan")
      .build()
      .nn

  private def withCalendar[A](userId: UserId)(f: Calendar => A): IO[JorlanError, A] = withClient(userId)(f)

  private def toInstant(dt: EventDateTime | Null): Instant =
    Option(dt) match {
      case None    => Instant.EPOCH
      case Some(d) =>
        Option(d.getDateTime)
          .map(dateTime => Instant.ofEpochMilli(dateTime.getValue))
          .orElse(Option(d.getDate).map(date => Instant.parse(s"${date.toStringRfc3339}T00:00:00Z")))
          .getOrElse(Instant.EPOCH)
    }

  private def isAllDay(dt: EventDateTime | Null): Boolean =
    Option(dt).exists(d => d.getDate != null && d.getDateTime == null)

  private def eventToEntry(
    calendarId: CalendarId,
    e:          Event,
  ): CalendarEntry = {
    val statusStr = Option(e.getStatus).getOrElse("confirmed").nn
    val status = statusStr match {
      case "cancelled" => CalendarEventStatus.Cancelled
      case "tentative" => CalendarEventStatus.Tentative
      case _           => CalendarEventStatus.Confirmed
    }
    val attendees = Option(e.getAttendees).map(_.asScala.toList).getOrElse(Nil).map { a =>
      val response = Option(a.getResponseStatus).getOrElse("needsAction").nn match {
        case "accepted"  => AttendeeResponse.Accepted
        case "declined"  => AttendeeResponse.Declined
        case "tentative" => AttendeeResponse.Tentative
        case _           => AttendeeResponse.NeedsAction
      }
      CalendarAttendee(
        email = Option(a.getEmail).getOrElse("").nn,
        displayName = Option(a.getDisplayName),
        responseStatus = response,
      )
    }
    CalendarEntry(
      id = CalendarEventId(Option(e.getId).getOrElse("").nn),
      calendarId = calendarId,
      summary = Option(e.getSummary).getOrElse("").nn,
      description = Option(e.getDescription),
      location = Option(e.getLocation),
      start = toInstant(e.getStart),
      end = toInstant(e.getEnd),
      allDay = isAllDay(e.getStart),
      attendees = attendees,
      organizer = Option(e.getOrganizer).flatMap(o => Option(o.getEmail)),
      status = status,
    )
  }

  private def entryToEvent(entry: CalendarEntry): Event = {
    val startDt = new EventDateTime().setDateTime(new DateTime(entry.start.toEpochMilli))
    val endDt = new EventDateTime().setDateTime(new DateTime(entry.end.toEpochMilli))
    val attendees = entry.attendees.map { a =>
      new EventAttendee()
        .setEmail(a.email)
        .setDisplayName(a.displayName.orNull)
    }.asJava
    new Event()
      .setSummary(entry.summary)
      .setDescription(entry.description.orNull)
      .setLocation(entry.location.orNull)
      .setStart(startDt)
      .setEnd(endDt)
      .setAttendees(attendees)
  }

  override def listCalendars(userId: UserId): IO[JorlanError, List[UserCalendar]] =
    withCalendar(userId) { cal =>
      val result = cal.calendarList().list().nn.execute().nn
      Option(result.getItems).map(_.asScala.toList).getOrElse(Nil).map { item =>
        UserCalendar(
          id = CalendarId(Option(item.getId).getOrElse("").nn),
          summary = Option(item.getSummary).getOrElse("").nn,
          isPrimary = Option(item.getPrimary).exists(_.booleanValue),
          timeZone = Option(item.getTimeZone).getOrElse("UTC").nn,
        )
      }
    }

  override def listEvents(
    userId:     UserId,
    calendarId: CalendarId,
    maxResults: Int,
    timeMin:    Option[Instant],
    timeMax:    Option[Instant],
  ): IO[JorlanError, List[CalendarEntry]] =
    withCalendar(userId) { cal =>
      val req = cal
        .events().list(calendarId.value).nn
        .setMaxResults(maxResults)
        .setSingleEvents(true)
        .setOrderBy("startTime")
      timeMin.foreach(t => req.setTimeMin(new DateTime(t.toEpochMilli)))
      timeMax.foreach(t => req.setTimeMax(new DateTime(t.toEpochMilli)))
      val result = req.execute().nn
      Option(result.getItems)
        .map(_.asScala.toList).getOrElse(Nil)
        .map(eventToEntry(calendarId, _))
    }

  override def getEvent(
    userId:     UserId,
    calendarId: CalendarId,
    eventId:    CalendarEventId,
  ): IO[JorlanError, CalendarEntry] =
    withCalendar(userId) { cal =>
      val e = cal.events().get(calendarId.value, eventId.value).nn.execute().nn
      eventToEntry(calendarId, e)
    }

  override def createEvent(
    userId:     UserId,
    calendarId: CalendarId,
    entry:      CalendarEntry,
  ): IO[JorlanError, CalendarEntry] =
    withCalendar(userId) { cal =>
      val created = cal.events().insert(calendarId.value, entryToEvent(entry)).nn.execute().nn
      eventToEntry(calendarId, created)
    }

  override def updateEvent(
    userId:     UserId,
    calendarId: CalendarId,
    entry:      CalendarEntry,
  ): IO[JorlanError, CalendarEntry] =
    withCalendar(userId) { cal =>
      // Use patch instead of update so unset fields are not overwritten on the server
      val updated = cal.events().patch(calendarId.value, entry.id.value, entryToEvent(entry)).nn.execute().nn
      eventToEntry(calendarId, updated)
    }

  override def deleteEvent(
    userId:     UserId,
    calendarId: CalendarId,
    eventId:    CalendarEventId,
  ): IO[JorlanError, Unit] =
    withCalendar(userId) { cal =>
      cal.events().delete(calendarId.value, eventId.value).nn.execute()
    }.unit

}

object GoogleCalendarProvider {

  def apply(credentials: OAuthCredentialService): IO[JorlanError, GoogleCalendarProvider] =
    for {
      transport <- ZIO
        .attemptBlocking(GoogleNetHttpTransport.newTrustedTransport().nn)
        .mapError(e => JorlanError(s"Failed to initialize Calendar transport: ${e.getMessage}", Some(e)))
      jsonFactory = GsonFactory.getDefaultInstance.nn
      cache <- Ref.make(Map.empty[UserId, (String, Calendar)])
    } yield new GoogleCalendarProvider(credentials, transport, jsonFactory, cache)

}
