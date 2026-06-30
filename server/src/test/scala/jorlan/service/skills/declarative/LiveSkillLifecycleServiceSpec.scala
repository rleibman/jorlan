/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.skills.SkillRegistry
import jorlan.service.{ApprovalHub, ApprovalService, CapabilityEvaluator}
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object LiveSkillLifecycleServiceSpec extends ZIOSpecDefault {

  private val validManifestJson: Json = {
    val raw =
      """{"name":"testskill","version":"1.0.0","description":"A test skill","keywords":[],"tools":[{"name":"testskill.run","description":"run it","requiredCapabilities":[],"examplePrompts":[],"inputSchema":{"type":"object"},"outputSchema":{"type":"string"},"executor":{"HttpApi":{"config":{"method":"GET","url":"https://example.com","headers":{},"bodyTemplate":null,"responseJsonPath":null}}}}]}"""
    raw.fromJson[Json].getOrElse(Json.Obj())
  }

  private val allowAll: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.ResourcePermissionAllows))

  private val noOpApprovalService: ULayer[ApprovalService] =
    ZLayer.succeed(new ApprovalService {
      override def authorize(request: CapabilityRequest): IO[JorlanError, AuthorizationResult] =
        ZIO.succeed(AuthorizationResult.Allowed)
      override def recordDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision] =
        ZIO.succeed(decision)
      override def expireStaleRequests(): IO[JorlanError, Long] = ZIO.succeed(0L)
    })

  private val baseLayer: ULayer[SkillLifecycleService] =
    ZLayer.make[SkillLifecycleService](
      InMemoryRepositories.live(),
      allowAll,
      noOpApprovalService,
      ApprovalHub.live,
      FakeModelGateway.layer(List.empty),
      Client.default.orDie,
      SkillRegistry.liveSecure,
      SkillLifecycleService.live,
    )

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("LiveSkillLifecycleService")(
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
          result <- svc.createDraft(Json.Str("bad"), SkillTier.Declarative, UserId(1L)).either
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
      test("reject from AwaitingApproval returns to Draft") {
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
      test("advance with non-existent versionId fails") {
        for {
          svc    <- ZIO.service[SkillLifecycleService]
          result <- svc.advance(SkillVersionId(999999L)).either
        } yield assertTrue(result.isLeft)
      }.provide(baseLayer),
      test("approve with non-existent versionId fails") {
        for {
          svc    <- ZIO.service[SkillLifecycleService]
          result <- svc.approve(SkillVersionId(999999L), UserId(1L)).either
        } yield assertTrue(result.isLeft)
      }.provide(baseLayer),
      test("reject with non-existent versionId fails") {
        for {
          svc    <- ZIO.service[SkillLifecycleService]
          result <- svc.reject(SkillVersionId(999999L), "reason", UserId(1L)).either
        } yield assertTrue(result.isLeft)
      }.provide(baseLayer),
      test("createDraft with same name reuses skillId") {
        for {
          svc <- ZIO.service[SkillLifecycleService]
          sv1 <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          sv2 <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
        } yield assertTrue(sv1.skillId == sv2.skillId)
      }.provide(baseLayer),
      test("approve with malformed version string in manifest fails") {
        val badVersionManifest: Json = {
          val raw =
            """{"name":"badver","version":"not-a-semver","description":"bad","keywords":[],"tools":[{"name":"badver.run","description":"run","requiredCapabilities":[],"examplePrompts":[],"inputSchema":{"type":"object"},"outputSchema":{"type":"string"},"executor":{"HttpApi":{"config":{"method":"GET","url":"https://example.com","headers":{},"bodyTemplate":null,"responseJsonPath":null}}}}]}"""
          raw.fromJson[Json].getOrElse(Json.Obj())
        }
        for {
          svc    <- ZIO.service[SkillLifecycleService]
          sv     <- svc.createDraft(badVersionManifest, SkillTier.Declarative, UserId(1L))
          _      <- svc.advance(sv.id)
          _      <- svc.advance(sv.id)
          _      <- svc.advance(sv.id)
          _      <- svc.advance(sv.id)
          result <- svc.approve(sv.id, UserId(99L)).either
        } yield assertTrue(result.isLeft)
      }.provide(baseLayer),
      test("reject from non-AwaitingApproval status fails") {
        for {
          svc    <- ZIO.service[SkillLifecycleService]
          sv     <- svc.createDraft(validManifestJson, SkillTier.Declarative, UserId(1L))
          result <- svc.reject(sv.id, "not ready", UserId(99L)).either
        } yield assertTrue(result.isLeft)
      }.provide(baseLayer),
    )

}
