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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.db.repository.ZIORepositories
import jorlan.domain.*
import jorlan.service.CalendarProvider
import zio.*
import zio.json.*
import zio.json.ast.Json

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
  repo:             ZIORepositories,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "calendar",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "calendar.listCalendars",
        description = "List all available calendars for the authenticated user.",
        inputSchema = Json.decoder
          .decodeJson("""{"type":"object","properties":{},"required":[]}""")
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.read")),
      ),
      ToolDescriptor(
        name = "calendar.listEvents",
        description = "List events in a calendar within an optional time range.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"calendarId":{"type":"string","description":"Calendar ID (use 'primary' for primary calendar)"},"maxResults":{"type":"integer","description":"Maximum number of events (default 10)"},"timeMin":{"type":"string","description":"Start time in ISO 8601 format"},"timeMax":{"type":"string","description":"End time in ISO 8601 format"}},"required":["calendarId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.read")),
      ),
      ToolDescriptor(
        name = "calendar.getEvent",
        description = "Get details for a specific calendar event.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"calendarId":{"type":"string"},"eventId":{"type":"string","description":"The event ID"}},"required":["calendarId","eventId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.read")),
      ),
      ToolDescriptor(
        name = "calendar.createEvent",
        description = "Create a new calendar event.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"calendarId":{"type":"string"},"summary":{"type":"string","description":"Event title"},"description":{"type":"string"},"location":{"type":"string"},"start":{"type":"string","description":"Start time in ISO 8601 format"},"end":{"type":"string","description":"End time in ISO 8601 format"},"attendees":{"type":"array","items":{"type":"string"},"description":"Attendee email addresses"}},"required":["calendarId","summary","start","end"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.write")),
      ),
      ToolDescriptor(
        name = "calendar.updateEvent",
        description = "Update an existing calendar event.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"calendarId":{"type":"string"},"eventId":{"type":"string"},"summary":{"type":"string"},"description":{"type":"string"},"location":{"type":"string"},"start":{"type":"string"},"end":{"type":"string"}},"required":["calendarId","eventId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.write")),
      ),
      ToolDescriptor(
        name = "calendar.deleteEvent",
        description = "Delete a calendar event.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"calendarId":{"type":"string"},"eventId":{"type":"string","description":"The event ID to delete"}},"required":["calendarId","eventId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("calendar.write")),
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

  private def logEvent(
    ctx:       InvocationContext,
    eventType: EventType,
    payload:   Json,
  ): UIO[Unit] =
    Clock.instant.flatMap { now =>
      repo.eventLog
        .append(
          EventLog(
            id = EventLogId.empty,
            eventType = eventType,
            actorId = Some(ctx.actorId),
            agentId = ctx.agentId,
            sessionId = ctx.sessionId,
            resource = Option.empty[String],
            payloadJson = Some(payload),
            occurredAt = now,
          ),
        ).orDie.unit
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

  private def listCalendars(ctx: InvocationContext, args: Json): IO[JorlanError, Json] =
    for {
      cals <- calendarProvider.listCalendars(ctx.actorId)
      _    <- logEvent(ctx, EventType.CalendarEventRead, Json.Obj("action" -> Json.Str("listCalendars")))
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

  private def listEvents(ctx: InvocationContext, args: Json): IO[JorlanError, Json] =
    SkillArgs.str(args, "calendarId") match {
      case None    => ZIO.fail(JorlanError("calendar.listEvents: calendarId is required"))
      case Some(calId) =>
        val maxResults = SkillArgs.int(args, "maxResults").getOrElse(10)
        for {
          timeMin <- SkillArgs.str(args, "timeMin").fold[IO[JorlanError, Option[Instant]]](ZIO.none)(s =>
            ZIO.fromEither(parseInstant(s)).map(Some(_)),
          )
          timeMax <- SkillArgs.str(args, "timeMax").fold[IO[JorlanError, Option[Instant]]](ZIO.none)(s =>
            ZIO.fromEither(parseInstant(s)).map(Some(_)),
          )
          events  <- calendarProvider.listEvents(ctx.actorId, CalendarId(calId), maxResults, timeMin, timeMax)
          _       <- logEvent(
            ctx,
            EventType.CalendarEventRead,
            Json.Obj("calendarId" -> Json.Str(calId), "count" -> Json.Num(events.size)),
          )
        } yield Json.Obj(
          "events" -> Json.Arr(events.map(entryToJson)*),
          "count"  -> Json.Num(events.size),
        )
    }

  private def getEvent(ctx: InvocationContext, args: Json): IO[JorlanError, Json] =
    (SkillArgs.str(args, "calendarId"), SkillArgs.str(args, "eventId")) match {
      case (Some(calId), Some(evId)) =>
        for {
          event <- calendarProvider.getEvent(ctx.actorId, CalendarId(calId), CalendarEventId(evId))
          _     <- logEvent(
            ctx,
            EventType.CalendarEventRead,
            Json.Obj("calendarId" -> Json.Str(calId), "eventId" -> Json.Str(evId)),
          )
        } yield entryToJson(event)
      case _ => ZIO.fail(JorlanError("calendar.getEvent: calendarId and eventId are required"))
    }

  private def createEvent(ctx: InvocationContext, args: Json): IO[JorlanError, Json] = {
    val calIdOpt  = SkillArgs.str(args, "calendarId")
    val summaryOpt = SkillArgs.str(args, "summary")
    val startOpt  = SkillArgs.str(args, "start")
    val endOpt    = SkillArgs.str(args, "end")
    (calIdOpt, summaryOpt, startOpt, endOpt) match {
      case (Some(calId), Some(summary), Some(startStr), Some(endStr)) =>
        for {
          start <- ZIO.fromEither(parseInstant(startStr))
          end   <- ZIO.fromEither(parseInstant(endStr))
          attendees = SkillArgs.strList(args, "attendees").map(email =>
            CalendarAttendee(email, None, AttendeeResponse.NeedsAction),
          )
          entry = CalendarEntry(
            id = CalendarEventId(""),
            calendarId = CalendarId(calId),
            summary = summary,
            description = SkillArgs.str(args, "description"),
            location = SkillArgs.str(args, "location"),
            start = start,
            end = end,
            allDay = false,
            attendees = attendees,
            organizer = None,
            status = CalendarEventStatus.Confirmed,
          )
          created <- calendarProvider.createEvent(ctx.actorId, CalendarId(calId), entry)
          _       <- logEvent(
            ctx,
            EventType.CalendarEventCreated,
            Json.Obj("calendarId" -> Json.Str(calId), "eventId" -> Json.Str(created.id.value), "summary" -> Json.Str(summary)),
          )
        } yield entryToJson(created)
      case _ => ZIO.fail(JorlanError("calendar.createEvent: calendarId, summary, start, and end are required"))
    }
  }

  private def updateEvent(ctx: InvocationContext, args: Json): IO[JorlanError, Json] =
    (SkillArgs.str(args, "calendarId"), SkillArgs.str(args, "eventId")) match {
      case (Some(calId), Some(evId)) =>
        for {
          existing <- calendarProvider.getEvent(ctx.actorId, CalendarId(calId), CalendarEventId(evId))
          start    <- SkillArgs.str(args, "start").fold[IO[JorlanError, Instant]](ZIO.succeed(existing.start))(s =>
            ZIO.fromEither(parseInstant(s)),
          )
          end      <- SkillArgs.str(args, "end").fold[IO[JorlanError, Instant]](ZIO.succeed(existing.end))(s =>
            ZIO.fromEither(parseInstant(s)),
          )
          updated = existing.copy(
            summary = SkillArgs.str(args, "summary").getOrElse(existing.summary),
            description = SkillArgs.str(args, "description").orElse(existing.description),
            location = SkillArgs.str(args, "location").orElse(existing.location),
            start = start,
            end = end,
          )
          result  <- calendarProvider.updateEvent(ctx.actorId, CalendarId(calId), updated)
          _       <- logEvent(
            ctx,
            EventType.CalendarEventUpdated,
            Json.Obj("calendarId" -> Json.Str(calId), "eventId" -> Json.Str(evId)),
          )
        } yield entryToJson(result)
      case _ => ZIO.fail(JorlanError("calendar.updateEvent: calendarId and eventId are required"))
    }

  private def deleteEvent(ctx: InvocationContext, args: Json): IO[JorlanError, Json] =
    (SkillArgs.str(args, "calendarId"), SkillArgs.str(args, "eventId")) match {
      case (Some(calId), Some(evId)) =>
        for {
          _ <- calendarProvider.deleteEvent(ctx.actorId, CalendarId(calId), CalendarEventId(evId))
          _ <- logEvent(
            ctx,
            EventType.CalendarEventDeleted,
            Json.Obj("calendarId" -> Json.Str(calId), "eventId" -> Json.Str(evId)),
          )
        } yield Json.Obj("eventId" -> Json.Str(evId), "deleted" -> Json.Bool(true))
      case _ => ZIO.fail(JorlanError("calendar.deleteEvent: calendarId and eventId are required"))
    }

}

object GoogleCalendarSkill
