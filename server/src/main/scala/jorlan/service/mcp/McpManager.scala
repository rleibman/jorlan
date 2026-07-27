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

  override def loadAndRegister: UIO[Unit] = {
    loadedRef.set(doLoad())
  }

  private def doLoad(): ZIO[Scope, Nothing, Unit] = {
    val workspaceCfg: UIO[Option[WorkspaceSettings]] =
      settings
        .get("skill.workspace")
        .map(_.flatMap(_.as[WorkspaceSettings].toOption))
        .catchAll(_ => ZIO.none)

    workspaceCfg.flatMap { wsCfg =>
      repos.mcpServer
        .listMcpServers()
        .flatMap { configs =>
          val enabled = configs.filter(_.enabled)
          // Register servers in the background, in parallel, each individually bounded: a server that fails to
          // initialize (bad command, unreachable endpoint, npx 404, hang) must never block or slow server
          // startup — it is logged and skipped, and the rest still come up. Its tools simply appear a moment
          // after the HTTP server does.
          registry.unregisterWhere(_.startsWith("mcp.")) *> {
            if (enabled.isEmpty) ZIO.logDebug("No enabled MCP servers configured")
            else ZIO.foreachParDiscard(enabled)(cfg => registerServer(cfg, wsCfg)).forkScoped.unit
          }
        }
        .catchAll(e => ZIO.logWarning(s"MCP loadAndRegister error: ${e.msg}"))
    }
  }

  /** Longest a single MCP server may take to connect + list its tools before it is treated as unavailable and
    * skipped. Generous enough for a valid server whose package `npx`/`docker` must first download.
    */
  private val serverInitTimeout: Duration = Duration.fromSeconds(45)

  private def registerServer(
    cfg:   McpServerConfig,
    wsCfg: Option[WorkspaceSettings],
  ): ZIO[Scope, Nothing, Unit] =
    makeAdapter(cfg, wsCfg)
      .flatMap(adapter => registry.register(adapter))
      .timeoutFail(JorlanError(s"initialization timed out after ${serverInitTimeout.getSeconds}s"))(serverInitTimeout)
      .foldZIO(
        e => ZIO.logWarning(s"Skipping MCP server '${cfg.name}': ${e.msg}"),
        _ => ZIO.logInfo(s"MCP server '${cfg.name}' registered"),
      )

  private def makeAdapter(
    cfg:   McpServerConfig,
    wsCfg: Option[WorkspaceSettings],
  ): ZIO[Scope, JorlanError, McpSkillAdapter] =
    cfg.transport match {
      case McpTransport.Http =>
        cfg.url match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': HTTP transport requires 'url'"))
          case Some(url) =>
            HttpMcpClient.make(client, url, cfg.headers).flatMap { httpClient =>
              httpClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, httpClient, cfg.keywords, wsCfg))
            }
        }
      case McpTransport.HttpSse =>
        cfg.url match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': HTTP+SSE transport requires 'url'"))
          case Some(url) =>
            HttpSseMcpClient.make(client, url, cfg.headers).flatMap { httpClient =>
              httpClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, httpClient, cfg.keywords, wsCfg))
            }
        }
      case McpTransport.Stdio =>
        cfg.command match {
          case None =>
            ZIO.fail(JorlanError(s"MCP server '${cfg.name}': stdio transport requires 'command'"))
          case Some(_) =>
            StdioMcpClient.make(cfg).flatMap { stdioClient =>
              stdioClient.listTools.map(tools => McpSkillAdapter(cfg.name, tools, stdioClient, cfg.keywords, wsCfg))
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
