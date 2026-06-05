/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

object SchedulerSkillSpec extends ZIOSpec[SchedulerSkill & JobManager] {

  private val agentId = AgentId(1L)
  private val userId = UserId(1L)

  override val bootstrap: ULayer[SchedulerSkill & JobManager] =
    ZLayer.make[SchedulerSkill & JobManager](
      InMemoryRepositories.InMemorySchedulerRepo.layer,
      JobManagerImpl.live,
      SchedulerSkill.live,
    )

  override def spec: Spec[SchedulerSkill & JobManager & TestEnvironment & Scope, Any] =
    suite("SchedulerSkill")(
      test("createJob creates a pending job") {
        for {
          skill <- ZIO.service[SchedulerSkill]
          job   <- skill.createJob(agentId, userId, "skill-job", None)
        } yield assertTrue(job.name == "skill-job", job.status == JobStatus.Pending)
      },
      test("listJobs returns jobs for the agent") {
        for {
          skill <- ZIO.service[SchedulerSkill]
          _     <- skill.createJob(agentId, userId, "listed", None)
          jobs  <- skill.listJobs(agentId)
        } yield assertTrue(jobs.exists(_.name == "listed"))
      },
      test("pauseJob pauses a job") {
        for {
          skill  <- ZIO.service[SchedulerSkill]
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "pause-skill", None)
          _      <- skill.pauseJob(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Paused)
      },
      test("resumeJob resumes a paused job") {
        for {
          skill  <- ZIO.service[SchedulerSkill]
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "resume-skill", None)
          _      <- skill.pauseJob(job.id)
          _      <- skill.resumeJob(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Pending)
      },
      test("cancelJob cancels a job") {
        for {
          skill  <- ZIO.service[SchedulerSkill]
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "cancel-skill", None)
          _      <- skill.cancelJob(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Cancelled)
      },
      test("triggerNow resets job to Pending") {
        for {
          skill  <- ZIO.service[SchedulerSkill]
          mgr    <- ZIO.service[JobManager]
          job    <- skill.createJob(agentId, userId, "trigger-skill", None)
          _      <- skill.pauseJob(job.id)
          _      <- skill.triggerNow(job.id)
          result <- mgr.getJob(job.id)
        } yield assertTrue(result.status == JobStatus.Pending)
      },
    )

}
