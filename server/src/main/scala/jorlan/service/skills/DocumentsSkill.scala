/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Built-in read-only skill for reference documents: methodologies, guides, and playbooks that govern how an agent
  * behaves, kept in a jailed directory and read **section by section** rather than wholesale.
  *
  * This exists so a long instruction document (e.g. a 400-line teaching methodology) can inform an agent without
  * pasting the whole thing into every prompt: the agent lists a document's section headings, then reads only the one or
  * two sections relevant to the task at hand.
  *
  * Unlike [[WorkspaceSkill]] (read/write, per-session scratch space), documents are read-only, shared, and placed by an
  * administrator or the use-case importer into the jailed root.
  *
  * Tools:
  *   - `docs.list` — list available documents and their section headings (no content)
  *   - `docs.read` — read a whole document, or just one section by its heading
  */
class DocumentsSkill(
  documentsRoot: Path,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "docs",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    configKey = Some("skill.documents"),
    keywords = List(
      "document",
      "documents",
      "reference",
      "guide",
      "guideline",
      "methodology",
      "playbook",
      "manual",
      "instructions",
      "handbook",
      "section",
      "read document",
    ),
    doc = Some(
      """|## Documents Skill
         |
         |Read-only access to reference documents in a jailed directory. Designed for section-level reading so
         |long documents inform an agent without being pasted wholesale into prompts.
         |
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `docs.list` | List documents and their section headings | `docs.read` |
         || `docs.read` | Read a whole document or one section by heading | `docs.read` |
         |
         |Typical usage: call `docs.list` to see a document's sections, then `docs.read` with a `section` to pull
         |only the relevant part.""".stripMargin,
    ),
    tools = List(
      ToolDescriptor(
        name = "docs.list",
        description = "List the available reference documents and, for each, its section headings. Use this first to discover what documents exist and which sections they contain before reading.",
        inputSchema = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("docs.read")),
        examplePrompts = List(
          "What reference documents are available?",
          "List the sections of the training methodology",
        ),
      ),
      ToolDescriptor(
        name = "docs.read",
        description = "Read a reference document. Provide 'section' (a heading name from docs.list) to read only that section -- strongly preferred over reading the whole document, which can be very long. Omit 'section' only when the whole document is genuinely needed.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"name":{"type":"string","description":"Document name (from docs.list)"},"section":{"type":"string","description":"Optional heading name; returns only that section"}},"required":["name"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("docs.read")),
        examplePrompts = List(
          "Read the 'Practice Session Structure' section of drums-training-program",
          "Show me the whole onboarding guide",
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
      case "docs.list" => listDocuments()
      case "docs.read" => readDocument(args)
      case other       => ZIO.fail(JorlanError(s"DocumentsSkill: unknown tool '$other'"))
    }

  /** Resolve and validate a document name against the jailed root; rejects traversal and non-files. */
  private def safeDoc(name: String): IO[JorlanError, Path] =
    ZIO
      .attempt(documentsRoot.resolve(name).normalize())
      .mapError(e => JorlanError(s"docs: invalid document name: ${e.getMessage}"))
      .flatMap { resolved =>
        if (!resolved.startsWith(documentsRoot)) ZIO.fail(JorlanError("docs: path is outside the documents root"))
        else ZIO.succeed(resolved)
      }

  /** A markdown/section heading line and its 1-based level, e.g. `## Foo` -> (2, "Foo"). */
  private def headingOf(line: String): Option[(Int, String)] = {
    val trimmed = line.stripLeading
    if (trimmed.startsWith("#")) {
      val level = trimmed.takeWhile(_ == '#').length
      val text = trimmed.dropWhile(_ == '#').trim
      if (text.nonEmpty) Some((level, text)) else None
    } else None
  }

  private def sectionHeadings(content: String): List[String] =
    content.linesIterator.flatMap(headingOf).map(_._2).toList

  private def isDocFile(p: Path): Boolean = {
    val n = p.getFileName.toString.toLowerCase
    Files.isRegularFile(p) && (n.endsWith(".md") || n.endsWith(".txt") || n.endsWith(".markdown"))
  }

  private def listDocuments(): IO[JorlanError, Json] =
    ZIO
      .attemptBlocking {
        if (!Files.exists(documentsRoot)) List.empty[Path]
        else {
          val stream = Files.walk(documentsRoot)
          try stream.iterator().nn.asScalaList.filter(isDocFile)
          finally stream.close()
        }
      }
      .mapError(e => JorlanError(s"docs.list: ${e.getMessage}"))
      .flatMap { files =>
        ZIO
          .foreach(files) { p =>
            ZIO.attemptBlocking(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).map { content =>
              Json.Obj(
                "name"     -> Json.Str(documentsRoot.relativize(p).toString),
                "sections" -> Json.Arr(sectionHeadings(content).map(Json.Str(_))*),
              )
            }
          }
          .mapError(e => JorlanError(s"docs.list: ${e.getMessage}"))
          .map(docs => Json.Obj("documents" -> Json.Arr(docs*)))
      }

  /** Extract the body of the section whose heading matches `section` (case-insensitive; exact match preferred, else
    * first heading that contains the query). Returns the heading through to the next heading of the same or higher
    * level.
    */
  private def extractSection(
    content: String,
    section: String,
  ): Option[String] = {
    val lines = content.linesIterator.toVector
    val headings = lines.zipWithIndex.flatMap { case (line, idx) =>
      headingOf(line).map { case (lvl, txt) => (idx, lvl, txt) }
    }
    val q = section.trim.toLowerCase
    val exact = headings.find(_._3.toLowerCase == q)
    val chosen = exact.orElse(headings.find(_._3.toLowerCase.contains(q)))
    chosen.map { case (startIdx, level, _) =>
      val endIdx = headings
        .find { case (idx, lvl, _) => idx > startIdx && lvl <= level }
        .map(_._1)
        .getOrElse(lines.length)
      lines.slice(startIdx, endIdx).mkString("\n").trim
    }
  }

  private def readDocument(args: Json): IO[JorlanError, Json] =
    str(args, "name") match {
      case None       => ZIO.fail(JorlanError("docs.read: 'name' is required"))
      case Some(name) =>
        for {
          p <- safeDoc(name)
          _ <- ZIO
            .attemptBlocking(isDocFile(p))
            .mapError(e => JorlanError(s"docs.read: ${e.getMessage}"))
            .flatMap(ok => ZIO.unless(ok)(ZIO.fail(JorlanError(s"docs.read: no such document '$name'"))))
          content <- ZIO
            .attemptBlocking(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
            .mapError(e => JorlanError(s"docs.read: ${e.getMessage}"))
          out <- str(args, "section") match {
            case None          => ZIO.succeed(content)
            case Some(section) =>
              ZIO
                .fromOption(extractSection(content, section))
                .orElseFail(
                  JorlanError(s"docs.read: no section matching '$section' in '$name' (use docs.list to see sections)"),
                )
          }
        } yield Json.Str(out)
    }

  extension (it: java.util.Iterator[Path]) {

    private def asScalaList: List[Path] = {
      val b = List.newBuilder[Path]
      while (it.hasNext) b += it.next()
      b.result()
    }

  }

}
