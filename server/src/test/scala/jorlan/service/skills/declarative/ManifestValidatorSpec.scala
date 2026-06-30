/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ManifestValidatorSpec extends ZIOSpecDefault {

  private def makeManifest(
    name:    String = "weather",
    version: String = "1.0.0",
    desc:    String = "A weather skill",
    tools:   String = """[{
      "name": "weather.get_forecast",
      "description": "Get a weather forecast",
      "requiredCapabilities": [],
      "examplePrompts": [],
      "inputSchema": {"type": "object"},
      "outputSchema": {"type": "string"},
      "executor": {"HttpApi": {"config": {"method": "GET", "url": "https://api.example.com/forecast", "headers": {}, "bodyTemplate": null, "responseJsonPath": null}}}
    }]""",
  ): Json = {
    val raw =
      s"""{"name": ${name.toJson}, "version": ${version.toJson}, "description": ${desc.toJson}, "keywords": [], "tools": $tools}"""
    raw.fromJson[Json].getOrElse(Json.Obj())
  }

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("ManifestValidator")(
      test("valid http_api manifest passes") {
        val result = ManifestValidator.validate(makeManifest())
        assertTrue(result.isRight)
      },
      test("valid prompt_template manifest passes") {
        val manifest = makeManifest(
          tools = """[{
            "name": "recipe.suggest",
            "description": "Suggest a recipe",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"PromptTemplate": {"config": {"systemPrompt": "You are a chef.", "userPromptTemplate": "Suggest a recipe for {{ingredient}}"}}}
          }]""",
          name = "recipe",
        )
        assertTrue(ManifestValidator.validate(manifest).isRight)
      },
      test("invalid JSON fails gracefully") {
        val bad = Json.Str("not a manifest")
        assertTrue(ManifestValidator.validate(bad).isLeft)
      },
      test("skill name with uppercase fails") {
        val result = ManifestValidator.validate(makeManifest(name = "Weather"))
        assertTrue(result.isLeft, result.left.getOrElse(Nil).exists(_.contains("Skill name")))
      },
      test("skill name with spaces fails") {
        val result = ManifestValidator.validate(makeManifest(name = "my skill"))
        assertTrue(result.isLeft)
      },
      test("skill name with underscore and digits passes") {
        val manifest = makeManifest(
          name = "weather_v2",
          tools = """[{
            "name": "weather_v2.get",
            "description": "Get weather",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"HttpApi": {"config": {"method": "GET", "url": "https://api.example.com", "headers": {}, "bodyTemplate": null, "responseJsonPath": null}}}
          }]""",
        )
        assertTrue(ManifestValidator.validate(manifest).isRight)
      },
      test("empty version fails") {
        val result = ManifestValidator.validate(makeManifest(version = ""))
        assertTrue(result.isLeft)
      },
      test("empty description fails") {
        val result = ManifestValidator.validate(makeManifest(desc = ""))
        assertTrue(result.isLeft)
      },
      test("empty tools list fails") {
        val result = ManifestValidator.validate(makeManifest(tools = "[]"))
        assertTrue(result.isLeft)
      },
      test("tool name without skill prefix fails") {
        val manifest = makeManifest(tools = """[{
            "name": "get_forecast",
            "description": "Get forecast",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"HttpApi": {"config": {"method": "GET", "url": "https://api.example.com", "headers": {}, "bodyTemplate": null, "responseJsonPath": null}}}
          }]""")
        val result = ManifestValidator.validate(manifest)
        assertTrue(result.isLeft, result.left.getOrElse(Nil).exists(_.contains("prefixed")))
      },
      test("unsupported HTTP method fails") {
        val manifest = makeManifest(tools = """[{
            "name": "weather.get",
            "description": "Get weather",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"HttpApi": {"config": {"method": "CONNECT", "url": "https://api.example.com", "headers": {}, "bodyTemplate": null, "responseJsonPath": null}}}
          }]""")
        val result = ManifestValidator.validate(manifest)
        assertTrue(result.isLeft)
      },
      test("empty URL in http_api fails") {
        val manifest = makeManifest(tools = """[{
            "name": "weather.get",
            "description": "Get weather",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"HttpApi": {"config": {"method": "GET", "url": "", "headers": {}, "bodyTemplate": null, "responseJsonPath": null}}}
          }]""")
        val result = ManifestValidator.validate(manifest)
        assertTrue(result.isLeft)
      },
      test("empty userPromptTemplate in prompt_template fails") {
        val manifest = makeManifest(
          name = "recipe",
          tools = """[{
            "name": "recipe.suggest",
            "description": "Suggest recipe",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"PromptTemplate": {"config": {"systemPrompt": "You are a chef.", "userPromptTemplate": ""}}}
          }]""",
        )
        val result = ManifestValidator.validate(manifest)
        assertTrue(result.isLeft, result.left.getOrElse(Nil).exists(_.contains("userPromptTemplate")))
      },
      test("empty tool description fails") {
        val manifest = makeManifest(tools = """[{
            "name": "weather.get",
            "description": "",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"HttpApi": {"config": {"method": "GET", "url": "https://api.example.com", "headers": {}, "bodyTemplate": null, "responseJsonPath": null}}}
          }]""")
        val result = ManifestValidator.validate(manifest)
        assertTrue(result.isLeft, result.left.getOrElse(Nil).exists(_.contains("description")))
      },
      test("non-object inputSchema fails") {
        val manifest = makeManifest(tools = """[{
            "name": "weather.get",
            "description": "Get weather",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": "not an object",
            "outputSchema": {"type": "string"},
            "executor": {"HttpApi": {"config": {"method": "GET", "url": "https://api.example.com", "headers": {}, "bodyTemplate": null, "responseJsonPath": null}}}
          }]""")
        val result = ManifestValidator.validate(manifest)
        assertTrue(result.isLeft, result.left.getOrElse(Nil).exists(_.contains("inputSchema")))
      },
      test("empty systemPrompt in prompt_template fails") {
        val manifest = makeManifest(
          name = "recipe",
          tools = """[{
            "name": "recipe.suggest",
            "description": "Suggest recipe",
            "requiredCapabilities": [],
            "examplePrompts": [],
            "inputSchema": {"type": "object"},
            "outputSchema": {"type": "string"},
            "executor": {"PromptTemplate": {"config": {"systemPrompt": "", "userPromptTemplate": "Suggest {{ingredient}}"}}}
          }]""",
        )
        val result = ManifestValidator.validate(manifest)
        assertTrue(result.isLeft)
      },
    )

}
