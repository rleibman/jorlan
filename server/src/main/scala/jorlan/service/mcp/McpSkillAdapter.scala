/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Adapts an MCP server's tool list as a Jorlan [[Skill]].
  *
  * Tool names follow the pattern `mcp.<serverName>.<mcpToolName>`, where `mcp.<serverName>` is the skill namespace.
  * SkillRegistry routing uses longest-prefix matching (`toolName.startsWith(skillName + ".")`), so dots in the skill
  * name are safe.
  *
  * `serverName` is sanitized to replace characters that are not `[A-Za-z0-9_.]` with `_`; dots are preserved.
  *
  * When a tool result exceeds [[spillThresholdBytes]] and [[workspaceCfg]] is configured, the full content is written
  * to a `mcp-spill/` subdirectory under the session-scoped workspace root and the LLM receives a compact descriptor
  * instead. The relative path `mcp-spill/<filename>` is valid for `workspace.read` because the file is written inside
  * the same scoped root that WorkspaceSkill uses. This prevents oversized results from filling the context window.
  */
class McpSkillAdapter(
  serverName:          String,
  tools:               List[McpTool],
  client:              McpClient,
  serverKeywords:      List[String] = List.empty,
  workspaceCfg:        Option[WorkspaceSettings] = None,
  spillThresholdBytes: Int = 4 * 1024,
) extends Skill {

  private val sanitizedName: String = serverName.replaceAll("[^A-Za-z0-9_.]", "_")
  private val namespace:     String = s"mcp.$sanitizedName"

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = namespace,
    tier = SkillTier.Imported,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    tools = tools.map { t =>
      ToolDescriptor(
        name = s"$namespace.${t.name}",
        description = t.description.getOrElse(""),
        inputSchema = t.inputSchema,
        outputSchema = Json.Obj("type" -> Json.Str("string")),
        requiredCapabilities = List(CapabilityName("mcp.call")),
        examplePrompts = List.empty,
        keywords = serverKeywords,
      )
    },
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    val prefix = s"$namespace."
    val mcpToolName = tool.stripPrefix(prefix)
    if (mcpToolName == tool)
      ZIO.fail(JorlanError(s"McpSkillAdapter: tool '$tool' is not in namespace '$namespace'"))
    else
      client.callTool(mcpToolName, args).flatMap { result =>
        if (result.length <= spillThresholdBytes || workspaceCfg.isEmpty)
          ZIO.succeed(Json.Str(result))
        else
          spillToWorkspace(ctx, result, mcpToolName)
      }
  }

  /** Compute the workspace root scoped by session/user, mirroring WorkspaceSkill.scopedRoot. */
  private def scopedRoot(
    cfg: WorkspaceSettings,
    ctx: InvocationContext,
  ): Path = {
    val base = Paths.get(cfg.root).toAbsolutePath.normalize()
    cfg.defaultScope match {
      case WorkspaceScope.Flat    => base
      case WorkspaceScope.Session => ctx.sessionId.fold(base)(s => base.resolve(s"session-${s.value}"))
      case WorkspaceScope.User    => base.resolve(s"user-${ctx.actorId.value}")
    }
  }

  private def spillToWorkspace(
    ctx:         InvocationContext,
    result:      String,
    mcpToolName: String,
  ): IO[JorlanError, Json] = {
    val cfg = workspaceCfg.get
    val safeTool = mcpToolName.replaceAll("[^A-Za-z0-9_]", "_")
    val fileName = s"mcp_${sanitizedName}_${safeTool}_${java.lang.System.currentTimeMillis()}.json"
    val relPath = s"mcp-spill/$fileName"
    val dir = scopedRoot(cfg, ctx).resolve("mcp-spill")
    ZIO
      .attemptBlocking {
        Files.createDirectories(dir)
        Files.write(dir.resolve(fileName), result.getBytes(StandardCharsets.UTF_8))
      }.mapBoth(
        e => JorlanError(s"MCP spill: failed to write workspace file '$relPath': ${e.getMessage}"),
        { _ =>
          Json.Str(
            s"[Result too large: ${result.length} bytes — inline limit is $spillThresholdBytes bytes]\n" +
              s"Full content saved to workspace file: $relPath\n" +
              s"Use workspace.read with path \"$relPath\" to access the data.",
          )
        },
      )
  }

}
