/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell

import jorlan.*
import jorlan.shell.client.*
import jorlan.usecase.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.logging.backend.SLF4J

import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.language.unsafeNulls

/** Headless importer that provisions a [[UseCaseManifest]] JSON file into a running Jorlan server: a role and its
  * capability grants, an agent persona, memory seeds, MCP servers, declarative skill drafts, and a scheduled pipeline
  * job with an optional trigger.
  *
  * Uses the same client stack as the interactive shell (AuthClient, GraphQLClient, ZIOClientRepositories), but never
  * opens a chat session — this only calls GraphQL mutations/queries directly.
  *
  * Every step is idempotent: re-running against the same manifest reports `[SKIP]` for everything that already exists
  * rather than creating duplicates. The importer is additive/updating only — it never deletes a capability grant or
  * memory record that was removed from the manifest on a later run.
  *
  * Usage:
  * {{{
  * sbtn "shell/runMain jorlan.shell.UseCaseImporterApp doc/use-cases/manifests/food_calendar.json \
  *   --server-url http://localhost:8080 --email roberto@leibman.net --password ..."
  * }}}
  * (or configure `~/.jorlan/jorlan-shell.json` and omit the flags).
  *
  * See `doc/use-cases/manifest-schema.md` and `doc/use-cases/HOW-TO-IMPLEMENT-A-USE-CASE.md`.
  */
object UseCaseImporterApp extends ZIOApp {

  override type Environment = ShellConfig & AuthClient & GraphQLClient & ZIOClientRepositories

  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  /** [[ShellConfig.layer]] loads from `~/.jorlan/jorlan-shell.json` (or `--config`); this additionally folds in
    * `--server-url`/`--email`/`--password` CLI overrides, matching the flags [[ShellConfig.applyArgs]] already
    * supports.
    */
  private val configLayer: ZLayer[ZIOAppArgs, Throwable, ShellConfig] =
    ShellConfig.layer.flatMap { env =>
      ZLayer.fromZIO(ZIOAppArgs.getArgs.map(args => ShellConfig.applyArgs(env.get[ShellConfig], args.toList)))
    }

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    (Runtime.removeDefaultLoggers >>> SLF4J.slf4j) ++
      ZLayer.makeSome[ZIOAppArgs, Environment](
        configLayer,
        AuthClient.live,
        GraphQLClient.live,
        ZIOClientRepositories.live,
      )

  private def logStep(
    status: String,
    step:   String,
    detail: String = "",
  ): UIO[Unit] =
    ZIO.succeed {
      val d = if (detail.nonEmpty) s" — $detail" else ""
      println(s"  [$status] $step$d")
    }

  /** Just enough of a declarative-skill manifest to check for an existing draft before creating another. */
  private case class SkillManifestPeek(
    name:    String,
    version: String,
  ) derives JsonCodec

  // ─── Provisioning steps ─────────────────────────────────────────────────────

  private def provisionRole(m: UseCaseManifest): ZIO[ZIOClientRepositories, Nothing, Option[Role]] =
    (for {
      repo     <- ZIO.service[ZIOClientRepositories]
      existing <- repo.permission.searchRoles(RoleSearch()).map(_.find(_.name == m.role.name))
      role     <- existing match {
        case Some(r) => logStep("SKIP", s"role '${m.role.name}'", "already exists").as(r)
        case None    =>
          repo.permission
            .upsertRole(Role(RoleId.empty, m.role.name, m.role.description))
            .tap(r => logStep("OK", s"role '${m.role.name}'", s"created id=${r.id.value}"))
      }
    } yield Some(role)).catchAll(err => logStep("FAIL", s"role '${m.role.name}'", err).as(None))

  private def provisionCapabilityGrants(
    m:    UseCaseManifest,
    role: Role,
  ): ZIO[ZIOClientRepositories, Nothing, Unit] =
    ZIO.foreachDiscard(m.role.capabilities) { cap =>
      (for {
        repo <- ZIO.service[ZIOClientRepositories]
        _    <- repo.permission.upsertCapabilityGrant(
          CapabilityGrant(
            id = CapabilityGrantId.empty,
            capability = CapabilityName(cap.capability),
            scopeJson = None,
            granteeId = role.id.value,
            granteeType = GranteeType.Role,
            grantorId = None,
            approvalMode = cap.approvalMode,
            expiresAt = None,
            resourceConstraints = None,
            createdAt = Instant.now(),
          ),
        )
        _ <- logStep("OK", s"capability '${cap.capability}' -> role '${role.name}'")
      } yield ()).catchAll(err => logStep("FAIL", s"capability '${cap.capability}'", err))
    }

  private def provisionRoleAssignment(
    m:    UseCaseManifest,
    role: Role,
  ): ZIO[ZIOClientRepositories, Nothing, Unit] =
    m.assignRoleToUser match {
      case None        => ZIO.unit
      case Some(email) =>
        (for {
          repo    <- ZIO.service[ZIOClientRepositories]
          userOpt <- repo.user.userByEmail(email)
          _       <- userOpt match {
            case None    => logStep("FAIL", s"assign role to '$email'", "no such user")
            case Some(u) =>
              repo.permission.assignRole(u.id, role.id) *> logStep("OK", s"assigned role '${role.name}' to '$email'")
          }
        } yield ()).catchAll(err => logStep("FAIL", s"assign role to '$email'", err))
    }

  private def provisionAgent(m: UseCaseManifest): ZIO[ZIOClientRepositories, Nothing, Option[Agent]] =
    (for {
      repo     <- ZIO.service[ZIOClientRepositories]
      existing <- repo.agent.search(AgentSearch(pageSize = 500)).map(_.find(_.name == m.agent.name))
      spec = m.agent
      agent <- repo.agent
        .upsert(
          Agent(
            id = existing.map(_.id).getOrElse(AgentId.empty),
            name = spec.name,
            description = spec.description,
            defaultModel = spec.defaultModel.map(ModelId(_)),
            trustLevel = spec.trustLevel,
            prioritizedSkills = spec.prioritizedSkills,
            invariants = spec.invariants,
            createdAt = Instant.now(),
          ),
        )
        .tap(a =>
          logStep(
            "OK",
            s"agent '${spec.name}'",
            s"id=${a.id.value} (${if (existing.isDefined) "updated" else "created"})",
          ),
        )
    } yield Some(agent)).catchAll(err => logStep("FAIL", s"agent '${m.agent.name}'", err).as(None))

  private def provisionMemorySeeds(m: UseCaseManifest): ZIO[ZIOClientRepositories, Nothing, Unit] =
    ZIO.foreachDiscard(m.memorySeeds) { seed =>
      (for {
        repo     <- ZIO.service[ZIOClientRepositories]
        existing <- repo.memory
          .search(MemorySearch(scope = seed.scope, textSearch = Some(seed.key)))
          .map(_.find(_.recordKey == seed.key))
        _ <- existing match {
          case Some(_) => logStep("SKIP", s"memory '${seed.key}'", "already seeded")
          case None    =>
            repo.memory.upsert(
              MemoryRecord(
                id = MemoryRecordId.empty,
                scope = seed.scope,
                userId = None,
                workspaceId = None,
                agentId = None,
                recordKey = seed.key,
                value = Json.Str(seed.text),
                ttl = None,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
              ),
            ) *> logStep("OK", s"memory '${seed.key}'", "seeded")
        }
      } yield ()).catchAll(err => logStep("FAIL", s"memory '${seed.key}'", err))
    }

  private def provisionMcpServers(m: UseCaseManifest): ZIO[ZIOClientRepositories, Nothing, Unit] =
    ZIO.foreachDiscard(m.mcpServers) { srv =>
      (for {
        repo <- ZIO.service[ZIOClientRepositories]
        _    <- repo.upsertMcpServer(
          McpServerInfo(
            name = srv.name,
            transport = srv.transport,
            command = srv.command,
            args = srv.args,
            env = srv.env.map(e => McpEnvVarInfo(e.key, e.value)),
            url = srv.url,
            enabled = srv.enabled,
            keywords = srv.keywords,
          ),
        )
        _ <- logStep("OK", s"mcp server '${srv.name}'")
      } yield ()).catchAll(err => logStep("FAIL", s"mcp server '${srv.name}'", err))
    }

  private def provisionDeclarativeSkills(m: UseCaseManifest): ZIO[ZIOClientRepositories, Nothing, Unit] =
    ZIO.foreachDiscard(m.declarativeSkills) { skill =>
      (for {
        peek             <- ZIO.fromEither(skill.manifestJson.fromJson[SkillManifestPeek])
        repo             <- ZIO.service[ZIOClientRepositories]
        existingVersions <- repo.allCustomSkills().map(_.filter(_.skillName == peek.name))
        _                <- existingVersions.find(_.version == peek.version) match {
          case Some(_) => logStep("SKIP", s"skill '${peek.name}' v${peek.version}", "already drafted")
          case None    =>
            repo.createSkillDraft(skill.manifestJson) *>
              logStep("OK", s"skill '${peek.name}' v${peek.version}", "drafted — review in web UI")
        }
      } yield ()).catchAll(err => logStep("FAIL", "declarative skill", err))
    }

  private def provisionJob(
    m:          UseCaseManifest,
    agentIdOpt: Option[AgentId],
  ): ZIO[ZIOClientRepositories, Nothing, Option[SchedulerJob]] =
    m.job match {
      case None          => ZIO.none
      case Some(jobSpec) =>
        (for {
          repo <- ZIO.service[ZIOClientRepositories]
          // Search across all jobs (not just those owned by this agent) -- a prior run may have
          // created this job before the agent existed (agentId=None), and the DB enforces a
          // (userId, name) uniqueness constraint that a narrower agentId-scoped search would miss,
          // causing a duplicate-name createJob attempt to fail.
          existing <- repo.scheduler.listJobs(None, 200).map(_.find(_.name == jobSpec.name))
          pipeline = Pipeline(
            steps = jobSpec.steps.map(s =>
              PipelineStep(s.name, s.systemPrompt, s.userPrompt, s.tools, s.mode, s.outputVar, s.retryOnFail),
            ),
            invariants = jobSpec.invariants,
            personality = jobSpec.personality,
          )
          job <- existing match {
            case Some(j) =>
              for {
                _       <- repo.scheduler.updateJobPipeline(j.id, pipeline)
                updated <- repo.updateJob(
                  j.id,
                  jobSpec.name,
                  jobSpec.maxRetries,
                  jobSpec.backoffSeconds,
                  jobSpec.backoffPolicy,
                  jobSpec.missedRunPolicy,
                )
                _ <- logStep("OK", s"job '${jobSpec.name}'", s"updated id=${updated.id.value}")
              } yield updated
            case None =>
              repo
                .createJobWithPipeline(
                  jobSpec.name,
                  pipeline,
                  agentIdOpt,
                  jobSpec.maxRetries,
                  jobSpec.backoffSeconds,
                  jobSpec.backoffPolicy,
                  jobSpec.missedRunPolicy,
                )
                .tap(j => logStep("OK", s"job '${jobSpec.name}'", s"created id=${j.id.value}"))
          }
        } yield Some(job)).catchAll(err => logStep("FAIL", s"job '${jobSpec.name}'", err).as(None))
    }

  private def provisionTrigger(
    m:      UseCaseManifest,
    jobOpt: Option[SchedulerJob],
  ): ZIO[ZIOClientRepositories, Nothing, Unit] =
    (m.job.flatMap(_.trigger), jobOpt) match {
      case (Some(t), Some(job)) =>
        (for {
          repo     <- ZIO.service[ZIOClientRepositories]
          existing <- repo.scheduler
            .searchTriggers(TriggerSearch(job.id))
            .map(_.exists(tr => tr.triggerType == t.triggerType && tr.expression == t.expression))
          _ <-
            if (existing) logStep("SKIP", "trigger", "already exists")
            else
              repo.addTrigger(job.id, t.triggerType, t.expression) *>
                logStep("OK", "trigger", s"${t.triggerType} ${t.expression}")
        } yield ()).catchAll(err => logStep("FAIL", "trigger", err))
      case _ => ZIO.unit
    }

  // ─── Main runner ────────────────────────────────────────────────────────────

  // $COVERAGE-OFF$ — requires a live server
  override def run: ZIO[Environment & ZIOAppArgs & Scope, Throwable, Unit] =
    for {
      args <- ZIOAppArgs.getArgs
      path <- ZIO
        .fromOption(args.toList.headOption)
        .orElseFail(
          new IllegalArgumentException(
            "Usage: UseCaseImporterApp <manifest.json> [--server-url URL] [--email E] [--password P]",
          ),
        )
      raw      <- ZIO.attempt(Files.readString(Paths.get(path)))
      manifest <- ZIO
        .fromEither(raw.fromJson[UseCaseManifest])
        .mapError(e => new RuntimeException(s"Invalid manifest '$path': $e"))
      _   <- ZIO.succeed(println(s"\n=== Importing use case: ${manifest.useCaseName} ===\n"))
      cfg <- ZIO.service[ShellConfig]
      _   <- (cfg.email, cfg.password) match {
        case (Some(e), Some(p)) =>
          AuthClient.login(e, p).mapError(err => new RuntimeException(s"Login failed: $err")).unit
        case _ =>
          ZIO.fail(
            new IllegalArgumentException(
              "No credentials: pass --email/--password or configure ~/.jorlan/jorlan-shell.json",
            ),
          )
      }
      roleOpt <- provisionRole(manifest)
      _       <- roleOpt match {
        case Some(role) => provisionCapabilityGrants(manifest, role) *> provisionRoleAssignment(manifest, role)
        case None       => ZIO.unit
      }
      agentOpt <- provisionAgent(manifest)
      _        <- provisionMemorySeeds(manifest)
      _        <- provisionMcpServers(manifest)
      _        <- provisionDeclarativeSkills(manifest)
      jobOpt   <- provisionJob(manifest, agentOpt.map(_.id))
      _        <- provisionTrigger(manifest, jobOpt)
      _        <- ZIO.succeed(println(s"\n=== Import complete: ${manifest.useCaseName} ===\n"))
    } yield ()
  // $COVERAGE-ON$

}
