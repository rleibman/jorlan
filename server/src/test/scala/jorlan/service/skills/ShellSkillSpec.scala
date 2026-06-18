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
import jorlan.connector.InvocationContext
import jorlan.db.repository.ZIORepositories
import jorlan.service.skills.ShellSkill
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.*

import java.nio.file.{Files, Path}
import scala.language.unsafeNulls

object ShellSkillSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val defaultSettings = ShellSettings(
    allowedBinaries = List("echo", "ls", "cat", "grep", "find", "pwd", "sleep"),
    timeoutSeconds = 5,
  )

  private def safeMkArgs(
    tool:  String,
    extra: (String, Json)*,
  ): Json =
    Json.Obj(extra*)

  private def stdoutField(result: Json): Option[String] =
    result match {
      case Json.Obj(fields) => fields.collectFirst { case ("stdout", Json.Str(s)) => s }
      case _                => None
    }

  private def makeSkill(settings: ShellSettings = defaultSettings): URIO[ZIORepositories, ShellSkill] =
    ZIO.serviceWith[ZIORepositories](new ShellSkill(settings, _))

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

  /** A scoped temporary directory used as sandboxRoot for the safe-tool tests. */
  private def withTempSandbox[R, E, A](f: (Path, ShellSkill) => ZIO[R & ZIORepositories, E, A])
    : ZIO[R & ZIORepositories & Scope, E, A] =
    ZIO
      .acquireRelease(
        ZIO.attemptBlocking(Files.createTempDirectory("shellskill-sandbox")).orDie,
      )(dir => ZIO.attemptBlocking(deleteRecursively(dir)).orDie).flatMap { dir =>
        ZIO.serviceWith[ZIORepositories](new ShellSkill(defaultSettings.copy(sandboxRoot = dir.toString), _)).flatMap {
          skill =>
            f(dir, skill)
        }
      }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try
        stream.forEach(deleteRecursively)
      finally
        stream.close()
    }
    Files.deleteIfExists(path)
    ()
  }

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("ShellSkill")(
      // ─── allowed binary executes successfully ──────────────────────────────────
      test("echo with args returns stdout") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.run", mkArgs("echo", List("hello", "world")))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(_.trim == "hello world"),
        )
      } @@ withLiveClock,
      test("echo with no args returns exit code 0") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.run", mkArgs("echo"))
        } yield assertTrue(
          exitCode(result).contains(0),
        )
      } @@ withLiveClock,
      test("pwd returns a non-empty path") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.run", mkArgs("pwd"))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(_.trim.nonEmpty),
        )
      } @@ withLiveClock,
      // ─── blocked binary ────────────────────────────────────────────────────────
      test("blocked binary returns exitCode=1 and error in stderr") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.run", mkArgs("rm"))
        } yield assertTrue(
          exitCode(result).contains(1),
          stderrOf(result).exists(_.contains("not in the allowed list")),
        )
      },
      test("blocked absolute-path binary is rejected") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.run", mkArgs("/bin/rm"))
        } yield assertTrue(
          exitCode(result).contains(1),
          stderrOf(result).exists(_.contains("not in the allowed list")),
        )
      },
      test("allowed binary via absolute path executes when full path is in allowlist") {
        for {
          skill  <- makeSkill(ShellSettings(allowedBinaries = List("/bin/echo"), timeoutSeconds = 5))
          result <- skill.invoke(ctx, "shell.run", mkArgs("/bin/echo", List("abs")))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(_.trim == "abs"),
        )
      } @@ withLiveClock,
      // ─── missing required field ────────────────────────────────────────────────
      test("missing binary field returns JorlanError") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.run", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      // ─── unknown tool ──────────────────────────────────────────────────────────
      test("unknown tool name returns JorlanError") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.launch", mkArgs("echo")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── timeout ──────────────────────────────────────────────────────────────
      test("command exceeding timeout returns exit code -1 with timeout message") {
        val slowSettings = ShellSettings(allowedBinaries = List("sleep"), timeoutSeconds = 1)
        for {
          skill  <- makeSkill(slowSettings)
          result <- skill.invoke(
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
          skill  <- makeSkill()
          result <- skill.invoke(ctx, "shell.run", mkArgs("pwd", Nil, Some("/tmp")))
        } yield assertTrue(
          exitCode(result).contains(0),
          stdoutOf(result).exists(s => s.trim == "/tmp" || s.trim.startsWith("/tmp")),
        )
      } @@ withLiveClock,
      // ─── result structure ─────────────────────────────────────────────────────
      test("result always has exitCode, stdout, stderr fields") {
        for {
          skill  <- makeSkill()
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
          skill  <- makeSkill()
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
    ) +
      suite("ShellSkill — safe sandbox tools")(
        // ─── shell.ls ─────────────────────────────────────────────────────────
        test("shell.ls on sandbox root returns output") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                ZIO.attemptBlocking(Files.writeString(dir.resolve("hello.txt"), "hello")).orDie *>
                  skill.invoke(ctx, "shell.ls", Json.Obj()).map { result =>
                    assertTrue(
                      exitCode(result).contains(0),
                      stdoutOf(result).exists(_.contains("hello.txt")),
                    )
                  }
            }
          }
        } @@ withLiveClock,
        test("shell.ls with all=true includes hidden files") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                ZIO.attemptBlocking(Files.writeString(dir.resolve(".hidden"), "secret")).orDie *>
                  skill.invoke(ctx, "shell.ls", Json.Obj("all" -> Json.Bool(true))).map { result =>
                    assertTrue(
                      exitCode(result).contains(0),
                      stdoutOf(result).exists(_.contains(".hidden")),
                    )
                  }
            }
          }
        } @@ withLiveClock,
        // ─── shell.cat ────────────────────────────────────────────────────────
        test("shell.cat on an existing file returns contents") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                ZIO.attemptBlocking(Files.writeString(dir.resolve("test.txt"), "line one\nline two\n")).orDie *>
                  skill.invoke(ctx, "shell.cat", Json.Obj("path" -> Json.Str("test.txt"))).map { result =>
                    assertTrue(
                      exitCode(result).contains(0),
                      stdoutOf(result).exists(_.contains("line one")),
                    )
                  }
            }
          }
        } @@ withLiveClock,
        test("shell.cat respects maxLines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                val content = (1 to 10).map(i => s"line $i").mkString("\n")
                ZIO.attemptBlocking(Files.writeString(dir.resolve("many.txt"), content)).orDie *>
                  skill
                    .invoke(
                      ctx,
                      "shell.cat",
                      Json.Obj("path" -> Json.Str("many.txt"), "maxLines" -> Json.Num(3)),
                    ).map { result =>
                      assertTrue(
                        stdoutOf(result).exists(_.contains("[output truncated at 3 lines]")),
                      )
                    }
            }
          }
        } @@ withLiveClock,
        // ─── shell.grep ───────────────────────────────────────────────────────
        test("shell.grep finds matching lines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                ZIO.attemptBlocking(Files.writeString(dir.resolve("data.txt"), "apple\nbanana\napricot\n")).orDie *>
                  skill
                    .invoke(
                      ctx,
                      "shell.grep",
                      Json.Obj("pattern" -> Json.Str("ap"), "path" -> Json.Str("data.txt")),
                    ).map { result =>
                      assertTrue(
                        exitCode(result).contains(0),
                        stdoutOf(result).exists(_.contains("apple")),
                        stdoutOf(result).exists(_.contains("apricot")),
                        stdoutOf(result).exists(!_.contains("banana")),
                      )
                    }
            }
          }
        } @@ withLiveClock,
        // ─── shell.find ───────────────────────────────────────────────────────
        test("shell.find lists files matching a pattern") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                ZIO.attemptBlocking {
                  Files.writeString(dir.resolve("foo.scala"), "object Foo")
                  Files.writeString(dir.resolve("bar.txt"), "bar")
                }.orDie *>
                  skill
                    .invoke(
                      ctx,
                      "shell.find",
                      Json.Obj("name" -> Json.Str("*.scala")),
                    ).map { result =>
                      assertTrue(
                        exitCode(result).contains(0),
                        stdoutOf(result).exists(_.contains("foo.scala")),
                        stdoutOf(result).exists(!_.contains("bar.txt")),
                      )
                    }
            }
          }
        } @@ withLiveClock,
        // ─── shell.head ───────────────────────────────────────────────────────
        test("shell.head returns first N lines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                val content = (1 to 20).map(i => s"line $i").mkString("\n")
                ZIO.attemptBlocking(Files.writeString(dir.resolve("lines.txt"), content)).orDie *>
                  skill
                    .invoke(
                      ctx,
                      "shell.head",
                      Json.Obj("path" -> Json.Str("lines.txt"), "lines" -> Json.Num(3)),
                    ).map { result =>
                      val out = stdoutOf(result).getOrElse("")
                      assertTrue(
                        exitCode(result).contains(0),
                        out.contains("line 1"),
                        out.contains("line 3"),
                        !out.contains("line 4"),
                      )
                    }
            }
          }
        } @@ withLiveClock,
        // ─── shell.tail ───────────────────────────────────────────────────────
        test("shell.tail returns last N lines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                val content = (1 to 20).map(i => s"line $i").mkString("\n")
                ZIO.attemptBlocking(Files.writeString(dir.resolve("lines.txt"), content)).orDie *>
                  skill
                    .invoke(
                      ctx,
                      "shell.tail",
                      Json.Obj("path" -> Json.Str("lines.txt"), "lines" -> Json.Num(3)),
                    ).map { result =>
                      val out = stdoutOf(result).getOrElse("")
                      assertTrue(
                        exitCode(result).contains(0),
                        out.contains("line 20"),
                        out.contains("line 18"),
                        !out.contains("line 17"),
                      )
                    }
            }
          }
        } @@ withLiveClock,
        // ─── shell.wc ─────────────────────────────────────────────────────────
        test("shell.wc with lines=true returns line count") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                ZIO.attemptBlocking(Files.writeString(dir.resolve("count.txt"), "one\ntwo\nthree\n")).orDie *>
                  skill
                    .invoke(
                      ctx,
                      "shell.wc",
                      Json.Obj("path" -> Json.Str("count.txt"), "lines" -> Json.Bool(true)),
                    ).map { result =>
                      assertTrue(
                        exitCode(result).contains(0),
                        stdoutOf(result).exists(_.contains("3")),
                      )
                    }
            }
          }
        } @@ withLiveClock,
        // ─── path traversal rejection ─────────────────────────────────────────
        test("shell.ls rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                skill.invoke(ctx, "shell.ls", Json.Obj("path" -> Json.Str("../../etc"))).either.map { result =>
                  assertTrue(result.isLeft)
                }
            }
          }
        },
        test("shell.cat rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                skill.invoke(ctx, "shell.cat", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either.map { result =>
                  assertTrue(result.isLeft)
                }
            }
          }
        },
        test("shell.grep rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                skill
                  .invoke(
                    ctx,
                    "shell.grep",
                    Json.Obj("pattern" -> Json.Str("root"), "path" -> Json.Str("../../etc/passwd")),
                  ).either.map { result =>
                    assertTrue(result.isLeft)
                  }
            }
          }
        },
        test("shell.find rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                skill.invoke(ctx, "shell.find", Json.Obj("path" -> Json.Str("../../etc"))).either.map { result =>
                  assertTrue(result.isLeft)
                }
            }
          }
        },
        test("shell.head rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                skill.invoke(ctx, "shell.head", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either.map { result =>
                  assertTrue(result.isLeft)
                }
            }
          }
        },
        test("shell.tail rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                skill.invoke(ctx, "shell.tail", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either.map { result =>
                  assertTrue(result.isLeft)
                }
            }
          }
        },
        test("shell.wc rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                skill.invoke(ctx, "shell.wc", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either.map { result =>
                  assertTrue(result.isLeft)
                }
            }
          }
        },
      ) +
      suite("ShellSkill — safe sandbox tools")(
        // ─── shell.ls ─────────────────────────────────────────────────────────
        test("shell.ls on sandbox root returns output") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                for {
                  _      <- ZIO.attemptBlocking(Files.writeString(dir.resolve("hello.txt"), "hello"))
                  result <- skill.invoke(ctx, "shell.ls", Json.Obj())
                } yield assertTrue(
                  exitCode(result).contains(0),
                  stdoutField(result).exists(_.contains("hello.txt")),
                )
            }
          }
        } @@ withLiveClock,
        test("shell.ls with all=true includes hidden files") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                Files.writeString(dir.resolve(".hidden"), "secret")
                for {
                  result <- skill.invoke(ctx, "shell.ls", Json.Obj("all" -> Json.Bool(true)))
                } yield assertTrue(
                  exitCode(result).contains(0),
                  stdoutField(result).exists(_.contains(".hidden")),
                )
            }
          }
        } @@ withLiveClock,
        // ─── shell.cat ────────────────────────────────────────────────────────
        test("shell.cat on an existing file returns contents") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                Files.writeString(dir.resolve("test.txt"), "line one\nline two\n")
                for {
                  result <- skill.invoke(ctx, "shell.cat", Json.Obj("path" -> Json.Str("test.txt")))
                } yield assertTrue(
                  exitCode(result).contains(0),
                  stdoutField(result).exists(_.contains("line one")),
                )
            }
          }
        } @@ withLiveClock,
        test("shell.cat respects maxLines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                val content = (1 to 10).map(i => s"line $i").mkString("\n")
                Files.writeString(dir.resolve("many.txt"), content)
                for {
                  result <- skill.invoke(
                    ctx,
                    "shell.cat",
                    Json.Obj("path" -> Json.Str("many.txt"), "maxLines" -> Json.Num(3)),
                  )
                } yield assertTrue(
                  stdoutField(result).exists(_.contains("[output truncated at 3 lines]")),
                )
            }
          }
        } @@ withLiveClock,
        // ─── shell.grep ───────────────────────────────────────────────────────
        test("shell.grep finds matching lines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                Files.writeString(dir.resolve("data.txt"), "apple\nbanana\napricot\n")
                for {
                  result <- skill.invoke(
                    ctx,
                    "shell.grep",
                    Json.Obj("pattern" -> Json.Str("ap"), "path" -> Json.Str("data.txt")),
                  )
                } yield assertTrue(
                  exitCode(result).contains(0),
                  stdoutField(result).exists(_.contains("apple")),
                  stdoutField(result).exists(_.contains("apricot")),
                  stdoutField(result).exists(!_.contains("banana")),
                )
            }
          }
        } @@ withLiveClock,
        // ─── shell.find ───────────────────────────────────────────────────────
        test("shell.find lists files matching a pattern") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                Files.writeString(dir.resolve("foo.scala"), "object Foo")
                Files.writeString(dir.resolve("bar.txt"), "bar")
                for {
                  result <- skill.invoke(
                    ctx,
                    "shell.find",
                    Json.Obj("name" -> Json.Str("*.scala")),
                  )
                } yield assertTrue(
                  exitCode(result).contains(0),
                  stdoutField(result).exists(_.contains("foo.scala")),
                  stdoutField(result).exists(!_.contains("bar.txt")),
                )
            }
          }
        } @@ withLiveClock,
        // ─── shell.head ───────────────────────────────────────────────────────
        test("shell.head returns first N lines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                val content = (1 to 20).map(i => s"line $i").mkString("\n")
                Files.writeString(dir.resolve("lines.txt"), content)
                for {
                  result <- skill.invoke(
                    ctx,
                    "shell.head",
                    Json.Obj("path" -> Json.Str("lines.txt"), "lines" -> Json.Num(3)),
                  )
                  out = stdoutField(result).getOrElse("")
                } yield assertTrue(
                  exitCode(result).contains(0),
                  out.contains("line 1"),
                  out.contains("line 3"),
                  !out.contains("line 4"),
                )
            }
          }
        } @@ withLiveClock,
        // ─── shell.tail ───────────────────────────────────────────────────────
        test("shell.tail returns last N lines") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                val content = (1 to 20).map(i => s"line $i").mkString("\n")
                Files.writeString(dir.resolve("lines.txt"), content)
                for {
                  result <- skill.invoke(
                    ctx,
                    "shell.tail",
                    Json.Obj("path" -> Json.Str("lines.txt"), "lines" -> Json.Num(3)),
                  )
                  out = stdoutField(result).getOrElse("")
                } yield assertTrue(
                  exitCode(result).contains(0),
                  out.contains("line 20"),
                  out.contains("line 18"),
                  !out.contains("line 17"),
                )
            }
          }
        } @@ withLiveClock,
        // ─── shell.wc ─────────────────────────────────────────────────────────
        test("shell.wc with lines=true returns line count") {
          ZIO.scoped {
            withTempSandbox {
              (
                dir,
                skill,
              ) =>
                Files.writeString(dir.resolve("count.txt"), "one\ntwo\nthree\n")
                for {
                  result <- skill.invoke(
                    ctx,
                    "shell.wc",
                    Json.Obj("path" -> Json.Str("count.txt"), "lines" -> Json.Bool(true)),
                  )
                } yield assertTrue(
                  exitCode(result).contains(0),
                  stdoutField(result).exists(_.contains("3")),
                )
            }
          }
        } @@ withLiveClock,
        // ─── path traversal rejection ─────────────────────────────────────────
        test("shell.ls rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                for {
                  result <- skill.invoke(ctx, "shell.ls", Json.Obj("path" -> Json.Str("../../etc"))).either
                } yield assertTrue(result.isLeft)
            }
          }
        },
        test("shell.cat rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                for {
                  result <- skill.invoke(ctx, "shell.cat", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either
                } yield assertTrue(result.isLeft)
            }
          }
        },
        test("shell.grep rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                for {
                  result <- skill
                    .invoke(
                      ctx,
                      "shell.grep",
                      Json.Obj("pattern" -> Json.Str("root"), "path" -> Json.Str("../../etc/passwd")),
                    ).either
                } yield assertTrue(result.isLeft)
            }
          }
        },
        test("shell.find rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                for {
                  result <- skill.invoke(ctx, "shell.find", Json.Obj("path" -> Json.Str("../../etc"))).either
                } yield assertTrue(result.isLeft)
            }
          }
        },
        test("shell.head rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                for {
                  result <- skill.invoke(ctx, "shell.head", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either
                } yield assertTrue(result.isLeft)
            }
          }
        },
        test("shell.tail rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                for {
                  result <- skill.invoke(ctx, "shell.tail", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either
                } yield assertTrue(result.isLeft)
            }
          }
        },
        test("shell.wc rejects path traversal") {
          ZIO.scoped {
            withTempSandbox {
              (
                _,
                skill,
              ) =>
                for {
                  result <- skill.invoke(ctx, "shell.wc", Json.Obj("path" -> Json.Str("../../etc/passwd"))).either
                } yield assertTrue(result.isLeft)
            }
          }
        },
      )

}
