/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.skills.declarative.{LifecycleResult, SkillLifecycleService}
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Built-in skill that lets agents propose new declarative skills.
  *
  * Agents call `skill_authoring.propose` with a manifest JSON string. The skill creates a draft, auto-advances it
  * through validation and permission review to `AwaitingApproval`, then returns the version ID for admin review.
  */
class SkillAuthoringSkill(lifecycle: SkillLifecycleService) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "skill_authoring",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(jorlan.BuildInfo.version).getOrElse(jorlan.BuildInfo.version),
    keywords = List("create skill", "propose skill", "new skill", "author skill", "custom skill"),
    tools = List(
      ToolDescriptor(
        name = "skill_authoring.propose",
        description = "Propose a new declarative skill by providing a JSON manifest. The skill will be validated and submitted for admin approval.",
        inputSchema = json"""{
          "type": "object",
          "properties": {
            "manifest": {
              "type": "string",
              "description": "JSON string containing the skill manifest (name, version, description, keywords, tools)"
            }
          },
          "required": ["manifest"]
        }""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("skill.propose")),
        examplePrompts = List(
          "Create a skill to fetch weather data",
          "Propose a new skill that calls the GitHub API",
          "Build a skill to summarize text using a prompt template",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "skill_authoring.propose" => propose(ctx, args)
      case other                     => ZIO.fail(JorlanError(s"SkillAuthoringSkill: unknown tool '$other'"))
    }

  private def propose(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "manifest") match {
      case None          => ZIO.fail(JorlanError("skill_authoring.propose: 'manifest' argument is required"))
      case Some(jsonStr) =>
        for {
          manifestJson <- ZIO
            .fromEither(jsonStr.fromJson[Json])
            .mapError(e => JorlanError(s"Invalid manifest JSON: $e"))
          version <- lifecycle.createDraft(manifestJson, SkillTier.AgentDraft, ctx.actorId)
          // Auto-advance: Draft → Validated → PermissionReviewed → SandboxTested → AwaitingApproval
          r1 <- lifecycle.advance(version.id)
          r2 <- if (r1.errors.isEmpty) lifecycle.advance(version.id) else ZIO.succeed(r1)
          r3 <- if (r2.errors.isEmpty) lifecycle.advance(version.id) else ZIO.succeed(r2)
          r4 <- if (r3.errors.isEmpty) lifecycle.advance(version.id) else ZIO.succeed(r3)
        } yield Json.Obj(
          "versionId" -> Json.Str(version.id.value.toString),
          "status"    -> Json.Str(r4.newStatus.toString),
          "errors"    -> Json.Arr(r4.errors.map(Json.Str(_))*),
          "info"      -> Json.Arr(r4.info.map(Json.Str(_))*),
          "message"   -> Json.Str(
            if (r4.errors.isEmpty)
              s"Skill proposed successfully. Status: ${r4.newStatus}. An admin will review it."
            else
              s"Skill proposal had errors: ${r4.errors.mkString("; ")}",
          ),
        )
    }

}

object SkillAuthoringSkill {

  val live: URLayer[SkillLifecycleService, SkillAuthoringSkill] =
    ZLayer.fromFunction(new SkillAuthoringSkill(_))

}
