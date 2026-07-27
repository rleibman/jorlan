/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import jorlan.*
import zio.*
import zio.test.*

object StdioMcpClientSpec extends ZIOSpecDefault {

  /** A subprocess that starts, stays alive, and never writes a byte — the shape of a healthy but idle MCP server. */
  private val silentServer: McpServerConfig = McpServerConfig(
    name = "silent",
    transport = McpTransport.Stdio,
    command = Some("sleep"),
    args = List("60"),
    enabled = true,
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("StdioMcpClientSpec")(
      // Regression: the stdout/stderr drain fibers park in a blocking read that ZIO cannot preempt, so releasing
      // them before killing the process left the scope close waiting on an interrupt that could never land. Every
      // MCP reload closes the previous scope, so a single live stdio server was enough to wedge `upsertMcpServer`
      // (and any other reload) forever.
      test("closing the scope of a live, silent stdio server terminates promptly") {
        ZIO
          .scoped(StdioMcpClient.make(silentServer, initTimeout = 500.millis).unit)
          .timeout(20.seconds)
          .map(closed => assertTrue(closed.isDefined))
      },
    ) @@ TestAspect.withLiveClock

}
