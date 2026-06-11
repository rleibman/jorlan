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
import jorlan.domain.*
import zio.*
import zio.json.ast.Json

import java.io.{BufferedOutputStream, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.zip.{ZipEntry, ZipOutputStream}

/** Built-in skill for reading and writing files in the configured workspace root directory.
  *
  * All paths are resolved relative to `workspaceRoot` (scoped per [[WorkspaceScope]]) and validated against
  * path-traversal attacks. Traversal attempts return an error without revealing the root path.
  *
  * Tools:
  *   - `workspace.read` — read a file
  *   - `workspace.write` — write (overwrite) a file
  *   - `workspace.search` — list files matching a glob prefix
  *   - `workspace.delete` — delete a file
  *   - `workspace.snapshot` — zip all workspace files into `__snapshots/`
  */
class WorkspaceSkill(
  workspaceRoot: Path,
  settings:      WorkspaceSettings,
) extends Skill {

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
        requiredCapabilities = List(CapabilityName("workspace.delete")),
      ),
      ToolDescriptor(
        name = "workspace.snapshot",
        description = "Create a zip snapshot of all workspace files and store it under __snapshots/.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"tag":{"type":"string","description":"Optional snapshot label; defaults to the current timestamp"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("workspace.write")),
      ),
    ),
  )

  /** Return the effective workspace root, scoped by session or user depending on [[WorkspaceScope]]. */
  private def scopedRoot(ctx: InvocationContext): Path =
    settings.defaultScope match {
      case WorkspaceScope.Flat    => workspaceRoot
      case WorkspaceScope.Session =>
        ctx.sessionId.fold(workspaceRoot)(s => workspaceRoot.resolve(s"session-${s.value}"))
      case WorkspaceScope.User =>
        workspaceRoot.resolve(s"user-${ctx.actorId.value}")
    }

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    val root = scopedRoot(ctx)
    tool match {
      case "workspace.read"     => workspaceRead(root, args)
      case "workspace.write"    => workspaceWrite(root, args)
      case "workspace.search"   => workspaceSearch(root, args)
      case "workspace.delete"   => workspaceDelete(root, args)
      case "workspace.snapshot" => workspaceSnapshot(root, args)
      case other                => ZIO.fail(JorlanError(s"WorkspaceSkill: unknown tool '$other'"))
    }
  }

  /** Resolve and validate a relative path against the given root. Rejects traversal attempts. */
  private def safePath(
    root:     Path,
    relative: String,
  ): IO[JorlanError, Path] =
    ZIO
      .attempt(root.resolve(relative).normalize())
      .mapError(e => JorlanError(s"workspace: invalid path: ${e.getMessage}"))
      .flatMap { resolved =>
        if (!resolved.startsWith(root))
          ZIO.fail(JorlanError("workspace: path is outside workspace root"))
        else
          ZIO.succeed(resolved)
      }

  private def workspaceRead(
    root: Path,
    args: Json,
  ): IO[JorlanError, Json] =
    SkillArgs.str(args, "path") match {
      case None      => ZIO.fail(JorlanError("workspace.read: path is required"))
      case Some(rel) =>
        for {
          p       <- safePath(root, rel)
          content <- ZIO
            .attemptBlocking(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
            .mapError(e => JorlanError(s"workspace.read: ${e.getMessage}"))
        } yield Json.Str(content)
    }

  private def workspaceWrite(
    root: Path,
    args: Json,
  ): IO[JorlanError, Json] = {
    val pathOpt = SkillArgs.str(args, "path")
    val contentOpt = SkillArgs.str(args, "content")
    (pathOpt, contentOpt) match {
      case (None, _)                  => ZIO.fail(JorlanError("workspace.write: path is required"))
      case (_, None)                  => ZIO.fail(JorlanError("workspace.write: content is required"))
      case (Some(rel), Some(content)) =>
        for {
          p <- safePath(root, rel)
          _ <- ZIO
            .attemptBlocking {
              Option(p.getParent).foreach(parent => Files.createDirectories(parent))
              Files.write(p, content.getBytes(StandardCharsets.UTF_8))
            }.mapError(e => JorlanError(s"workspace.write: ${e.getMessage}"))
        } yield Json.Str("ok")
    }
  }

  private def workspaceSearch(
    root: Path,
    args: Json,
  ): IO[JorlanError, Json] = {
    val prefix = SkillArgs.str(args, "prefix").getOrElse("")
    for {
      baseDir <-
        if (prefix.isEmpty) ZIO.succeed(root)
        else
          safePath(root, prefix).flatMap { p =>
            ZIO
              .attemptBlocking(if (Files.isDirectory(p)) p else Option(p.getParent).getOrElse(root))
              .mapError(e => JorlanError(s"workspace.search: ${e.getMessage}"))
          }
      files <- ZIO
        .attemptBlocking {
          if (!Files.exists(baseDir)) List.empty[String]
          else {
            val stream = Files.walk(baseDir)
            try {
              stream
                .filter(p => Files.isRegularFile(p))
                .map(p => root.relativize(p).toString)
                .toArray
                .toList
                .collect { case s: String => s }
            } finally stream.close()
          }
        }.mapError(e => JorlanError(s"workspace.search: ${e.getMessage}"))
    } yield Json.Arr(files.map(Json.Str(_))*)
  }

  private def workspaceDelete(
    root: Path,
    args: Json,
  ): IO[JorlanError, Json] =
    SkillArgs.str(args, "path") match {
      case None      => ZIO.fail(JorlanError("workspace.delete: path is required"))
      case Some(rel) =>
        for {
          p <- safePath(root, rel)
          _ <- ZIO
            .attemptBlocking(Files.deleteIfExists(p))
            .mapError(e => JorlanError(s"workspace.delete: ${e.getMessage}"))
        } yield Json.Str("ok")
    }

  private def workspaceSnapshot(
    root: Path,
    args: Json,
  ): IO[JorlanError, Json] = {
    import java.time.Instant
    val tag = SkillArgs.str(args, "tag").filter(_.nonEmpty).getOrElse(Instant.now().toString.replace(':', '-'))
    val snapshotDir = root.resolve("__snapshots")
    val zipName = s"$tag.zip"
    val zipPath = snapshotDir.resolve(zipName)
    ZIO
      .attemptBlocking {
        Files.createDirectories(snapshotDir)
        val zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipPath.toFile)))
        try {
          val stream = Files.walk(root)
          try {
            stream
              .filter(p => Files.isRegularFile(p) && !p.startsWith(snapshotDir))
              .forEach { p =>
                val entryName = root.relativize(p).toString
                zos.putNextEntry(new ZipEntry(entryName))
                Files.copy(p, zos)
                zos.closeEntry()
              }
          } finally stream.close()
        } finally zos.close()
        s"__snapshots/$zipName"
      }
      .mapError(e => JorlanError(s"workspace.snapshot: ${e.getMessage}"))
      .map(Json.Str(_))
  }

}

object WorkspaceSkill
