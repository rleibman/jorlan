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
import jorlan.db.repository.ZIOServerSettingsRepository
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object McpManagerSpec extends ZIOSpecDefault {

  // Minimal fake MCP server: responds to initialize, tools/list, tools/call
  private val initializeResponseBody: String =
    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fake","version":"1.0"}}}"""

  private def toolsListResponseBody(toolsJson: String): String =
    s"""{"jsonrpc":"2.0","id":2,"result":{"tools":$toolsJson}}"""

  private def callResultResponseBody(resultText: String): String =
    s"""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"$resultText"}],"isError":false}}"""

  private def fakeMcpRoutes(
    toolsJson:      String,
    callResultText: String,
  ): Routes[Any, Nothing] = {
    Routes(
      Method.ANY / trailing -> handler {
        (
          _:   Path,
          req: Request,
        ) =>
          req.body.asString.map { body =>
            val responseBody =
              if (body.contains("\"initialize\"")) initializeResponseBody
              else if (body.contains("tools/list")) toolsListResponseBody(toolsJson)
              else if (body.contains("tools/call")) callResultResponseBody(callResultText)
              else if (body.contains("notifications/initialized"))
                """{"jsonrpc":"2.0","result":{}}"""
              else """{"jsonrpc":"2.0","result":{}}"""

            Response(
              status = Status.Ok,
              headers = Headers(Header.ContentType(MediaType.application.json).untyped),
              body = Body.fromString(responseBody),
            )
          }.orDie
      },
    )
  }

  private class FakeSettingsRepo(settingsMap: Map[String, Json]) extends ZIOServerSettingsRepository {

    override def get(key: String): UIO[Option[Json]] = ZIO.succeed(settingsMap.get(key))

    override def set(
      key:   String,
      value: Json,
    ): UIO[Unit] = ZIO.unit

    override def serverPersonality(): UIO[Option[jorlan.Personality]] = ZIO.succeed(None)

    override def updatePersonality(
      name:      String,
      formality: jorlan.Formality,
      languages: List[String],
      expertise: List[String],
      prompt:    String,
    ): UIO[Option[jorlan.Personality]] = ZIO.succeed(None)

  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("McpManagerSpec")(
      test("no mcp.servers key → loadAndRegister succeeds with no registrations") {
        val settings = FakeSettingsRepo(Map.empty)
        for {
          registry <- ZIO.service[SkillRegistry]
          client   <- ZIO.service[Client]
          manager = McpManagerImpl(registry, client, settings)
          _      <- ZIO.scoped(manager.loadAndRegister)
          skills <- registry.allSkills
        } yield assert(skills)(isEmpty)
      }.provide(SkillRegistry.live, Client.default),
      test("empty mcp.servers array → no registrations") {
        val settings = FakeSettingsRepo(Map("mcp.servers" -> Json.Arr()))
        for {
          registry <- ZIO.service[SkillRegistry]
          client   <- ZIO.service[Client]
          manager = McpManagerImpl(registry, client, settings)
          _      <- ZIO.scoped(manager.loadAndRegister)
          skills <- registry.allSkills
        } yield assert(skills)(isEmpty)
      }.provide(SkillRegistry.live, Client.default),
      test("disabled server is skipped") {
        val cfg = McpServerConfig(
          name = "disabled",
          transport = McpTransport.Http,
          url = Some("http://localhost:9999"),
          enabled = false,
        )
        val cfgJson = List(cfg).toJson
        val settings = FakeSettingsRepo(
          Map("mcp.servers" -> Json.decoder.decodeJson(cfgJson).getOrElse(Json.Arr())),
        )
        for {
          registry <- ZIO.service[SkillRegistry]
          client   <- ZIO.service[Client]
          manager = McpManagerImpl(registry, client, settings)
          _      <- ZIO.scoped(manager.loadAndRegister)
          skills <- registry.allSkills
        } yield assert(skills)(isEmpty)
      }.provide(SkillRegistry.live, Client.default),
      test("one HTTP server with tools → adapter registered with correct tool names") {
        val toolsJson =
          """[{"name":"read_file","description":"Read a file","inputSchema":{"type":"object"}},{"name":"write_file","description":"Write a file","inputSchema":{"type":"object"}}]"""
        for {
          port     <- Server.install(fakeMcpRoutes(toolsJson, "ok"))
          registry <- ZIO.service[SkillRegistry]
          client   <- ZIO.service[Client]
          cfg = McpServerConfig(
            name = "testserver",
            transport = McpTransport.Http,
            url = Some(s"http://localhost:$port/mcp"),
            enabled = true,
          )
          cfgJson = List(cfg).toJson
          settings = FakeSettingsRepo(
            Map("mcp.servers" -> Json.decoder.decodeJson(cfgJson).getOrElse(Json.Arr())),
          )
          manager = McpManagerImpl(registry, client, settings)
          _      <- ZIO.scoped(manager.loadAndRegister)
          skills <- registry.allSkills
        } yield {
          val mcpSkills = skills.filter(_.descriptor.name.startsWith("mcp_"))
          assert(mcpSkills)(hasSize(equalTo(1))) &&
          assert(mcpSkills.head.descriptor.name)(equalTo("mcp_testserver")) &&
          assert(mcpSkills.head.descriptor.tools.map(_.name))(
            equalTo(List("mcp_testserver.read_file", "mcp_testserver.write_file")),
          )
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default, SkillRegistry.live),
      test("HTTP server fails to respond → warning logged, registration skipped, no error") {
        // Use a port that is not listening
        val cfg = McpServerConfig(
          name = "badserver",
          transport = McpTransport.Http,
          url = Some("http://localhost:19999/mcp"),
          enabled = true,
        )
        val cfgJson = List(cfg).toJson
        val settings = FakeSettingsRepo(
          Map("mcp.servers" -> Json.decoder.decodeJson(cfgJson).getOrElse(Json.Arr())),
        )
        for {
          registry <- ZIO.service[SkillRegistry]
          client   <- ZIO.service[Client]
          manager = McpManagerImpl(registry, client, settings)
          // Should succeed even though the server is unreachable (error is logged and ignored)
          result <- ZIO.scoped(manager.loadAndRegister).exit
          skills <- registry.allSkills
        } yield assert(result)(succeeds(anything)) && assert(skills)(isEmpty)
      }.provide(SkillRegistry.live, Client.default),
    ) @@ TestAspect.withLiveClock

}
