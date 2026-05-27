/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import jorlan.db.TestFixtures.*
import jorlan.db.TestFixtures.given
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

object SchedulerRepositorySpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SchedulerRepository")(
      test("upsert and retrieve a job") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent", None, None, 0, T0))
          job = SchedulerJob(
            SchedulerJobId.empty,
            agent.id,
            None,
            "nightly-cleanup",
            None,
            JobStatus.Pending,
            T0,
            None,
            None,
            None,
            T0,
          )
          saved   <- repo.upsertJob(job)
          fetched <- repo.getJob(saved.id)
        } yield assertTrue(
          saved.id.value > 0L,
          fetched.isDefined,
          fetched.exists(_.name == "nightly-cleanup"),
        )
      },
      test("upsert updates mutable fields") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent2", None, None, 0, T0))
          job = SchedulerJob(
            SchedulerJobId.empty,
            agent.id,
            None,
            "updatable-job",
            None,
            JobStatus.Pending,
            T0,
            None,
            None,
            None,
            T0,
          )
          saved   <- repo.upsertJob(job)
          updated <- repo.upsertJob(saved.copy(status = JobStatus.Running, startedAt = Some(T0)))
          fetched <- repo.getJob(saved.id)
        } yield assertTrue(
          updated.id == saved.id,
          fetched.exists(_.status == JobStatus.Running),
        )
      },
      test("getPendingJobs returns jobs scheduled in the past") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent3", None, None, 0, T0))
          past = T0.minusSeconds(3600)
          future = T0.plusSeconds(3600)
          j1 <- repo.upsertJob(
            SchedulerJob(
              SchedulerJobId.empty,
              agent.id,
              None,
              "past-job",
              None,
              JobStatus.Pending,
              past,
              None,
              None,
              None,
              T0,
            ),
          )
          _ <- repo.upsertJob(
            SchedulerJob(
              SchedulerJobId.empty,
              agent.id,
              None,
              "future-job",
              None,
              JobStatus.Pending,
              future,
              None,
              None,
              None,
              T0,
            ),
          )
          // Advance TestClock to T0 so that "past-job" (T0-3600) is in the past and "future-job" (T0+3600) is not
          _       <- TestClock.setTime(T0)
          pending <- repo.getPendingJobs
        } yield assertTrue(pending.exists(_.id == j1.id), pending.forall(_.status == JobStatus.Pending))
      },
      test("deleteJob removes the job") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent4", None, None, 0, T0))
          job <- repo.upsertJob(
            SchedulerJob(
              SchedulerJobId.empty,
              agent.id,
              None,
              "del-job",
              None,
              JobStatus.Pending,
              T0,
              None,
              None,
              None,
              T0,
            ),
          )
          count   <- repo.deleteJob(job.id)
          fetched <- repo.getJob(job.id)
        } yield assertTrue(count == 1L, fetched.isEmpty)
      },
      test("upsertTrigger and searchTriggers") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent5", None, None, 0, T0))
          job <- repo.upsertJob(
            SchedulerJob(
              SchedulerJobId.empty,
              agent.id,
              None,
              "trig-job",
              None,
              JobStatus.Pending,
              T0,
              None,
              None,
              None,
              T0,
            ),
          )
          t1 <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Cron, "0 9 * * 1-5", true, T0),
          )
          t2 <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Interval, "PT15M", true, T0),
          )
          all <- repo.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 20))
        } yield assertTrue(
          t1.id.value > 0L,
          t2.id.value > 0L,
          all.length >= 2,
          all.exists(_.id == t1.id),
          all.exists(_.id == t2.id),
        )
      },
      test("searchTriggers descending sort") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent6", None, None, 0, T0))
          job <- repo.upsertJob(
            SchedulerJob(
              SchedulerJobId.empty,
              agent.id,
              None,
              "sort-job",
              None,
              JobStatus.Pending,
              T0,
              None,
              None,
              None,
              T0,
            ),
          )
          _ <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.OneShot, "2026-01-15T12:00:00Z", true, T0),
          )
          _ <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Event, "agent.completed", true, T0),
          )
          desc <- repo.searchTriggers(
            TriggerSearch(jobId = job.id, pageSize = 20, sorts = Some(Sort(TriggerOrder.Id, OrderDirection.Desc))),
          )
        } yield assertTrue(
          desc.length >= 2,
          desc.map(_.id.value) == desc.map(_.id.value).sorted.reverse,
        )
      },
      test("upsertTrigger updates expression and enabled flag") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent7", None, None, 0, T0))
          job <- repo.upsertJob(
            SchedulerJob(
              SchedulerJobId.empty,
              agent.id,
              None,
              "upd-trig-job",
              None,
              JobStatus.Pending,
              T0,
              None,
              None,
              None,
              T0,
            ),
          )
          t <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Cron, "0 8 * * *", true, T0),
          )
          _       <- repo.upsertTrigger(t.copy(expression = "0 10 * * *", enabled = false))
          updated <- repo.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 10))
        } yield assertTrue(updated.exists(e => e.id == t.id && e.expression == "0 10 * * *" && !e.enabled))
      },
      test("deleteTrigger removes the trigger") {
        for {
          agentRepo <- ZIO.service[AgentZIORepository]
          repo      <- ZIO.service[SchedulerZIORepository]
          agent     <- agentRepo.upsert(Agent(AgentId.empty, "SchedAgent8", None, None, 0, T0))
          job <- repo.upsertJob(
            SchedulerJob(
              SchedulerJobId.empty,
              agent.id,
              None,
              "deltrig-job",
              None,
              JobStatus.Pending,
              T0,
              None,
              None,
              None,
              T0,
            ),
          )
          t <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Cron, "0 0 * * *", true, T0),
          )
          count <- repo.deleteTrigger(t.id)
          after <- repo.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 10))
        } yield assertTrue(count == 1L, !after.exists(_.id == t.id))
      },
    ).provideLayerShared(JorlanContainer.repositoryLayer) @@ TestAspect.sequential

}
