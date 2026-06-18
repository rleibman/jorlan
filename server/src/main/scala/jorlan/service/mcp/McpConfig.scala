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

import zio.json.*

enum McpTransport derives JsonDecoder, JsonEncoder {

  case Stdio, Http

}

case class McpServerConfig(
  name:      String,
  transport: McpTransport,
  command:   Option[String] = None,
  args:      List[String] = Nil,
  env:       Map[String, String] = Map.empty,
  url:       Option[String] = None,
  enabled:   Boolean = true,
)

object McpServerConfig {

  given JsonDecoder[McpServerConfig] = DeriveJsonDecoder.gen[McpServerConfig]
  given JsonEncoder[McpServerConfig] = DeriveJsonEncoder.gen[McpServerConfig]

}
