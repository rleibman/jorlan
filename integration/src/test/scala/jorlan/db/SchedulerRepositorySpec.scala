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

import jorlan.*
import jorlan.db.TestFixtures.*
import jorlan.db.repository.*
import jorlan.domain.*
import zio.*
import zio.test.*

object SchedulerRepositorySpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

  private def makeJob(
    agentId: AgentId,
    userId:  UserId,
    name:    String,
  ): SchedulerJob =
    SchedulerJob(
      id = SchedulerJobId.empty,
      agentId = agentId,
      userId = userId,
      skillId = None,
      name = name,
      inputJson = None,
      status = JobStatus.Pending,
      scheduledAt = T0,
      startedAt = None,
      finishedAt = None,
      resultJson = None,
      maxRetries = 0,
      retryCount = 0,
      backoffSeconds = 60,
      backoffPolicy = RetryBackoffPolicy.Fixed,
      missedRunPolicy = MissedRunPolicy.Skip,
      leasedAt = None,
      leasedBy = None,
      createdAt = T0,
    )

  private def createUserAndAgent(
    userRepo:  ZIOUserRepository,
    agentRepo: ZIOAgentRepository,
    suffix:    String,
  ): UIO[(UserId, AgentId)] =
    for {
      user  <- userRepo.upsert(User(UserId.empty, s"User$suffix", s"$suffix@test.local", T0, T0)).orDie
      agent <- agentRepo.upsert(Agent(AgentId.empty, s"Agent$suffix", None, None, 0, T0)).orDie
    } yield (user.id, agent.id)

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("SchedulerRepository")(
      test("upsert and retrieve a job") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched1")
          job = makeJob(aid, uid, "nightly-cleanup")
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
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched2")
          job = makeJob(aid, uid, "updatable-job")
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
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched3")
          past = T0.minusSeconds(3600)
          future = T0.plusSeconds(3600)
          j1      <- repo.upsertJob(makeJob(aid, uid, "past-job").copy(scheduledAt = past))
          _       <- repo.upsertJob(makeJob(aid, uid, "future-job").copy(scheduledAt = future))
          _       <- TestClock.setTime(T0)
          pending <- repo.getPendingJobs
        } yield assertTrue(pending.exists(_.id == j1.id), pending.forall(_.status == JobStatus.Pending))
      },
      test("deleteJob removes the job") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched4")
          job        <- repo.upsertJob(makeJob(aid, uid, "del-job"))
          count      <- repo.deleteJob(job.id)
          fetched    <- repo.getJob(job.id)
        } yield assertTrue(count == 1L, fetched.isEmpty)
      },
      test("upsertTrigger and searchTriggers") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched5")
          job        <- repo.upsertJob(makeJob(aid, uid, "trig-job"))
          t1         <- repo.upsertTrigger(
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
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched6")
          job        <- repo.upsertJob(makeJob(aid, uid, "sort-job"))
          _          <- repo.upsertTrigger(
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
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched7")
          job        <- repo.upsertJob(makeJob(aid, uid, "upd-trig-job"))
          t          <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Cron, "0 8 * * *", true, T0),
          )
          _       <- repo.upsertTrigger(t.copy(expression = "0 10 * * *", enabled = false))
          updated <- repo.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 10))
        } yield assertTrue(updated.exists(e => e.id == t.id && e.expression == "0 10 * * *" && !e.enabled))
      },
      test("deleteTrigger removes the trigger") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched8")
          job        <- repo.upsertJob(makeJob(aid, uid, "deltrig-job"))
          t          <- repo.upsertTrigger(
            SchedulerTrigger(SchedulerTriggerId.empty, job.id, TriggerType.Cron, "0 0 * * *", true, T0),
          )
          count <- repo.deleteTrigger(t.id)
          after <- repo.searchTriggers(TriggerSearch(jobId = job.id, pageSize = 10))
        } yield assertTrue(count == 1L, !after.exists(_.id == t.id))
      },
      test("claimJob — only one worker wins") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched9")
          job        <- repo.upsertJob(makeJob(aid, uid, "claim-job"))
          win        <- repo.claimJob(job.id, "worker-A", T0, 300)
          lose       <- repo.claimJob(job.id, "worker-B", T0, 300)
        } yield assertTrue(win, !lose)
      },
      test("releaseJob sets final status and clears lease") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched10")
          job        <- repo.upsertJob(makeJob(aid, uid, "release-job"))
          _          <- repo.claimJob(job.id, "worker-A", T0, 300)
          _          <- repo.releaseJob(job.id, JobStatus.Succeeded, Some("""{"result":"ok"}"""), T0)
          fetched    <- repo.getJob(job.id)
        } yield assertTrue(
          fetched.exists(_.status == JobStatus.Succeeded),
          fetched.exists(_.leasedAt.isEmpty),
          fetched.exists(_.leasedBy.isEmpty),
          fetched.exists(_.resultJson.contains("""{"result":"ok"}""")),
        )
      },
      test("expireLeases reclaims stale Running jobs to Pending") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched11")
          job        <- repo.upsertJob(makeJob(aid, uid, "stale-lease"))
          leasedAt = T0.minusSeconds(600)
          _ <- repo.upsertJob(
            job.copy(status = JobStatus.Running, leasedAt = Some(leasedAt), leasedBy = Some("dead-worker")),
          )
          // expire leases older than 300 seconds — the 600s-old lease should be reclaimed
          expiredCount <- repo.expireLeases(T0.minusSeconds(300))
          fetched      <- repo.getJob(job.id)
        } yield assertTrue(
          expiredCount >= 1L,
          fetched.exists(_.status == JobStatus.Pending),
          fetched.exists(_.leasedAt.isEmpty),
          fetched.exists(_.leasedBy.isEmpty),
        )
      },
      test("listJobs(Some(agentId)) returns only jobs for that agent") {
        for {
          userRepo   <- ZIO.serviceWith[ZIORepositories](_.user)
          agentRepo  <- ZIO.serviceWith[ZIORepositories](_.agent)
          repo       <- ZIO.serviceWith[ZIORepositories](_.scheduler)
          (uid, aid) <- createUserAndAgent(userRepo, agentRepo, "Sched12a")
          (_, aid2)  <- createUserAndAgent(userRepo, agentRepo, "Sched12b")
          _          <- repo.upsertJob(makeJob(aid, uid, "agent1-only"))
          _          <- repo.upsertJob(makeJob(aid2, uid, "agent2-only"))
          all        <- repo.listJobs(None)
          filtered   <- repo.listJobs(Some(aid))
        } yield assertTrue(
          all.size >= 2,
          filtered.forall(_.agentId == aid),
          filtered.exists(_.name == "agent1-only"),
          !filtered.exists(_.name == "agent2-only"),
        )
      },
    ) @@ TestAspect.sequential

}
