/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.*

/** Transport used to reach an MCP (Model Context Protocol) server. */
enum McpTransport derives JsonCodec {

  /** Subprocess stdin/stdout (local MCP server). */
  case Stdio

  /** Streamable HTTP (MCP 2025-03-26): single POST endpoint, session via `mcp-session-id` header. */
  case Http

  /** HTTP+SSE (MCP 2024-11-05): GET establishes SSE stream, POST to messages endpoint. */
  case HttpSse

}

/** Configuration for one MCP server. Persisted in the dedicated `mcpServer` table (see `McpServerRepository`).
  *
  * `env` applies to [[McpTransport.Stdio]] only — it is the subprocess environment. `headers` applies to the HTTP
  * transports only, and is sent on every request; it is how a remote MCP server is authenticated (typically
  * `Authorization: Bearer …`). Like `env`, header values are stored in plaintext and are readable by any holder of
  * `admin.settings`.
  */
case class McpServerConfig(
  name:      String,
  transport: McpTransport,
  command:   Option[String] = None,
  args:      List[String] = List.empty,
  env:       Map[String, String] = Map.empty,
  url:       Option[String] = None,
  enabled:   Boolean = true,
  keywords:  List[String] = List.empty,
  headers:   Map[String, String] = Map.empty,
) derives JsonCodec
