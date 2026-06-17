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

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

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
      test("descriptor.name uses mcp_<serverName> with underscore") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        assert(adapter.descriptor.name)(equalTo("mcp_myserver"))
      },
      test("descriptor.tools are namespaced as mcp_<serverName>.<toolName>") {
        val client = FakeMcpClient(sampleTools, "result")
        val adapter = McpSkillAdapter("myserver", sampleTools, client)
        val toolNames = adapter.descriptor.tools.map(_.name)
        assert(toolNames)(equalTo(List("mcp_myserver.read_file", "mcp_myserver.write_file")))
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
          result <- adapter.invoke(dummyCtx, "mcp_myserver.read_file", Json.Obj("path" -> Json.Str("/tmp/test.txt")))
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
          result <- adapter.invoke(dummyCtx, "mcp_otherserver.read_file", Json.Obj()).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("empty tool list yields empty descriptor.tools") {
        val client = FakeMcpClient(Nil, "result")
        val adapter = McpSkillAdapter("emptyserver", Nil, client)
        assert(adapter.descriptor.tools)(isEmpty)
      },
    )

}
