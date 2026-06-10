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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.domain.*
import zio.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Built-in skill for reading and writing files in the configured workspace root directory.
  *
  * All paths are resolved relative to `workspaceRoot` and validated against path-traversal attacks before any
  * filesystem operation. Traversal attempts return an error without revealing the root path.
  *
  * Tools:
  *   - `workspace.read` — read a file
  *   - `workspace.write` — write (overwrite) a file
  *   - `workspace.search` — list files matching a glob prefix
  *   - `workspace.delete` — delete a file
  */
class WorkspaceSkill(workspaceRoot: Path) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "workspace",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "workspace.read",
        description = "Read the content of a file in the workspace directory.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Relative file path within the workspace"}},"required":["path"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("workspace.read")),
      ),
      ToolDescriptor(
        name = "workspace.write",
        description = "Write (overwrite) a file in the workspace directory.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Relative file path within the workspace"},"content":{"type":"string","description":"Text content to write"}},"required":["path","content"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("workspace.write")),
      ),
      ToolDescriptor(
        name = "workspace.search",
        description = "List files in the workspace directory matching a path prefix.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"prefix":{"type":"string","description":"Path prefix to search (empty string = all files)"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("workspace.read")),
      ),
      ToolDescriptor(
        name = "workspace.delete",
        description = "Delete a file from the workspace directory.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Relative file path within the workspace"}},"required":["path"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("workspace.write")),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "workspace.read"   => workspaceRead(args)
      case "workspace.write"  => workspaceWrite(args)
      case "workspace.search" => workspaceSearch(args)
      case "workspace.delete" => workspaceDelete(args)
      case other              => ZIO.fail(JorlanError(s"WorkspaceSkill: unknown tool '$other'"))
    }

  private def getStr(
    args: Json,
    key:  String,
  ): Option[String] =
    args match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  /** Resolve and validate a relative path against the workspace root. Rejects traversal attempts. */
  private def safePath(relative: String): IO[JorlanError, Path] =
    ZIO
      .attempt {
        val resolved = workspaceRoot.resolve(relative).normalize()
        if (!resolved.startsWith(workspaceRoot)) {
          throw new SecurityException("Path traversal rejected")
        }
        resolved
      }.mapError {
        case e: SecurityException => JorlanError(s"workspace: path is outside workspace root")
        case e => JorlanError(s"workspace: invalid path: ${e.getMessage}")
      }

  private def workspaceRead(args: Json): IO[JorlanError, Json] =
    getStr(args, "path") match {
      case None      => ZIO.fail(JorlanError("workspace.read: path is required"))
      case Some(rel) =>
        for {
          p       <- safePath(rel)
          content <- ZIO
            .attempt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
            .mapError(e => JorlanError(s"workspace.read: ${e.getMessage}"))
        } yield Json.Str(content)
    }

  private def workspaceWrite(args: Json): IO[JorlanError, Json] = {
    val pathOpt = getStr(args, "path")
    val contentOpt = getStr(args, "content")
    (pathOpt, contentOpt) match {
      case (None, _)                  => ZIO.fail(JorlanError("workspace.write: path is required"))
      case (_, None)                  => ZIO.fail(JorlanError("workspace.write: content is required"))
      case (Some(rel), Some(content)) =>
        for {
          p <- safePath(rel)
          _ <- ZIO
            .attempt {
              Option(p.getParent).foreach(parent => Files.createDirectories(parent))
              Files.write(p, content.getBytes(StandardCharsets.UTF_8))
            }.mapError(e => JorlanError(s"workspace.write: ${e.getMessage}"))
        } yield Json.Str("ok")
    }
  }

  private def workspaceSearch(args: Json): IO[JorlanError, Json] = {
    val prefix = getStr(args, "prefix").getOrElse("")
    for {
      baseDir <-
        if (prefix.isEmpty) ZIO.succeed(workspaceRoot)
        else
          safePath(prefix).map { p =>
            if (Files.isDirectory(p)) p else Option(p.getParent).getOrElse(workspaceRoot)
          }
      files <- ZIO
        .attempt {
          if (!Files.exists(baseDir)) List.empty[String]
          else {
            val stream = Files.walk(baseDir)
            try {
              stream
                .filter(p => Files.isRegularFile(p))
                .map(p => workspaceRoot.relativize(p).toString)
                .toArray
                .toList
                .collect { case s: String => s }
            } finally stream.close()
          }
        }.mapError(e => JorlanError(s"workspace.search: ${e.getMessage}"))
    } yield Json.Arr(files.map(Json.Str(_))*)
  }

  private def workspaceDelete(args: Json): IO[JorlanError, Json] =
    getStr(args, "path") match {
      case None      => ZIO.fail(JorlanError("workspace.delete: path is required"))
      case Some(rel) =>
        for {
          p <- safePath(rel)
          _ <- ZIO
            .attempt(Files.deleteIfExists(p))
            .mapError(e => JorlanError(s"workspace.delete: ${e.getMessage}"))
        } yield Json.Str("ok")
    }

}

object WorkspaceSkill {

  val live: ZLayer[WorkspaceSettings, Nothing, WorkspaceSkill] =
    ZLayer.fromZIO(
      ZIO.serviceWith[WorkspaceSettings](s => new WorkspaceSkill(Paths.get(s.root).toAbsolutePath.normalize())),
    )

}
