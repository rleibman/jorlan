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

/** Adapts an MCP server's tool list as a Jorlan [[Skill]].
  *
  * Tool names follow the pattern `mcp.<serverName>.<mcpToolName>`, where `mcp.<serverName>` is the skill namespace.
  * SkillRegistry routing uses longest-prefix matching (`toolName.startsWith(skillName + ".")`), so dots in the skill
  * name are safe.
  *
  * `serverName` is sanitized to replace characters that are not `[A-Za-z0-9_.]` with `_`; dots are preserved.
  */
class McpSkillAdapter(
  serverName:     String,
  tools:          List[McpTool],
  client:         McpClient,
  serverKeywords: List[String] = List.empty,
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
      client.callTool(mcpToolName, args).map(Json.Str(_))
  }

}
