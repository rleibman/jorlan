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

import jorlan.WorkspaceSettings

import java.nio.file.{Files, Paths}

object WorkspaceSkillSpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)

  private def mkArgs(pairs: (String, String)*): Json =
    Json.Obj(pairs.map { case (k, v) => k -> Json.Str(v) }*)

  /** Build a [[WorkspaceSkill]] rooted at a fresh temp directory that is deleted after the test. */
  private def withSkill[E, A](body: WorkspaceSkill => ZIO[Scope, E, A]): ZIO[Scope, E, A] =
    ZIO
      .acquireRelease(
        ZIO.succeed(Files.createTempDirectory("workspace-skill-test")),
      )(dir =>
        ZIO
          .attempt(
            if (Files.exists(dir)) {
              val stream = Files.walk(dir)
              try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
              finally stream.close()
            },
          )
          .orDie,
      ).flatMap { root =>
        body(new WorkspaceSkill(root))
      }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("WorkspaceSkill")(
      // ─── write + read round-trip ───────────────────────────────────────────────
      test("write then read round-trips content") {
        withSkill { skill =>
          for {
            writeResult <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "hello.txt", "content" -> "world"))
            readResult  <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "hello.txt"))
          } yield assertTrue(
            writeResult == Json.Str("ok"),
            readResult == Json.Str("world"),
          )
        }
      },
      test("write creates intermediate directories") {
        withSkill { skill =>
          for {
            writeResult <- skill.invoke(
              ctx,
              "workspace.write",
              mkArgs("path" -> "subdir/nested/file.txt", "content" -> "nested"),
            )
            readResult <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "subdir/nested/file.txt"))
          } yield assertTrue(
            writeResult == Json.Str("ok"),
            readResult == Json.Str("nested"),
          )
        }
      },
      // ─── path traversal rejection ─────────────────────────────────────────────
      test("read rejects path traversal (../escape)") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "../escape.txt")).either
          } yield assertTrue(result.isLeft)
        }
      },
      test("write rejects path traversal") {
        withSkill { skill =>
          for {
            result <- skill
              .invoke(ctx, "workspace.write", mkArgs("path" -> "../../bad.txt", "content" -> "evil"))
              .either
          } yield assertTrue(result.isLeft)
        }
      },
      test("read rejects absolute path outside workspace") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "/etc/passwd")).either
          } yield assertTrue(result.isLeft)
        }
      },
      // ─── read missing file ─────────────────────────────────────────────────────
      test("read returns error for missing file") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "nonexistent.txt")).either
          } yield assertTrue(result.isLeft)
        }
      },
      // ─── search ────────────────────────────────────────────────────────────────
      test("search lists files written to workspace") {
        withSkill { skill =>
          for {
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "a.txt", "content" -> "1"))
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "b.txt", "content" -> "2"))
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "sub/c.txt", "content" -> "3"))
            result <- skill.invoke(ctx, "workspace.search", Json.Obj())
          } yield result match {
            case Json.Arr(elems) =>
              val paths = elems.collect { case Json.Str(s) => s }.toList
              assertTrue(
                paths.length == 3,
                paths.contains("a.txt"),
                paths.contains("b.txt"),
                paths.contains("sub/c.txt"),
              )
            case _ => assertTrue(false)
          }
        }
      },
      test("search returns empty array when workspace is empty") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.search", Json.Obj())
          } yield result match {
            case Json.Arr(elems) => assertTrue(elems.isEmpty)
            case _               => assertTrue(false)
          }
        }
      },
      // ─── delete ────────────────────────────────────────────────────────────────
      test("delete removes a file") {
        withSkill { skill =>
          for {
            _            <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "del.txt", "content" -> "bye"))
            deleteResult <- skill.invoke(ctx, "workspace.delete", mkArgs("path" -> "del.txt"))
            readResult   <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "del.txt")).either
          } yield assertTrue(
            deleteResult == Json.Str("ok"),
            readResult.isLeft,
          )
        }
      },
      test("delete is idempotent for non-existent files") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.delete", mkArgs("path" -> "gone.txt"))
          } yield assertTrue(result == Json.Str("ok"))
        }
      },
      // ─── missing required field ───────────────────────────────────────────────
      test("read fails when path is missing") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.read", Json.Obj()).either
          } yield assertTrue(result.isLeft)
        }
      },
      test("write fails when content is missing") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "x.txt")).either
          } yield assertTrue(result.isLeft)
        }
      },
      test("unknown tool returns error") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.unknown", Json.Obj()).either
          } yield assertTrue(result.isLeft)
        }
      },
      // ─── write fails when path is missing ─────────────────────────────────────
      test("write fails when path is missing") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.write", mkArgs("content" -> "data")).either
          } yield assertTrue(result.isLeft)
        }
      },
      // ─── delete fails when path arg is missing ─────────────────────────────────
      test("delete fails when path is missing") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.delete", Json.Obj()).either
          } yield assertTrue(result.isLeft)
        }
      },
      // ─── search with non-empty prefix ─────────────────────────────────────────
      test("search with non-empty prefix lists only matching files") {
        withSkill { skill =>
          for {
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "docs/readme.txt", "content" -> "doc"))
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "docs/guide.txt", "content" -> "guide"))
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "other.txt", "content" -> "other"))
            result <- skill.invoke(ctx, "workspace.search", mkArgs("prefix" -> "docs"))
          } yield result match {
            case Json.Arr(elems) =>
              val paths = elems.collect { case Json.Str(s) => s }.toList
              assertTrue(
                paths.length == 2,
                paths.forall(_.startsWith("docs/")),
              )
            case _ => assertTrue(false)
          }
        }
      },
      // ─── non-object args to workspace.read (covers getStr case _ => None) ─────
      test("read with non-object args returns error") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.read", Json.Arr(Json.Str("bad"))).either
          } yield assertTrue(result.isLeft)
        }
      },
      // ─── non-object args to workspace.write ─────────────────────────────────
      test("write with non-object args returns error") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.write", Json.Arr(Json.Str("bad"))).either
          } yield assertTrue(result.isLeft)
        }
      },
      // ─── search with non-existent prefix returns empty ─────────────────────────
      test("search with non-existent prefix directory returns empty array") {
        withSkill { skill =>
          for {
            result <- skill.invoke(ctx, "workspace.search", mkArgs("prefix" -> "nodir/sub"))
          } yield result match {
            case Json.Arr(elems) => assertTrue(elems.isEmpty)
            case _               => assertTrue(false)
          }
        }
      },
      // ─── WorkspaceSkill.live factory (covers lines 196-197) ───────────────────
      test("WorkspaceSkill.live builds a functional skill from WorkspaceSettings") {
        ZIO
          .acquireRelease(
            ZIO.succeed(Files.createTempDirectory("workspace-live-test")),
          )(dir =>
            ZIO
              .attempt(
                if (Files.exists(dir)) {
                  val stream = Files.walk(dir)
                  try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
                  finally stream.close()
                },
              )
              .orDie,
          ).flatMap { root =>
            ZIO
              .serviceWithZIO[WorkspaceSkill] { skill =>
                skill
                  .invoke(ctx, "workspace.write", mkArgs("path" -> "live.txt", "content" -> "hello"))
                  .map(r => assertTrue(r == Json.Str("ok")))
              }
              .provide(
                WorkspaceSkill.live,
                ZLayer.succeed(WorkspaceSettings(root = root.toString)),
              )
          }
      },
    )

}
