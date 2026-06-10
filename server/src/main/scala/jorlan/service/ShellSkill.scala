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
import zio.process.Command

import java.nio.file.Paths

/** Built-in skill for executing shell commands from a configured allowlist.
  *
  * The binary must appear in [[ShellSettings.allowedBinaries]] (exact match on the last path segment or the full
  * value). Attempts to execute unlisted binaries return an error without any execution.
  *
  * Tool:
  *   - `shell.run` — execute a binary with arguments, optional cwd and timeout override
  */
class ShellSkill(settings: ShellSettings) extends Skill {

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
      case "shell.run" => shellRun(args)
      case other       => ZIO.fail(JorlanError(s"ShellSkill: unknown tool '$other'"))
    }

  private def getStr(
    args: Json,
    key:  String,
  ): Option[String] =
    args match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  private def getStrList(
    args: Json,
    key:  String,
  ): List[String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`key`, Json.Arr(elems)) => elems.collect { case Json.Str(s) => s }.toList }
          .getOrElse(Nil)
      case _ => Nil
    }

  private def getInt(
    args: Json,
    key:  String,
  ): Option[Int] =
    args match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Num(n)) => n.intValue }
      case _                => None
    }

  private def isAllowed(binary: String): Boolean = {
    val binaryName = Paths.get(binary).getFileName.toString
    settings.allowedBinaries.exists { allowed =>
      allowed == binary || allowed == binaryName
    }
  }

  private def shellRun(args: Json): IO[JorlanError, Json] = {
    val binaryOpt = getStr(args, "binary")
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
          val cmdArgs = getStrList(args, "args")
          val cwdOpt = getStr(args, "cwd")
          val timeoutSecs = getInt(args, "timeoutSeconds").getOrElse(settings.timeoutSeconds)

          val baseCmd = Command(binary, cmdArgs*)
          val cmdWithCwd = cwdOpt.fold(baseCmd)(cwd => baseCmd.workingDirectory(Paths.get(cwd).toFile))

          cmdWithCwd.run
            .flatMap { process =>
              for {
                stdout   <- process.stdout.string
                stderr   <- process.stderr.string
                exitCode <- process.exitCode
              } yield Json.Obj(
                "exitCode" -> Json.Num(exitCode.code),
                "stdout"   -> Json.Str(stdout),
                "stderr"   -> Json.Str(stderr),
              )
            }
            .timeout(timeoutSecs.seconds)
            .map {
              case Some(result) => result
              case None         =>
                Json.Obj(
                  "exitCode" -> Json.Num(-1),
                  "stdout"   -> Json.Str(""),
                  "stderr"   -> Json.Str(s"Error: command timed out after ${timeoutSecs}s"),
                )
            }
            .mapError(e => JorlanError(s"shell.run: ${e.getMessage}"))
        }
    }
  }

}

object ShellSkill {

  val live: ZLayer[ShellSettings, Nothing, ShellSkill] =
    ZLayer.fromFunction(new ShellSkill(_))

}
