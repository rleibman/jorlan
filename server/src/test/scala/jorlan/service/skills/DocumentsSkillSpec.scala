/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object DocumentsSkillSpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val sampleDoc =
    """|# Training Program
       |
       |Intro line.
       |
       |## Warm-up
       |
       |Do the warm-up.
       |More warm-up.
       |
       |## Technique
       |
       |Work on technique.
       |
       |# Appendix
       |
       |The end.""".stripMargin

  private def cleanupDir(dir: Path): UIO[Unit] =
    ZIO
      .attempt(
        if (Files.exists(dir)) {
          val stream = Files.walk(dir)
          try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
          finally stream.close()
        },
      )
      .orDie

  private def withSkill[E, A](body: DocumentsSkill => ZIO[Scope, E, A]): ZIO[Scope, E, A] =
    ZIO
      .acquireRelease(
        ZIO.attempt {
          val dir = Files.createTempDirectory("documents-skill-test")
          Files.write(dir.resolve("program.md"), sampleDoc.getBytes(StandardCharsets.UTF_8))
          dir
        }.orDie,
      )(cleanupDir)
      .flatMap(dir => body(DocumentsSkill(dir)))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DocumentsSkill")(
      test("docs.list returns documents with their section headings") {
        withSkill { skill =>
          skill.invoke(ctx, "docs.list", Json.Obj()).map {
            case Json.Obj(fields) =>
              val docs = fields.collectFirst { case ("documents", Json.Arr(items)) => items }.getOrElse(Chunk.empty)
              val first = docs.headOption
              val name = first.collect { case Json.Obj(f) =>
                f.collectFirst { case ("name", Json.Str(n)) => n }.getOrElse("")
              }.getOrElse("")
              val sections = first.collect { case Json.Obj(f) =>
                f.collectFirst { case ("sections", Json.Arr(s)) => s.collect { case Json.Str(x) => x }.toList }
                  .getOrElse(Nil)
              }.getOrElse(Nil)
              assertTrue(
                docs.length == 1,
                name == "program.md",
                sections == List("Training Program", "Warm-up", "Technique", "Appendix"),
              )
            case _ => assertTrue(false)
          }
        }
      },
      test("docs.read without section returns the whole document") {
        withSkill { skill =>
          skill.invoke(ctx, "docs.read", Json.Obj("name" -> Json.Str("program.md"))).map {
            case Json.Str(content) => assertTrue(content.contains("Intro line."), content.contains("The end."))
            case _                 => assertTrue(false)
          }
        }
      },
      test("docs.read with a section returns only that section, up to the next same-level heading") {
        withSkill { skill =>
          skill
            .invoke(ctx, "docs.read", Json.Obj("name" -> Json.Str("program.md"), "section" -> Json.Str("Warm-up")))
            .map {
              case Json.Str(content) =>
                assertTrue(
                  content.startsWith("## Warm-up"),
                  content.contains("More warm-up."),
                  !content.contains("Work on technique."),
                  !content.contains("Intro line."),
                )
              case _ => assertTrue(false)
            }
        }
      },
      test("docs.read matches a section case-insensitively") {
        withSkill { skill =>
          skill
            .invoke(ctx, "docs.read", Json.Obj("name" -> Json.Str("program.md"), "section" -> Json.Str("technique")))
            .map {
              case Json.Str(content) => assertTrue(content.contains("Work on technique."))
              case _                 => assertTrue(false)
            }
        }
      },
      test("docs.read fails for an unknown section") {
        withSkill { skill =>
          skill
            .invoke(ctx, "docs.read", Json.Obj("name" -> Json.Str("program.md"), "section" -> Json.Str("nope")))
            .exit
            .map(e => assertTrue(e.isFailure))
        }
      },
      test("docs.read rejects path traversal") {
        withSkill { skill =>
          skill
            .invoke(ctx, "docs.read", Json.Obj("name" -> Json.Str("../../../etc/passwd")))
            .exit
            .map(e => assertTrue(e.isFailure))
        }
      },
    )

}
