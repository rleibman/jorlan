/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.mcp

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import zio.*
import zio.json.ast.Json

/** Adapts an MCP server's tool list as a Jorlan [[Skill]].
  *
  * Tool names follow the pattern `mcp_<serverName>.<mcpToolName>`, where `mcp_<serverName>` is the skill namespace
  * (required for [[jorlan.service.skills.SkillRegistryLive]] routing via `takeWhile(_ != '.')`).
  */
class McpSkillAdapter(
  serverName: String,
  tools:      List[McpTool],
  client:     McpClient,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = s"mcp_$serverName",
    tier = SkillTier.Imported,
    tools = tools.map { t =>
      ToolDescriptor(
        name = s"mcp_$serverName.${t.name}",
        description = t.description.getOrElse(""),
        inputSchema = t.inputSchema,
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("mcp.call")),
        examplePrompts = Nil,
      )
    },
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    val prefix = s"mcp_$serverName."
    val mcpToolName = tool.stripPrefix(prefix)
    if (mcpToolName == tool)
      ZIO.fail(JorlanError(s"McpSkillAdapter: tool '$tool' is not in namespace 'mcp_$serverName'"))
    else
      client.callTool(mcpToolName, args).map(Json.Str(_))
  }

}
