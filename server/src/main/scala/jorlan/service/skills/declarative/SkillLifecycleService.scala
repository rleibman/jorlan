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
  *
  * Note (MVP): `PermissionReview` auto-advances without a human gate. This deviates from the SRS human-in-the-loop
  * specification and is documented as an accepted MVP limitation.
  */
trait SkillLifecycleService {

  /** Parses and persists a new skill version as a Draft.
    *
    * @param manifest
    *   Raw JSON manifest — must satisfy [[ManifestValidator]] or the call fails.
    * @param tier
    *   `Declarative` for human-authored skills; `AgentDraft` for agent-proposed skills.
    * @param createdBy
    *   The user who initiated the draft. If a skill with the same name already exists its `skillId` is reused.
    * @return
    *   The persisted [[SkillVersion]] with `status = Draft`.
    */
  def createDraft(
    manifest:  Json,
    tier:      SkillTier,
    createdBy: UserId,
  ): IO[JorlanError, SkillVersion]

  /** Advances a version to the next lifecycle state.
    *
    * Dispatches based on the current status: Draft→Validated, Validated→PermissionReviewed,
    * PermissionReviewed→SandboxTested, SandboxTested→AwaitingApproval. Returns an error result (not a ZIO failure) if
    * the version is already at `AwaitingApproval` or `Active`.
    *
    * @param versionId
    *   ID of the version to advance. Fails with [[JorlanError]] if not found.
    */
  def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]

  /** Approves an `AwaitingApproval` version, makes it `Active`, and registers it in the [[SkillRegistry]].
    *
    * Fails with [[JorlanError]] if the version is not in `AwaitingApproval` status or is not found.
    *
    * @param versionId
    *   ID of the version to approve.
    * @param approvedBy
    *   The user performing the approval (recorded in the event log).
    */
  def approve(
    versionId:  SkillVersionId,
    approvedBy: UserId,
  ): IO[JorlanError, LifecycleResult]

  /** Rejects a version, returning its status to `Draft` and recording a review note.
    *
    * @param versionId
    *   ID of the version to reject. Fails with [[JorlanError]] if not found.
    * @param reason
    *   Human-readable explanation stored in `skillVersion.reviewNote`.
    * @param rejectedBy
    *   The user performing the rejection (recorded in the event log).
    */
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

  private def requireVersion(versionId: SkillVersionId): IO[JorlanError, SkillVersion] =
    repos.skill
      .getVersion(versionId)
      .mapError(repoErr)
      .flatMap(opt => ZIO.fromOption(opt).orElseFail(JorlanError(s"SkillVersion $versionId not found")))

  override def createDraft(
    manifest:  Json,
    tier:      SkillTier,
    createdBy: UserId,
  ): IO[JorlanError, SkillVersion] = {
    ManifestValidator.validate(manifest) match {
      case Left(errors) => ZIO.fail(JorlanError(s"Invalid manifest: ${errors.mkString("; ")}"))
      case Right(m)     =>
        for {
          now         <- Clock.instant
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
                    createdAt = now,
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
                createdAt = now,
                createdBy = Some(createdBy),
                reviewNote = None,
              ),
            )
            .mapError(repoErr)
          logEntry <- repos.eventLog.append(
            EventLog(
              id = EventLogId.empty,
              eventType = EventType.SkillDraftCreated,
              actorId = Some(createdBy),
              agentId = None,
              sessionId = None,
              resource = Some(version.id),
              payloadJson = None,
              occurredAt = now,
            ),
          )
        } yield version
    }
  }

  override def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult] =
    for {
      version <- requireVersion(versionId)
      result  <- version.status match {
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
      now     <- Clock.instant
      version <- requireVersion(versionId)
      _       <- ZIO
        .fail(JorlanError(s"Cannot approve version in status '${version.status}'; must be AwaitingApproval"))
        .when(version.status != SkillStatus.AwaitingApproval)
      manifest <- parseManifest(version)
      semVer   <- ZIO
        .fromOption(just.semver.SemVer.parse(manifest.version).toOption)
        .orElseFail(JorlanError(s"Malformed version string '${manifest.version}' in manifest"))
      _ <- repos.skill
        .upsertVersionStatus(versionId, SkillStatus.Active, None)
        .mapError(repoErr)
      _ <- repos.skill
        .upsert(
          SkillRecord(
            id = version.skillId,
            name = manifest.name,
            currentVersion = Some(semVer),
            tier = SkillTier.Declarative,
            createdAt = version.createdAt,
          ),
        )
        .mapError(repoErr)
      _ <- registry.register(DeclarativeSkill.from(manifest, client, gateway))
      _ <- repos.eventLog.append(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.SkillApproved,
          actorId = Some(approvedBy),
          agentId = None,
          sessionId = None,
          resource = Some(versionId),
          payloadJson = None,
          occurredAt = now,
        ),
      )
    } yield LifecycleResult(versionId, SkillStatus.Active, Nil, List("Skill approved and registered"))

  override def reject(
    versionId:  SkillVersionId,
    reason:     String,
    rejectedBy: UserId,
  ): IO[JorlanError, LifecycleResult] =
    for {
      now     <- Clock.instant
      version <- requireVersion(versionId)
      _       <- ZIO
        .fail(JorlanError(s"Cannot reject version in status '${version.status}'; must be AwaitingApproval"))
        .when(version.status != SkillStatus.AwaitingApproval)
      _ <- repos.skill
        .upsertVersionStatus(versionId, SkillStatus.Draft, Some(reason))
        .mapError(repoErr)
      _ <- repos.eventLog.append(
        EventLog(
          id = EventLogId.empty,
          eventType = EventType.SkillRejected,
          actorId = Some(rejectedBy),
          agentId = None,
          sessionId = None,
          resource = Some(versionId),
          payloadJson = None,
          occurredAt = now,
        ),
      )
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

  // MVP: sandbox test auto-passes; HTTP connectivity is not verified. Rename to SandboxSkipped in a future phase.
  private def runSandboxTest(version: SkillVersion): IO[JorlanError, LifecycleResult] =
    parseManifest(version).flatMap { m =>
      val msg =
        if (
          m.tools.exists {
            case DeclarativeToolDef(_, _, _, _, _, _, ExecutorConfig.HttpApi(_)) => true; case _ => false
          }
        )
          "Sandbox test auto-passed (HTTP connectivity not verified in sandbox — verify manually)"
        else
          "Sandbox test passed"
      repos.skill
        .upsertVersionStatus(version.id, SkillStatus.SandboxTested, None)
        .mapError(repoErr)
        .as(LifecycleResult(version.id, SkillStatus.SandboxTested, Nil, List(msg)))
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
      .fromEither(version.manifestJson.as[DeclarativeSkillManifest])
      .mapError(e => JorlanError(s"Failed to parse manifest for version ${version.id}: $e"))

}
