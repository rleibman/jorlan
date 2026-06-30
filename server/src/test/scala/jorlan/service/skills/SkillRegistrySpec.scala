/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.{CapabilityEvaluator, ToolSpec}
import jorlan.service.skills.SkillRegistry
import just.semver.SemVer
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object SkillRegistrySpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)

  private def makeSkill(
    namespace:    String,
    toolNames:    List[String],
    result:       Json = Json.Str("ok"),
    capabilities: List[CapabilityName] = List.empty,
  ): Skill =
    new Skill {
      override val descriptor: SkillDescriptor = SkillDescriptor(
        name = namespace,
        tier = SkillTier.BuiltIn,
        skillVersion = SemVer.parse(jorlan.BuildInfo.version).getOrElse(jorlan.BuildInfo.version),
        tools = toolNames.map { t =>
          ToolDescriptor(
            name = t,
            description = s"Tool $t",
            inputSchema = Json.decoder
              .decodeJson("""{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}""")
              .getOrElse(Json.Obj()),
            outputSchema = Json.Obj("type" -> Json.Str("string")),
            requiredCapabilities = capabilities,
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
            skillVersion = SemVer.parse(jorlan.BuildInfo.version).getOrElse(jorlan.BuildInfo.version),
            tools = List(
              ToolDescriptor(
                name = "fail.run",
                description = "Always fails",
                inputSchema = Json.decoder
                  .decodeJson("""{"type":"object","properties":{},"required":[]}""")
                  .getOrElse(Json.Obj()),
                outputSchema = Json.Obj("type" -> Json.Str("string")),
                requiredCapabilities = List.empty,
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
      // ─── allSkills ────────────────────────────────────────────────────────────
      test("allSkills returns all registered skill instances") {
        for {
          registry <- ZIO.service[SkillRegistry]
          s1 = makeSkill("s1", List("s1.a"))
          s2 = makeSkill("s2", List("s2.b"))
          _   <- registry.register(s1)
          _   <- registry.register(s2)
          all <- registry.allSkills
        } yield assertTrue(all.map(_.descriptor.name).contains("s1") && all.map(_.descriptor.name).contains("s2"))
      }.provide(SkillRegistry.live),
      // ─── allAdminCapabilities ─────────────────────────────────────────────────
      test("allAdminCapabilities returns distinct capability names from all tools") {
        val cap1 = CapabilityName("cap.one")
        val cap2 = CapabilityName("cap.two")
        for {
          registry <- ZIO.service[SkillRegistry]
          s1 = makeSkill("capped1", List("capped1.a"), capabilities = List(cap1, cap2))
          s2 = makeSkill("capped2", List("capped2.b"), capabilities = List(cap1))
          _    <- registry.register(s1)
          _    <- registry.register(s2)
          caps <- registry.allAdminCapabilities
        } yield assertTrue(caps.contains(cap1) && caps.contains(cap2) && caps.distinct == caps)
      }.provide(SkillRegistry.live),
      // ─── purgeStaleIndex ──────────────────────────────────────────────────────
      test("purgeStaleIndex is a no-op when no index repo is configured") {
        for {
          registry <- ZIO.service[SkillRegistry]
          _        <- registry.purgeStaleIndex()
        } yield assertCompletes
      }.provide(SkillRegistry.live),
      // ─── filteredToolSpecs ────────────────────────────────────────────────────
      test("filteredToolSpecs without DB index returns all tool specs") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("flt", List("flt.go"))
          _     <- registry.register(skill)
          specs <- registry.filteredToolSpecs("some prompt", "general", List.empty, List.empty)
        } yield assertTrue(specs.exists(_.name == "flt.go"))
      }.provide(SkillRegistry.live),
      // ─── getSkill ─────────────────────────────────────────────────────────────
      test("getSkill returns Some for registered skill") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("finder", List("finder.tool"))
          _     <- registry.register(skill)
          found <- registry.getSkill[Skill]("finder")
        } yield assertTrue(found.isDefined && found.get.descriptor.name == "finder")
      }.provide(SkillRegistry.live),
      test("getSkill returns None for unregistered skill") {
        for {
          registry <- ZIO.service[SkillRegistry]
          found    <- registry.getSkill[Skill]("nonexistent")
        } yield assertTrue(found.isEmpty)
      }.provide(SkillRegistry.live),
      // ─── unregister ───────────────────────────────────────────────────────────
      test("unregister removes a registered skill from allTools") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("removeme", List("removeme.go"))
          _      <- registry.register(skill)
          before <- registry.allTools
          _      <- registry.unregister("removeme")
          after  <- registry.allTools
        } yield assertTrue(before.exists(_.name == "removeme.go") && !after.exists(_.name == "removeme.go"))
      }.provide(SkillRegistry.live),
      // ─── unregisterWhere ──────────────────────────────────────────────────────
      test("unregisterWhere removes skills matching a predicate") {
        for {
          registry <- ZIO.service[SkillRegistry]
          s1 = makeSkill("mcp.serverA", List("mcp.serverA.tool1"))
          s2 = makeSkill("mcp.serverB", List("mcp.serverB.tool2"))
          s3 = makeSkill("builtin", List("builtin.act"))
          _      <- registry.register(s1)
          _      <- registry.register(s2)
          _      <- registry.register(s3)
          _      <- registry.unregisterWhere(_.startsWith("mcp."))
          skills <- registry.allSkills
        } yield {
          val names = skills.map(_.descriptor.name)
          assertTrue(!names.contains("mcp.serverA") && !names.contains("mcp.serverB") && names.contains("builtin"))
        }
      }.provide(SkillRegistry.live),
      // ─── enableSkill / disableSkill / isDisabled / disabledSet ───────────────
      test("disableSkill hides tools from allTools") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("toggleable", List("toggleable.run"))
          _          <- registry.register(skill)
          _          <- registry.disableSkill("toggleable")
          tools      <- registry.allTools
          isDisabled <- registry.isDisabled("toggleable")
        } yield assertTrue(!tools.exists(_.name == "toggleable.run") && isDisabled)
      }.provide(SkillRegistry.live),
      test("enableSkill restores tools to allTools after disabling") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("reactivate", List("reactivate.run"))
          _          <- registry.register(skill)
          _          <- registry.disableSkill("reactivate")
          _          <- registry.enableSkill("reactivate")
          tools      <- registry.allTools
          isDisabled <- registry.isDisabled("reactivate")
        } yield assertTrue(tools.exists(_.name == "reactivate.run") && !isDisabled)
      }.provide(SkillRegistry.live),
      test("disabledSet returns names of all disabled skills") {
        for {
          registry <- ZIO.service[SkillRegistry]
          s1 = makeSkill("d1", List("d1.go"))
          s2 = makeSkill("d2", List("d2.go"))
          _   <- registry.register(s1)
          _   <- registry.register(s2)
          _   <- registry.disableSkill("d1")
          _   <- registry.disableSkill("d2")
          dis <- registry.disabledSet
        } yield assertTrue(dis.contains("d1") && dis.contains("d2"))
      }.provide(SkillRegistry.live),
      test("invoke returns error when skill is disabled") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("dskill", List("dskill.run"), Json.Str("should not get here"))
          _      <- registry.register(skill)
          _      <- registry.disableSkill("dskill")
          result <- registry.invoke("dskill.run", """{"key":"x"}""", ctx)
        } yield assertTrue(result.toString.contains("Error:") && result.toString.contains("disabled"))
      }.provide(SkillRegistry.live),
      // ─── toolSpec cache ───────────────────────────────────────────────────────
      test("allToolSpecs result is cached on second call") {
        for {
          registry <- ZIO.service[SkillRegistry]
          skill = makeSkill("cached", List("cached.run"))
          _      <- registry.register(skill)
          specs1 <- registry.allToolSpecs
          specs2 <- registry.allToolSpecs
        } yield assertTrue(specs1 == specs2 && specs1.exists(_.name == "cached.run"))
      }.provide(SkillRegistry.live),
      test("registering a new skill invalidates the toolSpecs cache") {
        for {
          registry <- ZIO.service[SkillRegistry]
          s1 = makeSkill("c1", List("c1.go"))
          s2 = makeSkill("c2", List("c2.go"))
          _      <- registry.register(s1)
          specs1 <- registry.allToolSpecs
          _      <- registry.register(s2)
          specs2 <- registry.allToolSpecs
        } yield assertTrue(!specs1.exists(_.name == "c2.go") && specs2.exists(_.name == "c2.go"))
      }.provide(SkillRegistry.live),
      // ─── registerSkillFactory / reloadSkillConfig ─────────────────────────────
      test("registerSkillFactory and reloadSkillConfig recreates the skill") {
        for {
          registry <- ZIO.service[SkillRegistry]
          initial = makeSkill("reloadable", List("reloadable.v1"))
          _ <- registry.register(initial)
          // Register a factory that returns a new version of the skill
          _ <- registry.registerSkillFactory(
            "reloadable",
            configJson => {
              val _ = configJson
              ZIO.succeed(makeSkill("reloadable", List("reloadable.v2")))
            },
          )
          _     <- registry.reloadSkillConfig("reloadable", "{}")
          tools <- registry.allTools
        } yield assertTrue(!tools.exists(_.name == "reloadable.v1") && tools.exists(_.name == "reloadable.v2"))
      }.provide(SkillRegistry.live),
      test("reloadSkillConfig fails when no factory is registered") {
        for {
          registry <- ZIO.service[SkillRegistry]
          result   <- registry.reloadSkillConfig("unknown-config-key", "{}").exit
        } yield assert(result)(failsWithA[JorlanError])
      }.provide(SkillRegistry.live),
    )

}
