/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.google

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.DriveProvider
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

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
    doc = Some(
      """|## Google Drive Skill
         |
         |Reads files from Google Drive using OAuth.
         |
         |### Tools
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `drive.listFiles` | List files in Drive or a folder | `drive.read` |
         || `drive.readFile` | Read text content of a file | `drive.read` |
         || `drive.downloadFile` | Download binary file as Artifact | `drive.read` |
         |
         |### Setup
         |1. Configure Google OAuth credentials in Server Settings:
         |   - `google.clientId`, `google.clientSecret`, `google.redirectUri`
         |2. Users must connect their Google account via Admin → Integrations → Google.
         |3. Grant the `drive.read` capability to agents.""".stripMargin,
    ),
    tools = List(
      ToolDescriptor(
        name = "drive.listFiles",
        description = "List files in Google Drive, optionally filtered by folder or search query.",
        inputSchema = json"""{"type":"object","properties":{"folderId":{"type":"string","description":"Parent folder ID from drive.listFiles `id` field, or folder name (resolved automatically)"},"query":{"type":"string","description":"Search query"},"maxResults":{"type":"integer","description":"Maximum number of files (default 10)"}},"required":[]}""",
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
        inputSchema = json"""{"type":"object","properties":{"fileId":{"type":"string","description":"Drive file ID from drive.listFiles `id` field, or the file name (resolved automatically)"}},"required":["fileId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"fileId":{"type":"string","description":"Drive file ID from drive.listFiles `id` field, or the file name (resolved automatically)"},"name":{"type":"string","description":"Display name for the artifact"}},"required":["fileId"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("drive.read")),
        examplePrompts = List(
          "Download the PDF report from Drive",
          "Get the spreadsheet file and save it as an artifact",
        ),
      ),
    ),
    oauthProvider = Some(OAuthProvider.Google),
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

  private val driveIdPattern = "^[a-zA-Z0-9_-]{20,}$".r

  /** Resolve a fileId or folderId that may be a human-readable name rather than a real Drive ID. Drive file IDs are
    * long alphanumeric strings (≥20 chars, no spaces). Anything else is treated as a filename and looked up via Drive
    * search.
    */
  private def resolveFileId(
    userId:       UserId,
    fileIdOrName: String,
  ): IO[JorlanError, DriveFileId] =
    if (driveIdPattern.matches(fileIdOrName)) {
      ZIO.succeed(DriveFileId(fileIdOrName))
    } else {
      for {
        files    <- driveProvider.listFiles(userId, None, Some(s"name='$fileIdOrName'"), 1)
        resolved <- ZIO
          .fromOption(files.headOption.map(f => DriveFileId(f.id.value))).orElseFail(
            JorlanError(s"No Drive file found with name '$fileIdOrName'. Use the 'id' field from drive.listFiles."),
          )
      } yield resolved
    }

  private def listFiles(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val rawFolderId = str(args, "folderId")
    val query = str(args, "query")
    val maxResults = int(args, "maxResults").getOrElse(10)
    for {
      // Resolve folderId by name if it doesn't look like a real Drive ID
      folderId <- rawFolderId match {
        case None     => ZIO.none
        case Some(id) =>
          resolveFileId(ctx.actorId, id).map(f => Some(f.value)).catchAll(_ => ZIO.succeed(Some(id)))
      }
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
      case None            => ZIO.fail(JorlanError("drive.readFile: fileId is required"))
      case Some(rawFileId) =>
        for {
          fileId  <- resolveFileId(ctx.actorId, rawFileId)
          content <- driveProvider.readTextFile(ctx.actorId, fileId)
        } yield Json.Obj("fileId" -> Json.Str(fileId.value), "content" -> Json.Str(content))
    }

  private def downloadFile(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "fileId") match {
      case None            => ZIO.fail(JorlanError("drive.downloadFile: fileId is required"))
      case Some(rawFileId) =>
        for {
          fileId <- resolveFileId(ctx.actorId, rawFileId)
          bytes  <- driveProvider.downloadFile(ctx.actorId, fileId)
        } yield Json.Obj(
          "fileId"    -> Json.Str(fileId.value),
          "sizeBytes" -> Json.Num(bytes.length),
          "content"   -> Json.Str(java.util.Base64.getEncoder.encodeToString(bytes)),
        )
    }

}

object GoogleDriveSkill
