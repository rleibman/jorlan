/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db

import jorlan.*
import jorlan.db.repository.*
import zio.*
import zio.test.*

/** Integration tests for `QuillMcpServerRepository` (the dedicated `mcpServer` table from migration V039). */
object McpServerRepositorySpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

  private val stdioCfg = McpServerConfig(
    name = "grampsweb",
    transport = McpTransport.Stdio,
    command = Some("npx"),
    args = List("-y", "mcp-grampsweb"),
    env = Map("GRAMPS_API_URL" -> "https://example.test", "GRAMPS_USERNAME" -> "u"),
    url = None,
    enabled = false,
    keywords = List("gramps", "genealogy"),
  )

  /** An HTTP-transport server, which is the case that carries `headers` (V040) — how a remote MCP server is
    * authenticated.
    */
  private val httpCfg = McpServerConfig(
    name = "mealorama",
    transport = McpTransport.Http,
    command = None,
    args = List.empty,
    env = Map.empty,
    url = Some("http://localhost:8077/mcp"),
    enabled = true,
    keywords = List("recipe", "meal"),
    headers = Map("Authorization" -> "Bearer test-token", "X-Trace" -> "on"),
  )

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("McpServerRepository")(
      test("upsert then list round-trips all fields (args/env/keywords as JSON, transport as enum)") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.mcpServer)
          _      <- repo.upsertMcpServer(stdioCfg)
          all    <- repo.listMcpServers()
          stored = all.find(_.name == "grampsweb")
        } yield assertTrue(
          stored.contains(stdioCfg),
        )
      },
      test("upsert with an existing name replaces in place (no duplicate row)") {
        for {
          repo    <- ZIO.serviceWith[ZIORepositories](_.mcpServer)
          _       <- repo.upsertMcpServer(stdioCfg)
          _       <- repo.upsertMcpServer(stdioCfg.copy(enabled = true, keywords = List("changed")))
          all     <- repo.listMcpServers()
          matches = all.filter(_.name == "grampsweb")
        } yield assertTrue(
          matches.size == 1,
          matches.head.enabled,
          matches.head.keywords == List("changed"),
        )
      },
      test("round-trips headers on an HTTP server, and an upsert that clears them takes effect") {
        for {
          repo    <- ZIO.serviceWith[ZIORepositories](_.mcpServer)
          _       <- repo.upsertMcpServer(httpCfg)
          all     <- repo.listMcpServers()
          stored  = all.find(_.name == "mealorama")
          _       <- repo.upsertMcpServer(httpCfg.copy(headers = Map.empty))
          all2    <- repo.listMcpServers()
          cleared = all2.find(_.name == "mealorama")
        } yield assertTrue(
          stored.contains(httpCfg),
          stored.exists(_.headers("Authorization") == "Bearer test-token"),
          cleared.exists(_.headers.isEmpty),
        )
      },
      test("delete removes the row and reports whether one was removed") {
        for {
          repo    <- ZIO.serviceWith[ZIORepositories](_.mcpServer)
          _       <- repo.upsertMcpServer(stdioCfg.copy(name = "todelete"))
          removed <- repo.deleteMcpServer("todelete")
          again   <- repo.deleteMcpServer("todelete")
          all     <- repo.listMcpServers()
        } yield assertTrue(removed, !again, !all.exists(_.name == "todelete"))
      },
    ) @@ TestAspect.sequential

}
