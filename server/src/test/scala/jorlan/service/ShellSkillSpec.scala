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
import jorlan.connector.InvocationContext
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.*

object ShellSkillSpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val defaultSettings = ShellSettings(
    allowedBinaries = List("echo", "ls", "cat", "grep", "find", "pwd", "sleep"),
    timeoutSeconds = 5,
  )

  private val skill = new ShellSkill(defaultSettings)

  private def mkArgs(
    binary: String,
    args:   List[String] = Nil,
    cwd:    Option[String] = None,
  ): Json = {
    val base: List[(String, Json)] = List(
      "binary" -> Json.Str(binary),
      "args"   -> Json.Arr(args.map(Json.Str(_))*),
    )
    val withCwd = cwd.fold(base)(c => base :+ ("cwd" -> Json.Str(c)))
    Json.Obj(withCwd*)
  }

  private def exitCode(result: Json): Option[Int] =
    result match {
      case Json.Obj(fields) => fields.collectFirst { case ("exitCode", Json.Num(n)) => n.intValue }
      case _                => None
    }

  private def stdoutOf(result: Json): Option[String] =
    result match {
      case Json.Obj(fields) => fields.collectFirst { case ("stdout", Json.Str(s)) => s }
      case _                => None
    }

  private def stderrOf(result: Json): Option[String] =
    result match {
      case Json.Obj(fields) => fields.collectFirst { case ("stderr", Json.Str(s)) => s }
      case _                => None
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ShellSkill")(
      // ─── allowed binary executes successfully ──────────────────────────────────
      test("echo with args returns stdout") {
        for {
          result <- skill.invoke(ctx, "shell.run", mkArgs("echo", List("hello", "world")))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(_.trim == "hello world"),
        )
      } @@ withLiveClock,
      test("echo with no args returns exit code 0") {
        for {
          result <- skill.invoke(ctx, "shell.run", mkArgs("echo"))
        } yield assertTrue(
          exitCode(result).contains(0),
        )
      } @@ withLiveClock,
      test("pwd returns a non-empty path") {
        for {
          result <- skill.invoke(ctx, "shell.run", mkArgs("pwd"))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(_.trim.nonEmpty),
        )
      } @@ withLiveClock,
      // ─── blocked binary ────────────────────────────────────────────────────────
      test("blocked binary returns exitCode=1 and error in stderr") {
        for {
          result <- skill.invoke(ctx, "shell.run", mkArgs("rm"))
        } yield assertTrue(
          exitCode(result).contains(1),
          stderrOf(result).exists(_.contains("not in the allowed list")),
        )
      },
      test("blocked absolute-path binary is rejected") {
        for {
          result <- skill.invoke(ctx, "shell.run", mkArgs("/bin/rm"))
        } yield assertTrue(
          exitCode(result).contains(1),
          stderrOf(result).exists(_.contains("not in the allowed list")),
        )
      },
      test("allowed binary via absolute path executes when full path is in allowlist") {
        val skillWithAbsolute = new ShellSkill(ShellSettings(allowedBinaries = List("/bin/echo"), timeoutSeconds = 5))
        for {
          result <- skillWithAbsolute.invoke(ctx, "shell.run", mkArgs("/bin/echo", List("abs")))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(_.trim == "abs"),
        )
      } @@ withLiveClock,
      // ─── missing required field ────────────────────────────────────────────────
      test("missing binary field returns JorlanError") {
        for {
          result <- skill.invoke(ctx, "shell.run", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      // ─── unknown tool ──────────────────────────────────────────────────────────
      test("unknown tool name returns JorlanError") {
        for {
          result <- skill.invoke(ctx, "shell.launch", mkArgs("echo")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── timeout ──────────────────────────────────────────────────────────────
      test("command exceeding timeout returns exit code -1 with timeout message") {
        val slowSettings = ShellSettings(allowedBinaries = List("sleep"), timeoutSeconds = 1)
        val slowSkill = new ShellSkill(slowSettings)
        for {
          result <- slowSkill.invoke(
            ctx,
            "shell.run",
            Json.Obj(
              "binary" -> Json.Str("sleep"),
              "args"   -> Json.Arr(Json.Str("10")),
            ),
          )
        } yield assertTrue(
          exitCode(result).contains(-1),
          stderrOf(result).exists(_.contains("timed out")),
        )
      } @@ withLiveClock @@ timeout(10.seconds),
      // ─── cwd override ──────────────────────────────────────────────────────────
      test("cwd override changes working directory") {
        for {
          result <- skill.invoke(ctx, "shell.run", mkArgs("pwd", Nil, Some("/tmp")))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(s => s.trim == "/tmp" || s.trim.startsWith("/tmp")),
        )
      } @@ withLiveClock,
      // ─── result structure ─────────────────────────────────────────────────────
      test("result always has exitCode, stdout, stderr fields") {
        for {
          result <- skill.invoke(ctx, "shell.run", mkArgs("echo", List("test")))
        } yield result match {
          case Json.Obj(fields) =>
            val keys = fields.map(_._1).toSet
            assertTrue(
              keys.contains("exitCode"),
              keys.contains("stdout"),
              keys.contains("stderr"),
            )
          case _ => assertTrue(false)
        }
      } @@ withLiveClock,
      // ─── custom numeric timeoutSeconds (covers getInt success branch) ──────────
      test("shell.run with custom timeoutSeconds succeeds") {
        for {
          result <- skill.invoke(
            ctx,
            "shell.run",
            Json.Obj(
              "binary"         -> Json.Str("echo"),
              "args"           -> Json.Arr(Json.Str("hi")),
              "timeoutSeconds" -> Json.Num(3),
            ),
          )
        } yield assertTrue(exitCode(result).contains(0))
      } @@ withLiveClock,
    )

}
