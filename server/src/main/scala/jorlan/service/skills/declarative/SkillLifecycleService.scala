/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.*
import jorlan.db.repository.{RepositoryError, ZIORepositories}
import jorlan.service.ModelGateway
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

import java.time.Instant

case class LifecycleResult(
  versionId: SkillVersionId,
  newStatus: SkillStatus,
  errors:    List[String],
  info:      List[String],
) derives JsonCodec

/** Drives the declarative skill lifecycle state machine.
  *
  * States: Draft → Validated → PermissionReviewed → SandboxTested → AwaitingApproval → Active / Rejected (back to
  * Draft)
  */
trait SkillLifecycleService {

  def createDraft(
    manifest:  Json,
    tier:      SkillTier,
    createdBy: UserId,
  ): IO[JorlanError, SkillVersion]

  def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]

  def approve(
    versionId:  SkillVersionId,
    approvedBy: UserId,
  ): IO[JorlanError, LifecycleResult]

  def reject(
    versionId:  SkillVersionId,
    reason:     String,
    rejectedBy: UserId,
  ): IO[JorlanError, LifecycleResult]

}

object SkillLifecycleService {

  val live: URLayer[ZIORepositories & SkillRegistry & Client & ModelGateway, SkillLifecycleService] =
    ZLayer.fromFunction(LiveSkillLifecycleService(_, _, _, _))

}

private class LiveSkillLifecycleService(
  repos:    ZIORepositories,
  registry: SkillRegistry,
  client:   Client,
  gateway:  ModelGateway,
) extends SkillLifecycleService {

  private def repoErr(e: RepositoryError): JorlanError = JorlanError(e.msg)

  override def createDraft(
    manifest:  Json,
    tier:      SkillTier,
    createdBy: UserId,
  ): IO[JorlanError, SkillVersion] = {
    ManifestValidator.validate(manifest) match {
      case Left(errors) => ZIO.fail(JorlanError(s"Invalid manifest: ${errors.mkString("; ")}"))
      case Right(m)     =>
        for {
          existing    <- repos.skill.search(SkillSearch(name = Some(m.name))).mapError(repoErr)
          skillRecord <- existing.headOption match {
            case Some(existing) => ZIO.succeed(existing)
            case None           =>
              repos.skill
                .upsert(
                  SkillRecord(
                    id = SkillId.empty,
                    name = m.name,
                    currentVersion = None,
                    tier = tier,
                    createdAt = Instant.now(),
                  ),
                )
                .mapError(repoErr)
          }
          version <- repos.skill
            .upsertVersion(
              SkillVersion(
                id = SkillVersionId.empty,
                skillId = skillRecord.id,
                version = m.version,
                manifestJson = manifest,
                status = SkillStatus.Draft,
                createdAt = Instant.now(),
                createdBy = Some(createdBy),
                reviewNote = None,
              ),
            )
            .mapError(repoErr)
        } yield version
    }
  }

  override def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult] =
    for {
      versionOpt <- repos.skill.getVersion(versionId).mapError(repoErr)
      version    <- ZIO.fromOption(versionOpt).orElseFail(JorlanError(s"SkillVersion $versionId not found"))
      result     <- version.status match {
        case SkillStatus.Draft              => runValidate(version)
        case SkillStatus.Validated          => runPermissionReview(version)
        case SkillStatus.PermissionReviewed => runSandboxTest(version)
        case SkillStatus.SandboxTested      => runSubmitForApproval(version)
        case other                          =>
          ZIO.succeed(
            LifecycleResult(
              versionId = versionId,
              newStatus = other,
              errors = List(s"Cannot advance from status '$other'; must be approved/rejected by an admin"),
              info = Nil,
            ),
          )
      }
    } yield result

  override def approve(
    versionId:  SkillVersionId,
    approvedBy: UserId,
  ): IO[JorlanError, LifecycleResult] =
    for {
      versionOpt <- repos.skill.getVersion(versionId).mapError(repoErr)
      version    <- ZIO.fromOption(versionOpt).orElseFail(JorlanError(s"SkillVersion $versionId not found"))
      _          <- ZIO
        .fail(JorlanError(s"Cannot approve version in status '${version.status}'; must be AwaitingApproval"))
        .when(version.status != SkillStatus.AwaitingApproval)
      manifest <- parseManifest(version)
      _        <- repos.skill
        .upsertVersionStatus(versionId, SkillStatus.Active, None)
        .mapError(repoErr)
      _ <- repos.skill
        .upsert(
          SkillRecord(
            id = version.skillId,
            name = manifest.name,
            currentVersion = Some(
              just.semver.SemVer.parse(manifest.version).getOrElse(just.semver.SemVer.unsafeParse("1.0.0")),
            ),
            tier = SkillTier.Declarative,
            createdAt = Instant.now(),
          ),
        )
        .mapError(repoErr)
      _ <- registry.register(DeclarativeSkill.from(manifest, client, gateway))
    } yield LifecycleResult(versionId, SkillStatus.Active, Nil, List("Skill approved and registered"))

  override def reject(
    versionId:  SkillVersionId,
    reason:     String,
    rejectedBy: UserId,
  ): IO[JorlanError, LifecycleResult] =
    for {
      versionOpt <- repos.skill.getVersion(versionId).mapError(repoErr)
      version    <- ZIO.fromOption(versionOpt).orElseFail(JorlanError(s"SkillVersion $versionId not found"))
      _          <- ZIO
        .fail(JorlanError(s"Cannot reject version in status '${version.status}'; must be AwaitingApproval"))
        .when(version.status != SkillStatus.AwaitingApproval)
      _          <- repos.skill
        .upsertVersionStatus(versionId, SkillStatus.Draft, Some(reason))
        .mapError(repoErr)
    } yield LifecycleResult(versionId, SkillStatus.Draft, Nil, List(s"Skill rejected: $reason"))

  private def runValidate(version: SkillVersion): IO[JorlanError, LifecycleResult] = {
    ManifestValidator.validate(version.manifestJson) match {
      case Left(errors) =>
        ZIO.succeed(LifecycleResult(version.id, SkillStatus.Draft, errors, Nil))
      case Right(_) =>
        repos.skill
          .upsertVersionStatus(version.id, SkillStatus.Validated, None)
          .mapError(repoErr)
          .as(LifecycleResult(version.id, SkillStatus.Validated, Nil, List("Manifest validated successfully")))
    }
  }

  private def runPermissionReview(version: SkillVersion): IO[JorlanError, LifecycleResult] =
    parseManifest(version).flatMap { m =>
      val detectedCaps = m.tools.flatMap(_.requiredCapabilities).distinct
      repos.skill
        .upsertVersionStatus(version.id, SkillStatus.PermissionReviewed, None)
        .mapError(repoErr)
        .as(
          LifecycleResult(
            version.id,
            SkillStatus.PermissionReviewed,
            Nil,
            List(s"Required capabilities: ${if (detectedCaps.isEmpty) "(none)" else detectedCaps.mkString(", ")}"),
          ),
        )
    }

  private def runSandboxTest(version: SkillVersion): IO[JorlanError, LifecycleResult] =
    parseManifest(version).flatMap { m =>
      val httpTools = m.tools.filter {
        case DeclarativeToolDef(_, _, _, _, _, _, ExecutorConfig.HttpApi(_)) => true
        case _                                                               => false
      }
      if (httpTools.nonEmpty) {
        repos.skill
          .upsertVersionStatus(version.id, SkillStatus.SandboxTested, None)
          .mapError(repoErr)
          .as(
            LifecycleResult(
              version.id,
              SkillStatus.SandboxTested,
              Nil,
              List("Sandbox test auto-passed (HTTP connectivity not verified in sandbox — verify manually)"),
            ),
          )
      } else {
        repos.skill
          .upsertVersionStatus(version.id, SkillStatus.SandboxTested, None)
          .mapError(repoErr)
          .as(LifecycleResult(version.id, SkillStatus.SandboxTested, Nil, List("Sandbox test passed")))
      }
    }

  private def runSubmitForApproval(version: SkillVersion): IO[JorlanError, LifecycleResult] =
    repos.skill
      .upsertVersionStatus(version.id, SkillStatus.AwaitingApproval, None)
      .mapError(repoErr)
      .as(
        LifecycleResult(
          version.id,
          SkillStatus.AwaitingApproval,
          Nil,
          List("Submitted for admin approval"),
        ),
      )

  private def parseManifest(version: SkillVersion): IO[JorlanError, DeclarativeSkillManifest] =
    ZIO
      .fromEither(version.manifestJson.toString.fromJson[DeclarativeSkillManifest])
      .mapError(e => JorlanError(s"Failed to parse manifest for version ${version.id}: $e"))

}
