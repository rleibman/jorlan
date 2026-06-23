/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.mcp

import jorlan.*
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object McpParserSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("McpParserSpec")(
      // ─── parseToolsList ───────────────────────────────────────────────────────
      suite("parseToolsList")(
        test("parses a list with one tool") {
          val result = Json.decoder
            .decodeJson(
              """{"tools":[{"name":"read_file","description":"Read a file","inputSchema":{"type":"object"}}]}""",
            ).getOrElse(Json.Obj())
          for {
            tools <- parseToolsList(result)
          } yield {
            assert(tools)(hasSize(equalTo(1))) &&
            assertTrue(tools.head.name == "read_file") &&
            assertTrue(tools.head.description.contains("Read a file"))
          }
        },
        test("parses a list with multiple tools") {
          val result = Json.decoder
            .decodeJson(
              """{"tools":[{"name":"tool_a","inputSchema":{}},{"name":"tool_b","description":"B","inputSchema":{}}]}""",
            ).getOrElse(Json.Obj())
          for {
            tools <- parseToolsList(result)
          } yield assert(tools)(hasSize(equalTo(2))) &&
            assertTrue(tools.map(_.name) == List("tool_a", "tool_b"))
        },
        test("returns empty list when tools array is absent") {
          val result = Json.Obj("other" -> Json.Str("field"))
          for {
            tools <- parseToolsList(result)
          } yield assert(tools)(isEmpty)
        },
        test("returns empty list when result is not an object") {
          val result = Json.Arr()
          for {
            tools <- parseToolsList(result)
          } yield assert(tools)(isEmpty)
        },
        test("fails when a tool entry is missing 'name'") {
          val result = Json.decoder
            .decodeJson("""{"tools":[{"description":"No name","inputSchema":{}}]}""")
            .getOrElse(Json.Obj())
          for {
            exit <- parseToolsList(result).exit
          } yield assert(exit)(failsWithA[JorlanError])
        },
        test("fails when a tool has an empty 'name'") {
          val result = Json.decoder
            .decodeJson("""{"tools":[{"name":"","inputSchema":{}}]}""")
            .getOrElse(Json.Obj())
          for {
            exit <- parseToolsList(result).exit
          } yield assert(exit)(failsWithA[JorlanError])
        },
        test("fails when a tool entry is not an object") {
          val result = Json.decoder
            .decodeJson("""{"tools":["not-an-object"]}""")
            .getOrElse(Json.Obj())
          for {
            exit <- parseToolsList(result).exit
          } yield assert(exit)(failsWithA[JorlanError])
        },
        test("uses empty Obj as default inputSchema when absent") {
          val result = Json.decoder
            .decodeJson("""{"tools":[{"name":"bare_tool"}]}""")
            .getOrElse(Json.Obj())
          for {
            tools <- parseToolsList(result)
          } yield assertTrue(tools.head.inputSchema == Json.Obj())
        },
      ),
      // ─── parseCallResult ──────────────────────────────────────────────────────
      suite("parseCallResult")(
        test("returns combined text from content array") {
          val result = Json.decoder
            .decodeJson(
              """{"content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}],"isError":false}""",
            ).getOrElse(Json.Obj())
          for {
            text <- parseCallResult(result)
          } yield assertTrue(text == "hello\nworld")
        },
        test("fails when isError is true") {
          val result = Json.decoder
            .decodeJson(
              """{"content":[{"type":"text","text":"something went wrong"}],"isError":true}""",
            ).getOrElse(Json.Obj())
          for {
            exit <- parseCallResult(result).exit
          } yield assert(exit)(failsWithA[JorlanError])
        },
        test("returns JSON string when result is not an object") {
          val result = Json.Str("raw string")
          for {
            text <- parseCallResult(result)
          } yield assertTrue(text == "\"raw string\"")
        },
        test("returns empty string when content array is empty") {
          val result = Json.decoder
            .decodeJson("""{"content":[],"isError":false}""")
            .getOrElse(Json.Obj())
          for {
            text <- parseCallResult(result)
          } yield assertTrue(text == "")
        },
        test("returns empty string when content field is absent") {
          val result = Json.Obj("isError" -> Json.Bool(false))
          for {
            text <- parseCallResult(result)
          } yield assertTrue(text == "")
        },
      ),
    )

}
