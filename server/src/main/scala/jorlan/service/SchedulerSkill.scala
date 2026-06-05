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
import zio.*

/** Tier 0 scheduler skill — agent-directed job management.
  *
  * Exposes [[JobManager]] operations as named tools callable from the ReAct tool-calling loop. Registry wiring is
  * deferred to Phase 12 when [[SkillRegistry]] is built.
  */
class SchedulerSkill(jobManager: JobManager) {

  def createJob(
    agentId:         AgentId,
    userId:          UserId,
    name:            String,
    inputJson:       Option[String],
    maxRetries:      Int = 0,
    backoffSeconds:  Int = 60,
    backoffPolicy:   RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedRunPolicy: MissedRunPolicy = MissedRunPolicy.Skip,
  ): IO[JorlanError, SchedulerJob] =
    jobManager.createJob(agentId, userId, name, inputJson, maxRetries, backoffSeconds, backoffPolicy, missedRunPolicy)

  def listJobs(agentId: AgentId): UIO[List[SchedulerJob]] =
    jobManager.listJobs(Some(agentId))

  def pauseJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.pauseJob(id)

  def resumeJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.resumeJob(id)

  def cancelJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.cancelJob(id)

  def triggerNow(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.triggerNow(id)

}

object SchedulerSkill {

  val live: URLayer[JobManager, SchedulerSkill] = ZLayer.fromFunction(SchedulerSkill(_))

}
