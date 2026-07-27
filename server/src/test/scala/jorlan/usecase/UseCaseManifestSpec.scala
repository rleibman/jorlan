/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.usecase

import jorlan.McpTransport
import zio.*
import zio.json.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Static validation of the checked-in use-case manifests in `doc/use-cases/manifests`.
  *
  * The use-case importer validates `{{steps.*}}` references but not tool prefixes, transport names, or capability
  * grants — and every failure it does surface is opaque (`<mutation> returned nothing`). So the mistakes below are
  * silent at import time and only show up as an agent that mysteriously has no tools. They are cheap to catch here.
  */
object UseCaseManifestSpec extends ZIOSpecDefault {

  /** Tests do not run from a guaranteed working directory, so walk up until the manifests directory appears. */
  private def manifestsDir: Task[Path] = ZIO.attempt {
    import scala.language.unsafeNulls
    val relative = Paths.get("doc", "use-cases", "manifests")
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.isDirectory(_))
      .getOrElse(throw new RuntimeException(s"Could not locate $relative from ${Paths.get("").toAbsolutePath}"))
  }

  private def loadManifests: Task[List[(String, UseCaseManifest)]] =
    manifestsDir.flatMap { dir =>
      ZIO.attempt {
        import scala.language.unsafeNulls
        Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".json")).toList.sortBy(_.toString)
      }.flatMap { files =>
        ZIO.foreach(files) { f =>
          for {
            raw      <- ZIO.attempt(Files.readString(f))
            name     = f.getFileName.toString
            manifest <- ZIO
              .fromEither(raw.fromJson[UseCaseManifest])
              .mapError(e => new RuntimeException(s"$name: does not decode as a UseCaseManifest: $e"))
          } yield name -> manifest
        }
      }
    }

  /** `PipelineStep.tools` is a *prefix allowlist* (`AgentRunnerImpl`: `t.name == p || t.name.startsWith(p + ".")`), and
    * `McpSkillAdapter` registers MCP tools as `mcp.<server>.<tool>`. So a step that wants an MCP server's tools must say
    * `mcp.<server>`, never the bare server name — which matches nothing and silently leaves the step with no tools.
    */
  private def badMcpPrefixes(m: UseCaseManifest): List[String] =
    for {
      server <- m.mcpServers.map(_.name)
      step   <- m.job.toList.flatMap(_.steps)
      tool   <- step.tools
      if tool == server || tool.startsWith(server + ".")
    } yield s"step '${step.name}' declares tool '$tool' — should be 'mcp.$server…'"

  private def declaresMcpTools(m: UseCaseManifest): Boolean =
    m.job.toList.flatMap(_.steps).exists(_.tools.exists(_.startsWith("mcp")))

  /** The skill namespace an MCP server registers under. `McpSkillAdapter` sanitises the configured name with
    * `[^A-Za-z0-9_.] -> _`, so `home-assistant` is reachable as `mcp.home_assistant` — the hyphenated form matches
    * nothing. Easy to get wrong, and silent when you do.
    */
  private def namespaceOf(serverName: String): String = {
    import scala.language.unsafeNulls
    "mcp." + serverName.replaceAll("[^A-Za-z0-9_.]", "_")
  }

  /** Names captured by a single-group regex. `Match.group` is nullable under `-Yexplicit-nulls`. */
  private def captures(
    pattern: scala.util.matching.Regex,
    text:    String,
  ): List[String] = {
    import scala.language.unsafeNulls
    pattern.findAllMatchIn(text).map(_.group(1)).toList
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("UseCaseManifestSpec")(
      test("every manifest decodes as a UseCaseManifest") {
        loadManifests.map(ms => assertTrue(ms.nonEmpty))
      },
      test("MCP tool prefixes are 'mcp.<server>', not the bare server name") {
        loadManifests.map { ms =>
          val bad = ms.flatMap { case (name, m) => badMcpPrefixes(m).map(b => s"$name: $b") }
          assertTrue(bad.isEmpty)
        }
      },
      test("a job that declares MCP tools also grants the mcp.call capability") {
        // Every MCP tool, on every server, requires exactly this one capability (McpSkillAdapter). Without the grant
        // the tools register but every call is denied.
        loadManifests.map { ms =>
          val missing = ms.collect {
            case (name, m) if declaresMcpTools(m) && !m.role.capabilities.exists(_.capability == "mcp.call") => name
          }
          assertTrue(missing.isEmpty)
        }
      },
      test("each MCP server is listed in prioritizedSkills under its sanitized namespace") {
        loadManifests.map { ms =>
          val missing = for {
            (name, m) <- ms
            server    <- m.mcpServers
            ns        = namespaceOf(server.name)
            if !m.agent.prioritizedSkills.contains(ns)
          } yield s"$name: server '${server.name}' is not in prioritizedSkills as '$ns'"
          assertTrue(missing.isEmpty)
        }
      },
      test("MCP transports name a real McpTransport case") {
        // The resolver does `McpTransport.valueOf(input.transport)`; "http" or "stdio" fail the import opaquely.
        loadManifests.map { ms =>
          val valid = McpTransport.values.map(_.toString).toSet
          val bad   = for {
            (name, m) <- ms
            server    <- m.mcpServers
            if !valid.contains(server.transport)
          } yield s"$name: server '${server.name}' has transport '${server.transport}' (expected one of $valid)"
          assertTrue(bad.isEmpty)
        }
      },
      test("HTTP transports carry a url and Stdio carries a command") {
        loadManifests.map { ms =>
          val bad = for {
            (name, m) <- ms
            server    <- m.mcpServers
            problem   <- server.transport match {
              case "Stdio" if server.command.isEmpty => List(s"$name: '${server.name}' is Stdio but has no command")
              case "Http" | "HttpSse" if server.url.isEmpty =>
                List(s"$name: '${server.name}' is ${server.transport} but has no url")
              case _ => Nil
            }
          } yield problem
          assertTrue(bad.isEmpty)
        }
      },
      test("no credentials are committed — placeholders only") {
        loadManifests.map { ms =>
          val suspicious = for {
            (name, m) <- ms
            server    <- m.mcpServers
            kv        <- server.env ++ server.headers
            key       = kv.key.toUpperCase
            if key.contains("PASSWORD") || key.contains("TOKEN") || key.contains("SECRET") ||
              key.contains("API_KEY") || key == "AUTHORIZATION"
            if !kv.value.contains("CHANGE_ME")
          } yield s"$name: '${server.name}' has a real-looking value for '${kv.key}'"
          assertTrue(suspicious.isEmpty)
        }
      },
      test("{{steps.X.output}} references resolve to an earlier step's outputVar") {
        // Mirrors the importer's own validatePipelineReferences; without it these blow up at runtime inside langchain4j.
        loadManifests.map { ms =>
          val pattern = """\{\{steps\.(\w+)\.\w+}}""".r
          val bad     = for {
            (name, m) <- ms
            job       <- m.job.toList
            (step, i) <- job.steps.zipWithIndex
            earlier   = job.steps.take(i).map(_.outputVar).toSet
            ref       <- captures(pattern, step.systemPrompt + step.userPrompt)
            if !earlier.contains(ref)
          } yield s"$name: step '${step.name}' references '{{steps.$ref}}', not an earlier outputVar"
          assertTrue(bad.isEmpty)
        }
      },
      test("{{invariants.X}} references resolve to a declared invariant") {
        loadManifests.map { ms =>
          val pattern = """\{\{invariants\.(\w+)}}""".r
          val bad     = for {
            (name, m) <- ms
            job       <- m.job.toList
            step      <- job.steps
            ref       <- captures(pattern, step.systemPrompt + step.userPrompt)
            if !job.invariants.contains(ref) && !m.agent.invariants.contains(ref)
          } yield s"$name: step '${step.name}' references '{{invariants.$ref}}', which is not declared"
          assertTrue(bad.isEmpty)
        }
      },
    )

}
