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
import zio.*
import zio.json.ast.Json
import zio.process.Command

import java.net.URI
import java.nio.file.{Path, Paths}
import scala.language.unsafeNulls

/** Built-in skill for executing shell commands from a configured allowlist, plus a set of pre-configured, sandboxed
  * read-only file inspection tools that require no admin allowlist configuration.
  *
  * The binary must appear in [[ShellSettings.allowedBinaries]] (exact match on the last path segment or the full value)
  * for `shell.run`. The read-only tools (`shell.ls`, `shell.cat`, `shell.grep`, `shell.find`, `shell.head`,
  * `shell.tail`, `shell.wc`) are sandboxed to [[ShellSettings.sandboxRoot]] and require only the `shell.read`
  * capability.
  *
  * Tools:
  *   - `shell.run` — execute a binary with arguments, optional cwd and timeout override
  *   - `shell.ls` — list directory contents within sandbox
  *   - `shell.cat` — read file contents within sandbox
  *   - `shell.grep` — search for a pattern in a file or directory tree within sandbox
  *   - `shell.find` — find files/directories matching criteria within sandbox
  *   - `shell.head` — return first N lines of a file within sandbox
  *   - `shell.tail` — return last N lines of a file within sandbox
  *   - `shell.wc` — word/line/char count of a file within sandbox
  */
class ShellSkill(
  settings: ShellSettings,
  repo:     ZIORepositories,
) extends Skill {

  // Resolved once at construction; used by all sandbox tools.
  private val sandboxRoot: Path = Paths.get(settings.sandboxRoot).toAbsolutePath.normalize()

  private val MaxSafeOutputBytes: Int = 65536 // 64 KB

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "shell",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "shell.run",
        description = "Execute an allowed shell command. The binary must be in the server-configured allowlist.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"binary":{"type":"string","description":"Binary name or absolute path to execute"},"args":{"type":"array","items":{"type":"string"},"description":"Arguments to pass to the binary"},"cwd":{"type":"string","description":"Working directory (optional)"},"timeoutSeconds":{"type":"integer","description":"Timeout in seconds (optional, default from server config)"}},"required":["binary"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.execute")),
        examplePrompts = List(
          "Run ls -la in /tmp",
          "Execute git status in the project directory",
          "Check disk usage with df -h",
        ),
      ),
      ToolDescriptor(
        name = "shell.ls",
        description = "List directory contents within the sandbox root. No admin configuration required.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Path relative to sandbox root (optional, defaults to sandbox root)"},"all":{"type":"boolean","description":"Show hidden files (equivalent to -a)"},"long":{"type":"boolean","description":"Long listing format (equivalent to -l)"}}}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.read")),
        examplePrompts = List(
          "List files in the workspace",
          "Show all files including hidden ones",
        ),
      ),
      ToolDescriptor(
        name = "shell.cat",
        description = "Read file contents within the sandbox root. No admin configuration required.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Path to the file relative to sandbox root"},"maxLines":{"type":"integer","description":"Maximum number of lines to return (default 500)"}},"required":["path"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.read")),
        examplePrompts = List(
          "Show the contents of README.md",
          "Read the config file",
        ),
      ),
      ToolDescriptor(
        name = "shell.grep",
        description = "Search for a pattern in files within the sandbox root. No admin configuration required.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression pattern to search for"},"path":{"type":"string","description":"File or directory path relative to sandbox root"},"recursive":{"type":"boolean","description":"Search recursively (equivalent to -r)"},"caseInsensitive":{"type":"boolean","description":"Case-insensitive search (equivalent to -i)"},"maxMatches":{"type":"integer","description":"Maximum number of matching lines to return (default 200)"}},"required":["pattern","path"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.read")),
        examplePrompts = List(
          "Search for TODO comments in the source tree",
          "Find all uses of a function name",
        ),
      ),
      ToolDescriptor(
        name = "shell.find",
        description = "Find files or directories within the sandbox root. No admin configuration required.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Starting path relative to sandbox root (optional, defaults to sandbox root)"},"name":{"type":"string","description":"Filename pattern (glob, e.g. '*.scala')"},"type_":{"type":"string","description":"Type filter: 'f' for files, 'd' for directories"},"maxResults":{"type":"integer","description":"Maximum number of results to return (default 200)"}}}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.read")),
        examplePrompts = List(
          "Find all Scala files in the project",
          "List all directories under src",
        ),
      ),
      ToolDescriptor(
        name = "shell.head",
        description = "Return the first N lines of a file within the sandbox root. No admin configuration required.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Path to the file relative to sandbox root"},"lines":{"type":"integer","description":"Number of lines to return (default 20)"}},"required":["path"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.read")),
        examplePrompts = List(
          "Show the first 20 lines of a log file",
          "Preview the top of a CSV file",
        ),
      ),
      ToolDescriptor(
        name = "shell.tail",
        description = "Return the last N lines of a file within the sandbox root. No admin configuration required.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Path to the file relative to sandbox root"},"lines":{"type":"integer","description":"Number of lines to return (default 20)"}},"required":["path"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.read")),
        examplePrompts = List(
          "Show the last 20 lines of a log file",
          "Check the most recent entries in a log",
        ),
      ),
      ToolDescriptor(
        name = "shell.wc",
        description =
          "Count lines, words, or characters in a file within the sandbox root. No admin configuration required.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"path":{"type":"string","description":"Path to the file relative to sandbox root"},"lines":{"type":"boolean","description":"Count lines (equivalent to -l)"},"words":{"type":"boolean","description":"Count words (equivalent to -w)"},"chars":{"type":"boolean","description":"Count characters (equivalent to -c)"}},"required":["path"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("shell.read")),
        examplePrompts = List(
          "How many lines does this file have?",
          "Count the words in a document",
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
      case "shell.run"  => shellRun(ctx, args)
      case "shell.ls"   => shellLs(args)
      case "shell.cat"  => shellCat(args)
      case "shell.grep" => shellGrep(args)
      case "shell.find" => shellFind(args)
      case "shell.head" => shellHead(args)
      case "shell.tail" => shellTail(args)
      case "shell.wc"   => shellWc(args)
      case other        => ZIO.fail(JorlanError(s"ShellSkill: unknown tool '$other'"))
    }

  // ─── shell.run helpers ────────────────────────────────────────────────────

  private def isAllowed(binary: String): Boolean = {
    val binaryName = Paths.get(binary).getFileName.toString
    settings.allowedBinaries.exists { allowed =>
      allowed == binary || allowed == binaryName
    }
  }

  private def logShellEvent(
    ctx:         InvocationContext,
    eventType:   EventType,
    payloadJson: Json,
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
            payloadJson = Some(payloadJson),
            occurredAt = now,
          ),
        ).orDie.unit
    }

  private def captureArtifact(
    ctx:    InvocationContext,
    binary: String,
    stdout: String,
  ): UIO[Option[String]] =
    if (stdout.length <= settings.captureThreshold) ZIO.none
    else
      Clock.instant.flatMap { now =>
        val artifact = Artifact(
          id = ArtifactId.empty,
          workspaceId = ctx.workspaceId,
          sessionId = ctx.sessionId,
          name = s"shell-output-${binary.replace('/', '_')}-${now.toEpochMilli}.txt",
          mimeType = zio.http.MediaType.text.plain,
          sizeBytes = stdout.length.toLong,
          storageUri = URI.create(s"artifact://inline/${now.toEpochMilli}"),
          metadataJson = Some(Json.Obj("binary" -> Json.Str(binary), "bytes" -> Json.Num(stdout.length))),
          createdAt = now,
        )
        repo.artifact.upsert(artifact).orDie.map(a => Some(a.storageUri.toString))
      }

  private def shellRun(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val binaryOpt = SkillArgs.str(args, "binary")
    binaryOpt match {
      case None         => ZIO.fail(JorlanError("shell.run: binary is required"))
      case Some(binary) =>
        if (!isAllowed(binary)) {
          ZIO.succeed(
            Json.Obj(
              "exitCode" -> Json.Num(1),
              "stdout"   -> Json.Str(""),
              "stderr"   -> Json.Str(s"Error: binary '$binary' is not in the allowed list"),
            ),
          )
        } else {
          val cmdArgs = SkillArgs.strList(args, "args")
          val cwdOpt = SkillArgs.str(args, "cwd")
          val timeoutSecs = SkillArgs.int(args, "timeoutSeconds").getOrElse(settings.timeoutSeconds)

          val baseCmd = Command(binary, cmdArgs*)
          val cmdWithCwd = cwdOpt.fold(baseCmd)(cwd => baseCmd.workingDirectory(Paths.get(cwd).toFile))

          for {
            startTime <- Clock.instant
            _         <- logShellEvent(
              ctx,
              EventType.ShellCommandInvoked,
              Json.Obj("binary" -> Json.Str(binary), "args" -> Json.Arr(cmdArgs.map(Json.Str(_))*)),
            )
            rawResult <- cmdWithCwd.run
              .flatMap { process =>
                for {
                  stdout   <- process.stdout.string
                  stderr   <- process.stderr.string
                  exitCode <- process.exitCode
                } yield (exitCode.code, stdout, stderr)
              }
              .timeout(timeoutSecs.seconds)
              .mapError(e => JorlanError(s"shell.run: ${e.getMessage}"))
            (exitCode, stdout, stderr) = rawResult match {
              case Some(t) => t
              case None    => (-1, "", s"Error: command timed out after ${timeoutSecs}s")
            }
            endTime <- Clock.instant
            durationMs = endTime.toEpochMilli - startTime.toEpochMilli
            artifactUri <- captureArtifact(ctx, binary, stdout)
            _           <- logShellEvent(
              ctx,
              EventType.ShellCommandCompleted,
              Json.Obj(
                "binary"     -> Json.Str(binary),
                "exitCode"   -> Json.Num(exitCode),
                "durationMs" -> Json.Num(durationMs),
                "captured"   -> Json.Bool(artifactUri.isDefined),
              ),
            )
            stdoutField = artifactUri.fold[Json](Json.Str(stdout))(uri => Json.Str(s"artifact:$uri"))
          } yield Json.Obj(
            "exitCode" -> Json.Num(exitCode),
            "stdout"   -> stdoutField,
            "stderr"   -> Json.Str(stderr),
          )
        }
    }
  }

  // ─── sandbox helpers ──────────────────────────────────────────────────────

  /** Resolve a relative-or-absolute path against sandboxRoot, rejecting traversal. */
  private def resolveSafePath(relOrAbs: String): IO[JorlanError, Path] =
    ZIO
      .attempt(sandboxRoot.resolve(relOrAbs).normalize())
      .mapError(e => JorlanError(s"shell: invalid path: ${e.getMessage}"))
      .flatMap { resolved =>
        for {
          rootReal <- ZIO.attemptBlocking(sandboxRoot.toRealPath()).orElseSucceed(sandboxRoot)
          real     <- ZIO.attemptBlocking(resolved.toRealPath()).orElseSucceed(resolved)
          _        <- ZIO.fail(JorlanError(s"Path traversal rejected: '$relOrAbs'")).unless(real.startsWith(rootReal))
        } yield resolved
      }

  /** Run a command (binary + args), capture its stdout+stderr, apply the 64 KB cap. */
  private def runSafeCommand(
    binary:  String,
    cmdArgs: Seq[String],
  ): IO[JorlanError, Json] = {
    val cmd = Command(binary, cmdArgs*)
    cmd.run
      .flatMap { process =>
        val limit = (MaxSafeOutputBytes + 1).toLong
        for {
          stdoutChunk <- process.stdout.stream.take(limit).runCollect
          stderrChunk <- process.stderr.stream.take(limit).runCollect
          exitCode    <- process.exitCode
        } yield (
          exitCode.code,
          truncateOutput(new String(stdoutChunk.toArray, java.nio.charset.StandardCharsets.UTF_8)),
          truncateOutput(new String(stderrChunk.toArray, java.nio.charset.StandardCharsets.UTF_8)),
        )
      }
      .timeout(10.seconds).mapBoth(
        e => JorlanError(s"$binary: ${e.getMessage}"),
        {
          case Some((code, out, err)) =>
            val truncOut = truncateOutput(out)
            Json.Obj(
              "exitCode" -> Json.Num(code),
              "stdout"   -> Json.Str(truncOut),
              "stderr"   -> Json.Str(err),
            )
          case None =>
            Json.Obj(
              "exitCode" -> Json.Num(-1),
              "stdout"   -> Json.Str(""),
              "stderr"   -> Json.Str("Error: command timed out after 10s"),
            )
        },
      )
  }

  private def truncateOutput(s: String): String =
    if (s.length <= MaxSafeOutputBytes) s
    else s.take(MaxSafeOutputBytes) + "\n[output truncated at 64 KB]"

  // ─── individual safe tools ────────────────────────────────────────────────

  private def shellLs(args: Json): IO[JorlanError, Json] = {
    val pathStr = SkillArgs.str(args, "path").getOrElse(".")
    val all = SkillArgs.bool(args, "all").getOrElse(false)
    val long = SkillArgs.bool(args, "long").getOrElse(false)
    for {
      resolved <- resolveSafePath(pathStr)
      flags = (if (all) "a" else "") + (if (long) "l" else "")
      cmdArgs = if (flags.nonEmpty) List(s"-$flags", resolved.toString) else List(resolved.toString)
      result <- runSafeCommand("ls", cmdArgs)
    } yield result
  }

  private def shellCat(args: Json): IO[JorlanError, Json] = {
    val maxLines = SkillArgs.int(args, "maxLines").getOrElse(500)
    SkillArgs.str(args, "path") match {
      case None       => ZIO.fail(JorlanError("shell.cat: path is required"))
      case Some(path) =>
        for {
          resolved <- resolveSafePath(path)
          result   <- runSafeCommand("cat", List(resolved.toString))
          limited = result match {
            case Json.Obj(fields) =>
              val newFields = fields.map {
                case ("stdout", Json.Str(s)) =>
                  val lines = s.linesWithSeparators.toVector
                  val out =
                    if (lines.length <= maxLines) s
                    else lines.take(maxLines).mkString + s"\n[output truncated at $maxLines lines]"
                  "stdout" -> Json.Str(out)
                case other => other
              }
              Json.Obj(newFields*)
            case other => other
          }
        } yield limited
    }
  }

  private def shellGrep(args: Json): IO[JorlanError, Json] = {
    val maxMatches = SkillArgs.int(args, "maxMatches").getOrElse(200)
    (SkillArgs.str(args, "pattern"), SkillArgs.str(args, "path")) match {
      case (None, _)                   => ZIO.fail(JorlanError("shell.grep: pattern is required"))
      case (_, None)                   => ZIO.fail(JorlanError("shell.grep: path is required"))
      case (Some(pattern), Some(path)) =>
        val recursive = SkillArgs.bool(args, "recursive").getOrElse(false)
        val caseInsensitive = SkillArgs.bool(args, "caseInsensitive").getOrElse(false)
        for {
          resolved <- resolveSafePath(path)
          flags = List(
            if (recursive) Some("-r") else None,
            if (caseInsensitive) Some("-i") else None,
          ).flatten
          cmdArgs = flags ++ List(pattern, resolved.toString)
          result <- runSafeCommand("grep", cmdArgs)
          limited = result match {
            case Json.Obj(fields) =>
              val newFields = fields.map {
                case ("stdout", Json.Str(s)) =>
                  val lines = s.linesWithSeparators.toVector
                  val out =
                    if (lines.length <= maxMatches) s
                    else lines.take(maxMatches).mkString + s"\n[output truncated at $maxMatches matches]"
                  "stdout" -> Json.Str(out)
                case other => other
              }
              Json.Obj(newFields*)
            case other => other
          }
        } yield limited
    }
  }

  private def shellFind(args: Json): IO[JorlanError, Json] = {
    val maxResults = SkillArgs.int(args, "maxResults").getOrElse(200)
    val pathStr = SkillArgs.str(args, "path").getOrElse(".")
    for {
      resolved <- resolveSafePath(pathStr)
      nameOpt = SkillArgs.str(args, "name")
      typeOpt = SkillArgs.str(args, "type_")
      nameArgs = nameOpt.toList.flatMap(n => List("-name", n))
      typeArgs = typeOpt.toList.flatMap(t => List("-type", t))
      cmdArgs = List(resolved.toString) ++ nameArgs ++ typeArgs
      result <- runSafeCommand("find", cmdArgs)
      limited = result match {
        case Json.Obj(fields) =>
          val newFields = fields.map {
            case ("stdout", Json.Str(s)) =>
              val lines = s.linesWithSeparators.toVector
              val out =
                if (lines.length <= maxResults) s
                else lines.take(maxResults).mkString + s"\n[output truncated at $maxResults results]"
              "stdout" -> Json.Str(out)
            case other => other
          }
          Json.Obj(newFields*)
        case other => other
      }
    } yield limited
  }

  private def shellHead(args: Json): IO[JorlanError, Json] =
    SkillArgs.str(args, "path") match {
      case None       => ZIO.fail(JorlanError("shell.head: path is required"))
      case Some(path) =>
        val lines = SkillArgs.int(args, "lines").getOrElse(20)
        for {
          resolved <- resolveSafePath(path)
          result   <- runSafeCommand("head", List("-n", lines.toString, resolved.toString))
        } yield result
    }

  private def shellTail(args: Json): IO[JorlanError, Json] =
    SkillArgs.str(args, "path") match {
      case None       => ZIO.fail(JorlanError("shell.tail: path is required"))
      case Some(path) =>
        val lines = SkillArgs.int(args, "lines").getOrElse(20)
        for {
          resolved <- resolveSafePath(path)
          result   <- runSafeCommand("tail", List("-n", lines.toString, resolved.toString))
        } yield result
    }

  private def shellWc(args: Json): IO[JorlanError, Json] =
    SkillArgs.str(args, "path") match {
      case None       => ZIO.fail(JorlanError("shell.wc: path is required"))
      case Some(path) =>
        val countLines = SkillArgs.bool(args, "lines").getOrElse(false)
        val countWords = SkillArgs.bool(args, "words").getOrElse(false)
        val countChars = SkillArgs.bool(args, "chars").getOrElse(false)
        for {
          resolved <- resolveSafePath(path)
          flags = List(
            if (countLines) Some("-l") else None,
            if (countWords) Some("-w") else None,
            if (countChars) Some("-c") else None,
          ).flatten
          cmdArgs = flags :+ resolved.toString
          result <- runSafeCommand("wc", cmdArgs)
        } yield result
    }

}

object ShellSkill
