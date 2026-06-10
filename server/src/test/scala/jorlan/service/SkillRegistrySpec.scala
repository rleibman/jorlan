/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object SkillRegistrySpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)

  private def makeSkill(
    namespace: String,
    toolNames: List[String],
    result:    Json = Json.Str("ok"),
  ): Skill =
    new Skill {
      override val descriptor: SkillDescriptor = SkillDescriptor(
        name = namespace,
        tier = SkillTier.BuiltIn,
        tools = toolNames.map { t =>
          ToolDescriptor(
            name = t,
            description = s"Tool $t",
            inputSchema = Json.decoder
              .decodeJson("""{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}""")
              .getOrElse(Json.Obj()),
            outputSchema = Json.Obj("type" -> Json.Str("string")),
            requiredCapabilities = Nil,
          )
        },
      )
      override def invoke(
        ctx:  InvocationContext,
        tool: String,
        args: Json,
      ): IO[JorlanError, Json] =
        ZIO.succeed(result)
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SkillRegistry")(
      test("register and allTools returns tools from all skills") {
        for {
          registry <- ZIO.service[SkillRegistry]
          a = makeSkill("alpha", List("alpha.run"))
          b = makeSkill("beta", List("beta.go", "beta.stop"))
          _     <- registry.register(a)
          _     <- registry.register(b)
          tools <- registry.allTools
        } yield assertTrue(
          tools.exists(_.name == "alpha.run"),
          tools.exists(_.name == "beta.go"),
          tools.exists(_.name == "beta.stop"),
        )
      }.provide(SkillRegistry.live),
      test("allToolSpecs converts tools to ToolSpec") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("gamma", List("gamma.act"))
          _     <- registry.register(skill)
          specs <- registry.allToolSpecs
        } yield assertTrue(specs.exists(_.name == "gamma.act"))
      }.provide(SkillRegistry.live),
      test("invoke routes to correct skill") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("echo", List("echo.say"), Json.Str("echoed"))
          _      <- registry.register(skill)
          result <- registry.invoke("echo.say", """{"key":"hello"}""", ctx)
        } yield assertTrue(result == Json.Str("echoed"))
      }.provide(SkillRegistry.live),
      test("invoke returns error for unknown namespace") {
        for {
          registry <- ZIO.service[SkillRegistry]
          result   <- registry.invoke("unknown.tool", "{}", ctx)
        } yield assertTrue(result.toString.contains("Error:"))
      }.provide(SkillRegistry.live),
      test("invoke returns error when required field is missing") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("strict", List("strict.run"))
          _      <- registry.register(skill)
          result <- registry.invoke("strict.run", "{}", ctx)
        } yield assertTrue(result.toString.contains("Error:"))
      }.provide(SkillRegistry.live),
      test("invoke succeeds when required fields are present") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("ok", List("ok.run"), Json.Str("done"))
          _      <- registry.register(skill)
          result <- registry.invoke("ok.run", """{"key":"value"}""", ctx)
        } yield assertTrue(result == Json.Str("done"))
      }.provide(SkillRegistry.live),
      test("liveWith pre-populates the registry") {
        val skill = makeSkill("pre", List("pre.go"), Json.Str("prewired"))
        for {
          tools  <- SkillRegistry.allTools
          result <- SkillRegistry.invoke("pre.go", """{"key":"x"}""", ctx)
        } yield assertTrue(
          tools.exists(_.name == "pre.go"),
          result == Json.Str("prewired"),
        )
      }.provide(SkillRegistry.liveWith(makeSkill("pre", List("pre.go"), Json.Str("prewired")))),
      // ─── error paths in validateRequiredFields ─────────────────────────────────
      test("invoke returns error for malformed JSON args") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("bad", List("bad.run"))
          _      <- registry.register(skill)
          result <- registry.invoke("bad.run", "not-valid-json", ctx)
        } yield assertTrue(result.toString.contains("Error:"))
      }.provide(SkillRegistry.live),
      test("invoke returns error when args is not a JSON object") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("arr", List("arr.run"))
          _      <- registry.register(skill)
          result <- registry.invoke("arr.run", "[1,2,3]", ctx)
        } yield assertTrue(result.toString.contains("Error:"))
      }.provide(SkillRegistry.live),
      test("invoke returns error when skill fails with JorlanError") {
        val failingSkill: Skill = new Skill {
          override val descriptor: SkillDescriptor = SkillDescriptor(
            name = "fail",
            tier = SkillTier.BuiltIn,
            tools = List(
              ToolDescriptor(
                name = "fail.run",
                description = "Always fails",
                inputSchema = Json.decoder
                  .decodeJson("""{"type":"object","properties":{},"required":[]}""")
                  .getOrElse(Json.Obj()),
                outputSchema = Json.Obj("type" -> Json.Str("string")),
                requiredCapabilities = Nil,
              ),
            ),
          )
          override def invoke(
            ctx:  InvocationContext,
            tool: String,
            args: Json,
          ): IO[JorlanError, Json] =
            ZIO.fail(JorlanError("skill blew up"))
        }
        for {
          registry <- ZIO.service[SkillRegistry]
          _        <- registry.register(failingSkill)
          result   <- registry.invoke("fail.run", "{}", ctx)
        } yield assertTrue(result.toString.contains("Error:"))
      }.provide(SkillRegistry.live),
      // ─── companion accessor methods ────────────────────────────────────────────
      test("companion allToolSpecs returns ToolSpec list") {
        for {
          specs <- SkillRegistry.allToolSpecs
        } yield assertTrue(specs.exists(_.name == "pre.go"))
      }.provide(SkillRegistry.liveWith(makeSkill("pre", List("pre.go")))),
      test("companion register adds skill") {
        for {
          _     <- SkillRegistry.register(makeSkill("reg", List("reg.run")))
          tools <- SkillRegistry.allTools
        } yield assertTrue(tools.exists(_.name == "reg.run"))
      }.provide(SkillRegistry.live),
    )

}
