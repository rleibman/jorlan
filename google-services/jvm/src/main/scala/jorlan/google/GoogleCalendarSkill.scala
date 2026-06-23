/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.google

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.CalendarProvider
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

import java.time.Instant

/** Built-in skill for reading and managing Google Calendar events.
  *
  * Tools:
  *   - `calendar.listCalendars` — list available calendars
  *   - `calendar.listEvents` — list events in a calendar
  *   - `calendar.getEvent` — get a specific event
  *   - `calendar.createEvent` — create a new event
  *   - `calendar.updateEvent` — update an existing event
  *   - `calendar.deleteEvent` — delete an event
  */
class GoogleCalendarSkill(
  calendarProvider: CalendarProvider[[A] =>> IO[JorlanError, A]],
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "calendar",
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    tier = SkillTier.BuiltIn,
    keywords = List(
      "calendar",
      "event",
      "meeting",
      "appointment",
      "schedule",
      "invite",
      "reminder",
      "Google Calendar",
      "RSVP",
      "recurring",
      "availability",
      "book",
      "agenda",
      "due date",
    ),
    tools = List(
      ToolDescriptor(
        name = "calendar.listCalendars",
        description = "List all available calendars for the authenticated user.",
        inputSchema = json"""{"type":"object","properties":{},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.read")),
        examplePrompts = List(
          "What calendars do I have?",
          "Show me all my Google calendars",
        ),
      ),
      ToolDescriptor(
        name = "calendar.listEvents",
        description = "List events in a calendar within an optional time range.",
        inputSchema = json"""{"type":"object","properties":{"calendarId":{"type":"string","description":"Calendar ID (default: 'primary')"},"maxResults":{"type":"integer","description":"Maximum number of events (default 10)"},"timeMin":{"type":"string","description":"Start time in ISO 8601 format"},"timeMax":{"type":"string","description":"End time in ISO 8601 format"}}}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.read")),
        examplePrompts = List(
          "What meetings do I have this week?",
          "Show me my calendar for tomorrow",
          "List events between Monday and Friday",
        ),
      ),
      ToolDescriptor(
        name = "calendar.getEvent",
        description = "Get details for a specific calendar event.",
        inputSchema = json"""{"type":"object","properties":{"calendarId":{"type":"string"},"eventId":{"type":"string","description":"The event ID"}},"required":["calendarId","eventId"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.read")),
        examplePrompts = List(
          "Get the details of the standup meeting event",
          "Show me the full info for event abc123",
        ),
      ),
      ToolDescriptor(
        name = "calendar.createEvent",
        description = "Create a new calendar event.",
        inputSchema = json"""{"type":"object","properties":{"calendarId":{"type":"string"},"summary":{"type":"string","description":"Event title"},"description":{"type":"string"},"location":{"type":"string"},"start":{"type":"string","description":"Start time in ISO 8601 format"},"end":{"type":"string","description":"End time in ISO 8601 format"},"attendees":{"type":"array","items":{"type":"string"},"description":"Attendee email addresses"}},"required":["calendarId","summary","start","end"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.write")),
        examplePrompts = List(
          "Schedule a team meeting for Friday at 2pm",
          "Add a dentist appointment tomorrow at 10am",
          "Create a reminder event for the quarterly review",
        ),
      ),
      ToolDescriptor(
        name = "calendar.updateEvent",
        description = "Update an existing calendar event.",
        inputSchema = json"""{"type":"object","properties":{"calendarId":{"type":"string"},"eventId":{"type":"string"},"summary":{"type":"string"},"description":{"type":"string"},"location":{"type":"string"},"start":{"type":"string"},"end":{"type":"string"}},"required":["calendarId","eventId"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.write")),
        examplePrompts = List(
          "Move the standup meeting to 3pm",
          "Update the sprint review to include Alice as attendee",
          "Change the location of tomorrow's lunch meeting",
        ),
      ),
      ToolDescriptor(
        name = "calendar.deleteEvent",
        description = "Delete a calendar event.",
        inputSchema = json"""{"type":"object","properties":{"calendarId":{"type":"string"},"eventId":{"type":"string","description":"The event ID to delete"}},"required":["calendarId","eventId"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.write")),
        examplePrompts = List(
          "Cancel the weekly sync for next week",
          "Delete the dentist appointment event",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "calendar.listCalendars" => listCalendars(ctx, args)
      case "calendar.listEvents"    => listEvents(ctx, args)
      case "calendar.getEvent"      => getEvent(ctx, args)
      case "calendar.createEvent"   => createEvent(ctx, args)
      case "calendar.updateEvent"   => updateEvent(ctx, args)
      case "calendar.deleteEvent"   => deleteEvent(ctx, args)
      case other                    => ZIO.fail(JorlanError(s"GoogleCalendarSkill: unknown tool '$other'"))
    }

  private def entryToJson(entry: CalendarEntry): Json =
    Json.Obj(
      "id"          -> Json.Str(entry.id.value),
      "calendarId"  -> Json.Str(entry.calendarId.value),
      "summary"     -> Json.Str(entry.summary),
      "description" -> entry.description.fold[Json](Json.Null)(Json.Str(_)),
      "location"    -> entry.location.fold[Json](Json.Null)(Json.Str(_)),
      "start"       -> Json.Str(entry.start.toString),
      "end"         -> Json.Str(entry.end.toString),
      "allDay"      -> Json.Bool(entry.allDay),
      "status"      -> Json.Str(entry.status.toString),
    )

  private def parseInstant(s: String): Either[JorlanError, Instant] =
    try Right(Instant.parse(s))
    catch { case e: Exception => Left(JorlanError(s"Invalid date format: $s", Some(e))) }

  private def listCalendars(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    for {
      cals <- calendarProvider.listCalendars(ctx.actorId)
    } yield Json.Obj(
      "calendars" -> Json.Arr(
        cals.map(c =>
          Json.Obj(
            "id"        -> Json.Str(c.id.value),
            "summary"   -> Json.Str(c.summary),
            "isPrimary" -> Json.Bool(c.isPrimary),
            "timeZone"  -> Json.Str(c.timeZone),
          ),
        )*,
      ),
    )

  private def listEvents(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val calId = str(args, "calendarId").getOrElse("primary")
    val maxResults = int(args, "maxResults").getOrElse(10)
    for {
      timeMin <- str(args, "timeMin").fold[IO[JorlanError, Option[Instant]]](ZIO.none)(s =>
        ZIO.fromEither(parseInstant(s)).map(Some(_)),
      )
      timeMax <- str(args, "timeMax").fold[IO[JorlanError, Option[Instant]]](ZIO.none)(s =>
        ZIO.fromEither(parseInstant(s)).map(Some(_)),
      )
      events <- calendarProvider.listEvents(ctx.actorId, CalendarId(calId), maxResults, timeMin, timeMax)
    } yield Json.Obj(
      "events" -> Json.Arr(events.map(entryToJson)*),
      "count"  -> Json.Num(events.size),
    )
  }

  private def getEvent(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    (str(args, "calendarId"), str(args, "eventId")) match {
      case (Some(calId), Some(evId)) =>
        for {
          event <- calendarProvider.getEvent(ctx.actorId, CalendarId(calId), CalendarEventId(evId))
        } yield entryToJson(event)
      case _ => ZIO.fail(JorlanError("calendar.getEvent: calendarId and eventId are required"))
    }

  private def createEvent(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val calIdOpt = str(args, "calendarId")
    val summaryOpt = str(args, "summary")
    val startOpt = str(args, "start")
    val endOpt = str(args, "end")
    (calIdOpt, summaryOpt, startOpt, endOpt) match {
      case (Some(calId), Some(summary), Some(startStr), Some(endStr)) =>
        for {
          start <- ZIO.fromEither(parseInstant(startStr))
          end   <- ZIO.fromEither(parseInstant(endStr))
          attendees = strList(args, "attendees").map(email =>
            CalendarAttendee(email, None, AttendeeResponse.NeedsAction),
          )
          entry = CalendarEntry(
            id = CalendarEventId(""), // empty-string sentinel: provider assigns the real ID on create
            calendarId = CalendarId(calId),
            summary = summary,
            description = str(args, "description"),
            location = str(args, "location"),
            start = start,
            end = end,
            allDay = false,
            attendees = attendees,
            organizer = None,
            status = CalendarEventStatus.Confirmed,
          )
          created <- calendarProvider.createEvent(ctx.actorId, CalendarId(calId), entry)
        } yield entryToJson(created)
      case _ => ZIO.fail(JorlanError("calendar.createEvent: calendarId, summary, start, and end are required"))
    }
  }

  private def updateEvent(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    (str(args, "calendarId"), str(args, "eventId")) match {
      case (Some(calId), Some(evId)) =>
        for {
          existing <- calendarProvider.getEvent(ctx.actorId, CalendarId(calId), CalendarEventId(evId))
          start    <- str(args, "start").fold[IO[JorlanError, Instant]](ZIO.succeed(existing.start))(s =>
            ZIO.fromEither(parseInstant(s)),
          )
          end <- str(args, "end").fold[IO[JorlanError, Instant]](ZIO.succeed(existing.end))(s =>
            ZIO.fromEither(parseInstant(s)),
          )
          updated = existing.copy(
            summary = str(args, "summary").getOrElse(existing.summary),
            description = str(args, "description").orElse(existing.description),
            location = str(args, "location").orElse(existing.location),
            start = start,
            end = end,
          )
          result <- calendarProvider.updateEvent(ctx.actorId, CalendarId(calId), updated)
        } yield entryToJson(result)
      case _ => ZIO.fail(JorlanError("calendar.updateEvent: calendarId and eventId are required"))
    }

  private def deleteEvent(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    (str(args, "calendarId"), str(args, "eventId")) match {
      case (Some(calId), Some(evId)) =>
        for {
          _ <- calendarProvider.deleteEvent(ctx.actorId, CalendarId(calId), CalendarEventId(evId))
        } yield Json.Obj("eventId" -> Json.Str(evId), "deleted" -> Json.Bool(true))
      case _ => ZIO.fail(JorlanError("calendar.deleteEvent: calendarId and eventId are required"))
    }

}

object GoogleCalendarSkill
