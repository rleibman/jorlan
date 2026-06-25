/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.skills.SkillRegistry
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SkillLifecycleServiceSpec extends ZIOSpecDefault {

  private val validManifestJson: Json = {
    val raw =
      """{
        |  "name": "weather",
        |  "version": "1.0.0",
        |  "description": "Provides weather forecasts",
        |  "keywords": ["weather", "forecast"],
        |  "tools": [{
        |    "name": "weather.get_forecast",
        |    "description": "Get a weather forecast for a city",
        |    "requiredCapabilities": [],
        |    "examplePrompts": ["What is the weather in Paris?"],
        |    "inputSchema": {"type": "object"},
        |    "outputSchema": {"type": "string"},
        |    "executor": {"HttpApi": {"config": {
        |      "method": "GET",
        |      "url": "https://api.example.com/forecast?q={{city}}",
        |      "headers": {},
        |      "bodyTemplate": null,
        |      "responseJsonPath": null
        |    }}}
        |  }]
        |}""".stripMargin
    raw.fromJson[Json].getOrElse(Json.Obj())
  }

  private val baseLayer: ULayer[SkillLifecycleService] =
    ZLayer.make[SkillLifecycleService](
      InMemoryRepositories.live(),
      SkillRegistry.live,
      FakeModelGateway.layer(List.empty),
      Client.default.orDie,
      SkillLifecycleService.live,
    )

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("SkillLifecycleService")(
      test("createDraft creates a version with Draft status") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv  <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
        } yield assertTrue(
          sv.status == SkillStatus.Draft,
          sv.version == "1.0.0",
        )
      }.provide(baseLayer),
      test("createDraft with invalid manifest fails") {
        for {
          svc    <- ZIO.service[SkillLifecycleService]
          result <- svc.createDraft(Json.Str("bad json"), SkillTier.Declarative, UserId(1L)).either
        } yield assertTrue(result.isLeft)
      }.provide(baseLayer),
      test("advance from Draft moves to Validated") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv  <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          r   <- svc.advance(sv.id)
        } yield assertTrue(
          r.newStatus == SkillStatus.Validated,
          r.errors.isEmpty,
        )
      }.provide(baseLayer),
      test("advance from Validated moves to PermissionReviewed") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv  <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          _   <- svc.advance(sv.id)
          r   <- svc.advance(sv.id)
        } yield assertTrue(r.newStatus == SkillStatus.PermissionReviewed)
      }.provide(baseLayer),
      test("advance through full pipeline reaches AwaitingApproval") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv  <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          r1  <- svc.advance(sv.id)
          r2  <- svc.advance(sv.id)
          r3  <- svc.advance(sv.id)
          r4  <- svc.advance(sv.id)
        } yield assertTrue(
          r1.newStatus == SkillStatus.Validated,
          r2.newStatus == SkillStatus.PermissionReviewed,
          r3.newStatus == SkillStatus.SandboxTested,
          r4.newStatus == SkillStatus.AwaitingApproval,
        )
      }.provide(baseLayer),
      test("approve from AwaitingApproval makes skill Active") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv  <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          r   <- svc.approve(sv.id, UserId(99L))
        } yield assertTrue(r.newStatus == SkillStatus.Active)
      }.provide(baseLayer),
      test("approve from non-AwaitingApproval status fails") {
        for {
          svc    <- ZIO.service[SkillLifecycleService]
          sv     <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          result <- svc.approve(sv.id, UserId(99L)).either
        } yield assertTrue(result.isLeft)
      }.provide(baseLayer),
      test("reject from AwaitingApproval returns to Draft with note") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv  <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          r   <- svc.reject(sv.id, "Not approved by security team", UserId(99L))
        } yield assertTrue(
          r.newStatus == SkillStatus.Draft,
          r.info.exists(_.contains("rejected")),
        )
      }.provide(baseLayer),
      test("advance from AwaitingApproval returns error (no further advance)") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv  <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          _   <- svc.advance(sv.id)
          r   <- svc.advance(sv.id)
        } yield assertTrue(
          r.newStatus == SkillStatus.AwaitingApproval,
          r.errors.nonEmpty,
        )
      }.provide(baseLayer),
    )

}
