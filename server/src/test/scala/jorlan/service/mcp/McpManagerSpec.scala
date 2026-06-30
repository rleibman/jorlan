/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import jorlan.*
import jorlan.db.repository.{ZIORepositories, ZIOServerSettingsRepository}
import jorlan.service.skills.SkillRegistry
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object McpManagerSpec extends ZIOSpecDefault {

  private val initializeResponseBody: String =
    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fake","version":"1.0"}}}"""

  private def toolsListResponseBody(toolsJson: String): String =
    s"""{"jsonrpc":"2.0","id":2,"result":{"tools":$toolsJson}}"""

  private def callResultResponseBody(resultText: String): String =
    s"""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"$resultText"}],"isError":false}}"""

  private def fakeMcpRoutes(
    toolsJson:      String,
    callResultText: String,
  ): Routes[Any, Nothing] =
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
              else if (body.contains("notifications/initialized")) """{"jsonrpc":"2.0","result":{}}"""
              else """{"jsonrpc":"2.0","result":{}}"""
            Response(
              status = Status.Ok,
              headers = Headers(Header.ContentType(MediaType.application.json).untyped),
              body = Body.fromString(responseBody),
            )
          }.orDie
      },
    )

  private def reposLayer(settings: Map[String, Json]): ULayer[ZIORepositories] =
    ZLayer
      .fromZIO(
        InMemoryRepositories.InMemoryServerSettingsRepo
          .make(settings).map(settingsRepo => InMemoryRepositories.live(settingsRepoOpt = Some(settingsRepo))),
      ).flatten

  private def makeLayer(settings: Map[String, Json]): TaskLayer[McpManager & SkillRegistry & ZIORepositories] =
    ZLayer.make[McpManager & SkillRegistry & ZIORepositories](
      reposLayer(settings),
      SkillRegistry.live,
      Client.default,
      McpManager.live,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("McpManagerSpec")(
      test("no mcp.servers key → loadAndRegister succeeds with no registrations") {
        for {
          _      <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield assert(skills)(isEmpty)
      }.provide(makeLayer(Map.empty)),
      test("empty mcp.servers array → no registrations") {
        for {
          _      <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield assert(skills)(isEmpty)
      }.provide(makeLayer(Map("mcp.servers" -> Json.Arr()))),
      test("disabled server is skipped") {
        val cfgJson = List(
          McpServerConfig(
            name = "disabled",
            transport = McpTransport.Http,
            url = Some("http://localhost:9999"),
            enabled = false,
          ),
        ).toJson
        val settings = Map("mcp.servers" -> Json.decoder.decodeJson(cfgJson).getOrElse(Json.Arr()))
        for {
          _      <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield assert(skills)(isEmpty)
      }.provide(
        makeLayer(
          Map(
            "mcp.servers" -> Json.decoder
              .decodeJson(
                List(
                  McpServerConfig(
                    name = "disabled",
                    transport = McpTransport.Http,
                    url = Some("http://localhost:9999"),
                    enabled = false,
                  ),
                ).toJson,
              ).getOrElse(Json.Arr()),
          ),
        ),
      ),
      test("one HTTP server with tools → adapter registered with correct tool names") {
        val toolsJson =
          """[{"name":"read_file","description":"Read a file","inputSchema":{"type":"object"}},{"name":"write_file","description":"Write a file","inputSchema":{"type":"object"}}]"""
        val cfgJson = List(
          McpServerConfig(
            name = "testserver",
            transport = McpTransport.Http,
            url = Some("__PORT__"),
            enabled = true,
          ),
        ).toJson
        for {
          port <- Server.install(fakeMcpRoutes(toolsJson, "ok"))
          _    <- ZIO.serviceWithZIO[ZIORepositories](
            _.setting.set(
              "mcp.servers",
              Json.decoder
                .decodeJson(cfgJson.replace("__PORT__", s"http://localhost:$port/mcp"))
                .getOrElse(Json.Arr()),
            ),
          )
          _      <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield {
          val mcpSkills = skills.filter(_.descriptor.name.startsWith("mcp."))
          assert(mcpSkills)(hasSize(equalTo(1))) &&
          assert(mcpSkills.head.descriptor.name)(equalTo("mcp.testserver")) &&
          assert(mcpSkills.head.descriptor.tools.map(_.name))(
            equalTo(List("mcp.testserver.read_file", "mcp.testserver.write_file")),
          )
        }
      }.provide(Server.defaultWith(_.port(0)), makeLayer(Map.empty)),
      test("HTTP server fails to respond → warning logged, registration skipped, no error") {
        val settings = Map(
          "mcp.servers" -> Json.decoder
            .decodeJson(
              List(
                McpServerConfig(
                  name = "badserver",
                  transport = McpTransport.Http,
                  url = Some("http://localhost:19999/mcp"),
                  enabled = true,
                ),
              ).toJson,
            ).getOrElse(Json.Arr()),
        )
        for {
          result <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister).exit
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield assert(result)(succeeds(anything)) && assert(skills)(isEmpty)
      }.provide(
        makeLayer(
          Map(
            "mcp.servers" -> Json.decoder
              .decodeJson(
                List(
                  McpServerConfig(
                    name = "badserver",
                    transport = McpTransport.Http,
                    url = Some("http://localhost:19999/mcp"),
                    enabled = true,
                  ),
                ).toJson,
              ).getOrElse(Json.Arr()),
          ),
        ),
      ),
    ) @@ TestAspect.withLiveClock

}
