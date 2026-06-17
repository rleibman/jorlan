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
import jorlan.db.repository.ZIOServerSettingsRepository
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.http.Client
import zio.json.*

/** Reads MCP server configs from [[ZIOServerSettingsRepository]], connects to each server, and registers adapters in
  * [[SkillRegistry]].
  */
trait McpManager {

  /** Read MCP server configs from server_settings, create adapters, and register them in the SkillRegistry.
    *
    * Stdio clients start a subprocess that must live for the duration of the server; the caller is responsible for
    * providing an outer [[Scope]] so that stdio processes are cleaned up on shutdown.
    */
  def loadAndRegister: ZIO[Scope, JorlanError, Unit]

}

class McpManagerImpl(
  registry: SkillRegistry,
  client:   Client,
  settings: ZIOServerSettingsRepository,
) extends McpManager {

  override def loadAndRegister: ZIO[Scope, JorlanError, Unit] = {
    settings.get("mcp.servers").flatMap {
      case None =>
        ZIO.logDebug("No MCP servers configured (set 'mcp.servers' in server_settings to enable)")
      case Some(json) =>
        json.as[List[McpServerConfig]] match {
          case Left(err) =>
            ZIO.logWarning(s"Skipping all MCP servers: could not parse config: $err")
          case Right(configs) =>
            ZIO.foreachDiscard(configs.filter(_.enabled)) { cfg =>
              makeAdapter(cfg)
                .flatMap(adapter => registry.register(adapter))
                .tapError(e => ZIO.logWarning(s"Skipping MCP server '${cfg.name}': ${e.msg}"))
                .ignore
            }
        }
    }
  }

  private def makeAdapter(cfg: McpServerConfig): ZIO[Scope, JorlanError, McpSkillAdapter] = {
    cfg.transport match {
      case McpTransport.Http =>
        cfg.url match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': HTTP transport requires 'url'"))
          case Some(url) =>
            HttpMcpClient.make(client, url).flatMap { httpClient =>
              httpClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, httpClient))
            }
        }
      case McpTransport.Stdio =>
        cfg.command match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': stdio transport requires 'command'"))
          case Some(_) =>
            // StdioMcpClient.make uses ZIO.acquireRelease, so the process lifetime is bound to the caller's Scope
            StdioMcpClient.make(cfg).flatMap { stdioClient =>
              stdioClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, stdioClient))
            }
        }
    }
  }

}

object McpManager {

  val live: URLayer[SkillRegistry & Client & ZIOServerSettingsRepository, McpManager] =
    ZLayer.fromFunction(McpManagerImpl(_, _, _))

}
