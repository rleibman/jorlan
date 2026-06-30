/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.service.*
import jorlan.service.llm.FakeModelGateway
import zio.*
import zio.http.*
import zio.http.ZClient
import zio.json.ast.Json
import zio.test.*

object DeclarativeSkillSpec extends ZIOSpecDefault {

  private val dummyCtx = InvocationContext(actorId = UserId(1L), agentId = None, sessionId = None)

  private def stubClient(
    responseBody: String,
    status:       Status = Status.Ok,
  ): Client = {
    val driver = new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
        version:        Version,
        method:         Method,
        url:            URL,
        headers:        Headers,
        body:           Body,
        sslConfig:      Option[ClientSSLConfig],
        proxy:          Option[Proxy],
      )(implicit trace: Trace,
      ): ZIO[Any & Scope, Throwable, Response] =
        ZIO.succeed(Response(status = status, body = Body.fromString(responseBody)))

      override def socket[Env1 <: Any](
        version: Version,
        url:     URL,
        headers: Headers,
        app:     WebSocketApp[Env1],
      )(implicit
        trace: Trace,
        ev:    Scope =:= Scope,
      ): ZIO[Env1 & Scope, Throwable, Response] =
        ZIO.fail(new Exception("websocket not supported in stub"))
    }
    ZClient.fromDriver(driver)
  }

  private val httpTool = DeclarativeToolDef(
    name = "weather.get_forecast",
    description = "Get a weather forecast",
    requiredCapabilities = List.empty,
    examplePrompts = List.empty,
    inputSchema = Json.Obj("type" -> Json.Str("object")),
    outputSchema = Json.Obj("type" -> Json.Str("string")),
    executor = ExecutorConfig.HttpApi(
      HttpApiExecutorConfig(
        method = "GET",
        url = "https://api.example.com/forecast",
      ),
    ),
  )

  private val promptTool = DeclarativeToolDef(
    name = "summary.generate",
    description = "Generate a summary",
    requiredCapabilities = List.empty,
    examplePrompts = List.empty,
    inputSchema = Json.Obj("type" -> Json.Str("object")),
    outputSchema = Json.Obj("type" -> Json.Str("string")),
    executor = ExecutorConfig.PromptTemplate(
      PromptTemplateExecutorConfig(
        systemPrompt = "Summarize the following.",
        userPromptTemplate = "{{text}}",
      ),
    ),
  )

  private val manifest = DeclarativeSkillManifest(
    name = "test_skill",
    version = "1.0.0",
    description = "A test declarative skill",
    keywords = List("test"),
    tools = List(httpTool, promptTool),
  )

  override def spec =
    suite("DeclarativeSkillSpec")(
      suite("descriptor")(
        test("name comes from manifest") {
          val skill = DeclarativeSkill.from(manifest, stubClient("{}"), FakeModelGateway(List()))
          assertTrue(skill.descriptor.name == "test_skill")
        },
        test("tools list matches manifest") {
          val skill = DeclarativeSkill.from(manifest, stubClient("{}"), FakeModelGateway(List()))
          assertTrue(skill.descriptor.tools.map(_.name) == List("weather.get_forecast", "summary.generate"))
        },
        test("tier is Declarative") {
          val skill = DeclarativeSkill.from(manifest, stubClient("{}"), FakeModelGateway(List()))
          assertTrue(skill.descriptor.tier == SkillTier.Declarative)
        },
      ),
      suite("invoke routing")(
        test("routes HttpApi tool to HttpApiExecutor") {
          val skill = DeclarativeSkill.from(manifest, stubClient("""{"temp": 20}"""), FakeModelGateway(List()))
          for {
            result <- skill.invoke(dummyCtx, "weather.get_forecast", Json.Obj())
          } yield assertTrue(result == Json.Obj("temp" -> Json.Num(20)))
        },
        test("routes PromptTemplate tool to PromptTemplateExecutor") {
          val gateway = FakeModelGateway(List("Here is your summary."))
          val skill = DeclarativeSkill.from(manifest, stubClient(""), gateway)
          for {
            result <- skill.invoke(dummyCtx, "summary.generate", Json.Obj("text" -> Json.Str("A long document.")))
          } yield assertTrue(result == Json.Str("Here is your summary."))
        },
        test("fails with JorlanError for unknown tool") {
          val skill = DeclarativeSkill.from(manifest, stubClient("{}"), FakeModelGateway(List()))
          for {
            result <- skill.invoke(dummyCtx, "test_skill.nonexistent", Json.Obj()).either
          } yield assertTrue(result.isLeft)
        },
      ),
    )

}
