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
import jorlan.db.repository.ZIORepositories
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.process.Command

import java.net.URI
import java.nio.file.Paths

/** Built-in skill for executing shell commands from a configured allowlist.
  *
  * The binary must appear in [[ShellSettings.allowedBinaries]] (exact match on the last path segment or the full
  * value). Attempts to execute unlisted binaries return an error without any execution.
  *
  * Tool:
  *   - `shell.run` — execute a binary with arguments, optional cwd and timeout override
  */
class ShellSkill(
  settings: ShellSettings,
  repo:     ZIORepositories,
) extends Skill {

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
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "shell.run" => shellRun(ctx, args)
      case other       => ZIO.fail(JorlanError(s"ShellSkill: unknown tool '$other'"))
    }

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

}

object ShellSkill
