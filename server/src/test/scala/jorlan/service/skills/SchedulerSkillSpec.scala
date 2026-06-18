/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.service.JobManager
import jorlan.service.schedule.JobManagerImpl
import jorlan.service.skills.SchedulerSkill
import jorlan.testing.{FakeConfigurationService, InMemoryRepositories}
import zio.*
import zio.json.ast.Json
import zio.test.*

object SchedulerSkillSpec extends ZIOSpec[JobManager] {

  private val agentId = AgentId(1L)
  private val userId = UserId(1L)

  override val bootstrap: ULayer[JobManager] =
    ZLayer
      .make[JobManager](
        InMemoryRepositories.live(),
        FakeConfigurationService.layer,
        JobManagerImpl.live,
      ).orDie

  private def makeSkill: URIO[JobManager, SchedulerSkill] =
    ZIO.serviceWith[JobManager](new SchedulerSkill(_))

  override def spec: Spec[JobManager & TestEnvironment & Scope, Any] =
    suite("SchedulerSkill")(
      test("createJob creates a pending job") {
        for {
          skill <- makeSkill
          job   <- skill.createJob(agentId, userId, "skill-job", "", None)
        } yield assertTrue(job.name == "skill-job", job.status == JobStatus.Pending)
      },
      test("listJobs returns jobs for the agent") {
        for {
          skill <- makeSkill
          _     <- skill.createJob(agentId, userId, "listed", "", None)
          jobs  <- skill.listJobs(agentId)
        } yield assertTrue(jobs.exists(_.name == "listed"))
      },
      test("pauseJob pauses a job") {
        for {
          skill  <- makeSkill
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "pause-skill", "", None)
          _      <- skill.pauseJob(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Paused)
      },
      test("resumeJob resumes a paused job") {
        for {
          skill  <- makeSkill
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "resume-skill", "", None)
          _      <- skill.pauseJob(job.id)
          _      <- skill.resumeJob(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Pending)
      },
      test("cancelJob cancels a job") {
        for {
          skill  <- makeSkill
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "cancel-skill", "", None)
          _      <- skill.cancelJob(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Cancelled)
      },
      test("triggerNow resets job to Pending") {
        for {
          skill  <- makeSkill
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "trigger-skill", "", None)
          _      <- skill.pauseJob(job.id)
          _      <- skill.triggerNow(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Pending)
      },
      // ─── Skill.invoke dispatch ─────────────────────────────────────────────────
      test("invoke scheduler.create_job returns id and name") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(
            ctx,
            "scheduler.create_job",
            Json.Obj("name" -> Json.Str("invoke-job"), "cronExpression" -> Json.Str("0 * * * *")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val name = fields.collectFirst { case ("name", Json.Str(n)) => n }
            assertTrue(name.contains("invoke-job"))
          case _ => assertTrue(false)
        }
      },
      test("invoke scheduler.list_jobs returns array") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill <- makeSkill
          _     <- skill.invoke(
            ctx,
            "scheduler.create_job",
            Json.Obj("name" -> Json.Str("listed"), "cronExpression" -> Json.Str("0 * * * *")),
          )
          result <- skill.invoke(ctx, "scheduler.list_jobs", Json.Obj())
        } yield result match {
          case Json.Arr(_) => assertTrue(true)
          case _           => assertTrue(false)
        }
      },
      test("invoke scheduler.pause_job returns Bool(true)") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          job    <- skill.createJob(agentId, userId, "pause-invoke", "", None)
          result <- skill.invoke(ctx, "scheduler.pause_job", Json.Obj("id" -> Json.Str(job.id.value.toString)))
        } yield assertTrue(result == Json.Bool(true))
      },
      test("invoke scheduler.resume_job returns Bool(true)") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          job    <- skill.createJob(agentId, userId, "resume-invoke", "", None)
          _      <- skill.pauseJob(job.id)
          result <- skill.invoke(ctx, "scheduler.resume_job", Json.Obj("id" -> Json.Str(job.id.value.toString)))
        } yield assertTrue(result == Json.Bool(true))
      },
      test("invoke scheduler.cancel_job returns Bool(true)") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          job    <- skill.createJob(agentId, userId, "cancel-invoke", "", None)
          result <- skill.invoke(ctx, "scheduler.cancel_job", Json.Obj("id" -> Json.Str(job.id.value.toString)))
        } yield assertTrue(result == Json.Bool(true))
      },
      test("invoke scheduler.trigger_now returns Bool(true)") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          job    <- skill.createJob(agentId, userId, "trigger-invoke", "", None)
          result <- skill.invoke(ctx, "scheduler.trigger_now", Json.Obj("id" -> Json.Str(job.id.value.toString)))
        } yield assertTrue(result == Json.Bool(true))
      },
      test("invoke unknown tool returns failure") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "scheduler.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("invoke scheduler.create_job fails when required field is missing") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "scheduler.create_job", Json.Obj("name" -> Json.Str("no-cron"))).either
        } yield assertTrue(result.isLeft)
      },
      // ─── invalid job id (non-numeric) ─────────────────────────────────────────
      test("invoke scheduler.pause_job with non-numeric id fails") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "scheduler.pause_job", Json.Obj("id" -> Json.Str("not-a-number"))).either
        } yield assertTrue(result.isLeft)
      },
      test("invoke scheduler.resume_job with non-numeric id fails") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "scheduler.resume_job", Json.Obj("id" -> Json.Str("bad"))).either
        } yield assertTrue(result.isLeft)
      },
      // ─── non-object args ──────────────────────────────────────────────────────
      test("invoke with non-object args fails (non-Obj branch in field helper)") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "scheduler.create_job", Json.Arr(Json.Str("bad"))).either
        } yield assertTrue(result.isLeft)
      },
    )

}
