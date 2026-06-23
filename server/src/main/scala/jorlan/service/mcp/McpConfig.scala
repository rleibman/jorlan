/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import zio.json.*

enum McpTransport derives JsonCodec {

  case Stdio, Http

}

case class McpServerConfig(
  name:      String,
  transport: McpTransport,
  command:   Option[String] = None,
  args:      List[String] = List.empty,
  env:       Map[String, String] = Map.empty,
  url:       Option[String] = None,
  enabled:   Boolean = true,
) derives JsonCodec
