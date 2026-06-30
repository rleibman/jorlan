/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.{Files, Path}

object McpSkillAdapterSpec extends ZIOSpecDefault {

  private class FakeMcpClient(
    toolsToReturn: List[McpTool],
    callResult:    String,
  ) extends McpClient {

    override def listTools: IO[JorlanError, List[McpTool]] = ZIO.succeed(toolsToReturn)
    override def callTool(
      name:      String,
      arguments: Json,
    ): IO[JorlanError, String] = ZIO.succeed(callResult)

  }

  private val sampleTools: List[McpTool] = List(
    McpTool("read_file", Some("Read a file"), Json.Obj("type" -> Json.Str("object"))),
    McpTool("write_file", Some("Write a file"), Json.Obj("type" -> Json.Str("object"))),
  )

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("McpSkillAdapterSpec")(
      test("descriptor.name uses mcp.<serverName> dot notation") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        assert(adapter.descriptor.name)(equalTo("mcp.myserver"))
      },
      test("descriptor.tools are namespaced as mcp.<serverName>.<toolName>") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        val toolNames = adapter.descriptor.tools.map(_.name)
        assert(toolNames)(equalTo(List("mcp.myserver.read_file", "mcp.myserver.write_file")))
      },
      test("descriptor.tier is SkillTier.Imported") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        assert(adapter.descriptor.tier)(equalTo(SkillTier.Imported))
      },
      test("descriptor.tools.requiredCapabilities contains mcp.call") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        val allCaps = adapter.descriptor.tools.flatMap(_.requiredCapabilities).distinct
        assert(allCaps)(contains(CapabilityName("mcp.call")))
      },
      test("invoke strips prefix and calls client.callTool, wrapping result in Json.Str") {
        val client = FakeMcpClient(sampleTools, "file contents here")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        for {
          result <- adapter.invoke(dummyCtx, "mcp.myserver.read_file", Json.Obj("path" -> Json.Str("/tmp/test.txt")))
        } yield assert(result)(equalTo(Json.Str("file contents here")))
      },
      test("invoke with wrong namespace fails with JorlanError") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        for {
          result <- adapter.invoke(dummyCtx, "other_skill.some_tool", Json.Obj()).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("invoke with tool from different server name fails with JorlanError") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        for {
          result <- adapter.invoke(dummyCtx, "mcp.otherserver.read_file", Json.Obj()).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("empty tool list yields empty descriptor.tools") {
        val client = FakeMcpClient(List.empty, "result")
        val adapter = McpSkillAdapter("emptyserver", List.empty, client)
        assert(adapter.descriptor.tools)(isEmpty)
      },
      test("serverName with dots is preserved in namespace (dots are valid with longest-prefix routing)") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("my.server.com", sampleTools, client)
        assert(adapter.descriptor.name)(equalTo("mcp.my.server.com")) &&
        assert(adapter.descriptor.tools.map(_.name))(
          equalTo(List("mcp.my.server.com.read_file", "mcp.my.server.com.write_file")),
        )
      },
      test("serverName with non-dot special characters is sanitized to underscores") {
        val client = FakeMcpClient(List.empty, "result")
        val adapter = McpSkillAdapter("my-server/v2", List.empty, client)
        assert(adapter.descriptor.name)(equalTo("mcp.my_server_v2"))
      },
      test("invoke returns inline result when result is under spill threshold") {
        val smallResult = "x" * 100
        val client = FakeMcpClient(sampleTools, smallResult)
        val adapter = McpSkillAdapter("myserver", sampleTools, client, spillThresholdBytes = 200)
        for {
          result <- adapter.invoke(dummyCtx, "mcp.myserver.read_file", Json.Obj())
        } yield assert(result)(equalTo(Json.Str(smallResult)))
      },
      test("invoke returns inline result when result exceeds threshold but spillDir is None") {
        val bigResult = "x" * 10000
        val client = FakeMcpClient(sampleTools, bigResult)
        val adapter = McpSkillAdapter("myserver", sampleTools, client, spillDir = None, spillThresholdBytes = 100)
        for {
          result <- adapter.invoke(dummyCtx, "mcp.myserver.read_file", Json.Obj())
        } yield assert(result)(equalTo(Json.Str(bigResult)))
      },
      test("invoke spills to workspace file when result exceeds threshold and spillDir is set") {
        val bigResult = """{"items": [""" + (1 to 1000).map(i => s"""{"id":$i}""").mkString(",") + "]}"
        val client = FakeMcpClient(sampleTools, bigResult)
        for {
          tmpDir <- ZIO.attempt(Files.createTempDirectory("mcp-spill-test")).orDie
          adapter = McpSkillAdapter("myserver", sampleTools, client, spillDir = Some(tmpDir), spillThresholdBytes = 100)
          result <- adapter.invoke(dummyCtx, "mcp.myserver.read_file", Json.Obj())
          _      <- ZIO.attempt(tmpDir.toFile.listFiles().foreach(_.delete())).orDie
          _      <- ZIO.attempt(Files.delete(tmpDir)).orDie
        } yield {
          val desc = result match {
            case Json.Str(s) => s
            case other       => other.toString
          }
          assertTrue(
            desc.contains("Result too large"),
            desc.contains("mcp_myserver_read_file"),
            desc.contains("workspace.read"),
          )
        }
      },
      test("invoke spill file is named after server and tool") {
        val bigResult = "y" * 10000
        val client = FakeMcpClient(sampleTools, bigResult)
        for {
          tmpDir <- ZIO.attempt(Files.createTempDirectory("mcp-spill-name-test")).orDie
          adapter = McpSkillAdapter(
            "my-server",
            sampleTools,
            client,
            spillDir = Some(tmpDir),
            spillThresholdBytes = 100,
          )
          _     <- adapter.invoke(dummyCtx, "mcp.my_server.read_file", Json.Obj())
          files <- ZIO.attempt(Option(tmpDir.toFile.listFiles()).toList.flatten).orDie
          _     <- ZIO.attempt { files.foreach(_.delete()); Files.delete(tmpDir) }.orDie
        } yield assertTrue(
          files.exists(f => f.getName.startsWith("mcp_") && f.getName.endsWith(".json")),
        )
      },
    )

}
