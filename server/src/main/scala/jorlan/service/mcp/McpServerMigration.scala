/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import jorlan.*
import jorlan.db.repository.ZIORepositories
import zio.*
import zio.json.*

/** One-time migration of MCP server configs out of the legacy `server_settings['mcp.servers']` JSON blob into the
  * dedicated `mcpServer` table (created in migration V039). Runs at startup; a no-op once the setting is gone.
  *
  * Kept as an application-level step (rather than SQL in the Flyway migration) so it uses the same JSON codec as the
  * rest of the app and does not depend on a specific MariaDB JSON-function version.
  */
object McpServerMigration {

  private val LegacyKey = "mcp.servers"

  val run: ZIO[ZIORepositories, Nothing, Unit] = {
    val effect = for {
      repos <- ZIO.service[ZIORepositories]
      legacy <- repos.setting.get(LegacyKey)
      _ <- legacy match {
        case None => ZIO.unit
        case Some(json) =>
          json.as[List[McpServerConfig]] match {
            case Left(err) =>
              ZIO.logWarning(s"MCP migration: could not parse legacy '$LegacyKey' ($err); leaving it in place")
            case Right(configs) =>
              for {
                _ <- ZIO.logInfo(s"MCP migration: moving ${configs.size} server config(s) from '$LegacyKey' into the mcpServer table")
                _ <- ZIO.foreachDiscard(configs)(cfg => repos.mcpServer.upsertMcpServer(cfg))
                // Only drop the legacy key after every row is safely upserted.
                _ <- repos.setting.delete(LegacyKey)
              } yield ()
          }
      }
    } yield ()

    effect.catchAll(e => ZIO.logWarning(s"MCP migration failed (will retry next startup): ${e.msg}"))
  }

}
