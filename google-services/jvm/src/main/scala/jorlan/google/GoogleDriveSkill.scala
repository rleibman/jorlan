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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.DriveProvider
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Built-in skill for reading files from Google Drive.
  *
  * Tools:
  *   - `drive.listFiles` — list files in Drive or a specific folder
  *   - `drive.readFile` — read text content from a file
  *   - `drive.downloadFile` — download file bytes (stores as Artifact)
  */
class GoogleDriveSkill(
  driveProvider: DriveProvider[[A] =>> IO[JorlanError, A]],
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "drive",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "Drive",
      "file",
      "document",
      "Google Drive",
      "storage",
      "upload",
      "download",
      "share",
      "folder",
      "Docs",
      "Sheets",
      "Slides",
      "PDF",
      "cloud storage",
      "list files",
    ),
    tools = List(
      ToolDescriptor(
        name = "drive.listFiles",
        description = "List files in Google Drive, optionally filtered by folder or search query.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"folderId":{"type":"string","description":"Parent folder ID (omit for root)"},"query":{"type":"string","description":"Search query"},"maxResults":{"type":"integer","description":"Maximum number of files (default 10)"}},"required":[]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("drive.read")),
        examplePrompts = List(
          "What files do I have in Google Drive?",
          "List files in my Documents folder",
          "Find files about the Q4 report in Drive",
        ),
      ),
      ToolDescriptor(
        name = "drive.readFile",
        description = "Read the text content of a Google Drive file (Google Docs are exported as plain text).",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"fileId":{"type":"string","description":"The Drive file ID"}},"required":["fileId"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("drive.read")),
        examplePrompts = List(
          "Read the project spec document from Drive",
          "Show me the content of my notes file",
        ),
      ),
      ToolDescriptor(
        name = "drive.downloadFile",
        description = "Download a binary file from Google Drive. The file bytes are stored as an Artifact.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"fileId":{"type":"string","description":"The Drive file ID"},"name":{"type":"string","description":"Display name for the artifact"}},"required":["fileId"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("drive.read")),
        examplePrompts = List(
          "Download the PDF report from Drive",
          "Get the spreadsheet file and save it as an artifact",
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
      case "drive.listFiles"    => listFiles(ctx, args)
      case "drive.readFile"     => readFile(ctx, args)
      case "drive.downloadFile" => downloadFile(ctx, args)
      case other                => ZIO.fail(JorlanError(s"GoogleDriveSkill: unknown tool '$other'"))
    }

  private def fileToJson(f: DriveFile): Json =
    Json.Obj(
      "id"          -> Json.Str(f.id.value),
      "name"        -> Json.Str(f.name),
      "mimeType"    -> Json.Str(f.mimeType),
      "modifiedAt"  -> Json.Str(f.modifiedAt.toString),
      "webViewLink" -> f.webViewLink.fold[Json](Json.Null)(Json.Str(_)),
    )

  private def listFiles(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val folderId = str(args, "folderId")
    val query = str(args, "query")
    val maxResults = int(args, "maxResults").getOrElse(10)
    for {
      files <- driveProvider.listFiles(ctx.actorId, folderId, query, maxResults)
    } yield Json.Obj(
      "files" -> Json.Arr(files.map(fileToJson)*),
      "count" -> Json.Num(files.size),
    )
  }

  private def readFile(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "fileId") match {
      case None         => ZIO.fail(JorlanError("drive.readFile: fileId is required"))
      case Some(fileId) =>
        for {
          content <- driveProvider.readTextFile(ctx.actorId, DriveFileId(fileId))
        } yield Json.Obj("fileId" -> Json.Str(fileId), "content" -> Json.Str(content))
    }

  private def downloadFile(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "fileId") match {
      case None         => ZIO.fail(JorlanError("drive.downloadFile: fileId is required"))
      case Some(fileId) =>
        for {
          bytes <- driveProvider.downloadFile(ctx.actorId, DriveFileId(fileId))
        } yield Json.Obj(
          "fileId"    -> Json.Str(fileId),
          "sizeBytes" -> Json.Num(bytes.length),
          // Note: bytes are returned in-band as base64. Persistent artifact storage is deferred.
          "content" -> Json.Str(java.util.Base64.getEncoder.encodeToString(bytes)),
        )
    }

}

object GoogleDriveSkill
