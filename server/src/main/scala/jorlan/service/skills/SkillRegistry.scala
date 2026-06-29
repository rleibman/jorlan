/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, ToolDescriptor}
import jorlan.db.repository.{RepositoryError, ZIORepositories, ZIOSkillIndexRepository, ZIOSkillRepository}
import jorlan.service.skills.declarative.*
import jorlan.service.{CapabilityEvaluator, ModelGateway, ToolSpec}
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

/** Registry of all active [[Skill]] instances in the server.
  *
  * Provides tool discovery (for the ReAct loop), dispatches tool invocations, and drives the declarative skill
  * lifecycle state machine (Draft → Active).
  */
trait SkillRegistry {

  /** Register a skill, making its tools available to the ReAct loop. */
  def register(skill: Skill): UIO[Unit]

  /** Return all registered [[Skill]] instances. */
  def allSkills: UIO[List[Skill]]

  /** Return all tool descriptors from every registered skill. */
  def allTools: UIO[List[ToolDescriptor]]

  /** Return the union of all capability names required by any registered tool.
    *
    * Used by [[jorlan.init.InitService]] to automatically grant admin users every capability that any registered skill
    * needs — so adding a new skill doesn't require a manual update to the init service.
    */
  def allAdminCapabilities: UIO[List[CapabilityName]]

  /** Return all tools as [[ToolSpec]] values (model-module-safe view, no connector-api dependency). */
  def allToolSpecs: UIO[List[ToolSpec]]

  /** Remove stale [[skillIndex]] rows for skills no longer registered in this runtime.
    *
    * Call once after all built-in skills and connectors have been registered so that `skillIndex` rows from previous
    * runs (e.g. skills whose code was removed or renamed) do not pollute FULLTEXT search results.
    */
  def purgeStaleIndex(): UIO[Unit]

  /** Return a filtered [[ToolSpec]] list relevant to the given prompt.
    *
    * Uses MariaDB FULLTEXT search when a [[ZIOSkillIndexRepository]] is available; falls back to [[allToolSpecs]] in
    * test mode (no index). The `recentToolNames` list (from current session tool-call history) is used to boost
    * recently-invoked skills. `prioritizedSkills` are always included in the result regardless of score.
    */
  def filteredToolSpecs(
    prompt:            String,
    expertise:         String,
    recentToolNames:   List[String],
    prioritizedSkills: List[String],
  ): UIO[List[ToolSpec]]

  /** Invoke a named tool, returning its result as JSON.
    *
    * On failure the error is wrapped as `Json.Str("Error: <message>")` so the model can read it and recover.
    */
  def invoke(
    toolName: String,
    argsJson: String,
    ctx:      InvocationContext,
  ): UIO[Json]

  def getSkill[S <: Skill](name: String): UIO[Option[S]]

  /** Unregister a skill by exact name, making its tools unavailable. No-op if not registered. */
  def unregister(name: String): UIO[Unit]

  /** Unregister all skills whose name matches the given predicate. Used by [[McpManager]] on reload. */
  def unregisterWhere(pred: String => Boolean): UIO[Unit]

  def enableSkill(name:  String): UIO[Unit]
  def disableSkill(name: String): UIO[Unit]
  def isDisabled(name:   String): UIO[Boolean]
  def disabledSet:                UIO[Set[String]]

  // ── Declarative skill lifecycle ────────────────────────────────────────────

  /** Create a new skill draft version from the given JSON manifest. */
  def createDraft(
    manifest:  Json,
    tier:      SkillTier,
    createdBy: UserId,
  ): IO[JorlanError, SkillVersion]

  /** Advance the skill version one step through the lifecycle state machine. */
  def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult]

  /** Approve an `AwaitingApproval` version: set status to Active and register it in this registry. */
  def approve(
    versionId:  SkillVersionId,
    approvedBy: UserId,
  ): IO[JorlanError, LifecycleResult]

  /** Reject an `AwaitingApproval` version back to Draft with a review note. */
  def reject(
    versionId:  SkillVersionId,
    reason:     String,
    rejectedBy: UserId,
  ): IO[JorlanError, LifecycleResult]

  /** Register a factory that can rebuild a configurable skill from its raw JSON config string.
    *
    * Call once per configurable skill at startup. When [[reloadSkillConfig]] is later invoked (e.g. after the user
    * saves new settings), the factory re-creates the skill so the running server picks up the change without a restart.
    */
  def registerSkillFactory(
    configKey: String,
    factory:   String => IO[JorlanError, Skill],
  ): UIO[Unit]

  /** Re-create and re-register the skill associated with `configKey` using the supplied JSON string.
    *
    * Looks up the factory registered via [[registerSkillFactory]], creates a fresh skill instance, un-registers the old
    * instance, registers the new one, and enables it (the user explicitly saved a config, so it should be active).
    * Fails with [[JorlanError]] if no factory is registered for `configKey`.
    */
  def reloadSkillConfig(
    configKey:  String,
    configJson: String,
  ): IO[JorlanError, Unit]

}

object SkillRegistry {

  private def makeCache: UIO[Ref[Option[List[ToolSpec]]]] = Ref.make(Option.empty[List[ToolSpec]])

  /** Build an empty registry with capability enforcement and DB index disabled (for tests). */
  val live: ULayer[SkillRegistry] =
    ZLayer.fromZIO(
      for {
        ref       <- Ref.make(Map.empty[String, Skill])
        cache     <- makeCache
        disabled  <- Ref.make(Set.empty[String])
        factories <- Ref.make(Map.empty[String, String => IO[JorlanError, Skill]])
      } yield SkillRegistryLive(ref, None, cache, disabled, None, None, 5, 1.0, factories),
    )

  /** Build a registry pre-populated with the given skills (capability enforcement and DB index disabled; for tests). */
  def liveWith(skills: Skill*): ULayer[SkillRegistry] =
    ZLayer.fromZIO(
      for {
        ref       <- Ref.make(Map.empty[String, Skill])
        cache     <- makeCache
        disabled  <- Ref.make(Set.empty[String])
        factories <- Ref.make(Map.empty[String, String => IO[JorlanError, Skill]])
        registry = SkillRegistryLive(ref, None, cache, disabled, None, None, 4, 1.0, factories)
        _ <- ZIO.foreachDiscard(skills)(registry.register)
      } yield registry: SkillRegistry,
    )

  /** Build an empty registry wired with [[CapabilityEvaluator]], DB index, and lifecycle deps for production use. */
  val liveSecure: URLayer[CapabilityEvaluator & ZIORepositories & Client & ModelGateway, SkillRegistry] =
    ZLayer.fromZIO(
      for {
        evaluator <- ZIO.service[CapabilityEvaluator]
        repos     <- ZIO.service[ZIORepositories]
        client    <- ZIO.service[Client]
        gateway   <- ZIO.service[ModelGateway]
        ref       <- Ref.make(Map.empty[String, Skill])
        cache     <- makeCache
        disabled  <- Ref.make(Set.empty[String])
        factories <- Ref.make(Map.empty[String, String => IO[JorlanError, Skill]])
      } yield SkillRegistryLive(
        ref,
        Some(evaluator),
        cache,
        disabled,
        Some(repos.skill),
        Some(repos.skillIndex),
        4,
        1.0,
        factories,
        Some(client),
        Some(gateway),
      ),
    )

  /** Build a pre-populated registry wired with [[CapabilityEvaluator]], DB index, and lifecycle deps for production
    * use.
    */
  def liveSecureWith(skills: Skill*)
    : URLayer[CapabilityEvaluator & ZIORepositories & Client & ModelGateway, SkillRegistry] =
    ZLayer.fromZIO(
      for {
        evaluator <- ZIO.service[CapabilityEvaluator]
        repos     <- ZIO.service[ZIORepositories]
        client    <- ZIO.service[Client]
        gateway   <- ZIO.service[ModelGateway]
        ref       <- Ref.make(Map.empty[String, Skill])
        cache     <- makeCache
        disabled  <- Ref.make(Set.empty[String])
        factories <- Ref.make(Map.empty[String, String => IO[JorlanError, Skill]])
        registry = SkillRegistryLive(
          ref,
          Some(evaluator),
          cache,
          disabled,
          Some(repos.skill),
          Some(repos.skillIndex),
          4,
          1.0,
          factories,
          Some(client),
          Some(gateway),
        )
        _ <- ZIO.foreachDiscard(skills)(registry.register)
      } yield registry: SkillRegistry,
    )

  def register(skill: Skill): URIO[SkillRegistry, Unit] =
    ZIO.serviceWithZIO[SkillRegistry](_.register(skill))

  def allSkills: URIO[SkillRegistry, List[Skill]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allSkills)

  def allTools: URIO[SkillRegistry, List[ToolDescriptor]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allTools)

  def allToolSpecs: URIO[SkillRegistry, List[ToolSpec]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allToolSpecs)

  def allAdminCapabilities: URIO[SkillRegistry, List[CapabilityName]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allAdminCapabilities)

  def invoke(
    toolName: String,
    argsJson: String,
    ctx:      InvocationContext,
  ): URIO[SkillRegistry, Json] =
    ZIO.serviceWithZIO[SkillRegistry](_.invoke(toolName, argsJson, ctx))

}

class SkillRegistryLive(
  skills:         Ref[Map[String, Skill]],
  evaluator:      Option[CapabilityEvaluator],
  toolSpecsCache: Ref[Option[List[ToolSpec]]],
  disabled:       Ref[Set[String]],
  skillRepo:      Option[ZIOSkillRepository],
  skillIndexRepo: Option[ZIOSkillIndexRepository],
  topN:           Int,
  recentBoost:    Double,
  skillFactories: Ref[Map[String, String => IO[JorlanError, Skill]]],
  httpClient:     Option[Client] = None,
  modelGateway:   Option[ModelGateway] = None,
) extends SkillRegistry {

  private def repoErr(e: RepositoryError): JorlanError = JorlanError(e.msg)

  private def requireSkillRepo: IO[JorlanError, ZIOSkillRepository] =
    ZIO.fromOption(skillRepo).orElseFail(JorlanError("Skill lifecycle is not available in this runtime mode"))

  override def createDraft(
    manifest:  Json,
    tier:      SkillTier,
    createdBy: UserId,
  ): IO[JorlanError, SkillVersion] =
    ManifestValidator.validate(manifest) match {
      case Left(errors) => ZIO.fail(JorlanError(s"Invalid manifest: ${errors.mkString("; ")}"))
      case Right(m)     =>
        for {
          repo        <- requireSkillRepo
          existing    <- repo.search(SkillSearch(name = Some(m.name))).mapError(repoErr)
          skillRecord <- existing.headOption match {
            case Some(r) => ZIO.succeed(r)
            case None    =>
              repo
                .upsert(
                  SkillRecord(
                    id = SkillId.empty,
                    name = m.name,
                    currentVersion = None,
                    tier = tier,
                    createdAt = Instant.now(),
                  ),
                ).mapError(repoErr)
          }
          version <- repo
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
            ).mapError(repoErr)
        } yield version
    }

  override def advance(versionId: SkillVersionId): IO[JorlanError, LifecycleResult] =
    for {
      repo       <- requireSkillRepo
      versionOpt <- repo.getVersion(versionId).mapError(repoErr)
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
      repo    <- requireSkillRepo
      client  <- ZIO.fromOption(httpClient).orElseFail(JorlanError("HTTP client not available for skill approval"))
      gateway <- ZIO.fromOption(modelGateway).orElseFail(JorlanError("Model gateway not available for skill approval"))
      versionOpt <- repo.getVersion(versionId).mapError(repoErr)
      version    <- ZIO.fromOption(versionOpt).orElseFail(JorlanError(s"SkillVersion $versionId not found"))
      _          <- ZIO
        .fail(JorlanError(s"Cannot approve version in status '${version.status}'; must be AwaitingApproval"))
        .when(version.status != SkillStatus.AwaitingApproval)
      manifest <- parseManifest(version)
      _        <- repo.upsertVersionStatus(versionId, SkillStatus.Active, None).mapError(repoErr)
      _        <- repo
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
        ).mapError(repoErr)
      _ <- register(DeclarativeSkill.from(manifest, client, gateway))
    } yield LifecycleResult(versionId, SkillStatus.Active, Nil, List("Skill approved and registered"))

  override def reject(
    versionId:  SkillVersionId,
    reason:     String,
    rejectedBy: UserId,
  ): IO[JorlanError, LifecycleResult] =
    for {
      repo       <- requireSkillRepo
      versionOpt <- repo.getVersion(versionId).mapError(repoErr)
      version    <- ZIO.fromOption(versionOpt).orElseFail(JorlanError(s"SkillVersion $versionId not found"))
      _          <- ZIO
        .fail(JorlanError(s"Cannot reject version in status '${version.status}'; must be AwaitingApproval"))
        .when(version.status != SkillStatus.AwaitingApproval)
      _ <- repo.upsertVersionStatus(versionId, SkillStatus.Draft, Some(reason)).mapError(repoErr)
    } yield LifecycleResult(versionId, SkillStatus.Draft, Nil, List(s"Skill rejected: $reason"))

  private def runValidate(version: SkillVersion): IO[JorlanError, LifecycleResult] =
    ManifestValidator.validate(version.manifestJson) match {
      case Left(errors) =>
        ZIO.succeed(LifecycleResult(version.id, SkillStatus.Draft, errors, Nil))
      case Right(_) =>
        requireSkillRepo
          .flatMap(_.upsertVersionStatus(version.id, SkillStatus.Validated, None).mapError(repoErr))
          .as(LifecycleResult(version.id, SkillStatus.Validated, Nil, List("Manifest validated successfully")))
    }

  private def runPermissionReview(version: SkillVersion): IO[JorlanError, LifecycleResult] =
    parseManifest(version).flatMap { m =>
      val detectedCaps = m.tools.flatMap(_.requiredCapabilities).distinct
      requireSkillRepo
        .flatMap(_.upsertVersionStatus(version.id, SkillStatus.PermissionReviewed, None).mapError(repoErr))
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
      val hasHttp = m.tools.exists {
        case DeclarativeToolDef(_, _, _, _, _, _, ExecutorConfig.HttpApi(_)) => true
        case _                                                               => false
      }
      val infoMsg =
        if (hasHttp)
          "Sandbox test auto-passed (HTTP connectivity not verified in sandbox — verify manually)"
        else
          "Sandbox test passed"
      requireSkillRepo
        .flatMap(_.upsertVersionStatus(version.id, SkillStatus.SandboxTested, None).mapError(repoErr))
        .as(LifecycleResult(version.id, SkillStatus.SandboxTested, Nil, List(infoMsg)))
    }

  private def runSubmitForApproval(version: SkillVersion): IO[JorlanError, LifecycleResult] =
    requireSkillRepo
      .flatMap(_.upsertVersionStatus(version.id, SkillStatus.AwaitingApproval, None).mapError(repoErr))
      .as(LifecycleResult(version.id, SkillStatus.AwaitingApproval, Nil, List("Submitted for admin approval")))

  private def parseManifest(version: SkillVersion): IO[JorlanError, DeclarativeSkillManifest] =
    ZIO
      .fromEither(version.manifestJson.toString.fromJson[DeclarativeSkillManifest])
      .mapError(e => JorlanError(s"Failed to parse manifest for version ${version.id}: $e"))

  override def register(skill: Skill): UIO[Unit] =
    for {
      _ <- ZIO.logDebug("Registering skill: " + skill.descriptor.name)
      _ <- ZIO
        .logWarning(s"SkillRegistry: overwriting existing skill '${skill.descriptor.name}'")
        .whenZIO(skills.get.map(_.contains(skill.descriptor.name)))
      _ <- ZIO.when(skill.descriptor.tools.size > 10)(
        ZIO.logWarning(
          s"Skill '${skill.descriptor.name}' exposes ${skill.descriptor.tools.size} tools — consider splitting it to stay under the 10-tool guideline for local LLMs.",
        ),
      )
      _ <- skills.update(m => m + (skill.descriptor.name -> skill))
      _ <- toolSpecsCache.set(None)
      _ <- indexSkill(skill)
    } yield ()

  private def indexSkill(skill: Skill): UIO[Unit] =
    skillIndexRepo match {
      case None            => ZIO.unit
      case Some(indexRepo) =>
        skillRepo match {
          case None        => ZIO.unit
          case Some(sRepo) =>
            val keywords = (skill.descriptor.keywords ++ skill.descriptor.tools.flatMap(_.keywords)).mkString(" ")
            val searchText = (
              List(skill.descriptor.name) ++
                skill.descriptor.tools.map(_.name) ++
                skill.descriptor.tools.map(_.description) ++
                skill.descriptor.tools.flatMap(_.examplePrompts)
            ).mkString(" ")
            val effect = for {
              allRecords <- sRepo.search(SkillSearch(pageSize = 1000))
              existing = allRecords.find(_.name == skill.descriptor.name)
              skillRecord <- existing match {
                case Some(r) => ZIO.succeed(r)
                case None    =>
                  import java.time.Instant
                  sRepo.upsert(
                    SkillRecord(
                      id = SkillId.empty,
                      name = skill.descriptor.name,
                      currentVersion = skill.descriptor.skillVersion match {
                        case sv: just.semver.SemVer => Some(sv)
                        case _ => None
                      },
                      tier = skill.descriptor.tier,
                      createdAt = Instant.now(),
                    ),
                  )
              }
              _ <- indexRepo.upsert(skillRecord.id, keywords, searchText)
            } yield ()
            effect
              .tapError(e => ZIO.logWarning(s"Failed to index skill '${skill.descriptor.name}': ${e.msg}"))
              .ignore
        }
    }

  override def purgeStaleIndex(): UIO[Unit] =
    skillIndexRepo match {
      case None            => ZIO.unit
      case Some(indexRepo) =>
        for {
          currentNames <- skills.get.map(_.keySet)
          _            <- indexRepo
            .keepOnly(currentNames)
            .tapError(e => ZIO.logWarning(s"purgeStaleIndex failed: ${e.msg}"))
            .ignore
          _ <- ZIO.logInfo(
            s"purgeStaleIndex: kept ${currentNames.size} skills in index [${currentNames.toList.sorted.mkString(", ")}]",
          )
        } yield ()
    }

  override def allSkills: UIO[List[Skill]] =
    skills.get.map(_.values.toList)

  override def allTools: UIO[List[ToolDescriptor]] =
    for {
      dis   <- disabled.get
      tools <- skills.get.map(_.values.filter(s => !dis.contains(s.descriptor.name)).toList.flatMap(_.descriptor.tools))
    } yield tools

  override def allAdminCapabilities: UIO[List[CapabilityName]] =
    skills.get.map(_.values.toList.flatMap(_.descriptor.tools).flatMap(_.requiredCapabilities).distinct)

  override def allToolSpecs: UIO[List[ToolSpec]] =
    toolSpecsCache.get.flatMap {
      case Some(cached) => ZIO.succeed(cached)
      case None         =>
        allTools
          .map(_.map(t => ToolSpec(t.name, t.description, t.inputSchema.toString))).tap(specs =>
            toolSpecsCache.set(Some(specs)),
          )
    }

  override def filteredToolSpecs(
    prompt:            String,
    expertise:         String,
    recentToolNames:   List[String],
    prioritizedSkills: List[String],
  ): UIO[List[ToolSpec]] = {
    skillIndexRepo match {
      case None =>
        // No DB index (test/dev mode): return all specs
        allToolSpecs
      case Some(indexRepo) =>
        for {
          dis        <- disabled.get
          allEnabled <- skills.get.map(_.filter { case (name, _) => !dis.contains(name) })
          query = List(prompt, expertise).filter(_.nonEmpty).mkString(" ")
          dbMatches <- indexRepo
            .search(query, limit = topN * 2)
            .tapError(e => ZIO.logWarning(s"SkillIndex FULLTEXT search failed: ${e.msg}"))
            .orElseSucceed(List.empty)
          dbSkillNames = dbMatches.map(_._2).filter(name => !dis.contains(name) && allEnabled.contains(name))
          // Extract skill names from recent tool calls via longest-prefix matching
          recentNames = recentToolNames.flatMap { toolName =>
            allEnabled.keys.filter(name => toolName.startsWith(s"$name.")).maxByOption(_.length)
          }.toSet
          // Build final selection: prioritized always first, then DB results (up to topN), then recent extras
          prioritizedValid = prioritizedSkills.filter(n => allEnabled.contains(n) && !dis.contains(n))
          dbCapped = dbSkillNames.filterNot(prioritizedValid.contains).take(topN)
          recentExtras = recentNames.filterNot(n => prioritizedValid.contains(n) || dbCapped.contains(n)).toList
          selected = (prioritizedValid ++ dbCapped ++ recentExtras).distinct
          // Fallback: if nothing matched from DB, use recent; if still nothing, first N alphabetically
          finalSelected <-
            if (selected.nonEmpty) ZIO.succeed(selected)
            else if (recentNames.nonEmpty) ZIO.succeed(recentNames.toList.take(topN))
            else skills.get.map(m => m.keys.filter(k => !dis.contains(k)).toList.sorted.take(topN))
          _ <- ZIO.logInfo(
            s"filteredToolSpecs: selected [${finalSelected.mkString(", ")}] (db=${dbSkillNames.take(topN).mkString(", ")} recent=${recentNames.mkString(", ")} prio=${prioritizedValid.mkString(", ")}) prompt='${prompt.take(80)}'",
          )
          tools = finalSelected.flatMap(name => allEnabled.get(name).toList.flatMap(_.descriptor.tools))
        } yield tools.map(t => ToolSpec(t.name, t.description, t.inputSchema.toString))
    }
  }

  def getSkill[S <: Skill](name: String): UIO[Option[S]] =
    skills.get.map(_.find(_._2.descriptor.name == name).map(_._2.asInstanceOf[S]))

  override def unregister(name: String): UIO[Unit] =
    skills.update(_ - name) *>
      toolSpecsCache.set(None) *>
      skillIndexRepo.fold(ZIO.unit)(_.removeBySkillName(name).ignore)

  override def unregisterWhere(pred: String => Boolean): UIO[Unit] =
    for {
      names <- skills.get.map(_.keys.filter(pred).toList)
      _     <- skills.update(_.filterNot { case (name, _) => pred(name) })
      _     <- toolSpecsCache.set(None)
      _ <- skillIndexRepo.fold(ZIO.unit)(repo => ZIO.foreachDiscard(names)(name => repo.removeBySkillName(name).ignore))
    } yield ()

  override def registerSkillFactory(
    configKey: String,
    factory:   String => IO[JorlanError, Skill],
  ): UIO[Unit] =
    skillFactories.update(_.updated(configKey, factory))

  override def reloadSkillConfig(
    configKey:  String,
    configJson: String,
  ): IO[JorlanError, Unit] =
    for {
      factories <- skillFactories.get
      factory   <- ZIO
        .fromOption(factories.get(configKey))
        .orElseFail(JorlanError(s"No reload factory registered for config key '$configKey'"))
      newSkill <- factory(configJson)
      current  <- skills.get
      oldNames = current.values.filter(_.descriptor.configKey.contains(configKey)).map(_.descriptor.name).toList
      _ <- ZIO.foreachDiscard(oldNames)(unregister)
      _ <- register(newSkill)
      _ <- enableSkill(newSkill.descriptor.name)
    } yield ()

  override def enableSkill(name: String): UIO[Unit] =
    disabled.update(_ - name) *> toolSpecsCache.set(None)

  override def disableSkill(name: String): UIO[Unit] =
    disabled.update(_ + name) *> toolSpecsCache.set(None)

  override def isDisabled(name: String): UIO[Boolean] =
    disabled.get.map(_.contains(name))

  override def disabledSet: UIO[Set[String]] =
    disabled.get

  override def invoke(
    toolName: String,
    argsJson: String,
    ctx:      InvocationContext,
  ): UIO[Json] = {
    skills.get.flatMap { map =>
      // Longest-prefix match: find the skill whose name + "." is the longest prefix of toolName.
      // This supports dotted skill names (e.g. mcp.myserver) without confusing mcp.my vs mcp.my.server.
      val matchedSkill = map
        .filter { case (name, _) => toolName.startsWith(s"$name.") }
        .maxByOption { case (name, _) => name.length }

      matchedSkill match {
        case None =>
          ZIO.succeed(Json.Str(s"Error: no registered skill handles tool '$toolName'"))
        case Some((skillName, skill)) =>
          isDisabled(skillName).flatMap {
            case true =>
              ZIO.succeed(Json.Str(s"Error: skill '$skillName' is disabled"))
            case false =>
              validateRequiredFields(toolName, argsJson, skill).flatMap {
                case Left(err) =>
                  ZIO.succeed(Json.Str(s"Error: $err"))
                case Right(args) =>
                  checkCapabilities(toolName, ctx, skill).flatMap {
                    case Some(denied) => ZIO.succeed(Json.Str(s"Error: $denied"))
                    case None         =>
                      skill
                        .invoke(ctx, toolName, args)
                        .tapError(e => ZIO.logWarning(s"Skill '$toolName' failed: ${e.msg} (args: $argsJson)"))
                        .fold(
                          e => Json.Str(s"Error: ${e.msg}"),
                          identity,
                        )
                  }
              }
          }
      }
    }
  }

  /** Returns `Some(denialReason)` if capability gating is enabled and any required capability is denied. */
  private def checkCapabilities(
    toolName: String,
    ctx:      InvocationContext,
    skill:    Skill,
  ): UIO[Option[String]] =
    evaluator match {
      case None     => ZIO.none
      case Some(ev) =>
        skill.descriptor.tools.find(_.name == toolName) match {
          case None                                            => ZIO.none
          case Some(tool) if tool.requiredCapabilities.isEmpty => ZIO.none
          case Some(tool)                                      =>
            ZIO.foldLeft(tool.requiredCapabilities)(Option.empty[String]) {
              (
                denied,
                cap,
              ) =>
                if (denied.isDefined) ZIO.succeed(denied)
                else
                  ev.evaluate(
                    CapabilityRequest(cap, ctx.actorId, ctx.agentId, ctx.sessionId, None),
                  ).fold(
                      _ => Some(s"capability check failed for '${cap.value}'"),
                      {
                        case EvaluationResult.ExplicitDeny => Some(s"access denied: explicit deny on '${cap.value}'")
                        case EvaluationResult.DefaultDeny  => Some(s"access denied: no permission for '${cap.value}'")
                        case _                             => None
                      },
                    )
            }
        }
    }

  private def validateRequiredFields(
    toolName: String,
    argsJson: String,
    skill:    Skill,
  ): UIO[Either[String, Json]] = {
    val result = for {
      args <- Json.decoder.decodeJson(argsJson).left.map(e => s"invalid JSON: $e")
      obj  <- args match {
        case o: Json.Obj => Right(o)
        case _ => Left("arguments must be a JSON object")
      }
      tool <- skill.descriptor.tools
        .find(_.name == toolName)
        .toRight(s"unknown tool '$toolName' in skill '${skill.descriptor.name}'")
      requiredKeys = tool.inputSchema match {
        case Json.Obj(fields) =>
          fields
            .collectFirst { case ("required", Json.Arr(reqs)) =>
              reqs.collect { case Json.Str(k) => k }.toList
            }.getOrElse(List.empty)
        case _ => List.empty
      }
      missingKeys = requiredKeys.filterNot(k => obj.fields.exists { case (field, v) => field == k && v != Json.Null })
      _ <-
        if (missingKeys.isEmpty) Right(())
        else Left(s"missing required fields: ${missingKeys.mkString(", ")}")
    } yield args
    ZIO.succeed(result)
  }

}
