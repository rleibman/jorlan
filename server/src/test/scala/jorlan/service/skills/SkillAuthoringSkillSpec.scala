/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.service.skills.declarative.{LifecycleResult, SkillLifecycleService}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SkillAuthoringSkillSpec extends ZIOSpecDefault {

  private val dummyCtx = InvocationContext(actorId = UserId(1L), agentId = None, sessionId = None)

  private val validManifestStr =
    """{
      |  "name": "weather",
      |  "version": "1.0.0",
      |  "description": "Provides weather forecasts",
      |  "keywords": [],
      |  "tools": [{
      |    "name": "weather.get_forecast",
      |    "description": "Get a forecast",
      |    "requiredCapabilities": [],
      |    "examplePrompts": [],
      |    "inputSchema": {"type": "object"},
      |    "outputSchema": {"type": "string"},
      |    "executor": {"HttpApi": {"config": {
      |      "method": "GET",
      |      "url": "https://api.example.com/forecast",
      |      "headers": {},
      |      "bodyTemplate": null,
      |      "responseJsonPath": null
      |    }}}
      |  }]
      |}""".stripMargin

  private def fakeLifecycle(
    draftResult:   IO[JorlanError, SkillVersion],
    advanceResult: IO[JorlanError, LifecycleResult],
  ): SkillLifecycleService =
    new SkillLifecycleService {
      override def createDraft(
        manifest:  Json,
        tier:      SkillTier,
        createdBy: UserId,
      ): IO[JorlanError, SkillVersion] =
        draftResult

      override def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult] =
        advanceResult

      override def approve(
        versionId:  SkillVersionId,
        approvedBy: UserId,
      ): IO[JorlanError, LifecycleResult] =
        ZIO.fail(JorlanError("not expected"))

      override def reject(
        versionId:  SkillVersionId,
        reason:     String,
        rejectedBy: UserId,
      ): IO[JorlanError, LifecycleResult] =
        ZIO.fail(JorlanError("not expected"))
    }

  private def makeDraftVersion(id: Long = 1L): SkillVersion =
    SkillVersion(
      id = SkillVersionId(id),
      skillId = SkillId(1L),
      version = "1.0.0",
      status = SkillStatus.Draft,
      manifestJson = Json.Obj(),
      createdBy = Some(UserId(1L)),
      createdAt = java.time.Instant.EPOCH,
    )

  override def spec =
    suite("SkillAuthoringSkillSpec")(
      suite("skill_authoring.propose happy path")(
        test("returns versionId and status on success") {
          val version = makeDraftVersion(42L)
          val advanceResult = LifecycleResult(version.id, SkillStatus.AwaitingApproval, List.empty, List("advanced"))
          val lifecycle = fakeLifecycle(ZIO.succeed(version), ZIO.succeed(advanceResult))
          val skill = new SkillAuthoringSkill(lifecycle)
          for {
            result <- skill
              .invoke(dummyCtx, "skill_authoring.propose", Json.Obj("manifest" -> Json.Str(validManifestStr)))
          } yield assertTrue(
            result match {
              case Json.Obj(fields) =>
                fields.exists { case ("versionId", Json.Str("42")) => true; case _ => false } &&
                fields.exists { case ("status", Json.Str("AwaitingApproval")) => true; case _ => false }
              case _ => false
            },
          )
        },
        test("message says 'proposed successfully' on clean advance") {
          val version = makeDraftVersion(1L)
          val advanceResult = LifecycleResult(version.id, SkillStatus.AwaitingApproval, List.empty, List.empty)
          val lifecycle = fakeLifecycle(ZIO.succeed(version), ZIO.succeed(advanceResult))
          val skill = new SkillAuthoringSkill(lifecycle)
          for {
            result <- skill
              .invoke(dummyCtx, "skill_authoring.propose", Json.Obj("manifest" -> Json.Str(validManifestStr)))
          } yield assertTrue(result match {
            case Json.Obj(fields) =>
              fields.collectFirst { case ("message", Json.Str(v)) => v }.exists(_.contains("proposed successfully"))
            case _ => false
          })
        },
        test("message includes errors when advance has errors") {
          val version = makeDraftVersion(1L)
          val advanceResult = LifecycleResult(version.id, SkillStatus.Draft, List("schema is invalid"), List.empty)
          val lifecycle = fakeLifecycle(ZIO.succeed(version), ZIO.succeed(advanceResult))
          val skill = new SkillAuthoringSkill(lifecycle)
          for {
            result <- skill
              .invoke(dummyCtx, "skill_authoring.propose", Json.Obj("manifest" -> Json.Str(validManifestStr)))
          } yield assertTrue(result match {
            case Json.Obj(fields) =>
              fields.collectFirst { case ("message", Json.Str(v)) => v }.exists(_.contains("errors"))
            case _ => false
          })
        },
      ),
      suite("error cases")(
        test("fails when 'manifest' arg is missing") {
          val lifecycle = fakeLifecycle(ZIO.fail(JorlanError("not expected")), ZIO.fail(JorlanError("not expected")))
          val skill = new SkillAuthoringSkill(lifecycle)
          for {
            result <- skill.invoke(dummyCtx, "skill_authoring.propose", Json.Obj()).either
          } yield assertTrue(result.isLeft)
        },
        test("fails when manifest is not valid JSON") {
          val lifecycle = fakeLifecycle(ZIO.fail(JorlanError("not expected")), ZIO.fail(JorlanError("not expected")))
          val skill = new SkillAuthoringSkill(lifecycle)
          for {
            result <- skill
              .invoke(
                dummyCtx,
                "skill_authoring.propose",
                Json.Obj("manifest" -> Json.Str("this is not json {{{")),
              ).either
          } yield assertTrue(result.isLeft)
        },
        test("fails on unknown tool") {
          val lifecycle = fakeLifecycle(ZIO.fail(JorlanError("not expected")), ZIO.fail(JorlanError("not expected")))
          val skill = new SkillAuthoringSkill(lifecycle)
          for {
            result <- skill.invoke(dummyCtx, "skill_authoring.nonexistent", Json.Obj()).either
          } yield assertTrue(result.isLeft)
        },
        test("propagates createDraft failure") {
          val lifecycle = fakeLifecycle(ZIO.fail(JorlanError("db error")), ZIO.fail(JorlanError("not expected")))
          val skill = new SkillAuthoringSkill(lifecycle)
          for {
            result <- skill
              .invoke(dummyCtx, "skill_authoring.propose", Json.Obj("manifest" -> Json.Str(validManifestStr))).either
          } yield assertTrue(result.isLeft, result.left.exists(_.msg.contains("db error")))
        },
      ),
    )

}
