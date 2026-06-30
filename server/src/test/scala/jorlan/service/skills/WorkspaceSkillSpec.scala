/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.service.skills.WorkspaceSkill
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.zip.ZipFile

object WorkspaceSkillSpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val ctxWithSession =
    InvocationContext(UserId(1L), None, Some(AgentSessionId(42L)))

  private def mkArgs(pairs: (String, String)*): Json =
    Json.Obj(pairs.map { case (k, v) => k -> Json.Str(v) }*)

  private def cleanupDir(dir: java.nio.file.Path): UIO[Unit] =
    ZIO
      .attempt(
        if (Files.exists(dir)) {
          val stream = Files.walk(dir)
          try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
          finally stream.close()
        },
      )
      .orDie

  /** Build a [[WorkspaceSkill]] rooted at a fresh temp directory that is deleted after the test. */
  private def withSkill[E, A](body: WorkspaceSkill => ZIO[Scope, E, A]): ZIO[Scope, E, A] =
    ZIO
      .acquireRelease(
        ZIO.succeed(Files.createTempDirectory("workspace-skill-test")),
      )(cleanupDir)
      .flatMap { root =>
        body(new WorkspaceSkill(root, WorkspaceSettings(root = root.toString)))
      }

  /** Build a [[WorkspaceSkill]] with a custom scope setting. */
  private def withSkillScope[E, A](
    scope: WorkspaceScope,
  )(
    body: (WorkspaceSkill, java.nio.file.Path) => ZIO[Scope, E, A],
  ): ZIO[Scope, E, A] =
    ZIO
      .acquireRelease(
        ZIO.succeed(Files.createTempDirectory("workspace-skill-scope-test")),
      )(cleanupDir)
      .flatMap { root =>
        body(
          new WorkspaceSkill(root, WorkspaceSettings(root = root.toString, defaultScope = scope)),
          root,
        )
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
      // ─── search with a file prefix (not a directory) ─────────────────────
      test("search with a file-path prefix uses parent directory") {
        withSkill { skill =>
          for {
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "docs/a.txt", "content" -> "a"))
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "docs/b.txt", "content" -> "b"))
            result <- skill.invoke(ctx, "workspace.search", mkArgs("prefix" -> "docs/a.txt"))
          } yield result match {
            case Json.Arr(elems) =>
              val paths = elems.collect { case Json.Str(s) => s }.toList
              assertTrue(paths.nonEmpty && paths.forall(_.startsWith("docs/")))
            case _ => assertTrue(false)
          }
        }
      },
      // ─── snapshot ────────────────────────────────────────────────────────
      test("workspace.snapshot creates a zip file and returns its path") {
        withSkill { skill =>
          for {
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "file1.txt", "content" -> "hello"))
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "file2.txt", "content" -> "world"))
            result <- skill.invoke(ctx, "workspace.snapshot", Json.Obj())
          } yield result match {
            case Json.Str(path) => assertTrue(path.startsWith("__snapshots/") && path.endsWith(".zip"))
            case _              => assertTrue(false)
          }
        }
      },
      test("workspace.snapshot with a custom tag uses that tag in filename") {
        withSkill { skill =>
          for {
            _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "data.txt", "content" -> "data"))
            result <- skill.invoke(ctx, "workspace.snapshot", mkArgs("tag" -> "release-1.0"))
          } yield assertTrue(result == Json.Str("__snapshots/release-1.0.zip"))
        }
      },
      // ─── workspace scopes ─────────────────────────────────────────────────
      test("WorkspaceScope.Flat uses workspace root for all contexts") {
        withSkillScope(WorkspaceScope.Flat) {
          (
            skill,
            root,
          ) =>
            for {
              _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "flat.txt", "content" -> "flat"))
              result <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "flat.txt"))
            } yield assertTrue(
              result == Json.Str("flat"),
              Files.exists(root.resolve("flat.txt")),
            )
        }
      },
      test("WorkspaceScope.Session scopes files under session-<id> when sessionId is present") {
        withSkillScope(WorkspaceScope.Session) {
          (
            skill,
            root,
          ) =>
            for {
              _ <- skill.invoke(
                ctxWithSession,
                "workspace.write",
                mkArgs("path" -> "sess.txt", "content" -> "session-data"),
              )
              result <- skill.invoke(ctxWithSession, "workspace.read", mkArgs("path" -> "sess.txt"))
            } yield assertTrue(
              result == Json.Str("session-data"),
              Files.exists(root.resolve("session-42").resolve("sess.txt")),
            )
        }
      },
      test("WorkspaceScope.Session uses root when sessionId is absent") {
        withSkillScope(WorkspaceScope.Session) {
          (
            skill,
            root,
          ) =>
            for {
              _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "nosess.txt", "content" -> "no-session"))
              result <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "nosess.txt"))
            } yield assertTrue(
              result == Json.Str("no-session"),
              Files.exists(root.resolve("nosess.txt")),
            )
        }
      },
      test("WorkspaceScope.User scopes files under user-<id>") {
        withSkillScope(WorkspaceScope.User) {
          (
            skill,
            root,
          ) =>
            for {
              _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "userfile.txt", "content" -> "user-data"))
              result <- skill.invoke(ctx, "workspace.read", mkArgs("path" -> "userfile.txt"))
            } yield assertTrue(
              result == Json.Str("user-data"),
              Files.exists(root.resolve("user-1").resolve("userfile.txt")),
            )
        }
      },
      test("different users with User scope do not share files") {
        val ctx2 = InvocationContext(UserId(2L), None, None)
        withSkillScope(WorkspaceScope.User) {
          (
            skill,
            _,
          ) =>
            for {
              _      <- skill.invoke(ctx, "workspace.write", mkArgs("path" -> "shared.txt", "content" -> "user1-only"))
              result <- skill.invoke(ctx2, "workspace.read", mkArgs("path" -> "shared.txt")).either
            } yield assertTrue(result.isLeft)
        }
      },
    )

}
