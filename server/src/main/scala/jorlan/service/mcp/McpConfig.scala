/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import zio.json.*

enum McpTransport derives JsonCodec {

  /** Subprocess stdin/stdout (local MCP server). */
  case Stdio

  /** Streamable HTTP (MCP 2025-03-26): single POST endpoint, session via `mcp-session-id` header. */
  case Http

  /** HTTP+SSE (MCP 2024-11-05): GET establishes SSE stream, POST to messages endpoint. */
  case HttpSse

}

case class McpServerConfig(
  name:      String,
  transport: McpTransport,
  command:   Option[String] = None,
  args:      List[String] = List.empty,
  env:       Map[String, String] = Map.empty,
  url:       Option[String] = None,
  enabled:   Boolean = true,
  keywords:  List[String] = List.empty,
) derives JsonCodec
