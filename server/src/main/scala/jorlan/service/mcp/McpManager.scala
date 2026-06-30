/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import jorlan.*
import jorlan.db.repository.{ZIORepositories, ZIOServerSettingsRepository}
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.http.Client
import zio.json.*

import java.nio.file.{Path, Paths}

/** Reads MCP server configs from [[ZIOServerSettingsRepository]], connects to each server, and registers adapters in
  * [[SkillRegistry]].
  *
  * Lifecycle: stdio subprocess handles are acquired as resources tied to a [[ScopedRef]]. Each call to
  * [[loadAndRegister]] closes the previous set of subprocesses (via ScopedRef's finalizer) and starts fresh ones,
  * keeping them alive until the next reload or application shutdown.
  */
trait McpManager {

  /** Read MCP server configs from server_settings, create adapters, and register them in the SkillRegistry.
    *
    * Stdio processes are kept alive between calls. Each invocation tears down previously running processes and brings
    * up a new set, so there is no need for the caller to provide an outer [[Scope]].
    */
  def loadAndRegister: UIO[Unit]

}

class McpManagerImpl(
  registry:  SkillRegistry,
  client:    Client,
  repos:     ZIORepositories,
  loadedRef: ScopedRef[Unit],
) extends McpManager {

  private def settings: ZIOServerSettingsRepository = repos.setting

  override def loadAndRegister: UIO[Unit] =
    loadedRef.set(doLoad)

  private def doLoad: ZIO[Scope, Nothing, Unit] = {
    val workspaceSpillDir: UIO[Option[Path]] =
      settings
        .get("skill.workspace")
        .map(_.flatMap(_.as[WorkspaceSettings].toOption))
        .flatMap {
          case None      => ZIO.none
          case Some(cfg) =>
            ZIO
              .attempt(Paths.get(cfg.root).toAbsolutePath.normalize().resolve("mcp-spill"))
              .fold(_ => None, Some(_))
        }
        .catchAll(_ => ZIO.none)

    workspaceSpillDir.flatMap { spillDir =>
      settings
        .get("mcp.servers").flatMap {
          case None =>
            registry.unregisterWhere(_.startsWith("mcp.")) *>
              ZIO.logDebug("No MCP servers configured (set 'mcp.servers' in server_settings to enable)")
          case Some(json) =>
            json.as[List[McpServerConfig]] match {
              case Left(err) =>
                ZIO.logWarning(s"Skipping all MCP servers: could not parse config: $err")
              case Right(configs) =>
                registry.unregisterWhere(_.startsWith("mcp.")) *>
                  ZIO.foreachDiscard(configs.filter(_.enabled)) { cfg =>
                    makeAdapter(cfg, spillDir)
                      .flatMap(adapter => registry.register(adapter))
                      .tapError(e => ZIO.logWarning(s"Skipping MCP server '${cfg.name}': ${e.msg}"))
                      .ignore
                  }
            }
        }.catchAll(e => ZIO.logWarning(s"MCP loadAndRegister error: ${e.msg}"))
    }
  }

  private def makeAdapter(
    cfg:      McpServerConfig,
    spillDir: Option[Path],
  ): ZIO[Scope, JorlanError, McpSkillAdapter] =
    cfg.transport match {
      case McpTransport.Http =>
        cfg.url match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': HTTP transport requires 'url'"))
          case Some(url) =>
            HttpMcpClient.make(client, url).flatMap { httpClient =>
              httpClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, httpClient, cfg.keywords, spillDir))
            }
        }
      case McpTransport.HttpSse =>
        cfg.url match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': HTTP+SSE transport requires 'url'"))
          case Some(url) =>
            HttpSseMcpClient.make(client, url).flatMap { httpClient =>
              httpClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, httpClient, cfg.keywords, spillDir))
            }
        }
      case McpTransport.Stdio =>
        cfg.command match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': stdio transport requires 'command'"))
          case Some(_) =>
            StdioMcpClient.make(cfg).flatMap { stdioClient =>
              stdioClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, stdioClient, cfg.keywords, spillDir))
            }
        }
    }

}

object McpManager {

  /** Constructs a [[McpManager]] whose subprocess resources live in the ZLayer's scope.
    *
    * Use `ZIO.serviceWithZIO[McpManager](_.loadAndRegister)` at startup and on reload. The previous set of subprocesses
    * is automatically killed before new ones are started.
    */
  val live: URLayer[SkillRegistry & Client & ZIORepositories, McpManager] =
    ZLayer.scoped {
      for {
        registry  <- ZIO.service[SkillRegistry]
        client    <- ZIO.service[Client]
        repos     <- ZIO.service[ZIORepositories]
        loadedRef <- ScopedRef.make(())
      } yield McpManagerImpl(registry, client, repos, loadedRef)
    }

}
