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

  /** MCP registration is intentionally asynchronous (`loadAndRegister` forks so a slow/bad server never blocks
    * startup), so tests poll the registry until the expected mcp skill appears, bounded by a deadline.
    */
  private def awaitMcpSkills(iterationsLeft: Int = 200): ZIO[SkillRegistry, Throwable, List[jorlan.connector.Skill]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allSkills).flatMap { skills =>
      if (skills.exists(_.descriptor.name.startsWith("mcp."))) ZIO.succeed(skills)
      else if (iterationsLeft <= 0) ZIO.fail(new RuntimeException("MCP skill was not registered within the deadline"))
      else ZIO.sleep(50.millis) *> awaitMcpSkills(iterationsLeft - 1)
    }

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

  /** Same fake server, but records the headers of every request it receives, so a test can assert what actually went
    * out on the wire.
    */
  private def recordingMcpRoutes(
    seen:      Ref[List[Headers]],
    toolsJson: String,
  ): Routes[Any, Nothing] =
    Routes(
      Method.ANY / trailing -> handler {
        (
          _:   Path,
          req: Request,
        ) =>
          (seen.update(_ :+ req.headers) *> req.body.asString.map { body =>
            val responseBody =
              if (body.contains("\"initialize\"")) initializeResponseBody
              else if (body.contains("tools/list")) toolsListResponseBody(toolsJson)
              else """{"jsonrpc":"2.0","result":{}}"""
            Response(
              status = Status.Ok,
              headers = Headers(Header.ContentType(MediaType.application.json).untyped),
              body = Body.fromString(responseBody),
            )
          }).orDie
      },
    )

  /** Seed the dedicated mcpServer repository (the source of truth since MCP configs moved out of server_settings). */
  private def seed(configs: McpServerConfig*): URIO[ZIORepositories, Unit] =
    ZIO.serviceWithZIO[ZIORepositories](repos => ZIO.foreachDiscard(configs)(repos.mcpServer.upsertMcpServer(_).orDie))

  private val layer: TaskLayer[McpManager & SkillRegistry & ZIORepositories] =
    ZLayer.make[McpManager & SkillRegistry & ZIORepositories](
      InMemoryRepositories.live(),
      SkillRegistry.live,
      Client.default,
      McpManager.live,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("McpManagerSpec")(
      test("no MCP servers → loadAndRegister succeeds with no registrations") {
        for {
          _      <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield assert(skills)(isEmpty)
      }.provide(layer),
      test("disabled server is skipped") {
        for {
          _ <- seed(
            McpServerConfig("disabled", McpTransport.Http, url = Some("http://localhost:9999"), enabled = false),
          )
          _      <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield assert(skills)(isEmpty)
      }.provide(layer),
      test("one HTTP server with tools → adapter registered with correct tool names") {
        val toolsJson =
          """[{"name":"read_file","description":"Read a file","inputSchema":{"type":"object"}},{"name":"write_file","description":"Write a file","inputSchema":{"type":"object"}}]"""
        for {
          port <- Server.install(fakeMcpRoutes(toolsJson, "ok"))
          _    <- seed(
            McpServerConfig("testserver", McpTransport.Http, url = Some(s"http://localhost:$port/mcp"), enabled = true),
          )
          _      <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          skills <- awaitMcpSkills()
        } yield {
          val mcpSkills = skills.filter(_.descriptor.name.startsWith("mcp."))
          assert(mcpSkills)(hasSize(equalTo(1))) &&
          assert(mcpSkills.head.descriptor.name)(equalTo("mcp.testserver")) &&
          assert(mcpSkills.head.descriptor.tools.map(_.name))(
            equalTo(List("mcp.testserver.read_file", "mcp.testserver.write_file")),
          )
        }
      }.provide(Server.defaultWith(_.port(0)), layer),
      test("configured headers are sent on every request to an HTTP MCP server") {
        // Without this an authenticated MCP server (e.g. meal-o-rama) is unreachable: before `headers` existed the
        // client sent only Content-Type and mcp-session-id, so every request came back 401.
        val toolsJson = """[{"name":"search_recipes","description":"Find recipes","inputSchema":{"type":"object"}}]"""
        for {
          seen <- Ref.make(List.empty[Headers])
          port <- Server.install(recordingMcpRoutes(seen, toolsJson))
          _    <- seed(
            McpServerConfig(
              "mealorama",
              McpTransport.Http,
              url = Some(s"http://localhost:$port/mcp"),
              enabled = true,
              headers = Map("Authorization" -> "Bearer secret-token"),
            ),
          )
          _        <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
          _        <- awaitMcpSkills()
          requests <- seen.get
        } yield assertTrue(
          requests.nonEmpty,
          // initialize, notifications/initialized and tools/list must all carry it — not just the first.
          requests.forall(_.get("Authorization").contains("Bearer secret-token")),
        )
      }.provide(Server.defaultWith(_.port(0)), layer),
      test("HTTP server fails to respond → warning logged, registration skipped, no error") {
        for {
          _ <- seed(
            McpServerConfig("badserver", McpTransport.Http, url = Some("http://localhost:19999/mcp"), enabled = true),
          )
          result <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister).exit
          skills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
        } yield assert(result)(succeeds(anything)) && assert(skills)(isEmpty)
      }.provide(layer),
    ) @@ TestAspect.withLiveClock

}
