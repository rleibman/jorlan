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
import jorlan.service.DriveProvider
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.net.URI

/** Built-in skill for reading files from Google Drive.
  *
  * Tools:
  *   - `drive.listFiles` — list files in Drive or a specific folder
  *   - `drive.readFile` — read text content from a file
  *   - `drive.downloadFile` — download file bytes (stores as Artifact)
  */
class GoogleDriveSkill(
  driveProvider: DriveProvider[[A] =>> IO[JorlanError, A]],
  repo:          ZIORepositories,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "drive",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "drive.listFiles",
        description = "List files in Google Drive, optionally filtered by folder or search query.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"folderId":{"type":"string","description":"Parent folder ID (omit for root)"},"query":{"type":"string","description":"Search query"},"maxResults":{"type":"integer","description":"Maximum number of files (default 10)"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("drive.read")),
      ),
      ToolDescriptor(
        name = "drive.readFile",
        description = "Read the text content of a Google Drive file (Google Docs are exported as plain text).",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"fileId":{"type":"string","description":"The Drive file ID"}},"required":["fileId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("drive.read")),
      ),
      ToolDescriptor(
        name = "drive.downloadFile",
        description = "Download a binary file from Google Drive. The file bytes are stored as an Artifact.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"fileId":{"type":"string","description":"The Drive file ID"},"name":{"type":"string","description":"Display name for the artifact"}},"required":["fileId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("drive.read")),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "drive.listFiles"    => listFiles(ctx, args)
      case "drive.readFile"     => readFile(ctx, args)
      case "drive.downloadFile" => downloadFile(ctx, args)
      case other                => ZIO.fail(JorlanError(s"GoogleDriveSkill: unknown tool '$other'"))
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

  private def fileToJson(f: DriveFile): Json =
    Json.Obj(
      "id"          -> Json.Str(f.id.value),
      "name"        -> Json.Str(f.name),
      "mimeType"    -> Json.Str(f.mimeType),
      "modifiedAt"  -> Json.Str(f.modifiedAt.toString),
      "webViewLink" -> f.webViewLink.fold[Json](Json.Null)(Json.Str(_)),
    )

  private def listFiles(ctx: InvocationContext, args: Json): IO[JorlanError, Json] = {
    val folderId   = SkillArgs.str(args, "folderId")
    val query      = SkillArgs.str(args, "query")
    val maxResults = SkillArgs.int(args, "maxResults").getOrElse(10)
    for {
      files <- driveProvider.listFiles(ctx.actorId, folderId, query, maxResults)
      _     <- logEvent(
        ctx,
        EventType.DriveFileListed,
        Json.Obj("count" -> Json.Num(files.size), "folderId" -> folderId.fold[Json](Json.Null)(Json.Str(_))),
      )
    } yield Json.Obj(
      "files" -> Json.Arr(files.map(fileToJson)*),
      "count" -> Json.Num(files.size),
    )
  }

  private def readFile(ctx: InvocationContext, args: Json): IO[JorlanError, Json] =
    SkillArgs.str(args, "fileId") match {
      case None         => ZIO.fail(JorlanError("drive.readFile: fileId is required"))
      case Some(fileId) =>
        for {
          content <- driveProvider.readTextFile(ctx.actorId, DriveFileId(fileId))
          _       <- logEvent(ctx, EventType.DriveFileRead, Json.Obj("fileId" -> Json.Str(fileId)))
        } yield Json.Obj("fileId" -> Json.Str(fileId), "content" -> Json.Str(content))
    }

  private def downloadFile(ctx: InvocationContext, args: Json): IO[JorlanError, Json] =
    SkillArgs.str(args, "fileId") match {
      case None         => ZIO.fail(JorlanError("drive.downloadFile: fileId is required"))
      case Some(fileId) =>
        val name = SkillArgs.str(args, "name").getOrElse(s"drive-$fileId")
        for {
          bytes <- driveProvider.downloadFile(ctx.actorId, DriveFileId(fileId))
          now   <- Clock.instant
          artifact = Artifact(
            id = ArtifactId.empty,
            workspaceId = ctx.workspaceId,
            sessionId = ctx.sessionId,
            name = name,
            mimeType = zio.http.MediaType.application.`octet-stream`,
            sizeBytes = bytes.length.toLong,
            storageUri = URI.create(s"artifact://drive/$fileId/${now.toEpochMilli}"),
            metadataJson = Some(Json.Obj("driveFileId" -> Json.Str(fileId))),
            createdAt = now,
          )
          saved <- repo.artifact.upsert(artifact).mapError(e => JorlanError(e.msg, e.cause))
          _     <- logEvent(ctx, EventType.DriveFileRead, Json.Obj("fileId" -> Json.Str(fileId), "artifactId" -> Json.Str(saved.id.value.toString)))
        } yield Json.Obj(
          "fileId"      -> Json.Str(fileId),
          "artifactId"  -> Json.Str(saved.id.value.toString),
          "artifactUri" -> Json.Str(saved.storageUri.toString),
          "sizeBytes"   -> Json.Num(bytes.length),
        )
    }

}

object GoogleDriveSkill
